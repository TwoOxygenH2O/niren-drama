# 候选训练素材扫描

扫描时间：2026-05-28

## 扫描范围

- `backend/uploads`
- `output`

## 扫描结果

| 类型 | 数量 | 总大小 |
| --- | ---: | ---: |
| 视频 | 90 | 约 222 MB |
| 图片 | 366 | 约 398 MB |

## 观察

- 已发现 `backend/uploads/generated-videos`、`backend/uploads/generated-images`、`backend/uploads/dynamic-chain` 中有候选素材。
- 这些素材还不能直接进入 LoRA 训练，因为缺少授权确认、镜头元数据、prompt、workflow、质量标签和首帧-视频配对。
- 下一步应从数据库导出 `shotId -> imageUrl -> videoUrl -> prompt -> workflow -> taskId` 的血缘关系，再生成正式 `shot_manifest.v001.csv`。

## 下一步执行条件

1. 后端数据库可连接。
2. 当前素材确认可以用于训练。
3. 人工先标 30-50 个样本作为 `ok / bad reason` 校准集。
4. 再由脚本批量生成 manifest 和待审核质检表。
