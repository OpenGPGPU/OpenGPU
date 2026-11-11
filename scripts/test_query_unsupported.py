#!/usr/bin/env python3
"""Check the actual Linux query ioctl rejects unsupported query readback."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_compute.c').read_text()
start = source.index('int opengpu_compute_query_ioctl(')
end = source.index('void opengpu_compute_drm_postclose(', start)
with tempfile.TemporaryDirectory(prefix='opengpu-query-') as tmp:
    path = Path(tmp)
    (path / 'query_under_test.inc').write_text(source[start:end])
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + [
        '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        '-I', str(root / 'driver/tests/compute_submit_stubs'), '-I', str(root / 'driver'), '-I', tmp,
        str(root / 'driver/tests/opengpu_query_unsupported_fixture.c'), '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
print('Unsupported query ioctl: PASS')
