#!/usr/bin/env python3
"""Check generated record rules and exercise canonical native snapshots."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
subprocess.run(['python3', str(root / 'scripts/generate_tessellation_canonical.py'), '--check'], check=True)
with tempfile.TemporaryDirectory(prefix='opengpu-tess-canonical-') as directory:
    executable = Path(directory) / 'test'
    compiler = shlex.split(os.environ.get('CC', 'cc'))
    subprocess.run(compiler + ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        str(root / 'driver/tests/opengpu_tessellation_canonical_test.c'), '-o', str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
