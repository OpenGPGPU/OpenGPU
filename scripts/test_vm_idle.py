#!/usr/bin/env python3
"""Exercise the actual VM idle wait with bounded hardware-status mocks."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "driver/opengpu_hw.c").read_text()
start = source.index("int opengpu_hw_wait_idle_locked(")
end = source.index("\n}\n", start) + 2
with tempfile.TemporaryDirectory(prefix="opengpu-vm-idle-") as tmp:
    path = Path(tmp)
    (path / "vm_idle_under_test.inc").write_text(source[start:end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", tmp, str(ROOT / "driver/tests/opengpu_vm_idle_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
