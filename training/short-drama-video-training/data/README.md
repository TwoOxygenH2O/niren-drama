# 数据目录说明

本目录只提交模板和说明，不提交原始训练视频、图片、模型权重或训练输出。

## 推荐本地目录

```text
raw/
  videos/
  first_frames/
  scripts/
processed/
  ltx_dataset/
  wan_dataset/
  quality_gate/
runs/
checkpoints/
models/
```

## Manifest 使用方式

1. 复制模板：

```powershell
Copy-Item data/manifests/shot_manifest.template.csv data/manifests/shot_manifest.v001.csv
Copy-Item data/manifests/quality_labels.template.csv data/manifests/quality_labels.v001.csv
```

2. 填入真实素材路径和标签。
3. `license_status` 必须明确。
4. `usable_for_lora=true` 只能给高质量正样本。
5. 每次训练前固定一个数据版本，例如 `dataset_v001`。

## 数据拆分

按项目拆分，不要随机按镜头拆分：

- `train`: 80%
- `val`: 10%
- `test`: 10%

同一项目、同一角色、同一场景不要同时进入训练集和测试集，否则评估会虚高。
