#!/usr/bin/env python3
"""Exercise compute buffer publication through the actual driver entry point."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_hw.c').read_text()
start = source.index('int opengpu_hw_compute_async(')
end = source.index('\n/* Drop shared-L2 lines', start)
with tempfile.TemporaryDirectory(prefix='opengpu-compute-prepare-') as tmp:
    path = Path(tmp)
    (path / 'prepare_under_test.inc').write_text(source[start:end])
    cc = shlex.split(os.environ.get('CC', 'cc'))
    subprocess.run(cc + ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        '-I', str(root / 'driver'), '-I', tmp,
        str(root / 'driver/tests/opengpu_compute_texture_hw_fixture.c'),
        '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
