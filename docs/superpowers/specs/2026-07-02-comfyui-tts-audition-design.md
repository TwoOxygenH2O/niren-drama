# ComfyUI TTS Audition Design

## Goal

Build an independent TTS audition lane for Niren Drama that generates role voice candidates through ComfyUI TTS workflows, keeps them out of video composition until reviewed, and records enough metadata to compare voice quality across roles, prompts, reference audio, and emotion controls.

## Decision

Use ComfyUI TTS nodes as the primary execution layer. The backend will not call IndexTTS2, CosyVoice, Fish Speech, or GPT-SoVITS directly in the first version. Instead, it will submit configurable ComfyUI workflow templates and inject runtime values such as text, speaker reference audio, emotion reference audio, emotion text, emotion vector, seed, speed, and filename prefix.

The first target model is IndexTTS2 running inside ComfyUI because current ComfyUI IndexTTS2 wrappers expose voice cloning and emotion control. The implementation must remain workflow-template driven because available custom nodes differ in class names and input fields:

- `snicolast/ComfyUI-IndexTTS2` exposes Simple, Advanced, Emotion Vector, Emotion From Text, and Save Audio nodes.
- `kana112233/ComfyUI-kaola-IndexTTS2` exposes loader, voice clone, emotion audio, emotion vector, emotion text, and script dubbing nodes.
- `TTS-Audio-Suite` supports multi-engine TTS, character switching, IndexTTS2 emotion controls, and voice-folder driven character management.

## Scope

In scope:

- Project-level TTS audition task that generates `3-4` roles times `2-3` candidates each.
- A ComfyUI TTS provider/runner that can submit a workflow and poll ComfyUI history for audio outputs.
- Audio output storage under the normal public asset system, in an audition-specific directory.
- Result metadata that lets the user review voice only: role, candidate number, generated text, provider, workflow, prompt id, reference audio fields, emotion controls, seed, speed, duration, and public audio URL.
- Manual review state in the audition result: `pending`, `approved`, `rejected`, with rejection reasons carried in the API contract for the frontend review panel.
- No automatic use of audition audio in final videos.

Out of scope for this first implementation:

- Full frontend review UI beyond API-compatible response payloads.
- Database schema for permanent voice profiles.
- Automatic voice quality scoring by ASR or speaker verification.
- Direct Python process management for IndexTTS2.
- Replacing existing `/videos/generate-audio/{projectId}` behavior.

## Architecture

Add a new audition lane beside the existing TTS flow:

1. `TtsAuditionController` receives a project-level audition request.
2. `TtsAuditionService` validates project ownership, selects roles, builds candidate texts and controls, creates a `TaskRecord` with task type `TTS_AUDITION`, and runs async generation.
3. `ComfyUiTtsProvider` loads a workflow template using `ComfyUiWorkflowLoader`, injects text/reference/emotion values, submits to `/prompt`, polls `/history/{prompt_id}`, finds audio outputs, downloads them through `/view`, and returns audio bytes plus ComfyUI metadata.
4. `PublicAssetStorageService` stores each candidate under `audios/audition/{projectId}/{taskId}`.
5. The task `result` stores a JSON audition package. This package is reviewable but does not update `drama_storyboard.audio_url`.

The first implementation should reuse the existing ComfyUI connection from AI video config. If the user has a dedicated TTS config with provider `comfyui`, that config can override the base URL and workflow file. This keeps the deployment simple while still allowing future separation.

## Workflow Template Contract

The backend should support both API-format and UI-format ComfyUI workflows using the existing loader conversion path. Runtime injection must be driven by a small mapping object in AI config `extra`, not by hardcoded node IDs.

Example `extra` shape:

```json
{
  "workflowFile": "tts_indextts2_audition.json",
  "fieldMap": {
    "text": [{"node": "12", "input": "text"}],
    "speakerAudio": [{"node": "10", "input": "audio"}],
    "emotionAudio": [{"node": "11", "input": "audio"}],
    "emotionText": [{"node": "12", "input": "emo_text"}],
    "useEmotionText": [{"node": "12", "input": "use_emo_text"}],
    "emotionVector": [{"node": "13", "input": "emotion_vector"}],
    "speed": [{"node": "12", "input": "speech_speed"}],
    "seed": [{"node": "12", "input": "seed"}],
    "filenamePrefix": [{"node": "20", "input": "filename_prefix"}]
  },
  "defaults": {
    "speed": 1.0,
    "emotionAlpha": 0.65,
    "candidateCount": 3
  }
}
```

If no `fieldMap` is provided, the provider may use heuristic injection:

- text-like inputs: `text`, `prompt`, `input_text`
- speaker reference inputs: `speaker_audio`, `spk_audio_prompt`, `reference_audio`, `audio`
- emotion text inputs: `emo_text`, `emotion_text`
- emotion vector inputs: `emotion_vector`
- save nodes: `filename_prefix`

Heuristic injection is allowed only as fallback. Production templates should use `fieldMap`.

## Audio Output Handling

ComfyUI history output detection must support audio-specific output keys first:

- `audio`
- `audios`
- `sounds`

It should keep current image/video detection as a fallback only if the file extension is audio-like. Candidate files with extensions `wav`, `mp3`, `flac`, `ogg`, or `m4a` are accepted.

