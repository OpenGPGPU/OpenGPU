#!/usr/bin/env python3
"""Exercise production tessellation submission through mock MMIO and fences."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / "driver/opengpu_hw.c").read_text()
submit_start = source.index("static int opengpu_hw_unified_submit_locked(")
submit_end = source.index("static int opengpu_hw_unified_dma_submit_locked(", submit_start)
tess_start = source.index("static int opengpu_hw_invalidate_snapshots_locked(")
tess_end = source.index("/* Publish one draw as a unified render command.", tess_start)
with tempfile.TemporaryDirectory(prefix="opengpu-tess-transport-") as tmp:
    path = Path(tmp)
    (path / "submit_under_test.inc").write_text(source[submit_start:submit_end])
    (path / "tessellation_under_test.inc").write_text(source[tess_start:tess_end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", str(root / "driver"), "-I", tmp,
        str(root / "driver/tests/opengpu_tessellation_transport_submit_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
