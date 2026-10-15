#!/usr/bin/env python3
"""Compare native GLES cycles or draw latency in pixel-checked Debian guests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(data)
    return value.hexdigest()


def read_guest_result(run_dir, name, round_index, exit_status):
    log = run_dir / "weston-repro.log"
    text = log.read_text(errors="replace") if log.exists() else ""
    times = re.findall(r"complete draw=\d+ status=0 elapsed_ms=([\d.]+)", text)
    draws = [{"width": int(w), "height": int(h), "texture": int(t)}
             for w, h, t in re.findall(
                 r"submit draw=\d+ target=(\d+)x(\d+)[^\r\n]*texture=(\d+)", text)]
    scene_verified = ("gl_fill: Weston shader reproduction 160x120" in text
                      and draws == [{"width": 640, "height": 480, "texture": 0}])
    return {"name": name, "round": round_index, "work": str(run_dir),
            "gate_exit": exit_status, "pixel_pass": "OPENGPU GL FILL PASS" in text,
            "draw_ms": [float(value) for value in times],
            "draws": draws, "scene_verified": scene_verified}


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--base-image", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True,
                        help="new short absolute directory (QMP has a socket path limit)")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--display-work", type=Path,
                        help="baseline/shared guest payload; defaults to DISPLAY_WORK")
    parser.add_argument("--candidate-display-work", type=Path,
                        help="optional candidate payload for a software comparison")
    parser.add_argument("--cycles-only", action="store_true",
                        help="require a cycle-probed QEMU; report GPU cycles without a host speed claim")
    parser.add_argument("--record-trace", action="store_true",
                        help="record each run's hardware traffic (requires --cycles-only)")
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error("--rounds must be positive")
    if args.record_trace and not args.cycles_only:
        parser.error("--record-trace requires --cycles-only; recording perturbs host timing")
    work = args.work.resolve()
    binaries = {"baseline": args.baseline.resolve(), "candidate": args.candidate.resolve()}
    payload = args.display_work or (Path(os.environ["DISPLAY_WORK"])
                                   if os.environ.get("DISPLAY_WORK") else None)
    if args.candidate_display_work and not payload:
        parser.error("--candidate-display-work requires a baseline --display-work")
    payloads = {"baseline": payload, "candidate": args.candidate_display_work or payload}
    payload_reports = {}
    for name, selected in payloads.items():
        if selected:
            selected = selected.resolve()
            payloads[name] = selected
            driver = selected / "opengpu-driver"
            for required in ("gpu_drv.ko", "opengpu_dri.so", "opengpu_gl_fill.bin"):
                if not (driver / required).is_file():
                    parser.error(f"missing {name} guest payload file: {driver / required}")
            payload_reports[name] = {"path": str(selected),
                "sha256": {p.name: digest(p) for p in sorted(driver.iterdir()) if p.is_file()}}
    work.mkdir(parents=True, exist_ok=False)
    report = {"binaries": {name: {"path": str(path), "sha256": digest(path)}
                           for name, path in binaries.items()},
              "base_image": str(args.base_image.resolve()),
              "scope": "native GLES triangle with160x120bounding box in640x480mode; fresh guest; pixel proof required; no scanout capture",
              "workload": "Weston uniform-colour/opacity shader control; no texture sampling",
              "payload": payload_reports.get("baseline") if payloads["baseline"] == payloads["candidate"] else None,
              "payloads": payload_reports,
              "measurement": "GPU cycles only; elapsed times diagnostic" if args.cycles_only else "native guest host draw latency",
              "runs": [], "summary": {}}
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("ARTI_", "GTK_PROOF_", "QEMU_", "LLVM_PROFILE_"))}
    env.update(GTK_DEBIAN_BASE=str(args.base_image.resolve()),
               QEMU_ACCEL="hvf", QEMU_CPU="host",
               ARTI_MODEL_IRQ_PUMP="1024", ARTI_MODEL_IRQ_PUMP_NS="8000000",
               ARTI_MODEL_SETTLE_NS="8000000", GTK_PROOF_SMOKE="1",
               GTK_PROOF_KMS_MODE="64x64", GTK_PROOF_WESTON_REPRO="medium",
               GTK_PROOF_REPRO_ONLY="1", GTK_PROOF_SCANOUT_ENABLE="0",
               GTK_PROOF_SKIP_BUILD="1", GTK_PROOF_SKIP_COVERAGE="1",
               GTK_PROOF_SMOKE_TIMEOUT_S="900")
    report["configuration"] = {key: value for key, value in env.items()
                               if key.startswith(("ARTI_", "GTK_PROOF_", "QEMU_"))}

    def save():
        temporary = work / "result.json.tmp"
        temporary.write_text(json.dumps(report, indent=2) + "\n")
        temporary.replace(work / "result.json")

    save()
    for round_index in range(args.rounds):
        names = list(binaries) if round_index % 2 == 0 else list(reversed(binaries))
        for name in names:
            run_dir = work / (name[0] + str(round_index))
            env.update(QEMU=str(binaries[name]), PROOF_WORK=str(run_dir))
            if payloads[name]:
                env.update(DISPLAY_WORK=str(payloads[name]),
                           DRIVER_OUTPUT=str(payloads[name] / "opengpu-driver"))
            if args.record_trace:
                env["ARTI_MODEL_TRACE"] = str(work / (run_dir.name + ".trace"))
            with (work / (run_dir.name + ".log")).open("w") as log:
                result = subprocess.run(["bash", "scripts/run_gtk_coverage_guest.sh"],
                                        cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
            entry = read_guest_result(run_dir, name, round_index, result.returncode)
            boot = (run_dir / "boot.log").read_text(errors="replace") if (run_dir / "boot.log").exists() else ""
            cycles = re.findall(r"CYCLE_PROBE_GLOBAL ticks=(\d+)", boot)
            if args.cycles_only:
                if len(cycles) != 1:
                    raise SystemExit(f"Expected one cycle-probe report: {run_dir}")
                entry["gpu_cycles"] = int(cycles[0])
            elif cycles:
                raise SystemExit("Cycle-probe overhead invalidates host timing; use --cycles-only")
            if args.record_trace:
                trace = work / (run_dir.name + ".trace")
                entry["trace"] = {"path": str(trace), "sha256": digest(trace)}
            if payloads[name]:
                driver = payloads[name] / "opengpu-driver"
                after = {p.name: digest(p) for p in sorted(driver.iterdir()) if p.is_file()}
                entry["payload_unchanged"] = after == payload_reports[name]["sha256"]
                if not entry["payload_unchanged"]:
                    raise SystemExit(f"Guest payload changed during measurement: {driver}")
            report["runs"].append(entry)
            save()
            if (result.returncode or not entry["pixel_pass"]
                    or not entry["scene_verified"] or len(entry["draw_ms"]) != 1):
                raise SystemExit(f"Guest qualification failed: {run_dir}")
            measured = f"{entry['gpu_cycles']} GPU cycles" if args.cycles_only else f"{entry['draw_ms'][0]} ms"
            print(f"round {round_index + 1} {name}: {measured}; pixels PASS", flush=True)
    metric = "gpu_cycles" if args.cycles_only else "draw_ms"
    values_for = lambda name: [run[metric] if args.cycles_only else run[metric][0]
                              for run in report["runs"] if run["name"] == name]
    baseline = statistics.median(values_for("baseline"))
    for name in binaries:
        values = values_for(name)
        median = statistics.median(values)
        report["summary"][name] = {metric: values, "median_" + metric: median,
                                    "reduction_percent": 100 * (1 - median / baseline)}
    save()
    print(json.dumps(report["summary"], indent=2))


if __name__ == "__main__":
    main()
