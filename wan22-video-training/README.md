# Wan2.2 视频工作流训练资料包

这个目录用于沉淀当前项目的 Wan2.2 视频训练资料、数据模板、配置草案、外部训练仓库和执行状态。它服务于当前后端里的 I2V 工作流：

```text
backend/src/main/resources/comfyui/workflows/video_wan2_2_14B_i2v.json
```

## 目标

- 训练或微调 Wan2.2 I2V LoRA，让竖屏短剧镜头更稳定。
- 优化人物身份、服装、场景、灯光和单镜头连续运动的一致性。
- 降低黑屏、冻结、线稿化、人物漂移、突然新增人物。
- 将训练出的 LoRA 可回滚地接入 ComfyUI 工作流和后端视频生成配置。

## 目录

```text
wan22-video-training/
  README.md
  WAN22_TRAINING_WORKFLOW.md
  SOURCES.md
  TRAINING_STATUS.md
  data/
    README.md
    manifests/
      wan22_shot_manifest.template.csv
      wan22_quality_labels.template.csv
      wan22_validation_prompts.template.jsonl
  configs/
    comfyui_train_lora_node_draft.yaml
    diffsynth_wan22_i2v_lora_draft.yaml
  scripts/
    README.md
```

以下目录用于本地大文件、外部仓库、模型权重和训练输出，已加入 `.gitignore`，不提交：

```text
raw/
processed/
runs/
checkpoints/
models/
external/
```

## 当前建议路线

1. 不重训 Wan2.2 基座，先做可插拔 LoRA。
2. 主线优先使用 `DiffSynth-Studio` 的 Wan2.2 I2V LoRA 脚本，因为它有明确的 Wan2.2 I2V 训练入口、低显存策略和验证脚本。
3. 如果要在 ComfyUI 内完成训练，先试 ComfyUI 内置 `TrainLoraNode` 小样本训练；再评估 `comfyUI-Realtime-Lora` 的 Wan 2.2/Musubi Tuner 节点。
4. 只使用高质量、已授权、竖屏短剧单镜头样本。
5. 本机 RTX 5090 约 32GB VRAM 可以做 batch_size=1 的小跑和低分辨率验证；正式 720P Wan2.2 A14B 训练仍要准备 OOM 回退或 80GB 级 GPU。

## 已就绪

- 资料目录、manifest 模板和训练配置草案已创建。
- 已下载外部参考仓库到 `external/`：`DiffSynth-Studio`、`comfyUI-Realtime-Lora`。
- 可用 Codex 辅助技能：`jupyter-notebook`、`playwright`、`screenshot`。
- 当前会话检索过可安装技能列表，没有发现比上述技能更贴合 Wan2.2 训练资料整理的新增技能。

安装或新增技能后通常需要重启 Codex 才能在后续会话中直接加载。
