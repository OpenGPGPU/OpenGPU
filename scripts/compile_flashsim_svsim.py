#!/usr/bin/env python3
"""Build a FlashSim executable for Chisel's synchronous svsim protocol."""

from argparse import ArgumentParser
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_]*"
MACRO_IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_$]*"


@dataclass(frozen=True)
class Port:
    index: int
    name: str
    gettable: bool
    settable: bool


def read_testbench(path):
    text = path.read_text()
    top = re.search(rf"(?m)^\s*({IDENTIFIER}) dut \($", text)
    if top is None:
        raise ValueError("Cannot find the Chisel top in the svsim testbench")
    exports = set(re.findall(rf'export "DPI-C" function ({IDENTIFIER});', text))
    ports = [
        Port(int(index, 16), name, f"getBitsImpl_{name}" in exports, f"setBitsImpl_{name}" in exports)
        for index, name in re.findall(rf"(?m)^\s*// Port ([0-9a-f]+): ({IDENTIFIER})$", text)
    ]
    if [port.index for port in ports] != list(range(len(ports))):
        raise ValueError("The svsim port table is incomplete")
    return top[1], ports


def prepare_rtl(top, filelist, primary, build, defines):
    sources = []
    if defines:
        lines = []
        for definition in defines:
            name, separator, value = definition.partition("=")
            if not re.fullmatch(MACRO_IDENTIFIER, name) or "\n" in value or "\r" in value:
                raise ValueError(f"Invalid Verilog define {name!r}")
            lines.append(f"`define {name}" + (f" {value}" if separator else ""))
        prefix = build / "defines.sv"
        prefix.write_text("\n".join(lines) + "\n")
        sources.append(str(prefix))
    for line in filelist.read_text().splitlines():
        if not line.strip():
            continue
        source = (filelist.parent / line).resolve(strict=True)
        if not source.is_relative_to(primary):
            continue
        if source.suffix not in {".sv", ".v", ".svh", ".vh"}:
            raise ValueError(f"FlashSim cannot compile the svsim source {source}")
        if source.suffix in {".sv", ".v"}:
            sources.append(str(source))
    top_source = primary / f"{top}.sv"
    if not top_source.is_file():
        raise ValueError(f"Cannot locate split RTL for {top}")
    # svsim lists the top bind layers. Keep their transitively included
    # child layers available in the original split RTL directory.
    (primary / "filelist.f").write_text("\n".join(sources) + "\n")
    return top_source


