#!/usr/bin/env python3
"""Validate the actual checked-storage blit program, without claiming RTL execution."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
(ROOT / ".artifacts").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="blit-", dir=ROOT / ".artifacts") as tmp:
    binary = Path(tmp) / "program"
    flags = ["-std=c11", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
             "-Wno-unused-function", "-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
    includes = [ROOT / "userspace", ROOT / "userspace/tests", ROOT / "driver"]
    subprocess.run(shlex.split(os.environ.get("CC", "cc")) + flags +
                   ["-I" + str(p) for p in includes] +
                   [str(ROOT / "userspace/tests/framebuffer_blit_program.c"), "-lm", "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
