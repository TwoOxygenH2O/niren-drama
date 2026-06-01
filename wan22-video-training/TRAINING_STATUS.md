# Wan2.2 训练状态

更新时间：2026-05-28

## 本机检查

| 项目 | 状态 | 结果 |
| --- | --- | --- |
| GPU | 可用 | NVIDIA GeForce RTX 5090，约 32GB VRAM |
| FFmpeg | 待复核 | 后续跑预处理脚本前再检查版本 |
| FFprobe | 待复核 | 后续跑质量扫描前再检查版本 |
| Wan2.2 I2V workflow | 可用 | `backend/src/main/resources/comfyui/workflows/video_wan2_2_14B_i2v.json` |
| 训练资料 | 已创建 | `wan22-video-training/` |
| 外部参考仓库 | 已下载 | `external/DiffSynth-Studio`、`external/comfyUI-Realtime-Lora` |
| Codex 辅助技能 | 已可用 | `jupyter-notebook`、`playwright`、`screenshot` |
| 原始训练数据 | 未就绪 | 需要授权确认和首帧/视频配对 |
| 数据库血缘 | 未导出 | 待连接数据库导出 |
| Wan2.2 权重路径 | 未确认 | 需要确认 ComfyUI 或独立模型目录 |
| ComfyUI 内置训练节点路线 | 已记录 | 适合 GUI 小跑和实验 |
| DiffSynth-Studio I2V LoRA 路线 | 已记录 | 推荐作为第一版可复现主线 |
| LoRA 正式训练 | 未开始 | 等数据、权重路径和训练环境就绪 |

## 当前阻塞

正式训练不能空跑。必须先有：

1. 可授权训练素材。
2. 首帧和目标视频配对。
3. prompt / negative prompt。
4. workflow / model 血缘。
5. 人工质量标签。
6. Wan2.2 权重路径。
7. 训练环境选择：ComfyUI 内置节点、`comfyUI-Realtime-Lora`、DiffSynth-Studio 或云端。

## 我可以继续执行

- 扫描 `output/`、`uploads/` 或数据库记录，生成候选素材清单。
- 生成 `wan22_shot_manifest.v001.csv` 初稿。
- 写 FFmpeg 预处理脚本和黑屏/冻结检测脚本。
- 根据真实模型路径生成 DiffSynth-Studio 最终训练命令。
- 复制 `comfyUI-Realtime-Lora` 到 ComfyUI `custom_nodes` 并准备 Musubi Tuner 路线。
- 训练完成后把 LoRA 接入后端 ComfyUI workflow，并做强度、版本和回滚配置。
