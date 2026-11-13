#!/usr/bin/env python3
"""Exercise actual snapshot admission without holding a lock over retirement."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / "driver/opengpu_hw.c").read_text()
start = source.index("static int opengpu_hw_snapshot_submission_lock(")
end = source.index("\n}\n", start) + 2
with tempfile.TemporaryDirectory(prefix="opengpu-snapshot-submission-") as temporary:
    path = Path(temporary)
    (path / "snapshot_submission_under_test.inc").write_text(source[start:end])
    subprocess.run(shlex.split(os.environ.get("CC", "cc")) + [
        "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-I", temporary,
        str(root / "driver/tests/opengpu_snapshot_submission_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