def emit_bridge(module, top, ports, path):
    if module.name != top or set(module.ports) != {port.name for port in ports}:
        raise ValueError("FlashSim RTL ports do not match the Chisel testbench")
    clock = module.signals.get(module.always.clock)
    if clock is None or clock.kind != "input" or clock.width != 1 or clock.depth:
        if (module.always.body or module.mem_writes or module.assertions
                or module.reset_epilogue or module.clocked_processes):
            raise ValueError("FlashSim svsim requires a one-bit input clock for a sequential DUT")
        clock = None
    lines = [
        '#include "dut.h"',
        f"using FlashSimDut = {top}Dut;",
        '#include "flashsim_svsim_runtime.hpp"',
    ]
    if clock is None:
        lines.append("static bool clockHigh() { return false; }")
    else:
        # FlashSim can elide the C++ clock field if it has no combinational
        # consumers or observers. svsim still drives it and controls edges.
        lines += [
            "static uint8_t drivenClock = 0;",
            "static bool clockHigh() { return drivenClock != 0; }",
            f"template <typename Model> static auto driveClock(Model &d, int) "
            f"-> decltype(d.{clock.name} = drivenClock, void()) {{ d.{clock.name} = drivenClock; }}",
            "template <typename Model> static void driveClock(Model &, long) {}",
        ]
    getters, setters = [], []
    for port in ports:
        signal = module.signals[port.name]
        if signal.width < 1 or signal.depth or signal.kind not in {"input", "output"}:
            raise ValueError(f"Unsupported FlashSim port {port.name}")
        if port.settable != (signal.kind == "input") or not port.gettable:
            raise ValueError(f"Unexpected direction for Chisel port {port.name}")
        size = (signal.width + 7) // 8
        is_clock = clock is not None and port.name == clock.name
        field = "drivenClock" if is_clock else f"dut->{port.name}"
        evaluate = f"dut->eval_{port.name}(); " if signal.kind == "output" else ""
        lines.append(f"static void get_{port.index}(uint8_t *data) {{ "
                     f"{evaluate}getBits({field}, data, {size}); }}")
        getters.append(f"  case {port.index}: *bits = {signal.width}; *getter = get_{port.index}; return 0;")
        if port.settable:
            sync_clock = " driveClock(*dut, 0);" if is_clock else ""
            lines.append(f"static void set_{port.index}(const uint8_t *data) {{ "
                         f"setBits({field}, data, {size});{sync_clock} }}")
            setters.append(f"  case {port.index}: *bits = {signal.width}; *setter = set_{port.index}; return 0;")
    lines += [
        'extern "C" int port_getter(int id, int *bits, void (**getter)(uint8_t *)) {',
        "  switch (id) {", *getters, "  default: return -1;", "  }", "}",
        'extern "C" int port_setter(int id, int *bits, void (**setter)(const uint8_t *)) {',
        "  switch (id) {", *setters, "  default: return -1;", "  }", "}",
    ]
    path.write_text("\n".join(lines) + "\n")


def build_executable(build, generated, bridge, output, jobs):
    compiler = shlex.split(os.environ.get("CXX", "c++"))
    if not compiler:
        raise ValueError("CXX must identify a compiler")
    scripts = Path(__file__).resolve().parent
    options = ["-std=c++17", "-w", "-I", str(build), "-I", str(generated), "-I", str(scripts)]
    if "clang" in subprocess.check_output([*compiler, "--version"], text=True).lower():
        options.append("-fbracket-depth=4096")

    def compile_source(source):
        target = build / (source.stem + ".o")
        optimization = "-O0" if source.name == "dut_commit.cpp" else "-O1"
        subprocess.run([*compiler, *options, optimization, "-c", source, "-o", target], check=True)
        return target

    objects = [compile_source(bridge)]
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        objects.extend(pool.map(compile_source, sorted(build.glob("dut_*.cpp"))))
    linked = build / "simulation"
    subprocess.run([*compiler, *objects, "-o", linked], check=True)
    linked.replace(output)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("-F", required=True, type=Path, dest="filelist")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--jobs", required=True, type=int)
    parser.add_argument("--shards", required=True, type=int)
    parser.add_argument("--define", action="append", default=[])
    args = parser.parse_args()
    if args.jobs < 1 or args.shards < 0:
        parser.error("jobs must be positive and shards must be nonnegative")
    if root := os.environ.get("FLASHSIM_DIR"):
        sys.path.insert(0, root)
    # macOS system make and sh clear DYLD_LIBRARY_PATH before launching Python.
    if sys.platform == "darwin" and (libraries := os.environ.get("FLASHSIM_CIRCT_LIBRARY_PATH")):
        os.environ["DYLD_LIBRARY_PATH"] = libraries
    from flashsim.compile import compile_file

    filelist = args.filelist.resolve(strict=True)
    workspace = filelist.parent.parent
    generated = workspace / "generated-sources"
    top, ports = read_testbench(generated / "testbench.sv")
    build = filelist.parent / "flashsim"
    build.mkdir()
    rtl = prepare_rtl(top, filelist, workspace / "primary-sources", build, args.define)
    module = compile_file(rtl, build / "dut.h", frontend="circt", split=args.shards, assertions=True)
    bridge = build / "driver.cpp"
    emit_bridge(module, top, ports, bridge)
    del module
    build_executable(build, generated, bridge, args.output, args.jobs)


if __name__ == "__main__":
    main()
