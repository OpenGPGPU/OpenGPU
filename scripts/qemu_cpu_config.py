#!/usr/bin/env python3
"""Resolve guest CPU execution and the associated RTL progress budgets."""
import argparse
import json
import os
from pathlib import Path
import platform
import plistlib
import shlex
import subprocess


def select_cpu(accel, cpu, sim, system, machine, signed_hvf):
    if accel == "auto":
        eligible = (system == "Darwin" and machine in ("arm64", "aarch64") and
                    sim == "flashsim" and (not cpu or cpu.split(",", 1)[0] == "host"))
        accel = "hvf" if eligible and signed_hvf else "tcg"
    if not cpu:
        cpu = "host" if accel.split(",", 1)[0] in ("hvf", "kvm") else "cortex-a53"
    return accel, cpu


def supports_signed_hvf(qemu):
    try:
        env = dict(os.environ, LLVM_PROFILE_FILE=os.devnull)
        help_result = subprocess.run([str(qemu), "-accel", "help"], capture_output=True,
                                     text=True, timeout=10, env=env)
        if help_result.returncode or "hvf" not in help_result.stdout.split():
            return False
        signature = subprocess.run(["codesign", "-d", "--entitlements", ":-", str(qemu)],
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
        if signature.returncode:
            return False
        return bool(plistlib.loads(signature.stdout).get("com.apple.security.hypervisor"))
    except (OSError, subprocess.TimeoutExpired, plistlib.InvalidFileException, ValueError):
        return False


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--qemu", type=Path, required=True)
    p.add_argument("--sim", choices=("flashsim", "verilator"), default="flashsim")
    p.add_argument("--shell", action="store_true")
    args = p.parse_args()
    requested = os.environ.get("QEMU_ACCEL") or "auto"
    cpu = os.environ.get("QEMU_CPU") or ""
    system, machine = platform.system(), platform.machine()
    probe = (requested == "auto" and system == "Darwin" and machine in ("arm64", "aarch64")
             and args.sim == "flashsim" and (not cpu or cpu.split(",", 1)[0] == "host"))
    accel, cpu = select_cpu(requested, cpu, args.sim, system, machine,
                            supports_signed_hvf(args.qemu) if probe else False)
    native_rtl = accel.split(",", 1)[0] == "hvf" and args.sim == "flashsim"
    values = {"QEMU_ACCEL": accel, "QEMU_CPU": cpu,
              "ARTI_MODEL_IRQ_PUMP": os.environ.get("ARTI_MODEL_IRQ_PUMP") or ("1024" if native_rtl else "16"),
              "ARTI_MODEL_IRQ_PUMP_NS": os.environ.get("ARTI_MODEL_IRQ_PUMP_NS") or ("8000000" if native_rtl else "200000"),
              "ARTI_MODEL_SETTLE_NS": os.environ.get("ARTI_MODEL_SETTLE_NS") or "8000000"}
    if args.shell:
        for key, value in values.items():
            print(f"{key}={shlex.quote(value)}")
    else:
        print(json.dumps(values, indent=2))


if __name__ == "__main__":
    main()
