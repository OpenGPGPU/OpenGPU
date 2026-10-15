#!/usr/bin/env python3
"""Run a reproducible ASAP7 1 GHz PPA flow for an emitted RTL block.

Select the block positionally (the historical form) or by preset:

  run_graphics_ppa.py <block-dir> <top-module> [synthesis-only]
  run_graphics_ppa.py --preset <name> [synthesis-only]
  run_graphics_ppa.py --list-presets

Presets capture the whole-block flows (SRAM-macro closure, VectorBackend,
rasterizer) that previously lived in separate run_*_physical.py scripts, so
there is a single physical-flow entry point. ChipAgent is located through
CHIPAGENT_DIR (default: a sibling ../chipagent); GRAPHICS_PPA_* env vars tune
an individual run.
"""
import os
import sys
from pathlib import Path

from ppa_common import REPO, resolve_rtl, run_flow, sram_lef, sram_lib

PRESETS = {
    "scalar-backend": {
        "rtl_dir": "generated/ppa_refresh_head/scalar_backend",
        "module": "ScalarBackend",
        "description": "SRAM-macro ScalarBackend 1 GHz closure",
        "cell_vt": "SLVT",
        "core_utilization": 30,
        "place_density": 0.5,
        "setup_slack_margin": 50.0,
        "macros": ["srambank_64x4x32_6t122"],
        "macro_placement_tcl": "timing/asap7/compute_unit_sram_macro_placement.tcl",
        "run_tag": "_margin50",
    },
    "shared-l2-slice": {
        "rtl_dir": "generated/ppa_refresh_head/shared_l2_slice",
        "module": "SharedL2Slice",
        "description": "SRAM-macro SharedL2Slice 1 GHz closure",
        "cell_vt": "LVT",
        "core_utilization": 15,
        "place_density": 0.3,
        "setup_slack_margin": 50.0,
        "macros": ["srambank_64x4x32_6t122", "srambank_64x4x64_6t122"],
        "macro_placement_tcl": "timing/asap7/l2_sram_macro_placement.tcl",
        "run_tag": "_margin50",
    },
    "vector-backend": {
        "rtl_dir": "generated/ppa_candidate_061_vector_backend_issueskid",
        "module": "VectorBackend",
        "description": "whole-block VectorBackend (candidate 061)",
        "cell_vt": "SLVT",
        "core_utilization": 25,
        "place_density": 0.6,
        "io_delay_percent": 0.1,
        "macros": ["srambank_64x4x64_6t122"],
        "macro_placement_tcl": "timing/asap7/compute_unit_sram_macro_placement.tcl",
        "timeout": 18000,
        "run_tag": "_vb_issueskid",
        "io_false_path_ports": [
            "io_unimplemented_valid", "io_unimplemented_bits_*",
            "io_scalarReserve_valid", "io_scalarReserve_bits_*",
            "io_committedVectorFlags_valid", "io_committedVectorFlags_bits_*",
            "io_memoryFault_valid", "io_memoryFault_bits_*",
            "io_memoryResponse_valid", "io_memoryResponse_ready",
            "io_memoryResponse_bits_*",
        ],
    },
    "raster": {
        "rtl_dir": "generated/ppa_raster_quad",
        "module": "TriangleRasterizer",
        "description": "M4b incremental-edge graphics rasterizer",
        "cell_vt": "SLVT",
        "core_utilization": 25,
        "place_density": 0.6,
        "timeout": 18000,
        "run_tag": "_raster_incr_edges",
    },
}


def parse_args(argv):
    if argv and argv[0] == "--list-presets":
        return None, [], False, True
    preset = None
    if argv and argv[0] == "--preset":
        if len(argv) < 2:
            raise SystemExit("--preset needs a name (see --list-presets)")
        preset = argv[1]
        argv = argv[2:]
    synthesis_only = False
    if argv and argv[-1] == "synthesis-only":
        synthesis_only = True
        argv = argv[:-1]
    return preset, argv, synthesis_only, False


