# 当前工作流模型训练详细步骤

本文按“我接下来如何一步一步训练”的顺序写。当前机器检测到 RTX 5090 约 32GB VRAM，FFmpeg/FFprobe 可用；但正式训练还缺少可授权训练素材、训练框架下载和模型权重路径确认。因此第一阶段先执行数据准备和训练就绪检查。

## 0. 训练目标定义

### 0.1 分镜切分模型

输入：一集短剧剧本、角色/场景圣经、集数时长目标。

输出：稳定 JSON 分镜：

```json
{
  "episodeNo": 1,
  "shots": [
    {
      "shotNo": 1,
      "duration": 6,
      "cameraAngle": "medium",
      "description": "角色在办公室门口停下，压低声音质问。",
      "dialogue": "你到底瞒了我什么？",
      "characterRefs": ["女主"],
      "sceneRefs": ["办公室走廊"],
      "motionTier": "B"
    }
  ]
}
```

训练目标：

- 每个镜头 5-8 秒为主。
- 每个镜头只表达一个清晰动作或情绪转折。
- 自动识别 A/B/C 档：A 发布高质量 Wan，B 预览增强，C 静态或低动态。
- 输出字段严格可入库，避免自然语言散文。

### 0.2 Prompt 策略模型

输入：分镜、首帧说明、角色/场景锁定信息。

输出：I2V 连续单镜头 prompt。

训练目标：

- 明确“保持首帧人物身份、服装、场景布局、灯光”。
- 避免剪辑、转场、新人物、镜头突然跳切。
- 将动作写成 5-8 秒连续变化。
- 按真人短剧口吻描述，不写“AI 魔法感”文案。

### 0.3 视频 LoRA

输入：首帧 + 连续单镜头 prompt + 目标视频。

训练目标：

- 竖屏 9:16。
- 人物脸型、发型、服装和场景连续。
- 微动作稳定：呼吸、眨眼、轻微转头、手部小动作、布料/发丝轻动。
- 降低线稿化、黑屏、冻结、多人物、场景跳变。

### 0.4 质量门禁模型

输入：首/中/尾帧、视频片段、质量标签。

输出：问题类型和置信度。

第一版标签：

- `ok`
- `black_frame`
- `frozen_frame`
- `line_art`
- `identity_drift`
- `costume_drift`
- `extra_person`
- `wrong_aspect_ratio`
- `duration_out_of_range`

## 1. 数据采集

### 1.1 从系统导出样本

推荐从数据库和素材文件中导出这些字段：

- 项目：`projectId`、项目类型、题材、平台目标。
- 分镜：`shotId`、`episodeNo`、`shotNo`、`description`、`dialogue`、`narration`、`duration`、`cameraAngle`、`motionTier`。
- 首帧：`imageUrl`、`imagePrompt`。
- 视频：`videoUrl`、`videoPrompt`、`workflowFile`、`model`、`taskId`。
- 任务：生成耗时、失败信息、重试次数。
- 质检：问题类型、人工确认结果。
- 一致性：角色、服装、发型、场景、灯光、参考图。

第一批建议规模：

- 分镜切分：200-500 集剧本文本及对应分镜 JSON。
- Prompt 策略：1000-3000 条镜头描述和优质 I2V prompt。
- 质量门禁：每类问题至少 100 个样本，`ok` 至少 500 个。
- LTX 预览 LoRA：100-300 个稳定镜头。
- Wan 发布 LoRA：300-1000 个高质量镜头，宁少勿杂。

### 1.2 本地目录

创建本地不提交目录：

```powershell
New-Item -ItemType Directory -Force training/short-drama-video-training/raw/videos
New-Item -ItemType Directory -Force training/short-drama-video-training/raw/first_frames
New-Item -ItemType Directory -Force training/short-drama-video-training/processed
New-Item -ItemType Directory -Force training/short-drama-video-training/runs
New-Item -ItemType Directory -Force training/short-drama-video-training/checkpoints
```

素材命名建议：

```text
project_{projectId}/ep_{episodeNo}/shot_{shotNo}/
  first_frame.png
  target.mp4
  metadata.json
```

## 2. 数据清洗

### 2.1 视频标准化

所有训练视频先统一：

- 竖屏 9:16。
- 720x1280 起步。
- 5-8 秒为主。
- 24fps 或模型要求 fps。
- H.264 mp4 只作为中间查看格式，训练时按 trainer 要求解码。

命令模板：

```powershell
ffmpeg -y -i raw.mp4 `
  -vf "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2,fps=24" `
  -t 8 -an processed.mp4
```

### 2.2 首帧标准化

```powershell
ffmpeg -y -i first_frame.png `
  -vf "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2" `
  processed_first_frame.png
```

### 2.3 过滤规则

直接剔除：

