#!/usr/bin/env python3
"""Exercise the production unified submit function against mock MMIO and fences."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_hw.c').read_text()
start = source.index('static int opengpu_hw_unified_submit_locked(')
end = source.index('static int opengpu_hw_unified_dma_submit_locked(', start)
with tempfile.TemporaryDirectory(prefix='opengpu-texture-submit-') as tmp:
    path = Path(tmp)
    (path / 'submit_under_test.inc').write_text(source[start:end])
    cc = shlex.split(os.environ.get('CC', 'cc'))
    subprocess.run(cc + ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        '-I', str(root / 'driver'), '-I', tmp,
        str(root / 'driver/tests/opengpu_compute_texture_submit_fixture.c'),
        '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
