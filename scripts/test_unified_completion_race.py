#!/usr/bin/env python3
"""Run the actual completion drain with concurrent mock IRQ/poll callers.

The mock supplies pthread locking and a held MMIO snapshot, not a Linux IRQ or
an emulated GPU. Extracting the static function keeps this regression pointed at
the driver's actual critical section without adding a runtime injection hook.
"""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "driver/opengpu_hw.c").read_text()
complete_start = source.index("static void opengpu_hw_complete(struct opengpu_device *gpu, int error)\n{")
complete_end = source.index("static u32 opengpu_next_job_id(", complete_start)
abort_start = source.index("void opengpu_hw_abort(struct opengpu_device *gpu, int error)\n{")
abort_end = source.index("static void opengpu_timeout_work(", abort_start)
start = source.index("static bool opengpu_hw_unified_drain(struct opengpu_device *gpu)\n{")
kick = source.index("static void opengpu_hw_unified_kick(", start)
end = source.index("static int opengpu_hw_unified_submit_locked(", kick)
temporary_root = ROOT / ".artifacts"
temporary_root.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="completion-race-", dir=temporary_root) as tmp:
    path = Path(tmp)
    (path / "drain_under_test.inc").write_text(source[complete_start:complete_end] +
        source[abort_start:abort_end] + source[start:end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    flags = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread"]
    if os.environ.get("OPENGPU_TEST_SANITIZERS") == "1":
        flags += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
    environment = dict(os.environ, TMPDIR=tmp)
    subprocess.run(cc + flags + [
        "-I", str(ROOT / "driver"), "-I", tmp,
        str(ROOT / "driver/tests/opengpu_completion_race_fixture.c"), "-o", str(path / "test")], check=True, env=environment)
    subprocess.run([str(path / "test")], check=True, env=environment)
