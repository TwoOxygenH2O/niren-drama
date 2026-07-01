# ComfyUI Short Drama Experiment Design

Date: 2026-07-01
Workspace: `D:\javaProject\niren-drama`

## Purpose

The current short-drama generation quality is not yet good enough for watchable commercial output. The first goal is not to force the Niren Drama end-to-end pipeline to produce final videos. Instead, we will use ComfyUI as a controlled experiment bench, repeatedly generate and review short-drama clips, and then fold the proven methods back into Niren Drama.

The target outcome is a reproducible method for generating better, longer, more consistent vertical short-drama videos with open video models.

## First Experiment Scope

The first experiment will produce one 30-second vertical baseline short film.

- Format: 9:16 vertical short drama.
- Topic: Chinese urban family anti-fraud drama.
- Structure: 6 shots, about 4-5 seconds per shot.
- Characters: 2 recurring characters, one daughter and one father.
- Main model path: Wan2.2 image-to-video.
- Fallback path: LTX-2.3 only for fast previews or low-cost motion tests.
- Output target: a reviewable MP4 with TTS, subtitles, BGM, and a per-shot experiment log.

This is intentionally small. The first success criterion is not perfection; it is a repeatable loop that makes every failure useful.

## Chosen Approach

Use segmented generation and composition rather than one continuous 30-second model rollout.

Reasons:

- Current open video models still struggle with long temporal consistency, multi-subject identity, and accumulated drift.
- Niren already has Wan2.2 I2V workflow files and FFmpeg composition scripts.
- Segmenting gives us human review checkpoints after each shot.
- A 30-second video can be improved one shot at a time without regenerating the whole film.

The working assumption is:

```text
longer short drama = planned shots + stable references + per-shot quality gates + composition
```

not:

```text
longer short drama = one very long prompt
```

## Workflow Variants

The first experiment will compare three existing Wan2.2 workflow variants already present in the repository.

| Variant | Workflow | Use |
| --- | --- | --- |
| A | `video_wan2_2_14B_i2v_series_balanced.json` | Cheap baseline, fast iteration, short clips |
| B | `video_wan2_2_14B_i2v.json` | Main quality baseline |
| C | `video_wan2_2_14B_i2v_quality_long.json` | Longer per-shot test when identity stays stable |

The experiment should change only one important variable per retry: workflow variant, frame count, reference strength, prompt wording, seed, or motion instruction.

## Story Baseline

Working title: `别急着转账`

Logline:

A tired emergency doctor receives a fake refund call targeting her father. She almost follows the scammer's instructions, but her father quietly reveals he has been delaying the caller while waiting for help.

Shot list:

1. Daughter in hospital break room notices suspicious refund call.
2. Daughter hesitates, guilt rising because the caller mentions her father.
3. Phone evidence close-up: refund page and screen-share warning, no readable fake text.
4. Father at home calmly talks to the caller and delays them.
5. Daughter realizes the trap and stops the operation.
6. Father and daughter reconnect in a restrained emotional ending.

Each shot must be a single continuous take. No cuts, no new people, no clothing changes, no style switches inside one generated clip.

## Prompt Rules

Every video prompt should include four layers:

1. Global style lock: commercial vertical Chinese live-action short drama, photorealistic, stable color grade.
2. Reference lock: use the input image as the exact first frame; preserve face, hairstyle, wardrobe, props, lighting, camera angle, and room layout.
3. Motion plan: one clear action beat with beginning, middle, and end.
4. Negative constraints: no identity drift, no outfit change, no new person, no scene jump, no subtitles, no logo, no watermark, no sketch, no manga, no monochrome, no slideshow.

The prompt should describe actor-local motion first: blinking, breathing, eye movement, thumb movement, phone glow, small head turn, hand tension, cloth movement, and subtle lighting movement. Whole-frame pans, fake zooms, and uncontrolled camera movement are discouraged.

## Quality Gates

Every generated shot will be classified before composition.

Automatic checks:

- Duration is close to the intended shot duration.
- Resolution is vertical 9:16.
- File is playable.
- No black or frozen output.
- FPS is acceptable.
- Optional later checks: frame similarity, face consistency, optical flow, CLIP/aesthetic scoring, VMAF-like compression quality after composition.

Human checks:

- Usable: can be included in the current edit.
- Repairable: useful idea, but one clear defect needs a retry.
- Rejected: not usable for the current film.

Human review form:

```text
Usability: usable / repairable / rejected
Usable seconds:
Main issue: identity drift / wardrobe drift / weak motion / bad acting / style drift / continuity break / awkward cut / other
Next retry focus:
One-sentence review:
```

## Experiment Log

Every run should produce a structured summary.

```text
Run ID:
Shot:
Workflow:
Input frame:
Prompt:
Negative prompt:
Key parameters:
Output:
Automatic quality result:
Human rating:
Failure cause:
Next retry changes exactly one variable:
Reusable rule learned:
```

The logs should be append-only. Over time, they become the raw material for both product improvement and a paper-style methodology report.

## Integration Back Into Niren Drama

Only proven rules should be integrated into the main app.

Likely integration points:

- Storyboard prompt generation should emit shot-level motion plans, not just visual descriptions.
- Character and scene modules should maintain stronger visual anchors.
- The video provider should expose workflow variant selection and quality retry metadata.
- CASR should use the experiment failure taxonomy for diagnosis and repair planning.
- The production workspace should show per-shot review status and the learned retry recommendation.

Avoid integrating experimental complexity until it has repeated evidence.

## Error Handling

If ComfyUI is offline, the experiment should stop with a clear environment error.

If a shot fails, the system should keep all successful shots and retry only the failed shot.

If a generated shot drifts badly, the next retry should usually lower motion/noise or shorten the clip before changing story content.

If a shot is boring but stable, the next retry should improve actor-local motion rather than increasing camera movement.

## Success Criteria

First pass success:

- A 30-second MP4 exists.
- All 6 shots are reviewable.
- At least 3 shots are rated usable or repairable.
- Every shot has an experiment log.
- The next retry decision is clear.

Methodology success:

- We can explain which workflow variant worked best for which shot type.
- We can identify recurring failure types.
- We can state which prompt and parameter changes improved usable seconds.
- We can translate at least three learned rules back into Niren Drama.

## Non-Goals

- Do not attempt a polished commercial final video in the first run.
- Do not implement a new training pipeline in this first experiment.
- Do not force one 30-second single-shot model rollout as the main path.
- Do not hide failed generations; failed shots are useful evidence.

## Immediate Next Step

Create or adapt a script that runs the 6-shot baseline through the existing Wan2.2 I2V workflow, records outputs and quality metadata, then asks the user to review the generated MP4 and each shot.