def build_inputs(rtl_files, module, cfg, synthesis_only):
    core_utilization = int(os.environ.get(
        "GRAPHICS_PPA_CORE_UTILIZATION", cfg.get("core_utilization", 25)))
    place_density = float(os.environ.get(
        "GRAPHICS_PPA_PLACE_DENSITY", cfg.get("place_density", 0.6)))
    timing_effort = os.environ.get(
        "GRAPHICS_PPA_TIMING_EFFORT", cfg.get("timing_effort", "closure_no_cts"))
    io_delay_percent = float(os.environ.get(
        "GRAPHICS_PPA_IO_DELAY_PERCENT", cfg.get("io_delay_percent", 0.2)))
    env_false_paths = os.environ.get("GRAPHICS_PPA_IO_FALSE_PATH_PORTS", "")
    if env_false_paths:
        io_false_path_ports = [p for p in env_false_paths.split(",") if p.strip()] or None
    else:
        io_false_path_ports = cfg.get("io_false_path_ports")
    inputs = {
        "reg_code": "",
        "rtl_files": [str(p) for p in rtl_files],
        "module_name": module,
        "clock_port": "clock",
        "clock_period": 1000.0,
        "core_utilization": core_utilization,
        "place_density": place_density,
        "corner": "TC",
        "cell_vt": cfg.get("cell_vt", "SLVT"),
        "output_dir": "",
        "timeout": cfg.get("timeout", 28800),
        "cache": False,
        "clean": True,
        "generate_gds": False,
        "timing_effort": timing_effort,
        "synthesis_engine": "yosys",
        "sv_frontend": "native",
        "enable_retiming": False,
        "abc_clock_period_ps": 500.0,
        "io_delay_percent": io_delay_percent,
        "io_false_path_ports": io_false_path_ports,
        "synthesis_only": synthesis_only,
    }
    if cfg.get("setup_slack_margin") is not None:
        inputs["setup_slack_margin"] = cfg["setup_slack_margin"]
    macros = cfg.get("macros")
    if macros:
        inputs["macro_lefs"] = [str(sram_lef(m)) for m in macros]
        inputs["macro_libs"] = [str(sram_lib(m)) for m in macros]
    if cfg.get("macro_placement_tcl"):
        inputs["macro_placement_tcl"] = str(REPO / cfg["macro_placement_tcl"])
    return inputs, core_utilization, place_density, timing_effort


def out_dir_for(rtl_dir, cfg, core_utilization, place_density, timing_effort):
    effort_tag = "closure" if timing_effort.startswith("closure") else timing_effort
    density_tag = f"d{int(place_density * 100):02d}"
    vt_tag = cfg.get("cell_vt", "SLVT").lower()
    run_tag = os.environ.get("GRAPHICS_PPA_RUN_TAG", "") or cfg.get("run_tag", "")
    return REPO / "generated/ppa_runs" / (
        f"head_{Path(rtl_dir).name}_tc_{vt_tag}_1ghz_{effort_tag}"
        f"_u{core_utilization}_{density_tag}{run_tag}")


def main():
    preset, rest, synthesis_only, list_only = parse_args(sys.argv[1:])
    if list_only:
        for name, cfg in PRESETS.items():
            print(f"{name:16} {cfg['rtl_dir']}  {cfg['module']}")
        return
    if preset:
        if preset not in PRESETS:
            raise SystemExit(f"unknown preset: {preset} (see --list-presets)")
        cfg = dict(PRESETS[preset])
        if rest:
            cfg["rtl_dir"] = rest[0]
        if len(rest) > 1:
            cfg["module"] = rest[1]
    else:
        if len(rest) != 2:
            raise SystemExit(
                "usage: run_graphics_ppa.py <block-dir> <top-module> [synthesis-only]"
                " | --preset <name> [synthesis-only] | --list-presets")
        cfg = {"rtl_dir": rest[0], "module": rest[1]}

    rtl_dir = (REPO / cfg["rtl_dir"]).resolve()
    rtl_files = resolve_rtl(rtl_dir)
    inputs, core_utilization, place_density, timing_effort = build_inputs(
        rtl_files, cfg["module"], cfg, synthesis_only)
    out_dir = out_dir_for(rtl_dir, cfg, core_utilization, place_density, timing_effort)
    inputs["output_dir"] = str(out_dir)
    run_flow(inputs, out_dir, cfg["module"], cfg.get("description", ""))


if __name__ == "__main__":
    main()
