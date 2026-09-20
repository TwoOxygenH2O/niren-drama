"""Alternate one narration master with native dialogue without voice overlap or retiming."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FPS = 24


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stamp(t, srt=False):
    unit = 1000 if srt else 100
    n = round(t * unit)
    h, n = divmod(n, 3600 * unit)
    m, n = divmod(n, 60 * unit)
    s, fraction = divmod(n, unit)
    return f'{h:02}:{m:02}:{s:02}{"," if srt else "."}{fraction:0{3 if srt else 2}}'


def wrap_caption(text, width=14):
    text = text.strip().rstrip('。！？')
    if any(c in text for c in '{}\\\r\n'):
        raise ValueError('Unsafe subtitle control characters')
    if len(text) <= width:
        return text
    possible = [m.end() for m in re.finditer('[，。！？；、]', text)
                if max(1, len(text)-width) <= m.end() <= width]
    cut = min(possible, key=lambda i: abs(i-len(text)/2)) if possible else len(text)//2
    if max(cut, len(text)-cut) > width:
        raise ValueError('Caption needs another authored semantic break')
    return text[:cut].rstrip('，。；')+'\\N'+text[cut:]


def allocate(frames, sources):
    """Consume real source frames; never add a hold or loop to cover missing footage."""
    parts = []
    for source in sources:
        available = source['outFrame'] - source['inFrame']
        used = min(frames, available)
        if used:
            parts.append({**source, 'outFrame': source['inFrame']+used})
            frames -= used
        if not frames:
            return parts
    raise ValueError(f'Insufficient moving footage: missing {frames} frames')


def reviewed_source(clips, key, edit=None):
    source = dict(clips[key])
    edit = edit or {}
    if set(edit) - {'inFrame', 'outFrame', 'crop'}:
        raise ValueError('Only frame boundaries can override a source')
    source.update(edit)
    if not all(isinstance(source[k], int) for k in ('inFrame', 'outFrame')):
        raise ValueError('Source boundaries must be integer frames')
    if not 0 <= source['inFrame'] < source['outFrame'] <= clips[key]['outFrame']:
        raise ValueError('Invalid reviewed source range')
    if 'crop' in source:
        w,h,x,y = source['crop']
        if (not all(isinstance(v,int) and v%2==0 for v in (w,h,x,y)) or
                min(w,h)<=0 or min(x,y)<0 or x+w>768 or y+h>1344 or w*16!=h*9):
            raise ValueError('Invalid vertical reframe')
    return source


def protect_speech(spans, sources):
    previous = -1
    for source in sources:
        if source['inFrame'] < previous:
            raise ValueError('Dialogue edits must stay in source order')
        previous = source['outFrame']
    for span in spans:
        covered = sum(max(0,min(span['end'],p['outFrame']/FPS)-
                          max(span['start'],p['inFrame']/FPS)) for p in sources)
        if span['end']-span['start']-covered > .04:
            raise ValueError('Reviewed cut would remove detected speech')


def protect_narration(spans, picture):
    begin,end=picture['inFrame']/FPS,picture['outFrame']/FPS
    if any(s['start']-.08<end and s['end']+.08>begin for s in spans):
        raise ValueError('Possible native voice in the proposed narration ambience')


def dialogue_captions(qa, reviewed=None):
    captions=reviewed if reviewed is not None else qa['segments']
    if ''.join(c['text'] for c in captions)!=''.join(c['text'] for c in qa['segments']):
        raise ValueError('Caption timing edits cannot rewrite recognized dialogue')
    previous=0
    spans=qa['activity']['spans']
    for caption in captions:
        if not previous<=caption['start']<caption['end']<=qa['duration']+.04:
            raise ValueError('Invalid reviewed dialogue caption timing')
        if not any(caption['start']<s['end'] and caption['end']>s['start'] for s in spans):
            raise ValueError('Caption appears wholly outside detected speech; review timestamps')
        previous=caption['end']
    if captions[0]['start']<spans[0]['start']-.25:
        raise ValueError('Caption starts too early; review coarse ASR timestamps')
    return captions


def validate_schedule(rows):
    cursor = 0
    for row in rows:
        if row['startFrame'] != cursor or row['frames'] <= 0:
            raise ValueError('Broken episode timeline')
        if row['audioMode'] not in {'narration', 'native-dialogue', 'ambient'}:
            raise ValueError('Unspecified voice owner')
        if sum(p['outFrame']-p['inFrame'] for p in row['pictures']) != row['frames']:
            raise ValueError('Picture does not cover the audio block')
        if row['audioMode'] == 'native-dialogue' and len(row['pictures']) != 1:
            raise ValueError('Native dialogue needs an explicit synchronized picture source')
        cursor += row['frames']
    return cursor


def build(out, through=None):
    manifest = read(out/'manifest.json')
    alignment = read(out/'audio/narration-alignment.json')
    master = out/manifest['narrationMaster']
    if sha(master) != alignment['audioSha256']:
        raise ValueError('Narration changed after alignment')
    blocks = {b['id']: b for b in manifest['narrationBlocks']}
    ambient = {b['id']: b for b in manifest.get('ambientBlocks', [])}
    takes = {t['id']: t for t in manifest['takes']}
    overrides_file = out/'edit-overrides.json'
    overrides = read(overrides_file) if overrides_file.exists() else {}
    picture_edits = overrides.get('narrationPictures', {})
    take_edits = overrides.get('takes', {})
    caption_edits = overrides.get('captions', {})
    order = manifest['editOrder']
    if set(blocks) & set(ambient) or (set(blocks) | set(ambient)) & set(takes):
        raise ValueError('Ambiguous edit block IDs')
    if len(set(order)) != len(order) or any(k not in blocks and k not in takes and k not in ambient for k in order):
        raise ValueError('Invalid manifest edit order')
    if through:
        order = order[:order.index(through)+1]
    required = set()
    for key in order:
        if key in blocks:
            required.update(p['take'] for p in picture_edits.get(key,
                            [{'take':t} for t in blocks[key]['takes']]))
        elif key in ambient:
            required.add(ambient[key]['take'])
        else:
            required.add(key)
    clips = {}
    for key in sorted(required):
        if takes[key].get('excludedReason'):
            raise ValueError(f'Excluded take cannot enter the edit: {key}')
        folder = out/'clips'/key
        state = read(folder/'state.json')
        if state['status'] != 'complete':
            raise ValueError(f'Unfinished take: {key}')
        source = folder/'native.mp4'
        if sha(source) != state['outputSha256']:
            raise ValueError(f'Changed video source: {key}')
        stream = next(s for s in read(folder/'probe.json')['streams'] if s['codec_type']=='video')
        if stream['avg_frame_rate'] != '24/1' or (stream['width'],stream['height']) != (768,1344):
            raise ValueError('Unexpected native video profile')
        clips[key] = {'take': key, 'source': str(source.resolve()), 'sourceSha256':state['outputSha256'],
                      'inFrame':0, 'outFrame':int(stream['nb_frames'])}
    rows, captions, cursor = [], [], 0
    for key in order:
        if key in ambient:
            block = ambient[key]
            picture = reviewed_source(clips, block['take'],
                        {k:v for k,v in block.items() if k not in {'id','take'}})
            qa = read(out/'clips'/block['take']/'dialogue-qa.json')
            if sha(out/'clips'/block['take']/'native.wav') != qa['audioSha256']:
                raise ValueError('Ambient audio changed after speech detection')
            protect_narration(qa['activity']['spans'], picture)
            frames = picture['outFrame']-picture['inFrame']
            row = {'id':key, 'audioMode':'ambient', 'audio':picture['source'],
                   'audioStart':picture['inFrame']/FPS, 'frames':frames,
                   'startFrame':cursor, 'pictures':[picture]}
        elif key in blocks:
            block = blocks[key]
            segments = [alignment['segments'][i] for i in block['segments']]
            start = max(0, segments[0]['start']-.02)
            end = segments[-1]['end']+.02
            frames = math.ceil((end-start)*FPS)
            selections = picture_edits.get(key, [{'take':t} for t in block['takes']])
            sources = [reviewed_source(clips, p['take'],
                        {k:v for k,v in p.items() if k != 'take'}) for p in selections]
            pictures = allocate(frames, sources)
            row = {'id':key, 'audioMode':'narration', 'audio':str(master.resolve()),
                   'audioStart':start, 'frames':frames, 'startFrame':cursor, 'pictures':pictures}
            if all(p['take'] in manifest.get('nativeAmbienceTakes',[]) for p in pictures):
                for picture in pictures:
                    qa = read(out/'clips'/picture['take']/'dialogue-qa.json')
                    protect_narration(qa['activity']['spans'],picture)
                row['nativeAmbienceGain'] = .2
            for cap in segments:
                captions.append({'text':cap['text'], 'voice':'沈照旁白',
                    'start':cursor/FPS+max(0,cap['start']-start),
                    'end':cursor/FPS+min(frames/FPS,cap['end']-start)})
        else:
            qa = read(out/'clips'/key/'dialogue-qa.json')
            if qa['status'] != 'aligned' and not (qa['status']=='timing_review_required' and key in caption_edits):
                raise ValueError(f'Dialogue still needs review: {key}')
            spoken_captions=dialogue_captions(qa,caption_edits.get(key))
            source = dict(clips[key])
            start = min(qa['activity']['spans'][0]['start'],spoken_captions[0]['start'])
            end = max(qa['activity']['spans'][-1]['end'],spoken_captions[-1]['end'])
            source['inFrame'] = max(0,math.floor((start-.12)*FPS))
            source['outFrame'] = min(source['outFrame'],math.ceil((end+.14)*FPS))
            default = {'inFrame':source['inFrame'], 'outFrame':source['outFrame']}
            config = take_edits.get(key, {})
            edits = config['segments'] if 'segments' in config else [{**default,**config}]
            sources = [reviewed_source(clips,key,edit) for edit in edits]
            protect_speech(qa['activity']['spans'],sources)
            assigned = set()
            for index,source in enumerate(sources):
                frames = source['outFrame']-source['inFrame']
                row = {'id':key+f':{index}', 'audioMode':'native-dialogue', 'audio':source['source'],
                       'audioStart':source['inFrame']/FPS, 'frames':frames,
                       'startFrame':cursor, 'pictures':[source],
                       'captionTiming':'reviewed' if key in caption_edits else 'asr-vad-aligned'}
                for cap_index,cap in enumerate(spoken_captions):
                    a = max(0,cap['start']-source['inFrame']/FPS)
                    b = min(frames/FPS,cap['end']-source['inFrame']/FPS)
                    if a >= b:
                        continue
                    if cap_index in assigned:
                        raise ValueError('Cut crosses a spoken caption; author a semantic boundary first')
                    assigned.add(cap_index)
                    captions.append({'text':cap['text'], 'voice':'人物对白',
                                     'start':cursor/FPS+a, 'end':cursor/FPS+b})
                rows.append(row)
                cursor += frames
            if len(assigned) != len(spoken_captions):
                raise ValueError('Native dialogue cut would omit a caption')
            continue
        rows.append(row)
        cursor += frames
    count = validate_schedule(rows)
    for a,b in zip(captions,captions[1:]):
        if a['end'] > b['start']+.001:
            raise ValueError('Caption overlap')
    return {'title':manifest['title'], 'episode':manifest['episode'], 'fps':FPS,
            'width':756,'height':1344,'frameCount':count,'duration':count/FPS,
            'narrationSha256':sha(master),'timeline':rows,'captions':captions,
            'completeEpisode':order == manifest['editOrder'],
            'retimed':False,'interpolated':False,'voiceOverlap':False,
            'artisticApproval':False,'publicationApproval':False}


def write_captions(folder, data):
    header = '''[Script Info]
ScriptType: v4.00+
PlayResX: 756
PlayResY: 1344
ScaledBorderAndShadow: yes
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Speech,Niren Subtitle Sans,42,&H00FFFFFF,&H00FFFFFF,&H60202020,&H90000000,0,0,0,0,100,100,0,0,1,1.4,0.5,2,68,68,156,1
Style: Disclosure,Niren Subtitle Sans,23,&H10FFFFFF,&H00FFFFFF,&H70202020,&H90000000,0,0,0,0,100,100,0,0,1,1,0.3,7,30,30,35,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
'''
    events = [f'Dialogue: 1,{stamp(0)},{stamp(data["duration"])},Disclosure,,0,0,0,,AI生成 · 虚构故事']
    srt = []
    for i,cap in enumerate(data['captions'],1):
        text = wrap_caption(cap['text'])
        events.append(f'Dialogue: 0,{stamp(cap["start"])},{stamp(cap["end"])},Speech,,0,0,0,,{text}')
        plain = text.replace('\\N','\n')
        srt.append(f'{i}\n{stamp(cap["start"],True)} --> {stamp(cap["end"],True)}\n{plain}\n')
    (folder/'episode.ass').write_text(header+'\n'.join(events)+'\n',encoding='utf-8-sig')
    (folder/'episode.srt').write_text('\n'.join(srt),encoding='utf-8-sig')


def run(*args, **kwargs):
    subprocess.run(list(args),check=True,**kwargs)


def compose(out, data):
    folder = out/('final' if data['completeEpisode'] else 'preview')
    folder.mkdir(parents=True,exist_ok=True)
    save(folder/'edit.json',data)
    write_captions(folder,data)
    parts = []
    for i,row in enumerate(data['timeline']):
        picture_parts = []
        for j,picture in enumerate(row['pictures']):
            name = folder/f'picture-{i:02}-{j:02}.mp4'
            crop = ':'.join(str(n) for n in picture.get('crop',[756,1344,6,0]))
            vf = (f'trim=start_frame={picture["inFrame"]}:end_frame={picture["outFrame"]},'
                  f'setpts=PTS-STARTPTS,crop={crop},scale=756:1344:flags=lanczos,setsar=1')
            run('ffmpeg','-y','-v','error','-i',picture['source'],'-an','-vf',vf,
                '-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p',str(name))
            picture_parts.append(name)
        listing = folder/f'picture-{i:02}.txt'
        listing.write_text(''.join(f"file '{p.name}'\n" for p in picture_parts),encoding='utf-8')
        part = folder/f'part-{i:02}.mov'
        duration = row['frames']/FPS
        samples = row['frames']*2000
        target_loudness = -28 if row['audioMode'] == 'ambient' else -19
        af = (f'atrim=start={row["audioStart"]}:duration={duration},asetpts=PTS-STARTPTS,'
              f'loudnorm=I={target_loudness}:TP=-2:LRA=11,aresample=48000,asetpts=N/SR/TB,'
              f'apad=whole_len={samples},atrim=end_sample={samples},'
              f'afade=t=in:d=0.005,afade=t=out:st={max(0,duration-.005)}:d=0.005')
        inputs = ['-i',row['audio']]
        filters = [f'[1:a]{af}[speech]']
        mix = '[speech]'
        if row.get('nativeAmbienceGain'):
            labels = []
            for j,picture in enumerate(row['pictures'],2):
                inputs.extend(['-i',picture['source']])
                length=(picture['outFrame']-picture['inFrame'])/FPS
                labels.append(f'[amb{j}]')
                filters.append(f'[{j}:a]atrim=start={picture["inFrame"]/FPS}:duration={length},'
                    f'asetpts=PTS-STARTPTS,aresample=48000,apad,atrim=duration={length},'
                    f'afade=t=in:d=0.02,afade=t=out:st={max(0,length-.02)}:d=0.02[amb{j}]')
            filters.append(''.join(labels)+f'concat=n={len(labels)}:v=0:a=1,'
                           f'volume={row["nativeAmbienceGain"]}[ambience]')
            filters.append('[speech][ambience]amix=inputs=2:duration=first:normalize=0[mixed]')
            mix='[mixed]'
        # One voice source; optional nonverbal native sound follows the actual picture edit.
        # Muxing cannot use -t here: some resampling frames straddle its PTS cutoff.
        run('ffmpeg','-y','-v','error','-f','concat','-safe','0','-i',str(listing),
            *inputs,'-filter_complex',';'.join(filters),
            '-map','0:v:0','-map',mix,'-c:v','copy','-c:a','pcm_s16le','-ac','2',
            str(part))
        probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(part)]))
        sound=next(s for s in probe['streams'] if s['codec_type']=='audio')
        if abs(float(sound['duration'])-duration)>1/48000:
            raise ValueError('Audio segment length differs from its video frame grid')
        parts.append(part)
    listing = folder/'parts.txt'
    listing.write_text(''.join(f"file '{p.name}'\n" for p in parts),encoding='utf-8')
    clean = folder/'episode-clean.mp4'
    run('ffmpeg','-y','-v','error','-f','concat','-safe','0','-i',str(listing),
        '-c:v','copy','-c:a','aac','-b:a','192k','-movflags','+faststart',str(clean))
    fonts = (ROOT/'backend/uploads/fonts').resolve()
    # A relative path avoids Windows drive-colon escaping in the ASS filter.
    import os
    try:
        relative_fonts = Path(os.path.relpath(fonts,folder)).as_posix()
    except ValueError:
        import shutil
        bundled_fonts=folder/'fonts'
        bundled_fonts.mkdir(exist_ok=True)
        for font in fonts.iterdir():
            if font.is_file():
                shutil.copy2(font,bundled_fonts/font.name)
        relative_fonts='fonts'
    final = folder/'episode-subtitled.mp4'
    run('ffmpeg','-y','-v','error','-i',clean.name,
        '-vf',f'ass=episode.ass:fontsdir={relative_fonts}',
        '-af',f'loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000,asetpts=N/SR/TB,'
              f'apad=whole_len={data["frameCount"]*2000},atrim=end_sample={data["frameCount"]*2000}',
        '-ar','48000',
        '-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p',
        '-c:a','aac','-b:a','192k','-movflags','+faststart',final.name,cwd=folder)
    clean_normalized=folder/'episode-clean-normalized.mp4'
    run('ffmpeg','-y','-v','error','-i',str(clean),'-i',str(final),
        '-map','0:v:0','-map','1:a:0','-c','copy','-movflags','+faststart',str(clean_normalized))
    clean_normalized.replace(clean)
    data['outputSha256'] = sha(final)
    save(folder/'receipt.json',data)
    print(f'COMPOSED {final} duration={data["duration"]:.3f}s',flush=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output-dir',type=Path,default=ROOT/'output/jie-ming-ep01')
    p.add_argument('--plan-only',action='store_true')
    p.add_argument('--through',help='Render a clearly marked partial preview through this edit block')
    args=p.parse_args()
    out=args.output_dir.resolve()
    data=build(out,args.through)
    if args.plan_only:
        folder=out/('final' if data['completeEpisode'] else 'preview')
        save(folder/'edit.json',data)
        write_captions(folder,data)
        print(f'PLANNED {data["duration"]:.3f}s')
    else:
        compose(out,data)


if __name__=='__main__':
    main()
