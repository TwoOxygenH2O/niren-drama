#!/usr/bin/env python3
"""Generate production Chinese voice tracks with Qwen3-TTS."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf
import torch
from qwen_tts import Qwen3TTSModel


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument(
        "--model",
        default="Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice",
    )
    parser.add_argument("--speaker", default="Vivian")
    parser.add_argument("--device", default="cuda:0")
    parser.add_argument("--seed", type=int, default=20260713)
    parser.add_argument(
        "--candidates",
        type=int,
        default=1,
        help="Number of performance candidates per line; the closest duration is selected",
    )
    parser.add_argument("--shots", help="Comma-separated shot numbers to regenerate")
    return parser.parse_args()


def trim_excess_silence(waveform: np.ndarray, sample_rate: int) -> np.ndarray:
    """Keep natural breath room while removing long model-added head/tail silence."""
    samples = np.asarray(waveform, dtype=np.float32).squeeze()
    if samples.ndim != 1 or samples.size == 0:
        raise ValueError("Qwen3-TTS returned an empty or unsupported waveform")

    peak = float(np.max(np.abs(samples)))
    if peak <= 1e-6:
        return samples

    active = np.flatnonzero(np.abs(samples) >= peak * 0.012)
    if active.size == 0:
        return samples

    head = int(sample_rate * 0.07)
    tail = int(sample_rate * 0.12)
    start = max(0, int(active[0]) - head)
    end = min(samples.size, int(active[-1]) + tail + 1)
    return samples[start:end]


def main() -> None:
    args = parse_args()
    payload = json.loads(args.manifest.read_text(encoding="utf-8"))
    entries = payload.get("voice") if isinstance(payload, dict) else payload
    if not isinstance(entries, list) or not entries:
        raise ValueError(
            "manifest must be a non-empty JSON array or contain a non-empty voice array"
        )

    if args.shots:
        requested = {
            int(value.strip()) for value in args.shots.split(",") if value.strip()
        }
        entries = [entry for entry in entries if int(entry["shot"]) in requested]
        if not entries:
            raise ValueError("--shots did not match any voice entries")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    model = Qwen3TTSModel.from_pretrained(
        args.model,
        device_map=args.device,
        dtype=torch.bfloat16,
        attn_implementation="sdpa",
    )

    results: list[dict[str, object]] = []
    for index, entry in enumerate(entries):
        output_name = str(entry["output"])
        output_path = args.output_dir / output_name
        shot_no = int(entry.get("shot", index + 1))
        candidate_count = max(
            1,
            args.candidates,
            int(entry.get("candidates", 1)),
        )
        target_duration = float(entry.get("voiceTargetDuration", 0.0))
        max_duration = float(entry.get("maxDuration", 0.0))
        performance_instruction = str(entry.get("instruct", "")).strip()
        if target_duration > 0:
            performance_instruction = (
                f"{performance_instruction} 整句必须在{target_duration:.1f}秒内自然说完，"
                "保持真实连读，不添加额外停顿、吸气、语气词或句尾拖音。"
            ).strip()
        candidate_reports: list[dict[str, object]] = []
        selected: tuple[float, np.ndarray, int, int, float] | None = None

        print(
            f"[{index + 1}/{len(entries)}] generating {output_name} "
            f"({candidate_count} candidates)",
            flush=True,
        )
        for candidate_no in range(1, candidate_count + 1):
            seed = args.seed + (shot_no - 1) * 101 + candidate_no - 1
            torch.manual_seed(seed)
            torch.cuda.manual_seed_all(seed)
            wavs, sample_rate = model.generate_custom_voice(
                text=str(entry["text"]),
                language="Chinese",
                speaker=str(entry.get("speaker", args.speaker)),
                instruct=performance_instruction,
                do_sample=True,
                temperature=float(entry.get("temperature", 0.78)),
                top_p=float(entry.get("topP", 0.86)),
                repetition_penalty=float(entry.get("repetitionPenalty", 1.1)),
            )
            waveform = trim_excess_silence(wavs[0], sample_rate)
            duration = waveform.size / float(sample_rate)
            duration_score = abs(duration - target_duration) if target_duration > 0 else duration
            over_budget = max(0.0, duration - max_duration) if max_duration > 0 else 0.0
            score = duration_score + over_budget * 8.0
            candidate_reports.append(
                {
                    "candidate": candidate_no,
                    "durationSeconds": round(duration, 3),
                    "seed": seed,
                    "score": round(score, 4),
                    "overBudget": over_budget > 0,
                }
            )
            if selected is None or score < selected[0]:
                selected = (score, waveform, sample_rate, seed, duration)
            print(
                f"    candidate={candidate_no} duration={duration:.3f}s score={score:.4f}",
                flush=True,
            )

        if selected is None:
            raise RuntimeError(f"No TTS candidate generated for shot {shot_no}")
        _, waveform, sample_rate, seed, duration = selected
        raw_duration = duration
        tempo_correction = 1.0
        if max_duration > 0 and duration > max_duration:
            corrected_target = max(0.4, max_duration - 0.08)
            proposed_rate = duration / corrected_target
            if proposed_rate <= 1.25:
                waveform = librosa.effects.time_stretch(
                    y=np.asarray(waveform, dtype=np.float32),
                    rate=proposed_rate,
                )
                duration = waveform.size / float(sample_rate)
                tempo_correction = proposed_rate
        sf.write(output_path, waveform, sample_rate, subtype="PCM_16")
        results.append(
            {
                "shot": shot_no,
                "text": entry["text"],
                "speaker": entry.get("speaker", args.speaker),
                "path": str(output_path.resolve()),
                "sampleRate": sample_rate,
                "durationSeconds": round(duration, 3),
                "rawDurationSeconds": round(raw_duration, 3),
                "tempoCorrection": round(tempo_correction, 4),
                "passedBudget": max_duration <= 0 or duration <= max_duration,
                "seed": seed,
                "voiceTargetDuration": target_duration or None,
                "maxDuration": max_duration or None,
                "candidates": candidate_reports,
            }
        )
        print(
            f"    selected seed={seed} raw={raw_duration:.3f}s "
            f"final={duration:.3f}s tempo={tempo_correction:.4f}",
            flush=True,
        )

    report_path = args.output_dir / "qwen3-tts-report.json"
    if args.shots and report_path.exists():
        existing = json.loads(report_path.read_text(encoding="utf-8"))
        if isinstance(existing, list):
            regenerated = {int(item["shot"]) for item in results}
            results = [
                item
                for item in existing
                if int(item.get("shot", -1)) not in regenerated
            ] + results
            results.sort(key=lambda item: int(item.get("shot", 0)))
    report_path.write_text(
        json.dumps(results, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"report={report_path.resolve()}", flush=True)


if __name__ == "__main__":
    main()
