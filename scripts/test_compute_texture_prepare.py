#!/usr/bin/env python3
"""Run the real compute texture view preparation with mock GEM bindings."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_compute.c').read_text()
start = source.index('struct opengpu_compute_texture_prepare {')
end = source.index('struct opengpu_compute_storage_prepare {', start)
with tempfile.TemporaryDirectory(prefix='opengpu-texture-prepare-') as tmp:
    path = Path(tmp)
    (path / 'prepare_under_test.inc').write_text(source[start:end])
    gem_source = (root / 'driver/opengpu_drm_device.c').read_text()
    gem_start = gem_source.index('int opengpu_gem_backings_proven_disjoint(')
    gem_end = gem_source.index('int opengpu_gem_backings_same(', gem_start)
    (path / 'gem_under_test.inc').write_text(gem_source[gem_start:gem_end])
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + [
        '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        '-I', str(root / 'driver/tests/compute_submit_stubs'), '-I', str(root / 'driver'),
        '-I', tmp, str(root / 'driver/tests/opengpu_compute_texture_prepare_fixture.c'),
        '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
