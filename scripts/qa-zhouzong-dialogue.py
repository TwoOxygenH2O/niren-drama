"""Align native speech to authored captions on CPU; retain failures for review."""
import argparse
import hashlib
import importlib.util
import json
import re
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'output/zhouzong-ep01'


def caption_text(take):
    chunks = []
    for line in take['lines']:
        for clause in re.findall(r'[^，。？！；]+[，。？！；]?', line['text']):
            clause = clause.strip('，。？！；')
            if clause:
                chunks.append(clause)
    return '\n'.join(chunks)


def main():
    global OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=OUT)
    parser.add_argument('--watch', action='store_true')
    parser.add_argument('--take')
    parser.add_argument('--retry', action='store_true')
    parser.add_argument('--model',default='openai/whisper-medium')
    args = parser.parse_args()
    OUT = args.output_dir.resolve()
    import librosa
    import torch
    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor, pipeline
    from speech_activity import detect
    torch.set_num_threads(4)
    spec = importlib.util.spec_from_file_location('aligner', ROOT/'scripts/align-narration-subtitles.py')
    aligner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(aligner)
    processor = AutoProcessor.from_pretrained(args.model, local_files_only=True)
    model = AutoModelForSpeechSeq2Seq.from_pretrained(args.model, local_files_only=True,
                torch_dtype=torch.float32, attn_implementation='eager')
    asr = pipeline('automatic-speech-recognition', model=model, tokenizer=processor.tokenizer,
                   feature_extractor=processor.feature_extractor, device=-1)
    processed = set()
    deadline = time.monotonic()+14400
    while time.monotonic() < deadline:
        manifest = json.loads((OUT/'manifest.json').read_text(encoding='utf-8'))
        takes = [t for t in manifest['takes'] if not args.take or t['id']==args.take]
        waiting = 0
        for take in takes:
            folder = OUT/'clips'/take['id']
            target = folder/'dialogue-qa.json'
            if take['id'] in processed or (target.exists() and not args.retry):
                continue
            audio_path = folder/'native.wav'
            state_path = folder/'state.json'
            if not audio_path.exists() or not state_path.exists() or json.loads(state_path.read_text(encoding='utf-8')).get('status') != 'complete':
                waiting += 1
                continue
            audio, sr = librosa.load(audio_path, sr=16000, mono=True)
            report = {'audioSha256':hashlib.sha256(audio_path.read_bytes()).hexdigest(),
                      'artisticApproval':False, 'duration':len(audio)/sr, 'asrModel':args.model}
            report['activity'] = detect(audio,sr)
            text = caption_text(take)
            (folder/'captions-source.txt').write_text(text,encoding='utf-8')
            if not text:
                report.update(status='nonverbal_review_required', segments=[])
            else:
                spans = report['activity']['spans']
                # Long silent pre-roll can make Whisper skip a short opening clause.
                # Crop recognition input only; retain source audio and source timestamps.
                recognition_start = max(0, int((spans[0]['start']-.35)*sr)) if spans else 0
                recognition_end = min(len(audio), int((spans[-1]['end']+.35)*sr)) if spans else len(audio)
                result = asr({'raw':audio[recognition_start:recognition_end],'sampling_rate':sr},return_timestamps='word',
                             generate_kwargs={'language':'chinese','task':'transcribe'})
                result['recognitionRange'] = [recognition_start/sr, recognition_end/sr]
                for chunk in result['chunks']:
                    chunk['timestamp'] = [None if t is None else t+recognition_start/sr
                                          for t in chunk['timestamp']]
                report['asr'] = result
                try:
                    aligned = aligner.align(text,result['chunks'],len(audio)/sr)
                    spans = report['activity']['spans']
                    if spans:
                        # Whisper sometimes pins the first word to zero across leading silence.
                        first = aligned['segments'][0]
                        bounded = max(first['start'],spans[0]['start']-.12)
                        if bounded < first['end']:
                            aligned['rawFirstCaptionStart'] = first['start']
                            first['start'] = max(0,bounded)
                        last = aligned['segments'][-1]
                        bounded = min(last['end'],spans[-1]['end']+.16)
                        if bounded > last['start']:
                            last['end'] = bounded
                    report.update(aligned,status='aligned')
                    if spans and any(not any(c['start']<s['end'] and c['end']>s['start']
                        for s in spans) for c in aligned['segments']):
                        report.update(status='timing_review_required',
                            reason='A coarse ASR span put a caption outside speech; review clause boundaries.')
                except ValueError as error:
                    report.update(status='transcript_review_required',reason=str(error),segments=[])
            initial=folder/'dialogue-qa.initial.json'
            if target.exists() and not initial.exists():
                initial.write_bytes(target.read_bytes())
            target.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
            processed.add(take['id'])
            print(take['id'],report['status'],report.get('asr',{}).get('text',''),flush=True)
        if not args.watch or not waiting:
            break
        time.sleep(30)


if __name__ == '__main__':
    main()
