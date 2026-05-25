# Niren Drama Project Memory

Last updated: 2026-05-25

Niren Drama is a full-stack AI short drama production platform for vertical 9:16 short episodes. The core workflow is: idea -> project planning -> outline/script -> characters/scenes -> storyboard -> reference images -> ComfyUI dynamic video clips -> TTS -> FFmpeg composition -> final export.

## Runtime Stack

- Backend: Spring Boot 3.2, Java 17, Maven, MyBatis-Plus, MySQL.
- Frontend: Vue 3, TypeScript, Vite, Element Plus, Pinia.
- API response shape: `Result<T>` with `{ code, message, data }`.
- Async generation progress is stored in `drama_task_record`.

## Current AI/Media Defaults

- Text, image, video, and TTS providers resolve through `AiProviderFactory`.
- Video generation defaults to ComfyUI at `http://127.0.0.1:8188`.
- Default video model: `ltx-2-19b-distilled.safetensors`.
- ComfyUI local check on 2026-05-25: `/system_stats` returned 200; required LTX/VHS nodes and the default checkpoint were present.
- FFmpeg composition is handled by `VideoCompositionService`; dynamic shot clips must exist before final composition.

## Main Product Flow

- `DashboardView` creates a project from a one-line idea and routes into immersive creation.
- `ImmersiveCreateView` drives outline, character confirmation, script confirmation, storyboard readiness, and video generation entry points.
- `EpisodeWorkbenchView` lets users inspect episode shots and jump to synthesis/export.
- `SynthesisView` is the final operational page for generating selected shot videos, generating audio, composing partial/full videos, previewing, and downloading.

## Commercialization Notes

- Project ownership checks must be preserved on every project-scoped endpoint.
- COS credentials must be injected by environment variables; defaults are intentionally empty/disabled.
- Local browser/automation artifacts should not be committed.
- ComfyUI workflows should prefer known classpath/inline templates over stale user-workflow references unless explicitly configured.
