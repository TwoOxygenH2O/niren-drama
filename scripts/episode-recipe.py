"""Export reproducible inputs without videos, runtime state, credentials or model weights."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIGS = ('manifest.json', 'edit-overrides.json', 'audio/narration-manifest.json',
           'audio/narration.txt')


def inside(folder, name):
    target = (folder / name).resolve()
    target.relative_to(folder.resolve())
    if target == folder.resolve():
        raise ValueError('Expected a file below the recipe directory')
    return target


def export(source, destination):
    source, destination = source.resolve(), destination.resolve()
    if destination.exists():
        raise FileExistsError('Use a new recipe version; do not overwrite recorded inputs')
    manifest = json.loads((source / 'manifest.json').read_text(encoding='utf-8'))
    names = {name for name in CONFIGS if (source / name).is_file()}
    names.update('keyframes/' + take['image'] for take in manifest['takes'])
    files = []
    for name in sorted(names):
        path = inside(source, name)
        if not path.is_file():
            raise FileNotFoundError(path)
        files.append((name, path, hashlib.sha256(path.read_bytes()).hexdigest()))
    destination.mkdir(parents=True)
    for name, path, _ in files:
        target = inside(destination, name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    index = {'schemaVersion': 1, 'scope': 'generation-inputs-only',
             'files': [{'path': name, 'sha256': digest} for name, _, digest in files]}
    (destination / 'recipe.json').write_text(json.dumps(index, indent=2), encoding='utf-8')
    return verify(destination)


def verify(folder):
    record = json.loads((folder / 'recipe.json').read_text(encoding='utf-8'))
    for entry in record['files']:
        path = inside(folder, entry['path'])
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Recipe hash mismatch: ' + entry['path'])
    expected = {entry['path'] for entry in record['files']} | {'recipe.json'}
    actual = {p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file()}
    if actual != expected:
        raise ValueError('Unrecorded or missing recipe files')
    return {'verifiedFiles': len(record['files']), 'folder': str(folder)}


def restore(source, destination):
    verify(source)
    if destination.exists():
        raise FileExistsError('Restore needs a new output directory')
    shutil.copytree(source, destination)
    return {'restored': str(destination), 'rendered': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('export', 'verify', 'restore'))
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--destination', type=Path)
    args = parser.parse_args()
    if args.operation != 'verify' and args.destination is None:
        parser.error('--destination required for export/restore')
    result = (verify(args.source) if args.operation == 'verify' else
              globals()[args.operation](args.source, args.destination))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
