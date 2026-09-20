"""Resume manifest-driven native H3 takes; never retime or fake a pass."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import subprocess
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/zhouzong-ep01'
HTTP = requests.Session()
HTTP.trust_env = False
COMFY = 'http://127.0.0.1:8188'


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def get(path):
    response = HTTP.get(COMFY + path, timeout=40)
    response.raise_for_status()
    return response.json()


def load_builder():
    spec = importlib.util.spec_from_file_location('official_h3', ROOT / 'scripts/run-h3-attention-ab.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def render(take):
    folder = OUT / 'clips' / take['id']
    folder.mkdir(parents=True, exist_ok=True)
    source = OUT / 'keyframes' / take['image']
    prompt = take['prompt']
    identity = {'imageSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                'prompt': prompt, 'frames': take['frames'], 'seed': take['seed'],
                'width': 768, 'height': 1344, 'fps': 24, 'steps': 20}
    state_path = folder / 'state.json'
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding='utf-8'))
        if state['identity'] != identity:
            raise RuntimeError('Take changed; use a new take ID instead of overwriting an existing render')
        if state.get('status') == 'complete' and (folder / 'native.mp4').exists():
            print('SKIP complete ' + take['id'], flush=True)
            return
        if not state.get('promptId'):
            raise RuntimeError('Ambiguous prior submission; inspect queue before retrying')
        prompt_id = state['promptId']
    else:
        with source.open('rb') as stream:
            response = HTTP.post(COMFY + '/upload/image', files={'image': (source.name, stream, 'image/png')},
                                 data={'subfolder': 'niren-zhouzong', 'overwrite': 'false'}, timeout=60)
        response.raise_for_status()
        uploaded = response.json()
        image_name = '/'.join(x for x in (uploaded.get('subfolder'), uploaded['name']) if x)
        workflow = load_builder().build('walk')
        workflow['image']['inputs']['image'] = image_name
        workflow['i2v']['inputs'].update(width=768, height=1344, length=take['frames'], prompt=prompt)
        workflow['noise']['inputs']['noise_seed'] = take['seed']
        workflow['save']['inputs']['filename_prefix'] = 'niren-zhouzong/' + take['id']
        write_json(folder / 'workflow-api.json', workflow)
        state = {'identity': identity, 'status': 'submitting', 'startedAt': time.time()}
        write_json(state_path, state)
        response = HTTP.post(COMFY + '/prompt', json={'prompt': workflow, 'client_id': 'niren-zhouzong'}, timeout=60)
        if not response.ok:
            write_json(folder / 'submission-error.json', response.json())
        response.raise_for_status()
        prompt_id = response.json()['prompt_id']
        state.update(promptId=prompt_id, status='running')
        write_json(state_path, state)
    print(f"RUNNING {take['id']} prompt={prompt_id}", flush=True)
    deadline = time.monotonic() + 7200
    last_report = 0
    while time.monotonic() < deadline:
        entry = get('/history/' + prompt_id).get(prompt_id)
        if entry:
            write_json(folder / 'history.json', entry)
            if entry.get('status', {}).get('status_str') == 'error':
                state['status'] = 'failed'
                write_json(state_path, state)
                raise RuntimeError(f'H3 failed; see {folder / "history.json"}')
            if entry.get('status', {}).get('completed'):
                output = entry['outputs']['save']
                candidates = output.get('images', []) + output.get('videos', [])
                video = next(v for v in candidates if v['filename'].endswith('.mp4'))
                response = HTTP.get(COMFY + '/view', params=video, timeout=180)
                response.raise_for_status()
                (folder / 'native.mp4').write_bytes(response.content)
                subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', str(folder / 'native.mp4'),
                                '-vn', '-acodec', 'pcm_s16le', str(folder / 'native.wav')], check=True)
                probe = subprocess.run(['ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json',
                                        str(folder / 'native.mp4')], check=True, capture_output=True)
                write_json(folder / 'probe.json', json.loads(probe.stdout))
                state.update(status='complete', elapsedSeconds=round(time.time() - state['startedAt'], 1),
                             outputSha256=hashlib.sha256(response.content).hexdigest(), artisticApproval=False)
                write_json(state_path, state)
                print(f"COMPLETE {take['id']} elapsed={state['elapsedSeconds']}s", flush=True)
                return
        if time.monotonic() - last_report > 60:
            print('WAIT ' + take['id'], flush=True)
            last_report = time.monotonic()
        time.sleep(10)
    raise TimeoutError('Rendering continues on ComfyUI; use this same take ID to resume polling')


def main():
    global OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=OUT,
                        help='Episode folder containing manifest.json and keyframes/')
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--take')
    group.add_argument('--from-take', help='Resume this take and all later takes in manifest order')
    args = parser.parse_args()
    OUT = args.output_dir.resolve()
    manifest = json.loads((OUT / 'manifest.json').read_text(encoding='utf-8'))
    index = next(i for i,take in enumerate(manifest['takes']) if take['id'] == (args.take or args.from_take))
    takes = manifest['takes'][index:index+1] if args.take else manifest['takes'][index:]
    for take in takes:
        if not (OUT/'keyframes'/take['image']).is_file():
            raise FileNotFoundError(take['image'])
    for take in takes:
        if take.get('excludedReason'):
            print('SKIP excluded ' + take['id'], flush=True)
            continue
        render(take)


if __name__ == '__main__':
    main()
