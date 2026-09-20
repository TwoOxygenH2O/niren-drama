# 小说旁白与人物演绎：第一集认可版本

2026-09-20，用户认可《借命不还》第一集《背面》联合修订版的方向，要求提交代码并继续第二集。该认可不等于商业发行或法律审核通过。

## 固定参数

- H3 普通 I2V，INT8 ConvRot pruned，Qwen3-VL NVFP4 文本编码器。
- 768×1344、24 fps、20 步、res_multistep、simple、BasicGuider、denoise=1。
- Turbo 关闭；不插帧、不变速、不用静帧补足时长。最终居中裁切为 756×1344。
- 每集一条 Qwen3-TTS CustomVoice Serena 旁白母带。旁白不送入 H3 画面提示词。
- 对白使用 H3 原生声音。旁白与对白交替，不混响重叠。不克隆专业演员。
- 根据真实语音和动作完成点剪辑，保留完整口语句子。ASR/VAD 仅核对文本与时间，不代表演技合格。
- 字幕按最终音轨对齐，白色中等字重、细描边，最多两行，按语义断句。

## 可复现输入

`docs/production-recipes/` 保存已采用的 manifest、参考首帧、剪辑覆盖、旁白正文和 SHA256 索引。原始视频、模型、音频缓存和凭据不上传 Git。

- `jie-ming-ep01-v2`：第一集当前认可版本。
- `jie-ming-ep02-v2-final`：第二集最终审看片输入，包含补拍、废片排除、裁切和字幕时间修正，后续复现使用这一版。
- `jie-ming-ep02-v2`：第二集开始制作时的输入快照，保留对照，不包含后续修复。
- `jie-ming-ep01` / `jie-ming-ep02`：此前版本，保留测试和对照，不代表推荐成片。

第一集当前交付：76.958 秒、1847 帧，字幕版 SHA256 `fc6812d141b4ca1b723d7bbe3f31153abdb9dcc775ed9cba101f92758b0c52db`。成片仅保存在本机 `output/jie-ming-ep01-v2/final/`。

第二集当前交付：94.708 秒、2273 帧，字幕版 SHA256 `959949d3ec884cef23bb3e4b12d281d5521e58b6aefd18927f0939facf2a73b8`。成片位于本机 `output/jie-ming-ep02-v2/final/`；详见 [第二集制作记录](jie-ming-ep02-v2-delivery.md)。两集均为24fps，不插帧、不变速。

## 运行

需要已有 ComfyUI H3 节点/模型、FFmpeg/FFprobe、PyTorch、transformers、qwen-tts、librosa、soundfile、requests、numpy、scipy，以及 `scripts/requirements-narration-alignment.txt`。模型和字体应另行核对授权。原有 `.venv-narration` 是本机运行环境，不纳入 Git。

1. 启动已验证的 ComfyUI 服务 `127.0.0.1:8188`。不要自动升级内核、模型或 attention 实现。
2. 用 `episode-recipe.py verify --source docs/production-recipes/jie-ming-ep01-v2` 验证输入。
3. 用 `episode-recipe.py restore --source docs/production-recipes/jie-ming-ep01-v2 --destination output/replay-ep01` 创建新目录，不覆盖任何旧结果。
4. `generate-qwen3-tts.py --manifest output/replay-ep01/audio/narration-manifest.json --output-dir output/replay-ep01/audio --model <本机模型路径> --device cpu --seed 2026091932`。不要设 maxDuration，避免触发音频变速。
5. `align-narration-subtitles.py --audio output/replay-ep01/audio/narration-master.wav --text output/replay-ep01/audio/narration.txt --output output/replay-ep01/audio/narration-alignment.json`。
6. `produce-zhouzong-episode.py --output-dir output/replay-ep01 --from-take <manifest 中第一个 take id>`。名称是历史兼容名，实际读取指定 manifest。已明确排除的失败镜头不再提交。
7. `qa-zhouzong-dialogue.py --output-dir output/replay-ep01 --watch`。ASR 错误不能靠直接改为 aligned 绕过；核对音频或重新生成。
8. 根据新声音重新核对 `edit-overrides.json`。同种子也不保证跨设备/内核逐字节复现；不得照搬旧时间轴覆盖新语音。
9. 使用 `prepare-subtitle-font.py` 从有授权的 Noto Sans SC 可变字体生成 500 字重 `Niren Subtitle Sans`，放入 `backend/uploads/fonts`，保留生成的授权说明。
10. `compose-novel-performance.py --output-dir output/replay-ep01`，然后 `verify-novel-performance.py --folder output/replay-ep01/final`。

## 检查与限制

```powershell
$env:PYTHONPATH = (Resolve-Path scripts).Path
python -m unittest scripts/test_novel_performance.py scripts/test_novel_media.py scripts/test_speech_activity.py scripts/test_opening_pair_revision.py scripts/test_episode_recipe.py scripts/test_production_checkpoint.py
```

检查覆盖禁用失败镜头、语音完整性、声源互斥、字幕边界、帧数/PTS、实际 FFmpeg 拼接和输入导出完整性。不把 technicalPassed 当商业质量评分。每轮还需实际看人物、道具、视线和剪辑。

当前仍有轻微袖口纹理、纸张细节变化；H3 原生对白的跨镜头音色也没有声纹级锁定。第二集需延续右手借条、左袖命牌、左窗右门的空间关系。

## 官方模板来源

`templates/video_minimax_h3_i2v.json` 为此前下载并核验的 Comfy-Org 模板快照（2026-09-16）。来源：https://github.com/Comfy-Org/workflow_templates/blob/main/templates/video_minimax_h3_i2v.json 。运行器读取非 Turbo 支路，并检查节点链接和采样默认值；模型权重不随本项目分发。