- 明显版权不明素材。
- 低清、严重压缩、字幕/水印盖脸。
- 多次跳切、混剪、非单镜头片段。
- 尺寸不合规且无法补边的素材。
- 人物身份不稳定但没有标为负例的素材。

保留为负例：

- 线稿化。
- 黑屏。
- 冻结。
- 人物漂移。
- 服装变化。
- 场景突然跳变。

## 3. 标注规范

### 3.1 `shot_manifest`

使用 `data/manifests/shot_manifest.template.csv` 复制成正式 manifest。

关键字段：

- `split`: `train | val | test`
- `task_type`: `segmentation | prompt_policy | ltx_lora | wan_lora | quality_gate`
- `quality_label`: `ok` 或问题类型。
- `usable_for_lora`: 只有高质量正样本才能填 `true`。
- `license_status`: 必须是 `owned` 或 `licensed`。

### 3.2 分镜 SFT JSONL

使用 `data/manifests/segmentation_sft.template.jsonl`。

要求：

- 输出必须是 JSON。
- 每条样本包含项目类型、题材、目标平台、剧本文本和目标分镜。
- 保留人工改过的最终分镜，不用未经审核的初稿。

### 3.3 Prompt 策略 JSONL

使用 `data/manifests/prompt_policy.template.jsonl`。

要求：

- 输入是镜头信息 + 一致性约束。
- 输出是可直接送 Wan/LTX 的英文或中英混合 prompt。
- 明确负面约束：no cuts, no new person, no wardrobe drift, no scene jump。

## 4. 训练分镜切分模型

优先级高，因为它会直接影响后续所有镜头质量。

推荐路线：

1. 先用现有文本模型做 SFT 或偏好微调，目标是“输出结构正确”。
2. 训练集来自人工确认过的剧本和分镜。
3. 验证集按项目拆分，不能同一项目同时进训练和验证。
4. 指标以结构正确率、镜头时长分布、字段完整率、人工通过率为主。

验收阈值：

- JSON 解析成功率 >= 99%。
- 平均镜头时长在 5-8 秒。
- 角色/场景引用缺失率 < 5%。
- 人工改动率比当前基线下降 30%。

当前项目内要接入的位置：

- `StoryboardService`
- `ProjectStyleSupport`
- 前端 `StoryboardView`
- 生产线工作台的下一步动作和失败卡片。

## 5. 训练 Prompt 策略模型

这是低成本、收益很高的一步。

训练目标：

- 从中文分镜转成稳定 I2V prompt。
- 固定短剧语言结构：
  1. 主体和首帧锁定。
  2. 连续动作。
  3. 镜头运动。
  4. 环境微动。
  5. 负面约束。

样例输出结构：

```text
Keep the same actor identity, face, hairstyle, outfit, scene layout and lighting from the first frame. In one continuous 6-second shot, the actor slowly turns her head toward the doorway, breathes subtly, and tightens her fingers around the phone. The camera makes a very slow push-in. No cuts, no new people, no wardrobe change, no scene jump.
```

验收阈值：

- 禁止词覆盖率 >= 98%。
- 单镜头连续描述覆盖率 >= 95%。
- 人工可直接使用率 >= 80%。
- 生成视频的漂移率下降 20% 以上。

## 6. 训练 LTX-2 预览 LoRA

定位：快测，不追求最终质感，追求快速、稳定、少线稿化。

参考官方 LTX-2 trainer：

- 仓库：`https://github.com/Lightricks/LTX-2`
- Trainer 文档：`packages/ltx-trainer`

建议在 Linux / WSL2 CUDA 环境执行。RTX 5090 32GB 可以先尝试低显存配置；如果 Windows 原生训练遇到 Triton/CUDA 兼容问题，切到 WSL2 或云端。

步骤：

```powershell
git clone https://github.com/Lightricks/LTX-2.git external/LTX-2
cd external/LTX-2
uv sync --frozen
```

准备 dataset JSON/CSV 后，按官方脚本预处理：

```powershell
uv run python packages/ltx-trainer/scripts/process_dataset.py `
  D:/javaProject/niren-drama/training/short-drama-video-training/processed/ltx_dataset.json `
  --resolution-buckets "720x1280x121" `
  --model-path D:/models/LTX/ltx-2-19b-distilled.safetensors `
  --text-encoder-path D:/models/LTX/gemma `
  --lora-trigger "niren_short_drama"
```

训练配置参考 `configs/ltx2_i2v_lora_draft.yaml`。

推荐参数起步：

- LoRA rank: 16 或 32。
- learning rate: `1e-4` 起步。
- batch size: 1。
- gradient accumulation: 4-8。
- steps: 1000-3000 先小跑。
- validation 每 200-300 steps 出 6 个固定样例。

验收：

- 预览镜头 720P 成功率 >= 85%。
- 黑屏/冻结/线稿化下降 30%。
- 5-8 秒镜头平均生成时间不明显劣化。

## 7. 训练 Wan2.2 I2V 发布 LoRA

定位：A 档镜头和最终发布质量，不做大杂烩风格 LoRA。

推荐优先使用 DiffSynth-Studio 的 Wan 训练能力：

- 仓库：`https://github.com/modelscope/DiffSynth-Studio`
- Wan 文档：`docs/en/Model_Details/Wan.md`

