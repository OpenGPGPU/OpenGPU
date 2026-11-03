#!/usr/bin/env python3
"""Run the driver's context retirement code against bounded lifetime mocks."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "driver/opengpu_compute.c").read_text()
start = source.index("static void opengpu_context_release(")
end = source.index("\nstatic struct opengpu_render_context *\nopengpu_context_lookup", start)
with tempfile.TemporaryDirectory(prefix="opengpu-context-lifetime-") as tmp:
    path = Path(tmp)
    (path / "context_lifetime_under_test.inc").write_text(source[start:end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", tmp, str(ROOT / "driver/tests/opengpu_context_lifetime_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
