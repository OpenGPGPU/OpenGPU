#!/usr/bin/env python3
"""Exercise context fence ownership with the driver's actual wait helper."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "driver/opengpu_compute.c").read_text()
start = source.index("static int opengpu_wait_fence(struct opengpu_device *gpu,\n", source.index("static int opengpu_wait_fence(struct opengpu_device *gpu,\n") + 1)
end = source.index("\n}\n", start) + 2
with tempfile.TemporaryDirectory(prefix="opengpu-context-fence-") as tmp:
    path = Path(tmp)
    (path / "context_fence_wait_under_test.inc").write_text(source[start:end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", tmp, str(ROOT / "driver/tests/opengpu_context_fence_wait_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
