#!/usr/bin/env python3
"""Compose private snapshot production code with the actual MMU implementation."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
temporary_root = root / ".artifacts/tmp"
temporary_root.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="opengpu-private-snapshot-", dir=temporary_root) as temporary:
    binary = Path(temporary) / "test"
    source = (root / "driver/opengpu_hw.c").read_text()
    start = source.index("int opengpu_hw_snapshot_submission_lock(")
    end = source.index("\n}\n", start) + 2
    (Path(temporary) / "snapshot_submission_under_test.inc").write_text(source[start:end])
    environment = dict(os.environ, TMPDIR=temporary)
    subprocess.run(shlex.split(os.environ.get("CC", "cc")) + [
        "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-I" + temporary,
        "-I" + str(root / "driver/tests/private_snapshot_stubs"),
        "-I" + str(root / "driver/tests/mmu_stubs"),
        "-I" + str(root / "driver/tests/compute_submit_stubs"),
        str(root / "driver/tests/opengpu_private_snapshot_test.c"), "-o", str(binary)],
        check=True, env=environment)
    subprocess.run([str(binary)], check=True, env=environment)
