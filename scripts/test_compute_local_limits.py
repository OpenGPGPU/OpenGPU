#!/usr/bin/env python3
"""Exercise the actual userspace limit query with a mocked DRM ioctl."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'userspace/opengpu.c').read_text()
start = source.index('int opengpu_compute_local_limits(')
end = source.index('int opengpu_display_size(', start)
with tempfile.TemporaryDirectory(prefix='opengpu-local-limits-') as tmp:
    path = Path(tmp)
    (path / 'local_limits_under_test.inc').write_text(source[start:end])
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + [
        '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        '-I', str(root / 'driver/tests/compute_submit_stubs'), '-I', str(root / 'driver'), '-I', tmp,
        str(root / 'userspace/tests/compute_local_limits.c'), '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
