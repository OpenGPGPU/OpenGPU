#!/usr/bin/env python3
"""Bound ARTI's idle MMIO settle batch without rebuilding the Verilator DUT.

Only arti_rtl_model.o is replaced in a separate archive. All generated RTL and
runtime objects must remain byte-identical. This changes simulator scheduling,
not GPU capacity, operating frequency, assertions or guest timeout budgets.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from bench_arti_model import verilator_include


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def members(path):
    """Hash member payloads in ordinary BSD or GNU ar archives."""
    result, long_names = {}, b""
    with Path(path).open("rb") as stream:
        if stream.read(8) != b"!<arch>\n":
            raise ValueError("expected an ordinary ar archive")
        while header := stream.read(60):
            if len(header) != 60 or header[58:] != b"`\n":
                raise ValueError("invalid archive member header")
            size = int(header[48:58])
            name = header[:16].decode().strip()
            data = stream.read(size)
            if len(data) != size:
                raise ValueError("truncated archive member")
            if name == "//":
                long_names = data
            elif name not in {"/", "/SYM64/"}:
                if name.startswith("#1/"):
                    length = int(name[3:])
                    name, data = data[:length].rstrip(b"\0").decode(), data[length:]
                elif re.fullmatch(r"/\d+", name):
                    name = long_names[int(name[1:]):].split(b"/\n", 1)[0].decode()
                else:
                    name = name.rstrip("/")
                if name not in {"__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED"}:
                    if not name or name in result:
                        raise ValueError("empty or duplicate archive member: " + name)
                    result[name] = hashlib.sha256(data).hexdigest()
            if size % 2 and stream.read(1) != b"\n":
                raise ValueError("invalid archive padding")
    return result


def build(model, output, idle_cycles, compiler, include):
    model, output = Path(model).resolve(), Path(output).resolve()
    source, header = model / "arti_rtl_model.cpp", model / "arti_rtl_model.h"
    archive = model / "verilated/libarti_rtl_model.a"
    frozen = {str(p): digest(p) for p in [source, header, *sorted((model / "verilated").glob("*.h"))]}
    original_hash = digest(archive)
    original_members = members(archive)
    if "arti_rtl_model.o" not in original_members:
        raise ValueError("archive lacks the separate arti_rtl_model.o adapter")
    text = source.read_text()
    defaults = re.findall(r"^#define ARTI_MODEL_IDLE_GRACE (\d+)$", text, re.M)
    if len(defaults) != 1 or "#ifndef ARTI_MODEL_IDLE_GRACE" not in text:
        raise ValueError("unsupported adapter idle-grace definition")
    if not 0 < idle_cycles <= int(defaults[0]):
        raise ValueError("idle cycles must be positive and no larger than the original default")
    if not re.search(r'^#include "V\w+\.h"$', text, re.M):
        raise ValueError("expected a generated Verilator adapter")
    output.mkdir(parents=True, exist_ok=False)
    shutil.copy2(source, output / source.name)
    shutil.copy2(header, output / header.name)
    command = [compiler, "-std=gnu++17", "-fPIC", "-fPIE", "-O1", "-w",
               f"-DARTI_MODEL_IDLE_GRACE={idle_cycles}",
               f"-I{model / 'verilated'}", f"-I{include}", "-c",
               str(output / source.name), "-o", str(output / "arti_rtl_model.o")]
    with (output / "compile.log").open("w") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    candidate = output / archive.name
    shutil.copy2(archive, candidate)
    subprocess.run(["ar", "rcs", str(candidate), str(output / "arti_rtl_model.o")], check=True)
    candidate_members = members(candidate)
    if original_members.keys() != candidate_members.keys():
        raise ValueError("candidate changed archive membership")
    changed = [name for name in original_members
               if original_members[name] != candidate_members[name]]
    if set(changed) - {"arti_rtl_model.o"}:
        raise ValueError("candidate changed a DUT or runtime object")
    if digest(archive) != original_hash:
        raise ValueError("original archive changed during preparation")
    if any(digest(path) != expected for path, expected in frozen.items()):
        raise ValueError("original adapter or generated headers changed during preparation")
    result = dict(original_archive=str(archive), original_archive_sha256=original_hash,
                  archive=str(candidate), archive_sha256=digest(candidate),
                  wrapper_source_sha256=digest(source), header_sha256=digest(header),
                  original_idle_cycles=int(defaults[0]), idle_cycles=idle_cycles,
                  frozen_inputs_sha256=frozen,
                  changed_members=changed, unchanged_DUT_and_runtime_members=len(original_members) - 1,
                  compile=command, simulation_qualified=False,
                  scope="Software MMIO batching only; no DUT rebuild, capacity/clock/FPS or guest qualification.")
    (output / "manifest.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path, help="generated embedded directory")
    parser.add_argument("output", type=Path, help="fresh directory, normally under .artifacts/")
    parser.add_argument("--idle-cycles", type=int, required=True)
    parser.add_argument("--compiler", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--verilator-include", help="installed Verilator runtime headers")
    args = parser.parse_args()
    result = build(args.model, args.output, args.idle_cycles, args.compiler,
                   args.verilator_include or verilator_include())
    print(result["archive"])


if __name__ == "__main__":
    main()
