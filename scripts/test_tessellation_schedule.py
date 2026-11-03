#!/usr/bin/env python3
"""Exercise scheduler-owned native snapshots through the production MMU and transport."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
hw = (root / "driver/opengpu_hw.c").read_text()
start = hw.index("static int opengpu_hw_invalidate_snapshots_locked(")
end = hw.index("/* Publish one draw as a unified render command.", start)
compute = (root / "driver/opengpu_compute.c").read_text()
track = compute.index("int opengpu_job_track_mapping(")
track_end = compute.index("\n}\n", track) + 2
with tempfile.TemporaryDirectory(prefix="opengpu-tess-schedule-") as tmp:
    path = Path(tmp)
    (path / "tessellation_under_test.inc").write_text(hw[start:end])
    (path / "job_track_mapping_under_test.inc").write_text(compute[track:track_end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", str(root / "driver/tests/tessellation_sched_stubs"),
        "-I", str(root / "driver/tests/mmu_stubs"), "-I", str(root / "driver"),
        "-I", tmp, str(root / "driver/tests/opengpu_tessellation_schedule_test.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test"), *sys.argv[1:]], check=True)