Wan2.2 A14B 对显存和工程要求高。RTX 5090 32GB 更适合小规模 LoRA 试跑、低分辨率预处理、分层 offload；正式发布 LoRA 建议 A100/H100 级别或多卡。

步骤草案：

```powershell
git clone https://github.com/modelscope/DiffSynth-Studio.git external/DiffSynth-Studio
cd external/DiffSynth-Studio
pip install -e .
```

准备 Wan 数据：

```text
processed/wan_dataset/
  videos/
  first_frames/
  metadata.csv
```

训练配置参考 `configs/wan22_i2v_lora_diffsynth_draft.yaml`。

建议参数起步：

- 分辨率：先 480x832 或 720x1280，按显存决定。
- 时长：5 秒起步，再到 8 秒。
- LoRA rank：16。
- learning rate：`5e-5` 到 `1e-4`。
- steps：2000 小跑，满意后 6000-12000。
- 正样本只放最终可用镜头。
- 负样本不进 LoRA，进入质量门禁。

验收：

- A 档固定验证集首帧一致性 >= 80% 人工通过。
- 服装漂移率下降 25%。
- 多人物误生成率下降 30%。
- 发布版 1080x1920 合成前质检通过率 >= 85%。

## 8. 训练质量门禁模型

第一版可以不用训练大模型，先用组合策略：

1. FFmpeg/ffprobe：比例、时长、黑屏、冻结。
2. 图像分类器：线稿化、低清、异常色彩。
3. 人物一致性：首帧/中帧/尾帧 embedding 相似度。
4. 服装一致性：人物区域颜色直方图或轻量视觉模型。

训练数据来自 `quality_labels.template.csv`。

推荐先训练轻量分类器：

- 输入：首帧、中帧、尾帧拼图，或 8 帧序列。
- 输出：多标签问题类型。
- 模型：MobileNet/ConvNeXt/ViT 小模型均可。
- 验证：每类 F1 >= 0.75 后再进入自动重试。

接入策略：

- `blocking`: 黑屏、比例错误、缺素材。
- `warning`: 冻结、时长偏离、疑似线稿。
- `manual_required`: 人物漂移、服装变化、多人物。

## 9. 评估和验收

固定评估集：

- 10 个室内对白镜头。
- 10 个办公室/豪宅/街边常用短剧场景。
- 10 个情绪转折镜头。
- 10 个动作稍强镜头。
- 10 个纯环境镜头。

指标：

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
| 失败后自动修复成功率 | >= 60% |

## 10. 接入现有系统

后端建议改动：

1. `sys_ai_config.extra` 增加：

```json
{
  "workflowFile": "video_wan2_2_14B_i2v.json",
  "loraFile": "niren_short_drama_wan_v001.safetensors",
  "loraStrength": 0.65,
  "qualityGateVersion": "qgate_v001",
  "promptPolicyVersion": "prompt_policy_v001"
}
```

2. `ComfyUiWorkflowLoader` 支持给 LoRA 节点注入 `loraFile`、`loraStrength`。
3. `ProductionWorkspaceService` 的 `health.videoConfig` 展示 LoRA 版本。
4. `ProductionIssue` 记录质量门禁模型输出。
5. `AssetSnapshot` 保存训练数据版本和模型版本。

前端建议改动：

1. 设置页预设卡显示当前 LoRA 版本。
2. 工作台镜头检查器展示“模型版本 / LoRA / 质量门禁结果”。
3. 失败卡片增加“用旧 LoRA 重试 / 关闭 LoRA 重试”。

## 11. 我后续按本文执行的顺序

1. 检查数据目录是否存在。
2. 从数据库和素材目录导出 manifest。
3. 扫描视频尺寸、时长、fps，生成清洗报告。
4. 抽首/中/尾帧，生成质检标注初稿。
5. 让你确认授权和人工标签。
6. 先训练 Prompt 策略和质量门禁。
7. 小规模训练 LTX 预览 LoRA。
8. 对比基线和 LoRA 输出。
9. 数据质量达标后再训练 Wan2.2 发布 LoRA。
10. 把通过验收的 LoRA 接入 ComfyUI workflow 和系统预设。

## 12. 当前无法立即开始正式训练的原因

当前仓库还没有：

- 已授权原始训练视频。
- 已确认的 manifest。
- 已下载并验证的 trainer 仓库。
- 已确认的 LTX/Wan 权重本地路径。
- 人工质量标签。

所以我可以马上执行的是：数据目录创建、manifest 模板、训练路线、就绪检查。正式训练需要先补齐素材和标签。
