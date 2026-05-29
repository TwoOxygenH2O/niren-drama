# 训练 Wan2.2 视频工作流完整步骤

本文针对当前项目的 Wan2.2 I2V 工作流：

```text
backend/src/main/resources/comfyui/workflows/video_wan2_2_14B_i2v.json
```

目标是训练一个可插拔、可回滚的短剧 LoRA，而不是覆盖 Wan2.2 基座模型。优先解决竖屏短剧里最影响发布质量的问题：身份漂移、服装漂移、场景跳变、黑屏、冻结、线稿化、突然新增人物。

## 0. 我可以做什么，哪些需要你确认

| 阶段 | 我可以做 | 需要你确认或提供 |
| --- | --- | --- |
| 资料收集 | 查官方文档、核验仓库、维护本目录 | 无 |
| 技能准备 | 检查并安装可用 Codex 辅助技能 | 安装后重启 Codex 才能在新会话直接加载 |
| 外部仓库 | 下载 DiffSynth-Studio、ComfyUI 训练插件参考仓库 | 是否允许后续安装依赖到 Python/ComfyUI 环境 |
| 数据目录 | 创建 `raw/`、`processed/`、`data/`、`models/`、`runs/`、`checkpoints/` 模板 | 无 |
| 素材扫描 | 扫描 `output/`、`uploads/`、数据库导出的候选视频和首帧 | 哪些素材有训练授权 |
| 血缘导出 | 导出 `projectId/shotId/prompt/workflow/model/taskId/videoUrl/imageUrl` | 数据库需可连接 |
| 数据清洗 | 用 FFmpeg 统一 9:16、fps、时长，抽首/中/尾帧 | 剔除规则和保留阈值 |
| 质量初标 | 自动标黑屏、冻结、比例、时长、分辨率 | 人物身份和服装一致性需要人工抽检 |
| 训练小跑 | 生成命令，跑 batch_size=1、低分辨率、少步数试验 | 模型权重路径和训练环境 |
| 正式训练 | 记录日志、保存 checkpoint、生成验证样片 | 32GB 本机可能 OOM，必要时切 80GB 云端 |
| 系统接入 | 把 LoRA 注入 ComfyUI workflow，增加强度、版本和回滚 | 选择上线的 LoRA 版本 |

## 1. 训练目标

业务目标：

- 生成 5-8 秒竖屏短剧单镜头，镜头运动连续，不像幻灯片。
- 人物身份、服装、发型、场景、光线保持稳定。
- 不新增无关人物，不突然切景，不线稿化，不黑屏，不冻结。
- 输出 LoRA 能被当前后端的视频生成配置选择性启用。

技术目标：

```json
{
  "baseWorkflow": "video_wan2_2_14B_i2v.json",
  "baseModel": "Wan2.2-I2V-A14B",
  "trainingType": "i2v_lora",
  "targetModule": "dit",
  "output": "niren_wan22_short_drama_i2v_lora_v001.safetensors"
}
```

## 2. 数据准备

每个样本建议整理为：

```text
sample_id/
  first_frame.png
  target.mp4
  metadata.json
```

`metadata.json` 建议字段：

```json
{
  "sampleId": "project_1001_ep01_shot001",
  "projectId": 1001,
  "episodeNo": 1,
  "shotNo": 1,
  "durationSec": 6,
  "prompt": "Keep the same actor identity, same outfit, same room, single continuous shot...",
  "negativePrompt": "no cuts, no new people, no identity drift, no black frames, no sketch lines",
  "characterRefs": ["女主"],
  "sceneRefs": ["办公室走廊"],
  "workflowFile": "video_wan2_2_14B_i2v.json",
  "baseModel": "Wan2.2-I2V-A14B",
  "qualityLabel": "ok",
  "licenseStatus": "owned"
}
```

样本规模：

| 阶段 | 样本量 | 目标 |
| --- | --- | --- |
| smoke test | 10-20 个 | 验证数据格式、环境、训练命令 |
| 小跑 | 30-50 个 `ok` 样本，5-10 个固定验证样本 | 验证 LoRA 是否改善稳定性 |
| 第一版可用 LoRA | 300-1000 个高质量样本 | 覆盖常见角色、场景、景别和动作 |

不要把失败样本作为正样本混入 LoRA 训练。失败样本可用于质量门禁和错误归因。

## 3. 数据标准化

视频标准化为 9:16、固定 fps、短剧镜头长度：

```powershell
ffmpeg -y -i input.mp4 `
  -vf "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2,fps=24" `
  -t 8 -an processed.mp4
```

首帧标准化：

```powershell
ffmpeg -y -i first_frame.png `
  -vf "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2" `
  processed_first_frame.png
```

