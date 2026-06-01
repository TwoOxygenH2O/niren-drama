# 短剧视频模型训练资料包

本目录用于沉淀当前短剧生产线的模型训练资料、数据规范、训练步骤和验收标准。目标不是直接重训 Wan2.2 或 LTX-2 基座模型，而是围绕现有 ComfyUI 工作流训练和接入更轻量、可回滚的增量能力：

- 分镜切分模型：让剧本更稳定地拆成可生成的 9:16 短剧镜头。
- 视频 LoRA / IC-LoRA：让 I2V 更适配短剧镜头语言、人物连续、服装连续、场景连续。
- 质量门禁模型：自动识别黑屏、冻结、线稿化、人物漂移、服装变化、突然多人物。
- Prompt 策略模型：把分镜描述稳定改写成单镜头连续运动提示词。

## 当前还值得继续改的地方

1. 数据闭环还不够：需要从 `drama_storyboard`、`drama_task_record`、`drama_asset_snapshot`、`drama_production_issue` 导出训练样本，并给每个镜头打“好/坏/原因”标签。
2. 质检还要从启发式升级到模型化：现在本地规则能查比例、时长、黑屏、冻结；人物漂移、服装变化、多人物需要视觉模型和人工确认数据。
3. 分镜切分需要可学习：现在分镜质量主要靠提示词和规则，后面应积累“剧本文本 -> 镜头 JSON”的 SFT 数据。
4. 视频生成需要 A/B 路由：LTX 适合预览快测，Wan2.2 适合发布质量；系统要按镜头档位、失败原因、历史通过率自动选路线。
5. LoRA 接入需要产品化：设置页已有预设入口，后端还应把 LoRA 名称、强度、版本、适用档位纳入 `workflowPreset`。
6. 发布验收需要更硬：发布包要补 SRT/ASS 文件、封面安全区、平台码率检查、可追溯 manifest。
7. 样本资产要去重：同一角色、同一场景、近似镜头不要重复过多，否则 LoRA 容易过拟合。

## 推荐训练顺序

先训练可控、低风险的模型，再动视频 LoRA：

1. 分镜切分 SFT 数据集：低成本，直接改善短剧节奏。
2. Prompt 策略数据集：把“镜头描述”稳定转成“连续单镜头运动提示词”。
3. 质量门禁模型：先做黑屏/冻结/线稿/漂移标签识别，辅助重试策略。
4. LTX-2 预览 LoRA：用于快测，训练成本相对可控。
5. Wan2.2 I2V LoRA：用于发布质量，只用高质量、已授权、短剧风格统一的数据。

## 目录说明

```text
training/short-drama-video-training/
  README.md
  TRAINING_RUNBOOK.md
  TRAINING_STATUS.md
  data/
    README.md
    manifests/
      shot_manifest.template.csv
      quality_labels.template.csv
      segmentation_sft.template.jsonl
      prompt_policy.template.jsonl
  configs/
    ltx2_i2v_lora_draft.yaml
    wan22_i2v_lora_diffsynth_draft.yaml
    quality_gate_draft.yaml
```

`raw/`、`processed/`、`runs/`、`checkpoints/`、`models/` 已加入 `.gitignore`，只放本地训练素材和输出，不提交。

## 关键原则

- 只使用有授权的素材：自有拍摄、自生成且可商用、明确授权素材。
- 不训练“某个演员本人”的身份特征，除非有明确授权；短剧角色应训练“角色造型一致性”，不是现实个人身份克隆。
- 视频 LoRA 的训练目标要窄：竖屏短剧镜头、单镜头连续运动、人物/服装/场景稳定。
- 不把失败样本直接混入正样本；失败样本用于质量门禁和负例对照。
- 每个训练版本都要记录数据版本、基础模型、workflow、LoRA 强度、评估结果和回滚路径。

## 参考源

- LTX-2 官方仓库和 trainer：`https://github.com/Lightricks/LTX-2`
- LTX-2 trainer 数据准备文档：`https://github.com/Lightricks/LTX-2/blob/main/packages/ltx-trainer/docs/dataset-preparation.md`
- Wan2.2 官方仓库：`https://github.com/Wan-Video/Wan2.2`
- DiffSynth-Studio Wan 训练说明：`https://github.com/modelscope/DiffSynth-Studio/blob/main/docs/en/Model_Details/Wan.md`
