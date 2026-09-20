"""Four isolated native H3 takes. Only the attention CLI flag differs in A/B."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import socket
import subprocess
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output/h3-attention-ab-20260916"
CORE = Path(r"D:\Projects\ComfyUI-aki\ComfyUI")
PYTHON = CORE.parent / "python/python.exe"
OFFICIAL = ROOT / "docs/production-checkpoints/templates/video_minimax_h3_i2v.json"
PORT = 18189
URL = f"http://127.0.0.1:{PORT}"
HTTP = requests.Session()
HTTP.trust_env = False
PROMPTS = {
    "walk": (
        "Use the supplied first frame. One continuous full-body live-action shot in the daylight "
        "wooden corridor. The woman is already taking a step. She walks briskly forward past the "
        "open doorway on her right and continues toward and past the camera. Each foot lands "
        "naturally as her hips, shoulders and arms move with the stride. The camera stays in place. "
        "Keep her face, red tunic and black trousers. Ordinary real-time walking, no pose hold, "
        "slow motion or internal cut. Footsteps and room ambience, no speech or music."
    ),
    "rise": (
        "Use the supplied first frame. One continuous medium-wide live-action shot in the daylight "
        "wooden room. The seated woman immediately leans forward, transfers her weight onto both "
        "feet, stands up in one ordinary movement and walks briskly to the open doorway on the "
        "right. Her arms move naturally with her steps. The bench stays still. The camera stays "
        "in place. Keep her face, red tunic and black trousers. Ordinary real-time movement "
        "without a pause between standing and walking, no slow motion or internal cut. "
        "Footsteps and cloth rustle, no speech or music."
    ),
}
SEEDS = {"walk": 2026091611, "rise": 2026091612}


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def get(path):
    response = HTTP.get(URL + path, timeout=30)
    response.raise_for_status()
    return response.json()


def build(shot):
    # Resolve the official subgraph's false (non-Turbo) branches explicitly.
    ui = json.loads(OFFICIAL.read_text(encoding="utf-8"))
    graph = ui["definitions"]["subgraphs"][0]
    nodes = {n["id"]: n for n in graph["nodes"]}
    links = {link["id"]: link for link in graph["links"]}
    assert links[236]["origin_id"] == 124 and nodes[124]["widgets_values"][0] == 20
    assert links[232]["origin_id"] == 6 and nodes[6]["type"] == "UNETLoader"
    assert nodes[17]["widgets_values"][0] == "res_multistep"
    assert nodes[9]["widgets_values"][0] == "simple"
    model = nodes[6]["widgets_values"][0]
    clip = nodes[13]["widgets_values"][0]
    video_vae = nodes[11]["widgets_values"][0]
    audio_vae = nodes[24]["widgets_values"][0]
    def node(kind, **inputs):
        return {"class_type": kind, "inputs": inputs}
    return {
        "image": node("LoadImage", image=f"{shot}.png"),
        "model": node("UNETLoader", unet_name=model, weight_dtype="default"),
        "clip": node("CLIPLoader", clip_name=clip, type="minimax", device="default"),
        "video_vae": node("VAELoader", vae_name=video_vae),
        "audio_vae": node("VAELoader", vae_name=audio_vae),
        "i2v": node("MiniMaxH3ImageToVideo", clip=["clip", 0], vae=["video_vae", 0],
                    first_frame=["image", 0], width=576, height=1024, length=124, prompt=PROMPTS[shot]),
        "noise": node("RandomNoise", noise_seed=SEEDS[shot]),
        "guider": node("BasicGuider", model=["model", 0], conditioning=["i2v", 0]),
        "schedule": node("BasicScheduler", model=["model", 0], scheduler="simple", steps=20, denoise=1.0),
        "sampler": node("KSamplerSelect", sampler_name="res_multistep"),
        "sample": node("SamplerCustomAdvanced", noise=["noise", 0], guider=["guider", 0],
                       sampler=["sampler", 0], sigmas=["schedule", 0], latent_image=["i2v", 1]),
        "decode": node("VAEDecode", samples=["sample", 0], vae=["video_vae", 0]),
        "decode_audio": node("VAEDecodeAudio", samples=["sample", 0], vae=["audio_vae", 0]),
        "video": node("CreateVideo", images=["decode", 0], audio=["decode_audio", 0], fps=24.0),
        "save": node("SaveVideo", video=["video", 0], filename_prefix=f"attention-ab/{shot}", format="mp4", codec="h264"),
    }


def launch_args(backend):
    runtime = OUT / "runtime"
    runtime.resolve().relative_to(OUT.resolve())
    flag = "--use-sage-attention" if backend == "sage" else "--use-pytorch-cross-attention"
    return [str(PYTHON), "-B", str(CORE / "main.py"), "--listen", "127.0.0.1", "--port", str(PORT),
            "--base-directory", str(runtime), "--models-directory", str(CORE / "models"),
            "--database-url", "sqlite:///:memory:", "--disable-all-custom-nodes",
            "--preview-method", "none", "--cuda-malloc", flag]


def run_take(backend, shot, timeout):
    folder = OUT / f"{shot}-{backend}"
    workflow = build(shot)
    identity = {"inputSha256": digest(OUT / f"assets/{shot}.png"), "workflow": workflow,
                "officialSha256": digest(OFFICIAL), "attention": backend}
    state_file = folder / "state.json"
    if state_file.exists():
        state = json.loads(state_file.read_text(encoding="utf-8"))
        if state["identity"] != identity:
            raise RuntimeError("Experiment input changed; use a new output directory")
        if state.get("status") == "complete" and (folder / "native-24fps.mp4").exists():
            print(f"SKIP complete {shot}-{backend}", flush=True)
            return
        # A saved submission must never be silently repeated after a lost response.
        raise RuntimeError(f"Unfinished take {state_file}; inspect its promptId before retrying")
    save(folder / "workflow-api.json", workflow)
    state = {"identity": identity, "status": "submitting"}
    save(state_file, state)
    started = time.monotonic()
    response = HTTP.post(URL + "/prompt", json={"prompt": workflow, "client_id": f"niren-attention-{backend}-{shot}"}, timeout=60)
    if not response.ok:
        save(folder / "submission-error.json", response.json())
    response.raise_for_status()
    state.update(promptId=response.json()["prompt_id"], status="running")
    save(state_file, state)
    print(f"SUBMITTED {shot}-{backend} {state['promptId']}", flush=True)
    last_status = 0
    while time.monotonic() - started < timeout:
        history = get("/history/" + state["promptId"])
        entry = history.get(state["promptId"])
        if entry:
            save(folder / "history.json", entry)
            if entry.get("status", {}).get("status_str") == "error":
                state["status"] = "failed"
                save(state_file, state)
                raise RuntimeError(f"ComfyUI failed: {folder / 'history.json'}")
            if entry.get("status", {}).get("completed"):
                videos = entry["outputs"]["save"].get("images", []) + entry["outputs"]["save"].get("videos", [])
                item = next(v for v in videos if v["filename"].endswith(".mp4"))
                output_root = (OUT / "runtime/output").resolve()
                source = (output_root / item.get("subfolder", "") / item["filename"]).resolve()
                source.relative_to(output_root)
                shutil.copy2(source, folder / "native-24fps.mp4")
                state.update(status="complete", elapsedSeconds=round(time.monotonic() - started, 2),
                             outputSha256=digest(folder / "native-24fps.mp4"))
                save(state_file, state)
                print(f"COMPLETE {shot}-{backend} {state['elapsedSeconds']}s", flush=True)
                return
        elapsed = int(time.monotonic() - started)
        if elapsed - last_status >= 30:
            print(f"WAIT {shot}-{backend} {elapsed}s", flush=True)
            last_status = elapsed
        time.sleep(4)
    raise TimeoutError(f"Take still unfinished: {state['promptId']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=["sage", "sdpa", "both"], default="both")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--timeout", type=int, default=2400)
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for shot in PROMPTS:
        save(OUT / f"planned-{shot}.json", build(shot))
    if args.dry_run:
        print("Validated official ordinary branch; wrote plans, no server launched")
        return
    q = HTTP.get("http://127.0.0.1:8188/queue", timeout=15).json()
    if q["queue_running"] or q["queue_pending"]:
        raise RuntimeError("User instance has queued work; refusing concurrent GPU generation")
    with socket.socket() as sock:
        if sock.connect_ex(("127.0.0.1", PORT)) == 0:
            raise RuntimeError("Isolated port already occupied; refusing to reuse unknown instance")
    runtime = OUT / "runtime"
    for name in ("input", "output", "user", "temp"):
        (runtime / name).mkdir(parents=True, exist_ok=True)
    for shot in PROMPTS:
        shutil.copy2(OUT / f"assets/{shot}.png", runtime / f"input/{shot}.png")
    for backend in (["sage", "sdpa"] if args.backend == "both" else [args.backend]):
        env = os.environ.copy()
        env.update(PYTHONDONTWRITEBYTECODE="1", PYTHONUNBUFFERED="1", PYTHONUTF8="1",
                   TORCHINDUCTOR_CACHE_DIR=str(runtime / "torch-cache"), TRITON_CACHE_DIR=str(runtime / "triton-cache"))
        command = launch_args(backend)
        save(OUT / f"launch-{backend}.json", {"command": command})
        with (OUT / f"server-{backend}.log").open("ab", buffering=0) as log:
            process = subprocess.Popen(command, cwd=OUT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                for _ in range(150):
                    if process.poll() is not None:
                        raise RuntimeError(f"Isolated server exited; see server-{backend}.log")
                    try:
                        stats = get("/system_stats")
                        if command[-1] not in stats["system"]["argv"]:
                            raise RuntimeError("Unexpected attention launch flag")
                        save(OUT / f"system-{backend}.json", stats)
                        break
                    except requests.RequestException:
                        time.sleep(2)
                else:
                    raise TimeoutError("Isolated server startup timed out")
                print(f"READY {backend} pid={process.pid}", flush=True)
                for shot in PROMPTS:
                    run_take(backend, shot, args.timeout)
            finally:
                # This Popen handle belongs exclusively to this experiment.
                process.terminate()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)
        time.sleep(3)


if __name__ == "__main__":
    main()
