# CASR: Continuity-Aware Self-Repair for AI Short-Drama Production Pipelines

Target: arXiv preprint or equivalent public technical report.

Submission posture: remote-first. This paper should not depend on conference attendance, in-person demo booths, or live presentation slots.

## Abstract Draft

AI short-drama production systems increasingly combine script generation, storyboard decomposition, image generation, image-to-video synthesis, speech synthesis, and final video composition. Existing tools primarily focus on generating content or providing end-to-end authoring environments, while production teams still need a practical mechanism to diagnose generated failures, explain their causes, and choose repair actions under limited cost and time budgets. This paper introduces CASR, a Continuity-Aware Self-Repair algorithm for AI short-drama production pipelines. CASR builds a shot graph from storyboards, assets, consistency anchors, task records, and quality issues; scores structural quality and narrative continuity; attributes failures into actionable categories; and performs cost-aware policy search to recommend repair paths. A working system implementation demonstrates how CASR can expose missing first frames, identity drift risks, scene drift risks, motion failures, black frames, stale tasks, and duration anomalies, then recommend repair sequences with explicit cost, time, risk, success probability, and expected gain.

## 1. Introduction

Short-drama production pipelines are multi-stage multimedia systems. A single episode may pass through story ideation, script generation, storyboard decomposition, character and scene consistency management, first-frame generation, image-to-video synthesis, speech synthesis, subtitle rendering, and final composition. In practice, pipeline failures are not isolated. A missing first frame may increase identity drift risk. A stale generation task may block production state. A black frame or frozen video may break the rhythm of the final episode.

The central question in this paper is: how can a production system diagnose generated failures and select repair actions without simply rerunning everything?

CASR addresses this question as a post-generation repair layer. Instead of replacing video generators, it reasons over pipeline state, asset availability, task records, consistency anchors, quality issues, cost, time, and risk.

## 2. Related Work Positioning

CASR should be positioned against four nearby areas:

- Reference-based consistency generation: improves the generated output by conditioning on references.
- End-to-end authoring tools: connect script, storyboard, and video production flows.
- Short-drama foundation models: improve human-centric video generation.
- Video generation benchmarks: evaluate generated video quality.

CASR is different because it operates after generation and turns pipeline failures into an explainable repair planning problem.

## 3. Method

### 3.1 Shot Graph

Each shot node contains:

- Storyboard metadata.
- First-frame, video, audio, and preview asset state.
- Task status and task age.
- Character, wardrobe, scene, and style anchors.
- Existing production issues.
- Candidate repair actions.

### 3.2 Failure Attribution

Supported failure types:

- `missing_first_frame`
- `missing_media`
- `identity_drift_risk`
- `wardrobe_drift_risk`
- `scene_drift_risk`
- `motion_failure`
- `black_frame`
- `frozen_frame`
- `duration_out_of_range`
- `wrong_aspect_ratio`
- `video_task_failed`
- `stale_task`

### 3.3 Scoring

CASR computes:

- `qualityScore`: structural production readiness.
- `continuityScore`: continuity risk across character, wardrobe, scene, first-frame, and prompt inheritance.

### 3.4 Cost-Aware Policy Search

CASR evaluates candidate repair paths with:

```text
reward = scoreGain - costPenalty - timePenalty - riskPenalty
```

Actions include:

- `snapshot`
- `regenerateFirstFrame`
- `retryVideo`
- `switchLtx`
- `switchWan`
- `useFirstFrameOnly`
- `composePreview`
- `qualityCheck`

The first version uses deterministic policy search for stable demonstration and explanation. Later versions can extend this to MCTS and learned visual evaluators.

## 4. System Implementation

Implementation components:

- Backend: `CasrAnalysisService`, `CasrPolicySearchService`, `CasrWorkflowService`, `CasrDemoService`.
- API: analyze, plan, execute, and create demo endpoints.
- Persistence: `drama_casr_run` stores run summary, scores, failure types, strategy tree, recommendation, and cost estimate.
- Frontend: CASR self-repair laboratory in the episode workbench.

## 5. Case Study

Use the built-in CASR demo project:

- Seven shots.
- Missing first frame.
- Video task failure.
- Black frame.
- Duration anomaly.
- Identity and scene drift risk.
- Stale task.

Compare CASR against naive retry:

- Number of shots touched.
- Estimated cost.
- Estimated time.
- Number of explainable failure types.
- Whether user confirmation is required.

## 6. Limitations

The first version does not use heavy visual models. Identity drift, scene drift, and motion risks are inferred from pipeline state, assets, task records, issues, and prompt-anchor inheritance. Future work can add CLIP similarity, face identity checks, frame sampling, optical flow, and learned transition models.

## 7. Conclusion

CASR turns post-generation failure handling into a computable and explainable repair-planning problem. It is useful as a production reliability layer, a research prototype, and a research demo artifact.

## Figures To Add

- Figure 1: CASR system architecture.
- Figure 2: Shot graph representation.
- Figure 3: Workbench screenshot with CASR analysis.
- Figure 4: Strategy tree and reward components.
- Figure 5: Case-study comparison against naive retry.

## References To Keep

- Runway Gen-4 References.
- LTX Studio.
- SkyReels-V1.
- VBench-2.0.
- Related work on self-healing systems, workflow repair, cost-aware planning, and video quality assessment.
