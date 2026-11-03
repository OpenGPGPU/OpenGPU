#!/usr/bin/env python3
"""Exercise the production temporary-mapping cleanup failure path."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "driver/opengpu_compute.c").read_text()
start = source.index("int opengpu_job_track_mapping(")
end = source.index("\n}\n", start) + 2
with tempfile.TemporaryDirectory(prefix="opengpu-job-mapping-") as tmp:
    path = Path(tmp)
    (path / "job_track_mapping_under_test.inc").write_text(source[start:end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", tmp, str(ROOT / "driver/tests/opengpu_job_track_mapping_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
