# 脚本目录

后续建议添加：

- `export_wan22_manifest.ps1`: 从 MySQL 导出镜头血缘并生成 manifest 初稿。
- `prepare_wan22_dataset.ps1`: 用 FFmpeg 标准化视频和首帧。
- `extract_quality_frames.ps1`: 抽首/中/尾帧用于质检标注。
- `check_wan22_dataset.ps1`: 检查路径、比例、时长、fps、标签完整性。
- `run_diffsynth_wan22_lora.ps1`: 按配置启动小规模 LoRA 训练。

当前没有直接写训练启动脚本，是因为模型权重路径、训练数据和 DiffSynth-Studio 版本还未确认。确认后再生成可执行脚本，避免脚本看起来完整但实际不可跑。
