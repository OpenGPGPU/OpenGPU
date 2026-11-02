#!/usr/bin/env python3
"""Exercise the actual fence wait function with deterministic kernel mocks."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "driver/opengpu_hw.c").read_text()
start = source.index("int opengpu_hw_fence_wait(struct opengpu_device *gpu,")
end = source.index("\nstatic void opengpu_hw_complete(", start)
with tempfile.TemporaryDirectory(prefix="opengpu-fence-wait-") as tmp:
    path = Path(tmp)
    (path / "fence_wait_under_test.inc").write_text(source[start:end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", tmp, str(ROOT / "driver/tests/opengpu_fence_wait_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
