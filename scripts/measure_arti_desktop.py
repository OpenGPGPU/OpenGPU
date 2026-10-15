#!/usr/bin/env python3
"""Measure a fresh, headless Weston boot using an explicitly selected model.

The framebuffer heuristic locates a candidate for visual review, not a pixel
correctness verdict. There is no observation deadline: a slow draw stays alive.
The launcher and guest disk are private to the new output directory.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

from PIL import Image


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect_frame(path):
    with Image.open(path) as source:
        image = source.convert("RGB")
        colors = sorted(image.getcolors(image.width * image.height), reverse=True)
        count, body = colors[0]
        rows = {str(y): [image.getpixel((x, y)) for x in (8, 128, 256, 384, 632)]
                for y in (15, 465)} if image.size == (640, 480) else {}
        panel = any(sum(sum(abs(v[i] - body[i]) for i in range(3)) > 50
                        for v in values) >= 4 for values in rows.values())
        coverage = count / (image.width * image.height)
        return dict(size=list(image.size), dominant_rgb=list(body), coverage=coverage,
                    rows=rows, candidate=image.size == (640, 480) and coverage > .5
                    and max(body) > 70 and max(body) - min(body) > 30 and panel)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qemu", type=Path, required=True)
    parser.add_argument("--display-work", type=Path, required=True)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--capture-seconds", type=float, default=15)
    args = parser.parse_args()
    if args.capture_seconds <= 0:
        parser.error("capture interval must be positive")
    root = Path(__file__).resolve().parents[1]
    qemu, payload, base, out = (p.resolve() for p in
                              (args.qemu, args.display_work, args.base, args.output))
    for path in (qemu, base, payload / "opengpu-driver/gpu_drv.ko",
                 payload / "opengpu-driver/opengpu_dri.so"):
        if not path.is_file():
            parser.error(f"missing artifact: {path}")
    out.mkdir(parents=True, exist_ok=False)
    env = {k: v for k, v in os.environ.items() if not k.startswith(
        ("ARTI_", "QEMU_", "GTK_PROOF_", "OPENGPU_", "LLVM_PROFILE_"))}
    settings = dict(QEMU=str(qemu), DISPLAY_WORK=str(payload),
                    DRIVER_OUTPUT=str(payload / "opengpu-driver"), GPU_SIM="flashsim",
                    QEMU_DISPLAY="none", OPENGPU_AUTO_DISPLAY="weston",
                    DEBIAN_BASE=str(base), DISK=str(out / "guest.qcow2"),
                    CIDATA=str(out / "cloud-init.iso"),
                    MODULES_ISO=str(out / "opengpu-modules.iso"),
                    REBUILD_CLOUDINIT="1", BUILD_USERSPACE="0", CLOUDINIT_PACKAGES="0",
                    DEBIAN_SERIAL_TARGET="logfile", SERIAL_LOG=str(out / "serial.log"),
                    QEMU_QMP_SOCKET=str(out / "qmp.sock"), QEMU_ACCEL="hvf", QEMU_CPU="host",
                    ARTI_DISPLAY_DUMP=str(out / "live.ppm"), ARTI_DISPLAY_DUMP_PERIODIC="1",
                    ARTI_MODEL_STATS="1", ARTI_MODEL_IRQ_PUMP="1024",
                    ARTI_MODEL_IRQ_PUMP_NS="8000000", ARTI_MODEL_SETTLE_NS="8000000")
    env.update(settings)
    result = dict(status="running", scope="Fresh 640x480 Weston DRM desktop; candidate requires visual review",
                  qemu_sha256=digest(qemu), payload_sha256={p.name: digest(p) for p in
                  sorted((payload / "opengpu-driver").iterdir()) if p.is_file()},
                  settings=settings, host_load_start=list(os.getloadavg()),
                  events=[], captures=[], capture_seconds=args.capture_seconds)
    start, qstart = time.monotonic(), None

    def save():
        temp = out / "result.json.tmp"
        temp.write_text(json.dumps(result, indent=2) + "\n")
        temp.replace(out / "result.json")

    def event(name, **details):
        row = dict(event=name, command_seconds=time.monotonic() - start, **details)
        if qstart is not None:
            row["qemu_seconds"] = time.monotonic() - qstart
        result["events"].append(row)
        print(json.dumps(row), flush=True)
        save()

    with (out / "console.log").open("w") as log:
        process = subprocess.Popen(["bash", "scripts/run_arti_debian.sh"], cwd=root,
                                   env=env, stdin=subprocess.DEVNULL, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        result["launcher_pid"] = process.pid
        event("command_started")
        seen, next_capture = set(), 0
        try:
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"launcher exited {process.returncode}")
                if qstart is None:
                    command = subprocess.check_output(
                        ["ps", "-p", str(process.pid), "-o", "comm="], text=True).strip()
                    if command == str(qemu):
                        qstart = time.monotonic()
                        result["qemu_pid"] = process.pid
                        event("qemu_started", observation_uncertainty_seconds=1)
                serial = out / "serial.log"
                text = serial.read_text(errors="replace") if serial.exists() else ""
                for marker in ("GPU probe:", "Output 'Virtual-1' enabled",
                               "opengpu: submit draw=1", "opengpu: complete draw=1",
                               "OPENGPU GTK FRAME PASS: idle", "OPENGPU GTK FRAME PASS: active"):
                    if marker in text and marker not in seen:
                        seen.add(marker)
                        event(marker)
                live = out / "live.ppm"
                if qstart is not None and live.exists() and time.monotonic() >= next_capture:
                    capture = out / f'frame-{len(result["captures"]):04d}.ppm'
                    try:
                        capture.write_bytes(live.read_bytes())
                        info = inspect_frame(capture)
                    except (OSError, ValueError):
                        next_capture = time.monotonic() + 1
                        continue
                    info.update(file=capture.name, sha256=digest(capture),
                                qemu_seconds=time.monotonic() - qstart)
                    result["captures"].append(info)
                    save()
                    if info["candidate"]:
                        result.update(status="desktop_candidate", visual_review="pending",
                                      desktop_seconds=info["qemu_seconds"],
                                      host_load_end=list(os.getloadavg()))
                        with Image.open(capture) as image:
                            image.save(out / "desktop.png")
                        event("desktop_candidate_visible", capture=capture.name)
                        break
                    next_capture = time.monotonic() + args.capture_seconds
                time.sleep(1)
        except BaseException as error:
            result.update(status="failed", error=repr(error))
            save()
            raise
        finally:
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            try:
                result["qemu_exit"] = process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                result["qemu_exit"] = process.wait()
            save()


if __name__ == "__main__":
    main()
