#!/usr/bin/env python3
"""Exercise the driver's actual invalidate submit code with a tiny mock device.

The mock uses pthread synchronization; it is not a Linux IRQ or GPU model.
"""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'driver/opengpu_hw.c').read_text()
start = source.index('static int opengpu_hw_invalidate_validate(')
end = source.index('\nint opengpu_hw_resolve_async(', start)
with tempfile.TemporaryDirectory(prefix='opengpu-invalidate-race-') as tmp:
    path = Path(tmp)
    (path / 'invalidate_submit_under_test.inc').write_text(source[start:end])
    cc = shlex.split(os.environ.get('CC', 'cc'))
    subprocess.run(cc + ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        '-pthread', '-I', tmp,
        str(ROOT / 'driver/tests/opengpu_invalidate_submit_fixture.c'),
        '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
