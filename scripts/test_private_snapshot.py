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
    for name in ("opengpu_private_snapshot_test", "opengpu_native_frame_snapshot_test"):
        flags = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror"]
        if os.environ.get("OPENGPU_TEST_SANITIZERS") == "1":
            flags += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        sources = [str(root / "driver/tests" / (name + ".c"))]
        if name == "opengpu_native_frame_snapshot_test":
            sources += [str(root / "driver/opengpu_native_frame.c")]
        subprocess.run(shlex.split(os.environ.get("CC", "cc")) + flags + [
            "-I" + temporary,
            "-I" + str(root / "driver/tests/private_snapshot_stubs"),
            "-I" + str(root / "driver/tests/mmu_stubs"),
            "-I" + str(root / "driver/tests/compute_submit_stubs"),
            *sources, "-o", str(binary)], check=True, env=environment)
        subprocess.run([str(binary)], check=True, env=environment)
