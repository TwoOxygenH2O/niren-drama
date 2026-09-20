"""CPU speech-boundary evidence; never an acting score or permission to trim audio."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path


def summarize(spans, duration):
    if not math.isfinite(duration) or duration <= 0:
        raise ValueError("Invalid audio duration")
    previous_end = 0.0
    for span in spans:
        start, end = span["start"], span["end"]
        if (not math.isfinite(start) or not math.isfinite(end)
                or start < previous_end or not 0 <= start < end <= duration + 1e-6):
            raise ValueError("Invalid speech intervals")
        previous_end = end
    if not spans:
        return {"speechDetected": False, "speechSeconds": 0, "spans": [],
                "reviewRequired": True, "reason": "No speech detected; do not infer a safe dialogue cut."}
    gaps = [{"start": a["end"], "end": b["start"], "duration": b["start"] - a["end"]}
            for a, b in zip(spans, spans[1:])]
    return {"speechDetected": True, "spans": spans,
            "speechSeconds": sum(s["end"] - s["start"] for s in spans),
            "leadingNonSpeechSeconds": spans[0]["start"],
            "trailingNonSpeechSeconds": max(0, duration - spans[-1]["end"]),
            "internalNonSpeech": gaps, "reviewRequired": True,
            "reason": "Non-speech can contain important action, breath or room sound. Review picture and intact words before cutting."}


def detect(audio, sampling_rate=16000):
    import torch
    from silero_vad import get_speech_timestamps, load_silero_vad

    if sampling_rate != 16000 or getattr(audio, "ndim", None) != 1:
        raise ValueError("Speech audit requires mono 16 kHz audio")
    model = load_silero_vad(onnx=True)
    # A lower threshold retains uncertain word edges. Sample timestamps avoid
    # Silero's default one-decimal rounding for return_seconds=True.
    spans = get_speech_timestamps(torch.as_tensor(audio, dtype=torch.float32), model,
                                 sampling_rate=sampling_rate, threshold=.35,
                                 min_silence_duration_ms=120, speech_pad_ms=40)
    seconds = [{k: value / sampling_rate for k, value in span.items()} for span in spans]
    result = summarize(seconds, len(audio) / sampling_rate)
    result.update(detector="silero-vad", threshold=.35, speechPadMs=40,
                  minSilenceMs=120, samplingRate=sampling_rate)
    return result


def refine_single_caption(aligned, activity, duration):
    """Only bound a single authored caption; VAD cannot align multiple sentences."""
    if not activity.get("speechDetected"):
        raise ValueError("No speech activity supports the recognized dialogue")
    if len(aligned["segments"]) != 1:
        return aligned
    first, last = activity["spans"][0]["start"], activity["spans"][-1]["end"]
    segment = aligned["segments"][0]
    start, end = max(segment["start"], first - .06), min(segment["end"], last + .16, duration)
    if start >= end or start > first + .15 or end < last - .15:
        raise ValueError("ASR timing and detected speech disagree; review the audio")
    return {**aligned, "segments": [{**segment, "start": max(0, start), "end": end}],
            "timingEvidence": "asr-content-with-vad-envelope",
            "unrefinedSegments": aligned["segments"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", type=Path, nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    import librosa

    records = []
    for path in args.audio:
        audio, sr = librosa.load(path, sr=16000, mono=True)
        report = detect(audio, sr)
        records.append({"source": str(path.resolve()),
                        "sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "duration": len(audio) / sr, **report})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"version": 1, "artisticApproval": False,
                                      "files": records}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(records, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
