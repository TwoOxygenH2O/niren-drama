# Remote CASR Demo Video Script

Target length: 3:30 to 4:30.

Audience: research reviewers, arXiv readers, and remote collaborators.

## 0:00-0:25 Opening

This is CASR, a Continuity-Aware Self-Repair layer for AI short-drama production pipelines. Instead of only generating more video, CASR diagnoses production failures, explains their causes, and recommends cost-aware repair actions.

Visual:

- Show project README or dashboard.
- Highlight CASR self-repair demo entry.

## 0:25-0:55 Create Demo Project

The demo creates a reproducible short-drama project with seven shots and common production failures: missing first frames, failed video tasks, black frames, duration anomalies, stale tasks, and continuity risks.

Visual:

- Click "Create CASR Demo".
- Open the generated workbench.

## 0:55-1:45 Run CASR Analysis

CASR builds a shot graph from storyboards, assets, task records, consistency anchors, and quality issues. It computes a structural quality score and a continuity score, then labels each shot with actionable failure types.

Visual:

- Run CASR analysis.
- Show score cards.
- Show shot risk cards or graph.

## 1:45-2:35 Inspect Failure Attribution

The system maps symptoms to repairable causes. For example, a missing first frame increases identity drift risk, while a stale task blocks production state. This makes the workflow inspectable instead of relying on manual guessing.

Visual:

- Zoom into failure tags.
- Show one or two shot explanations.

## 2:35-3:25 Generate Repair Plan

CASR compares repair paths using a reward function: score gain minus cost, time, and risk penalties. A typical recommendation is to save a snapshot, switch to a higher-continuity workflow, rerun only high-risk shots, and then perform another quality check.

Visual:

- Generate repair plan.
- Show strategy tree.
- Show gain, cost, time, risk, and success probability.

## 3:25-4:10 Close

CASR is a remote-friendly research artifact: a full-stack product feature, a backend algorithm service, an explainable frontend visualization, and a research paper draft. It complements video generators by adding reliability and decision support after generation.

Visual:

- End on CASR panel and README paper section.
