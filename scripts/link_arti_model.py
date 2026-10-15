#!/usr/bin/env python3
"""Link a separate QEMU executable with a chosen ARTI model archive.

Reuse the configured Ninja link command without changing QEMU staging files,
the normal executable, or Meson settings. Keep the archive's C ABI compatible
with the QEMU objects in the selected build directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b""):
            result.update(chunk)
    return result.hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("archive", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--qemu-build", type=Path, default=root / "qemu-build")
    p.add_argument("--profile-runtime", action="store_true")
    p.add_argument("--device-object", type=Path,
                   help="replace only the compiled ARTI QEMU device in this executable")
    p.add_argument("--order-file", type=Path, help="Darwin linker symbol order file")
    args = p.parse_args()
    build, archive, output = args.qemu_build.resolve(), args.archive.resolve(), args.output.resolve()
    if output in (build / "qemu-system-aarch64", build / "qemu-system-aarch64-unsigned"):
        p.error("choose a separate output path")
    if not archive.is_file():
        p.error(f"archive not found: {archive}")
    text = subprocess.check_output(["ninja", "-C", str(build), "-t", "commands",
                                    "qemu-system-aarch64"], text=True)
    commands = [shlex.split(line) for line in text.splitlines() if line.strip()]
    candidates = [cmd for cmd in commands if any("libarti_rtl_model.a" in arg for arg in cmd)]
    if len(candidates) != 1:
        p.error("cannot identify a unique ARTI QEMU link command")
    link = candidates[0].copy()
    if any(arg.startswith("@") for arg in link):
        p.error("response-file link commands require an expanded Ninja command")
    libraries = [i for i,arg in enumerate(link) if Path(arg).name == "libarti_rtl_model.a"]
    if len(libraries) != 1 or "-o" not in link:
        p.error("unexpected model archive or output arguments")
    output.parent.mkdir(parents=True, exist_ok=True)
    unsigned = output.with_name(output.name+".unsigned")
    link[libraries[0]] = str(archive)
    link[link.index("-o")+1] = str(unsigned)
    device = args.device_object.resolve() if args.device_object else None
    if device:
        if not device.is_file():
            p.error(f"device object not found: {device}")
        slots = [i for i, arg in enumerate(link) if Path(arg).name == "hw_misc_arti-rtl.c.o"]
        if len(slots) != 1:
            p.error("cannot identify a unique compiled ARTI device object")
        link[slots[0]] = str(device)
    order = args.order_file.resolve() if args.order_file else None
    if order:
        if not order.is_file():
            p.error(f"symbol order file not found: {order}")
        link.extend(["-Xlinker", "-order_file", "-Xlinker", str(order)])
    if args.profile_runtime:
        link.append("-fprofile-instr-generate")
    result = subprocess.run(link, cwd=build)
    if result.returncode:
        raise SystemExit(result.returncode)
    entitlements = [cmd for cmd in commands if Path(cmd[0]).name == "entitlement.sh"]
    post = None
    if entitlements:
        if len(entitlements) != 1 or len(entitlements[0]) != 5:
            p.error("unexpected QEMU entitlement command")
        post = entitlements[0].copy()
        post[1], post[2] = str(output), str(unsigned)
        subprocess.run(post, cwd=build, check=True)
    else:
        unsigned.replace(output)
    manifest = {"archive": str(archive), "archive_sha256": digest(archive),
                "output": str(output), "output_sha256": digest(output),
                "profile_runtime": args.profile_runtime,
                "device_object": str(device) if device else None,
                "device_object_sha256": digest(device) if device else None,
                "order_file": str(order) if order else None,
                "order_file_sha256": digest(order) if order else None,
                "cwd": str(build),
                "link": link, "post_link": post}
    output.with_name(output.name+".json").write_text(json.dumps(manifest, indent=2)+"\n")
    print(output, flush=True)


if __name__ == "__main__":
    main()
