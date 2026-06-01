# CASR Remote-First Publication and Research Package

This package removes publication paths that require on-site attendance or in-person demo presentation.

The goal is to produce a remote-friendly research and research release package:

- arXiv preprint or equivalent public technical report.
- Online demo video that can be shared with research reviewers and collaborators.
- GitHub/README research artifact material.
- Optional workshop submission only when the official call explicitly allows remote presentation or does not require attendance.

## Recommended Route

### Primary: arXiv + Research Demo

Use arXiv as the first public research artifact. It does not require conference travel or on-site presentation. The paper should be written as a technical system paper rather than a position paper:

- Problem: generated short-drama pipelines lack post-generation diagnosis and repair planning.
- Method: shot graph, failure attribution, quality and continuity scores, cost-aware policy search.
- System: Spring Boot backend, Vue workbench, deterministic demo project, user-confirmed execution.
- Evidence: case study on the CASR demo project, cost and explanation comparison against naive retry.
- Artifact: repository, README, screenshots, and a 3-5 minute online demo video.

Recommended arXiv categories to evaluate at submission time:

- `cs.MM`: Multimedia.
- `cs.AI`: Artificial Intelligence.
- `cs.SE`: Software Engineering, if the final emphasis is engineering workflow reliability.

### Secondary: Remote-Friendly Workshop Only

Do not target a demo track or workshop unless the current call for papers explicitly says remote presentation is accepted, presentation is optional, or publication does not depend on attendance.

If a venue requires at least one author to register and present in person, exclude it.

### Not Recommended Under This Constraint

The following are intentionally removed from the active plan:

- On-site demo tracks.
- Poster/demo tracks that require an in-person booth.
- Conference proceedings that require at least one author to attend and present.
- Any option where non-attendance risks removal from the proceedings.

## Files

- `paper/casr-arxiv-paper.md`: English arXiv-style paper draft plan and content skeleton.
- `video/remote-demo-script.md`: remote demo narration and timing.
- `video/demo-shot-list.md`: screens to capture for an online demo video.
- `submission-checklist.md`: practical checklist for a remote-first release.

## Repository Split

The algorithm artifact is maintained in the standalone repository `TwoOxygenH2O/casr-core`.
It contains the reusable Java 17 CASR domain model, diagnosis engine, cost-aware
policy search, standalone demo server, Vue demo web app, LaTeX manuscript source,
and release packaging scripts. The production system in `niren-drama` consumes the
algorithm through `com.twooxygen.casr:casr-engine:0.1.0-SNAPSHOT` and keeps only a
thin entity-to-algorithm adapter.

## Immediate Next Steps

1. Expand `paper/casr-arxiv-paper.md` into a full English manuscript.
2. Generate or capture 4-6 figures: system overview, shot graph, CASR panel, strategy tree, failure attribution, and cost comparison.
3. Create a stable online video link.
4. Prepare an arXiv-compatible source package after the manuscript is moved to LaTeX.
5. Add the paper and demo video to the README research artifact section.