Manifest 从模板复制：

```powershell
Copy-Item wan22-video-training/data/manifests/wan22_shot_manifest.template.csv `
  wan22-video-training/data/manifests/wan22_shot_manifest.v001.csv
```

必须字段：

- `sample_id`
- `split`
- `first_frame_path`
- `video_path`
- `prompt`
- `negative_prompt`
- `workflow_file`
- `base_model`
- `quality_label`
- `usable_for_lora`
- `license_status`

## 4. 路线 A：ComfyUI 内置训练节点

适用场景：想先在 ComfyUI UI 内验证 LoRA 训练流程，或做小样本 GUI smoke test。

关键节点：

- `LoadImageTextDataSetFromFolder`
- `MakeTrainingDataset`
- `SaveTrainingDataset`
- `LoadTrainingDataset`
- `TrainLoraNode`
- `SaveLoRA`
- `LossGraphNode`

建议起步参数见：

```text
wan22-video-training/configs/comfyui_train_lora_node_draft.yaml
```

起步值：

- `batch_size`: 1
- `grad_accumulation_steps`: 4
- `steps`: 100
- `learning_rate`: `1e-4`
- `rank`: 8 或 16
- `training_dtype`: `bf16`
- `lora_dtype`: `bf16`
- `gradient_checkpointing`: true

注意：

- ComfyUI 内置训练节点适合先验证训练闭环，正式 Wan2.2 I2V LoRA 仍要以真实视频训练脚本验证。
- `ComfyUI-Training-Evolved` 这个精确插件名暂未找到可靠一手仓库；不要在生产路线里把未核验插件名当作已确认依赖。

## 5. 路线 B：ComfyUI 插件训练

已下载参考仓库：

```text
wan22-video-training/external/comfyUI-Realtime-Lora
```

该插件提供：

- `Realtime LoRA Trainer (Wan 2.2 - Musubi Tuner)`
- `Apply Trained LoRA`
- `Selective LoRA Loader (Wan)`
- Wan 2.2 High/Low/Combo noise modes

安装到 ComfyUI 时才复制或 clone 到：

```text
ComfyUI/custom_nodes/comfyUI-Realtime-Lora
```

然后重启 ComfyUI。

Wan 2.2 插件路线需要额外准备：

- `kohya-ss/musubi-tuner`
- Wan 2.2 fp16 DiT high/low noise 模型
- VAE
- T5 text encoder
- 训练图片或帧目录及 caption

建议起步：

- `vram_mode`: `Low (768px) fp8`
- `blocks_to_swap`: 26
- `training_steps`: 500
- `learning_rate`: `1e-4`
- `lora_rank`: 16
- `batch_size`: 1

注意：该插件的 Wan 2.2 说明更偏 T2V/单帧图像训练和 Musubi Tuner 路线。用于当前 I2V 视频 LoRA 前，需要先用小样本确认它是否符合我们的首帧到目标视频训练目标。

## 6. 路线 C：DiffSynth-Studio I2V LoRA 主线

已下载参考仓库：

```text
wan22-video-training/external/DiffSynth-Studio
```

安装草案：

```powershell
cd wan22-video-training/external/DiffSynth-Studio
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -e .
pip install accelerate
```

DiffSynth-Studio Wan 文档明确给了 `Wan2.2-I2V-A14B` 的 LoRA training 示例入口。当前项目先按它作为主线，因为它能直接表达 `input_image + video` 的 I2V 数据结构。

训练命令草案：

```powershell
accelerate launch examples/wanvideo/model_training/train.py `
  --dataset_base_path "D:/javaProject/niren-drama/wan22-video-training/processed/wan_dataset" `
  --dataset_metadata_path "D:/javaProject/niren-drama/wan22-video-training/data/manifests/wan22_shot_manifest.v001.csv" `
  --data_file_keys "video,input_image" `
  --dataset_repeat 20 `
  --dataset_num_workers 2 `
  --model_paths "[[\"D:/models/Wan2.2/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors\"],[\"D:/models/Wan2.2/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors\"],\"D:/models/Wan2.2/wan_2.1_vae.safetensors\",\"D:/models/Wan2.2/umt5-xxl-enc-bf16.safetensors\"]" `
  --learning_rate 0.0001 `
  --num_epochs 20 `
  --trainable_models "" `
  --lora_base_model "dit" `
  --lora_target_modules "to_q,to_k,to_v" `
  --lora_rank 16 `
  --height 1280 `
  --width 720 `
  --num_frames 121 `
  --output_path "D:/javaProject/niren-drama/wan22-video-training/checkpoints/niren_wan22_i2v_lora_v001.safetensors" `
  --save_steps 200 `
  --use_gradient_checkpointing `
  --find_unused_parameters
```

