"""Save explicit production inputs and outputs without changing the working tree."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inside(root, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError("Expected a nonempty relative path")
    target = (root / relative).resolve()
    target.relative_to(root.resolve())
    if target == root.resolve():
        raise ValueError("Whole-root snapshots are not allowed")
    return target


def create(root, definition, destination):
    root, destination = root.resolve(), destination.resolve()
    destination.relative_to(root)
    if destination.exists():
        raise FileExistsError("Checkpoint exists; choose a new ID")
    files = {}
    for entry in definition["sources"]:
        source = inside(root, entry)
        if not source.exists():
            raise FileNotFoundError(source)
        if source == destination or source in destination.parents:
            raise ValueError("Destination cannot be inside a selected source")
        for path in sorted(source.rglob("*")) if source.is_dir() else [source]:
            if not path.is_file():
                continue
            resolved = path.resolve()
            resolved.relative_to(root)
            if path.is_symlink() or resolved != path.absolute():
                raise ValueError("Symlinked sources are not supported")
            relative = path.relative_to(root).as_posix()
            if path.name.startswith(".env") or ".git" in path.parts:
                raise ValueError("Credentials and Git metadata cannot be checkpoint inputs")
            files[relative] = path
    if not files:
        raise ValueError("No checkpoint files")
    destination.mkdir(parents=True, exist_ok=False)
    entries = []
    for relative, source in sorted(files.items()):
        before = digest(source)
        target = inside(destination / "snapshot", relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        if digest(target) != before or digest(source) != before:
            raise ValueError(f"Source changed while saving: {relative}")
        entries.append({"path": relative, "bytes": target.stat().st_size, "sha256": before})
    record = {
        "schemaVersion": 1,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "definition": definition,
        "files": entries,
        "totalBytes": sum(entry["bytes"] for entry in entries),
        "scope": "local-file-snapshot; not a Git commit, database backup, or model-weight backup",
    }
    # A completed manifest is written only after every copy has been verified.
    manifest = destination / "checkpoint.json"
    manifest.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest.with_suffix(".sha256").write_text(digest(manifest) + "\n", encoding="ascii")
    return verify(destination)


def verify(folder):
    manifest = folder / "checkpoint.json"
    if digest(manifest) != manifest.with_suffix(".sha256").read_text(encoding="ascii").strip():
        raise ValueError("Checkpoint manifest hash mismatch")
    record = read(manifest)
    seen = set()
    for entry in record["files"]:
        if entry["path"] in seen:
            raise ValueError("Duplicate checkpoint path")
        seen.add(entry["path"])
        path = inside(folder / "snapshot", entry["path"])
        if path.stat().st_size != entry["bytes"] or digest(path) != entry["sha256"]:
            raise ValueError(f"Checkpoint file mismatch: {entry['path']}")
    actual = {p.relative_to(folder / "snapshot").as_posix()
              for p in (folder / "snapshot").rglob("*") if p.is_file()}
    if actual != seen:
        raise ValueError("Checkpoint contains unrecorded files")
    return {"id": record["definition"]["id"], "verifiedFiles": len(seen),
            "totalBytes": record["totalBytes"], "folder": str(folder)}


def fork(root, folder, destination):
    verify(folder)
    destination = destination.resolve()
    destination.relative_to(root.resolve())
    if destination == folder.resolve() or folder.resolve() in destination.parents:
        raise ValueError("Fork must not change the checkpoint")
    if destination.exists():
        raise FileExistsError("Fork destination must be new; existing files are never overwritten")
    shutil.copytree(folder / "snapshot", destination)
    return {"fork": str(destination), "note": "Use external Python/FFmpeg/models; saved outputs retain provenance paths"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    save = commands.add_parser("save")
    save.add_argument("--definition", type=Path, required=True)
    save.add_argument("--destination", type=Path, required=True)
    check = commands.add_parser("verify")
    check.add_argument("folder", type=Path)
    branch = commands.add_parser("fork")
    branch.add_argument("folder", type=Path)
    branch.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "save":
        result = create(ROOT, read(args.definition), args.destination)
    elif args.command == "verify":
        result = verify(args.folder.resolve())
    else:
        result = fork(ROOT, args.folder.resolve(), args.destination)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
