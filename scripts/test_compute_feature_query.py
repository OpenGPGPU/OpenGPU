#!/usr/bin/env python3
"""Test the real feature-query ioctl with small capability combinations."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_compute.c').read_text()
start = source.index('int opengpu_compute_get_param_ioctl(')
end = source.index('int opengpu_compute_get_fault_ioctl(', start)
with tempfile.TemporaryDirectory(prefix='opengpu-compute-features-') as tmp:
    path = Path(tmp)
    (path / 'feature_query_under_test.inc').write_text(source[start:end])
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + [
        '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
        '-I', str(root / 'driver/tests/compute_submit_stubs'), '-I', str(root / 'driver'), '-I', tmp,
        str(root / 'driver/tests/opengpu_compute_feature_query_fixture.c'), '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
