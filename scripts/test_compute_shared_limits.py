#!/usr/bin/env python3
"""Check production shared-memory discovery queries with a mocked DRM fd."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'userspace/opengpu.c').read_text()
start = source.index('int opengpu_compute_local_limits(')
end = source.index('int opengpu_display_size(', start)
pipe = (root / 'userspace/pipe_opengpu.c').read_text()
pipe_start = pipe.index('int pipe_opengpu_screen_compute_limits(')
pipe_end = pipe.index('int pipe_opengpu_bind_cs(', pipe_start)
with tempfile.TemporaryDirectory(prefix='opengpu-shared-limits-') as tmp:
    path = Path(tmp)
    (path / 'shared_limits_under_test.inc').write_text(source[start:end])
    (path / 'pipe_shared_limits_under_test.inc').write_text(pipe[pipe_start:pipe_end])
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + [
        '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        '-I', str(root / 'driver/tests/compute_submit_stubs'),
        '-I', str(root / 'driver'), '-I', tmp,
        str(root / 'userspace/tests/compute_shared_limits.c'), '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
