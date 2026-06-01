# Wan2.2 训练资料来源

检索时间：2026-05-28

## 一手资料

1. Wan2.2 官方仓库
   URL: https://github.com/Wan-Video/Wan2.2
   采用信息：
   - `Wan2.2-I2V-A14B` 是 Image-to-Video MoE 模型，支持 480P 和 720P。
   - 官方 I2V-A14B 单卡推理示例标注至少 80GB VRAM；低显存时可用 offload、dtype 转换和 T5 CPU 等方式降显存。
   - 官方 README 的社区工作区提到 DiffSynth-Studio 支持 Wan2.2 的低 GPU 显存逐层 offload、FP8、序列并行、LoRA training 和 full training。
   - TI2V-5B 官方示例标注至少 24GB VRAM，可作为更轻量验证路线，但它不是当前项目主用的 I2V-A14B 工作流。

2. ComfyUI 内置 `TrainLoraNode` 文档
   URL: https://docs.comfy.org/built-in-nodes/TrainLoraNode
   采用信息：
   - `TrainLoraNode` 可用 latents 和 positive conditioning 在扩散模型上训练 LoRA。
   - 关键参数包括 `batch_size`、`grad_accumulation_steps`、`steps`、`learning_rate`、`rank`、`optimizer`、`loss_function`、`training_dtype`、`lora_dtype`、`gradient_checkpointing`、`existing_lora`。
   - 输出包括应用 LoRA 后的模型、LoRA 权重、loss 记录和完成步数。

3. ComfyUI 训练数据节点文档
   URLs:
   - https://docs.comfy.org/built-in-nodes/MakeTrainingDataset
   - https://docs.comfy.org/built-in-nodes/SaveTrainingDataset
   - https://docs.comfy.org/built-in-nodes/LoadTrainingDataset
   采用信息：
   - `MakeTrainingDataset` 把图像和文本 caption 编码成 latents 与 conditioning。
   - `SaveTrainingDataset` 把训练数据保存为 shard 和 metadata。
   - `LoadTrainingDataset` 从 ComfyUI output 目录读取已保存训练数据，供训练节点使用。

4. ComfyUI Training Modules RFC
   URL: https://github.com/Comfy-Org/rfcs/discussions/27
   采用信息：
   - 训练模块的目标是把数据加载、LoRA 训练、保存 LoRA 和 loss 可视化放入同一 ComfyUI 工作流。
   - RFC 明确提到 `TrainLoraNode`、`SaveLoRA` 和 `LossGraphNode` 的设计方向，但部分训练节点仍应视为实验性能力。

5. DiffSynth-Studio Wan 文档
   URL: https://github.com/modelscope/DiffSynth-Studio/blob/main/docs/en/Model_Details/Wan.md
   采用信息：
   - Wan2.2-I2V-A14B 的训练数据键为 `input_image`。
   - 文档表格提供了 Wan2.2-I2V-A14B 的 LoRA training、full training、validate_lora 和 validate_full 示例入口。
   - 训练参数包含 `dataset_base_path`、`dataset_metadata_path`、`data_file_keys`、`model_paths`、`fp8_models`、`lora_base_model`、`lora_target_modules`、`lora_rank`、`height`、`width`、`num_frames`。

6. DiffSynth-Studio Model Training 文档
   URL: https://diffsynth-studio-doc.readthedocs.io/en/latest/Pipeline_Usage/Model_Training.html
   采用信息：
   - 训练基于 `accelerate launch`。
   - `--model_paths` 使用 JSON 格式。
   - LoRA 示例使用 `--lora_base_model dit --lora_target_modules "to_q,to_k,to_v" --lora_rank 32`。
   - 当前训练框架不支持 batch size > 1。
   - 视频模型训练建议开启 gradient checkpointing，显存紧张时启用 offload。
   - FP8 适合不更新梯度或只更新 LoRA 的模块，可降低显存。

7. comfyUI-Realtime-Lora 仓库
   URL: https://github.com/ShootTheSound/comfyUI-Realtime-Lora
   本地下载：`wan22-video-training/external/comfyUI-Realtime-Lora`
   采用信息：
   - 该插件 README 声称可在 ComfyUI 内训练、分析、选择性加载 LoRA，并支持 Wan 2.2。
   - 插件包含 `Realtime LoRA Trainer (Wan 2.2 - Musubi Tuner)` 节点，支持 High/Low/Combo noise modes。
   - 插件要求为 Wan 2.2 训练准备 Musubi Tuner、fp16 Wan 2.2 模型、VAE 和 T5。
   - 本仓库中 `musubi_wan_lora_trainer.py` 使用 `wan_train_network.py`、`wan_cache_latents.py`、`wan_cache_text_encoder_outputs.py`，并提供 512/768/1024/1256 与 fp8 的 VRAM preset。

## 未核验或需谨慎的信息

- 没有找到可靠一手来源确认名为 `ComfyUI-Training-Evolved` 的插件仓库。本文档把它作为“待核验名称”，实际可操作路线以 ComfyUI 内置训练节点、`comfyUI-Realtime-Lora`、DiffSynth-Studio 为准。
- 24GB+ 显存可以尝试的是低分辨率、batch_size=1、offload/fp8 小跑；当前项目主用的 Wan2.2-I2V-A14B 720P 正式训练不能按 24GB 舒适运行来承诺。

## 当前项目本地依据

- ComfyUI workflow: `backend/src/main/resources/comfyui/workflows/video_wan2_2_14B_i2v.json`
- 当前记忆中的本地模型线索：
  - `wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors`
  - `wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors`
  - `wan2.2_i2v_lightx2v_4steps_lora_v1_high_noise.safetensors`
  - `wan2.2_i2v_lightx2v_4steps_lora_v1_low_noise.safetensors`
  - `wan_2.1_vae.safetensors`
  - `umt5-xxl-enc-bf16.safetensors`

## 结论

- 本地 32GB 显存适合做数据准备、小规模 LoRA 试跑、低分辨率预处理和质量门禁。
- 第一版工程路线：先整理授权数据和 manifest，再跑 DiffSynth-Studio I2V LoRA 小样本；ComfyUI 内置训练节点作为 GUI 小跑/实验路线。
- 如果目标是稳定 720P Wan2.2 A14B 正式训练，应保留云端 80GB 级 GPU 方案。
