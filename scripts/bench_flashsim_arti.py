#!/usr/bin/env python3
"""Time a fixed OpenGPU guest workload using only FlashSim-linked QEMU."""
import argparse
from pathlib import Path
from statistics import median
import subprocess
from time import monotonic


WORKLOADS = {
    "probe": ("PROBE_TIMING: driver load returned to init",),
    "examples": (
        "OPENGPU DRM PASS: card registered", "kadd 1+2+3=6",
        "coloured=1009 sample=0xff0000ff", "coloured=2016 sample=0xff0000ff",
        "mode=2x expected=0x80808080", "OPENGPU USERSPACE EXAMPLES PASS",
    ),
}


def flashsim_binary(path):
    path = path.resolve(strict=True)
    symbols = subprocess.check_output(["nm", str(path)])
    if (b"arti_rtl_model_skip_stats" not in symbols or b"GpuHostSystemAxiDut" not in symbols or
            b"VGpuHostSystemAxi" in symbols):
        raise ValueError(f"Not a FlashSim-linked OpenGPU QEMU binary: {path}")
    return path


def measure(qemu, args):
    command = [
        str(qemu), "-L", str(args.firmware), "-machine", "virt",
        "-accel", args.accel, "-cpu", args.cpu, "-m", "1G", "-smp", "2",
        "-display", "none", "-monitor", "none", "-serial", "stdio",
        "-kernel", str(args.kernel), "-initrd", str(args.initrd),
        "-append", "console=ttyAMA0",
    ]
    start = monotonic()
    run = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, timeout=args.timeout)
    elapsed = monotonic() - start
    serial = run.stdout.decode(errors="replace")
    missing = [marker for marker in (*WORKLOADS[args.workload], "reboot: Power down")
               if marker not in serial]
    if (run.returncode or missing or "Kernel panic" in serial or
            "rcu_preempt detected stalls" in serial):
        raise RuntimeError(f"{qemu} exited {run.returncode}, missing {missing}\n"
                           + "\n".join(serial.splitlines()[-12:]))
    return elapsed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qemu", required=True, type=Path)
    parser.add_argument("--candidate", type=Path, help="optional second FlashSim binary")
    parser.add_argument("--kernel", required=True, type=Path)
    parser.add_argument("--initrd", required=True, type=Path)
    parser.add_argument("--firmware", required=True, type=Path)
    parser.add_argument("--workload", choices=WORKLOADS, required=True)
    parser.add_argument("--accel", default="tcg")
    parser.add_argument("--cpu", default="cortex-a53")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if args.rounds <= 0 or args.timeout <= 0:
        parser.error("rounds and timeout must be positive")
    executables = {"baseline": flashsim_binary(args.qemu)}
    if args.candidate:
        executables["candidate"] = flashsim_binary(args.candidate)
    runs = {name: [] for name in executables}
    pairs = list(executables.items())
    for round_index in range(args.rounds):
        for name, path in (pairs if round_index % 2 == 0 else reversed(pairs)):
            seconds = measure(path, args)
            runs[name].append(seconds)
            print(f"{name} round {round_index + 1}: {seconds:.3f} s", flush=True)
    for name, samples in runs.items():
        print(f"{name} median: {median(samples):.3f} s")
    if "candidate" in runs:
        print(f"candidate speedup: {median(runs['baseline']) / median(runs['candidate']):.3f}x")


if __name__ == "__main__":
    main()
