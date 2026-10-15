"""Shared helpers for the ASAP7 physical-flow runners.

Resolves the ChipAgent checkout without machine-specific paths, loads the
ASAP7 tool, expands an emitted `filelist.f`, and runs a flow while recording
`flow_result.json`. Keep block-specific configuration in the caller.
"""
import json
import os
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRAM = REPO / "depends/asap7_sram_0p0/generated"


def chipagent_dir():
    """ChipAgent checkout: CHIPAGENT_DIR override, else a sibling checkout."""
    return Path(os.environ.get("CHIPAGENT_DIR", REPO.parent / "chipagent")).resolve()


def load_tool():
    """Import and return the ASAP7 flow tool and its task/context types."""
    sys.path.insert(0, str(chipagent_dir()))
    from chipagent.models import TaskObject
    from chipagent.tools.base import ToolContext
    from chipagent.tools.phys_flow_asap7 import ASAP7PhysicalFlowTool
    return TaskObject, ToolContext, ASAP7PhysicalFlowTool


def sram_lef(name):
    return SRAM / "LEF" / f"{name}.lef"


def sram_lib(name):
    return SRAM / "LIB" / f"{name}.lib"


def resolve_rtl(rtl_dir):
    """Read `<rtl_dir>/filelist.f`, drop verification entries, verify files."""
    rtl_dir = Path(rtl_dir)
    filelist = rtl_dir / "filelist.f"
    rtl_files = [
        (rtl_dir / line.strip()).resolve()
        for line in filelist.read_text().splitlines()
        if line.strip() and not line.strip().startswith("verification/")
    ]
    for path in rtl_files:
        if not path.is_file():
            raise FileNotFoundError(path)
    return rtl_files


def run_flow(inputs, out_dir, module, description=""):
    """Run the ASAP7 flow, write flow_result.json, print the summary."""
    TaskObject, ToolContext, ASAP7PhysicalFlowTool = load_tool()
    res = ASAP7PhysicalFlowTool().run(ToolContext(
        task=TaskObject(task_type="physical_flow_asap7", module_name=module,
                        description=description),
        inputs=inputs,
    ))
    result = res.result
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "flow_result.json").write_text(json.dumps(result, indent=2, default=str))
    print(json.dumps(
        {k: result.get(k) for k in
         ("status", "stage", "output_dir", "qor", "overview", "issues")},
        ensure_ascii=False, indent=2))
    if result.get("report_tail"):
        print("--- report tail ---")
        print(result["report_tail"])
    return result