32GB 显存小跑回退：

```powershell
accelerate launch examples/wanvideo/model_training/train.py `
  --dataset_base_path "D:/javaProject/niren-drama/wan22-video-training/processed/wan_dataset" `
  --dataset_metadata_path "D:/javaProject/niren-drama/wan22-video-training/data/manifests/wan22_shot_manifest.v001.csv" `
  --data_file_keys "video,input_image" `
  --dataset_repeat 5 `
  --model_paths "[[\"D:/models/Wan2.2/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors\"],[\"D:/models/Wan2.2/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors\"],\"D:/models/Wan2.2/wan_2.1_vae.safetensors\",\"D:/models/Wan2.2/umt5-xxl-enc-bf16.safetensors\"]" `
  --learning_rate 0.0001 `
  --num_epochs 1 `
  --trainable_models "" `
  --lora_base_model "dit" `
  --lora_target_modules "to_q,to_k,to_v" `
  --lora_rank 8 `
  --height 832 `
  --width 480 `
  --num_frames 81 `
  --output_path "D:/javaProject/niren-drama/wan22-video-training/checkpoints/niren_wan22_i2v_lora_smoke.safetensors" `
  --save_steps 50 `
  --use_gradient_checkpointing `
  --use_gradient_checkpointing_offload `
  --fp8_models "text_encoder,vae" `
  --find_unused_parameters
```

如果仍然 OOM，依次降低：

1. `num_frames`
2. 分辨率到 512/768 级别
3. `lora_rank`
4. 数据 repeat/epoch
5. 启用更强 offload
6. 切到 WSL2/Linux 或 80GB 云端 GPU

## 7. 权重准备

不要把模型权重提交到 git。建议引用 ComfyUI 现有模型目录，或放在被忽略的：

```text
wan22-video-training/models/Wan2.2-I2V-A14B/
```

当前项目期望权重：

```text
wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors
wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors
wan_2.1_vae.safetensors
umt5-xxl-enc-bf16.safetensors
```

如果选择 `comfyUI-Realtime-Lora`/Musubi Tuner 路线，可能需要 fp16 版本而不是 fp8 版本，按插件 README 和实际脚本确认。

## 8. 验证方式

固定验证集至少包含：

- 室内对话镜头
- 情绪特写镜头
- 双人对峙镜头
- 走廊/办公室/卧室常用短剧场景
- 无人物环境镜头

每个 checkpoint 用同一组首帧、prompt、seed 生成视频，比较：

| 指标 | 目标 |
| --- | --- |
| 9:16 合规率 | >= 98% |
| 黑屏率 | <= 2% |
| 冻结率 | <= 5% |
| 线稿化率 | <= 5% |
| 人物身份通过率 | >= 85% |
| 服装一致通过率 | >= 85% |
| 无新增人物通过率 | >= 90% |
| 人工可用率 | >= 75% |

## 9. 接入后端和 ComfyUI workflow

训练完成后不要覆盖旧 workflow。新增 LoRA 参数：

```json
{
  "workflowFile": "video_wan2_2_14B_i2v.json",
  "loraFile": "niren_wan22_i2v_lora_v001.safetensors",
  "loraStrength": 0.55,
  "loraVersion": "wan22_i2v_short_drama_v001"
}
```

建议强度：

- 0.35：保守试用。
- 0.55：默认发布试用。
- 0.75：强风格，容易过拟合。

系统改造点：

1. `ComfyUiWorkflowLoader` 注入 LoRA 节点字段。
2. 后端视频配置展示 LoRA 版本和强度。
3. `AssetSnapshot.metadata` 保存 LoRA 版本。
4. 失败重试支持“关闭 LoRA”和“降低 LoRA 强度”。

## 10. 当前进度

已完成：

- 创建训练资料目录。
- 收集官方 Wan2.2、ComfyUI 训练节点、DiffSynth-Studio 和 ComfyUI 插件资料。
- 下载 `DiffSynth-Studio` 和 `comfyUI-Realtime-Lora` 到 `external/`。
- 创建 manifest 模板、DiffSynth 配置草案、ComfyUI 训练节点配置草案。
- 记录哪些步骤我可以执行，哪些需要你确认。

未完成：

- 正式训练素材授权确认。
- 数据库血缘导出。
- manifest 正式版本。
- 人工质量标签。
- Wan2.2 权重真实路径确认。
- 训练环境依赖安装。
- LoRA 小规模试跑。

下一步建议先做：扫描本地输出和上传目录，生成候选素材清单，再人工确认哪些样本可用于训练。
