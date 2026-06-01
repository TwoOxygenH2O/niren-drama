# 训练执行状态

更新时间：2026-05-28

## 本机就绪检查

| 项目 | 状态 | 结果 |
| --- | --- | --- |
| GPU | 可用 | NVIDIA GeForce RTX 5090, 32607 MiB VRAM, driver 596.49 |
| FFmpeg | 可用 | ffmpeg 2026-05-18 full build |
| FFprobe | 可用 | ffprobe 2026-05-18 full build |
| 项目 ComfyUI workflow | 可用 | 已发现 Wan2.2、LTX-2、Hunyuan I2V workflow |
| 候选素材 | 部分可用 | `backend/uploads` 与 `output` 中发现 90 个视频、366 张图片 |
| 训练原始视频 | 未就绪 | `raw/` 目录已创建，但尚未完成授权确认和 manifest 配对 |
| 训练 manifest | 未就绪 | 当前只有模板 |
| 人工质量标签 | 未就绪 | 当前只有模板 |
| LTX trainer | 未安装 | 需拉取 `Lightricks/LTX-2` |
| Wan trainer | 未安装 | 需拉取 `modelscope/DiffSynth-Studio` |
| 模型权重路径 | 未确认 | 需确认 ComfyUI 模型目录或单独训练模型目录 |

## 下一步

1. 从数据库导出 `shotId -> 首帧 -> 视频 -> prompt -> workflow -> taskId` 血缘。
2. 基于候选素材生成 `shot_manifest.v001.csv` 初稿。
3. 人工确认素材授权和质量标签。
4. 标出每个样本是否可用于 LoRA。
5. 确认 LTX 和 Wan 权重路径。
6. 我再开始执行 `TRAINING_RUNBOOK.md` 的训练准备和小规模试跑。
