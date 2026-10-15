#!/usr/bin/env python3
"""Compare native GLES draw time in fresh, pixel-checked Debian guests."""
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


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--base-image", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True,
                        help="new short absolute directory (QMP has a socket path limit)")
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error("--rounds must be positive")
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    binaries = {"baseline": args.baseline.resolve(), "candidate": args.candidate.resolve()}
    report = {"binaries": {name: {"path": str(path), "sha256": digest(path)}
                           for name, path in binaries.items()},
              "base_image": str(args.base_image.resolve()),
              "scope": "native GLES 160x120 draw; fresh guest; pixel proof required; no scanout capture",
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
            with (work / (run_dir.name + ".log")).open("w") as log:
                result = subprocess.run(["bash", "scripts/run_gtk_coverage_guest.sh"],
                                        cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
            text = (run_dir / "weston-repro.log").read_text(errors="replace") if (
                run_dir / "weston-repro.log").exists() else ""
            times = re.findall(r"complete draw=\d+ status=0 elapsed_ms=([\d.]+)", text)
            entry = {"name": name, "round": round_index, "work": str(run_dir),
                     "gate_exit": result.returncode, "pixel_pass": "OPENGPU GL FILL PASS" in text,
                     "draw_ms": [float(value) for value in times]}
            report["runs"].append(entry)
            save()
            if result.returncode or not entry["pixel_pass"] or len(times) != 1:
                raise SystemExit(f"Guest qualification failed: {run_dir}")
            print(f"round {round_index + 1} {name}: {times[0]} ms; pixels PASS", flush=True)
    baseline = statistics.median(run["draw_ms"][0] for run in report["runs"]
                                 if run["name"] == "baseline")
    for name in binaries:
        values = [run["draw_ms"][0] for run in report["runs"] if run["name"] == name]
        median = statistics.median(values)
        report["summary"][name] = {"draw_ms": values, "median_ms": median,
                                    "reduction_percent": 100 * (1 - median / baseline)}
    save()
    print(json.dumps(report["summary"], indent=2))


if __name__ == "__main__":
    main()
