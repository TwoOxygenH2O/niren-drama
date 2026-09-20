"""Verify the delivered edit, captions and audio timing, not acting or commercial appeal."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

import numpy as np
from scipy.signal import correlate

ROOT=Path(__file__).resolve().parents[1]
RATE=16000


def run(args):
    return subprocess.run(args,check=True,capture_output=True)


def audio(path):
    data=run(['ffmpeg','-v','error','-i',str(path),'-vn','-ac','1','-ar',str(RATE),
              '-f','f32le','pipe:1']).stdout
    return np.frombuffer(data,dtype=np.float32)


def audio_match(source, mixed, source_start, timeline_start, length):
    query=source[round(source_start*RATE):round((source_start+length)*RATE)].astype(np.float64)
    a=max(0,round((timeline_start-.25)*RATE))
    b=min(len(mixed),round((timeline_start+.25+length)*RATE))
    window=mixed[a:b].astype(np.float64)
    if len(query)<RATE//4 or len(window)<len(query) or np.sum(query**2)<1e-10:
        raise ValueError('Insufficient audible material to verify edit timing')
    scores=correlate(window,query,mode='valid',method='fft')
    cumulative=np.r_[0,np.cumsum(window**2)]
    energy=cumulative[len(query):]-cumulative[:-len(query)]
    scores/=np.sqrt(energy*np.sum(query**2)+1e-15)
    best=int(np.argmax(scores))
    return {'offsetSeconds':round((a+best)/RATE-timeline_start,5),
            'correlation':round(float(scores[best]),4)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--folder',type=Path,default=ROOT/'output/jie-ming-ep01/final')
    args=parser.parse_args()
    folder=args.folder.resolve()
    receipt=json.loads((folder/'receipt.json').read_text(encoding='utf-8'))
    video=folder/'episode-subtitled.mp4'
    if hashlib.sha256(video.read_bytes()).hexdigest()!=receipt['outputSha256']:
        raise ValueError('The delivery differs from the edit receipt')
    probe=json.loads(run(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(video)]).stdout)
    picture=next(s for s in probe['streams'] if s['codec_type']=='video')
    sound=next(s for s in probe['streams'] if s['codec_type']=='audio')
    failures=[]
    if (picture['width'],picture['height'],picture['avg_frame_rate'])!=(756,1344,'24/1'):
        failures.append('Unexpected delivery profile')
    if int(picture['nb_frames'])!=receipt['frameCount']:
        failures.append('Encoded frame count differs from edit')
    if abs(float(picture['duration'])-float(sound['duration']))>.075:
        failures.append('Unequal audio and video lengths')
    decoded=run(['ffmpeg','-v','error','-xerror','-i',str(video),'-f','null','-'])
    if decoded.stderr.strip():
        failures.append('Decode diagnostics need review')
    (folder/'decode.log').write_bytes(decoded.stderr)
    frame_data=json.loads(run(['ffprobe','-v','error','-select_streams','v:0','-show_frames',
        '-show_entries','frame=best_effort_timestamp_time','-of','json',str(video)]).stdout)
    pts=np.array([float(f['best_effort_timestamp_time']) for f in frame_data['frames']])
    max_step_error=float(np.max(np.abs(np.diff(pts)-1/24)))
    if len(pts)!=receipt['frameCount'] or max_step_error>.001:
        failures.append('Nonuniform or missing decoded frame timestamps')
    mixed=audio(video)
    if not len(mixed) or not np.isfinite(mixed).all():
        raise ValueError('No finite decoded audio')
    cache={}
    timing=[]
    for row in receipt['timeline']:
        source_path=row['audio']
        if source_path not in cache:
            cache[source_path]=audio(source_path)
        start=row['startFrame']/24
        end=start+row['frames']/24
        cues=[c for c in receipt['captions'] if c['start']>=start-.001 and c['end']<=end+.001]
        if row['audioMode']=='ambient':
            if cues:
                raise ValueError('Ambient-only block contains a speech caption')
            begin = start + .12
            length = min(1.2,end-.12-begin)
        else:
            cap=max(cues,key=lambda c:c['end']-c['start'])
            begin=max(start+.02,cap['start']+.12)
            length=min(1.2,cap['end']-.05-begin,end-.02-begin)
        match=audio_match(cache[source_path],mixed,row['audioStart']+begin-start,begin,length)
        timing.append({'id':row['id'],'audioMode':row['audioMode'],**match})
        if abs(match['offsetSeconds'])>.08 or match['correlation']<.65:
            failures.append('Audio edit alignment needs review: '+row['id'])
    previous=0
    for caption in receipt['captions']:
        if not previous-.001<=caption['start']<caption['end']<=receipt['duration']+.001:
            failures.append('Invalid or overlapping caption')
        previous=caption['end']
    loudness=run(['ffmpeg','-hide_banner','-nostats','-i',str(video),'-vn',
        '-af','loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json','-f','null','-']).stderr.decode('utf-8','replace')
    match=re.search(r'\{\s*"input_i".*?\}',loudness,re.S)
    meters=json.loads(match.group()) if match else None
    if meters is None or float(meters['input_tp'])>-.5:
        failures.append('Missing loudness measurement or excessive true peak')
    report={'completeEpisode':receipt['completeEpisode'], 'duration':float(picture['duration']),
        'width':picture['width'],'height':picture['height'],'fps':picture['avg_frame_rate'],
        'decodedFrames':len(pts),'maxFrameTimestampError':max_step_error,
        'fullDecodePassed':not decoded.stderr.strip(),'audioEditChecks':timing,'loudness':meters,
        'technicalFailures':failures,'technicalPassed':not failures,
        'artisticApproval':False,'publicationApproval':False,
        'note':'Uniform timestamps do not establish smooth acting. Correlation checks edit-induced offsets, not native H3 lip sync.'}
    (folder/'technical-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if failures:
        raise SystemExit(1)


if __name__=='__main__':
    main()
