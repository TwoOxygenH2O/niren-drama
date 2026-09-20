"""Match a narration transcript to local Whisper word timestamps; never replace the script with ASR text."""
from __future__ import annotations
import argparse
import difflib
import json
import re
import math
import hashlib
from pathlib import Path
from pypinyin import lazy_pinyin, Style
from opencc import OpenCC

SIMPLIFIER = OpenCC('tw2sp')


def speech_tokens(text):
    # Homophones and simplified/traditional spellings are indistinguishable in audio.
    # Retain tones; do not fuzzy-match arbitrary syllables or rewrite the authored text.
    normalized = SIMPLIFIER.convert(text)
    if len(normalized) != len(text):
        raise ValueError('Script normalization changed transcript positions')
    tokens = lazy_pinyin(normalized, style=Style.TONE3, neutral_tone_with_five=True,
                         errors=lambda value: list(value))
    if len(tokens) != len(text):
        raise ValueError('Pronunciation token count does not match transcript')
    return [token.lower() for token in tokens]


def longest_gap(length, matched):
    longest = current = 0
    for index in range(length):
        current = 0 if index in matched else current + 1
        longest = max(longest, current)
    return longest


def recognition_ranges(active, samples, sr):
    """Partition recognition at real pauses, without overlapping or cutting speech."""
    pauses = [(int(a[1]) + int(b[0])) // 2 for a, b in zip(active, active[1:])
              if int(b[0]) - int(a[1]) >= .18 * sr]
    ranges, start = [], 0
    while samples - start > 12 * sr:
        candidates = [p for p in pauses if start + 4 * sr <= p <= start + 12 * sr]
        if candidates:
            end = max(candidates)
        else:
            # Never force a cut inside quiet words just to reach a target length.
            extended = [p for p in pauses if start + 12 * sr < p <= start + 25 * sr]
            if extended:
                end = min(extended)
            elif samples - start <= 25 * sr:
                break
            else:
                raise ValueError('No safe speech pause for subtitle recognition')
        ranges.append((start, end))
        start = end
    ranges.append((start, samples))
    return ranges


def align(text, words, duration):
    expected = [(c, i) for i, c in enumerate(text) if c.isalnum()]
    observed = []
    for word in words:
        start, end = word['timestamp']
        if start is None or end is None:
            continue
        if not math.isfinite(start) or not math.isfinite(end) or end <= start:
            raise ValueError('Invalid recognition timestamps')
        chars = [c for c in word['text'] if c.isalnum()]
        for index, char in enumerate(chars):
            observed.append((char, float(start) + (end-start)*index/max(1,len(chars)),
                             float(start) + (end-start)*(index+1)/max(1,len(chars))))
    expected_text = ''.join(c for c,_ in expected)
    observed_text = ''.join(w[0] for w in observed)
    matcher = difflib.SequenceMatcher(None, speech_tokens(expected_text), speech_tokens(observed_text), autojunk=False)
    matched = {}
    expected_indices, observed_indices = set(), set()
    for block in matcher.get_matching_blocks():
        for j in range(block.size):
            matched[expected[block.a+j][1]] = observed[block.b+j][1:]
            expected_indices.add(block.a+j)
            observed_indices.add(block.b+j)
    coverage = len(matched)/max(1,len(expected))
    if coverage < .85:
        raise ValueError(f'ASR transcript coverage too low: {coverage:.3f}')
    precision = len(observed_indices)/max(1,len(observed))
    if precision < .85 or longest_gap(len(observed), observed_indices) >= 3:
        raise ValueError('Unexpected speech not present in the authored script')
    if longest_gap(len(expected), expected_indices) >= 3:
        raise ValueError('A phrase was omitted from the recognized speech')
    critical = set('不没未勿别无非只仅零一二三四五六七八九十百千万亿两')
    lexical_unmatched = {match.start() for match in re.finditer('未婚夫|未婚妻|未来|未央', text)}
    if any(i not in matched and i not in lexical_unmatched and (c in critical or c.isdigit()) for c,i in expected):
        raise ValueError('A negation, restriction or number could not be verified')
    segments = []
    # Authored lines are timing anchors, independent of how many sentences ASR returns.
    for match in re.finditer(r'[^\n]+', text):
        positions = [i for c,i in expected if match.start() <= i < match.end()]
        times = [matched[i] for i in positions if i in matched]
        if not times or len(times)/max(1,len(positions)) < .7:
            raise ValueError('An authored sentence could not be aligned reliably')
        start = max(0, times[0][0]-.06)
        end = min(duration, times[-1][1]+.16)
        if segments:
            segments[-1]['end'] = min(segments[-1]['end'], max(segments[-1]['start'], start-.04))
        segments.append({'text':match.group().strip(), 'start':start, 'end':end})
    if not segments:
        raise ValueError('No aligned speech')
    lexical = difflib.SequenceMatcher(None, expected_text, observed_text, autojunk=False)
    return {'coverage':coverage, 'observedCoverage':precision,
            'lexicalCoverage':sum(b.size for b in lexical.get_matching_blocks())/max(1,len(expected)),
            'matching':'tone-aware-mandarin', 'segments':segments}


def main():
    from speech_activity import detect, refine_single_caption
    p=argparse.ArgumentParser()
    for name in ['audio','text','output']: p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--model',default='openai/whisper-medium')
    args=p.parse_args()
    import librosa
    import torch
    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor, pipeline
    torch.set_num_threads(6)
    processor=AutoProcessor.from_pretrained(args.model,local_files_only=True)
    model=AutoModelForSpeechSeq2Seq.from_pretrained(args.model,local_files_only=True,
                                                   torch_dtype=torch.float32,attn_implementation='eager')
    # Short-form recognition avoids both overlapping-chunk duplication and long-form
    # repetition. Only the recognition input is partitioned; never edit the master.
    asr=pipeline('automatic-speech-recognition',model=model,tokenizer=processor.tokenizer,
                 feature_extractor=processor.feature_extractor,device=-1)
    audio,sr=librosa.load(args.audio,sr=16000)
    activity=detect(audio,sr)
    if not activity['speechDetected']:
        raise ValueError('No speech detected for the authored transcript')
    # Energy valleys can occur inside a quietly spoken word. Use the padded
    # speech detector intervals, not amplitude thresholding, to place cuts.
    active=[(round(span['start']*sr),round(span['end']*sr)) for span in activity['spans']]
    ranges=recognition_ranges(active,len(audio),sr)
    result={'text':'','chunks':[], 'recognitionRanges':[(a/sr,b/sr) for a,b in ranges],
            'recognitionStrategy':'speech-gap-partitions-12s-v2'}
    for start,end in ranges:
        part=asr({'raw':audio[start:end],'sampling_rate':sr},return_timestamps='word',
                 generate_kwargs={'language':'chinese','task':'transcribe'})
        result['text']+=part['text']
        for word in part['chunks']:
            result['chunks'].append({'text':word['text'], 'timestamp':[
                None if t is None else t+start/sr for t in word['timestamp']]})
    args.output.with_suffix('.asr.json').write_text(
        json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    aligned=align(args.text.read_text(encoding='utf-8'),result['chunks'],len(audio)/sr)
    aligned=refine_single_caption(aligned,activity,len(audio)/sr)
    aligned['speechActivity']=activity
    aligned['recognizedText']=result['text']
    aligned['audioSha256']=hashlib.sha256(args.audio.read_bytes()).hexdigest()
    args.output.write_text(json.dumps(aligned,ensure_ascii=False,indent=2),encoding='utf-8')


if __name__=='__main__': main()