The provider must build `/view?filename=...&type=...&subfolder=...`, download bytes, sniff format with `AudioFormatSupport`, and store the public copy. If ComfyUI returns no audio output, the task should fail that candidate with a clear error that includes prompt id and compact ComfyUI status.

## Audition Request

Initial request shape:

```json
{
  "characterIds": [1, 2, 3],
  "includeNarrator": true,
  "candidateCount": 3,
  "sampleText": "你不是说会保护我吗？为什么最后还是骗了我？",
  "roleOverrides": {
    "1": {
      "speakerReferenceAudioUrl": "https://example.com/daughter.wav",
      "emotionReferenceAudioUrl": "",
      "emotionText": "委屈、压抑、带一点哭腔，但吐字清楚",
      "emotionVector": [0.0, 0.0, 0.55, 0.1, 0.0, 0.35, 0.0, 0.15],
      "speed": 1.0
    }
  }
}
```

When `sampleText` is omitted, the service chooses short default lines by role:

- daughter: vulnerable accusation or suppressed crying
- father: restrained guilt or protective anger
- scammer: calm persuasive threat
- narrator: concise plot narration with low mechanical tone

Texts must be short enough for quick review. First version should cap each candidate line to about `80` Chinese characters.

## Audition Result

Task result JSON:

```json
{
  "mediaType": "tts_audition",
  "projectId": 100,
  "provider": "comfyui",
  "workflowFile": "tts_indextts2_audition.json",
  "summary": {
    "roles": 4,
    "candidateCount": 3,
    "generated": 11,
    "failed": 1
  },
  "roles": [
    {
      "roleType": "character",
      "characterId": 1,
      "characterName": "女儿",
      "candidates": [
        {
          "candidateNo": 1,
          "status": "pending",
          "audioUrl": "https://video.niren.life/api/files/audios/audition/...",
          "text": "你不是说会保护我吗？为什么最后还是骗了我？",
          "seed": 10123,
          "speed": 1.0,
          "promptId": "comfy-prompt-id",
          "workflowFile": "tts_indextts2_audition.json",
          "emotionText": "委屈、压抑、带一点哭腔，但吐字清楚",
          "emotionVector": [0.0, 0.0, 0.55, 0.1, 0.0, 0.35, 0.0, 0.15],
          "durationSeconds": 3.4,
          "review": {
            "decision": "pending",
            "reason": ""
          }
        }
      ]
    }
  ],
  "calls": []
}
```

The result is intentionally self-contained so the frontend review panel can render the audition package from the task alone.

## Review Gate

The audition package is a review artifact only. The video pipeline must not read it automatically. Later, a separate action can promote an approved candidate into a role voice profile, but that promotion is not part of this implementation.

The review vocabulary should include:

- `robotic`
- `wrong_role`
- `emotion_wrong`
- `emotion_too_much`
- `unclear_words`
- `speed_wrong`
- `noise_or_distortion`
- `reference_drift`
- `approved`

## Failure Handling

Candidate-level failures should not stop the whole audition unless all candidates fail. The task succeeds with warnings if at least one candidate is generated. Each candidate failure should include:

- role name
- candidate number
- ComfyUI prompt id if available
- compact error message
- whether the failure happened during workflow injection, queue submission, polling, output lookup, download, or storage

The service should cap candidate generation concurrency at `1` for the first version because local ComfyUI TTS nodes and GPU memory are sensitive to parallel load.

## Configuration

Add configuration under `niren.ai.tts`:

```yaml
niren:
  ai:
    tts:
      provider: ${AI_TTS_PROVIDER:aliyun}
      base-url: ${AI_TTS_BASE_URL:https://dashscope.aliyuncs.com/api/v1}
      workflow-file: ${AI_TTS_WORKFLOW_FILE:tts_indextts2_audition.json}
      audition:
        candidate-count: ${AI_TTS_AUDITION_CANDIDATE_COUNT:3}
        max-roles: ${AI_TTS_AUDITION_MAX_ROLES:4}
        max-text-chars: ${AI_TTS_AUDITION_MAX_TEXT_CHARS:80}
        poll-interval-ms: ${AI_TTS_AUDITION_POLL_INTERVAL_MS:2000}
        max-poll-attempts: ${AI_TTS_AUDITION_MAX_POLL_ATTEMPTS:900}
```

The AI config `extra` field may override workflow injection fields per user. This lets us support different custom nodes without redeploying.

## Tests

Backend tests should cover:

- Workflow field injection by explicit `fieldMap`.
- Fallback injection into common text and filename fields.
- History output detection for `audio`, `audios`, and file-extension fallbacks.
- Audition task result generation without mutating storyboard audio URLs.
- Partial failure behavior where one candidate fails and the task still succeeds with warnings.

No live ComfyUI dependency is required for unit tests; mock the provider response at the service boundary.

## References

- https://github.com/index-tts/index-tts
- https://github.com/snicolast/ComfyUI-IndexTTS2
- https://github.com/kana112233/ComfyUI-kaola-IndexTTS2
- https://github.com/diodiogod/TTS-Audio-Suite
- https://docs.comfy.org/development/core-concepts/custom-nodes
