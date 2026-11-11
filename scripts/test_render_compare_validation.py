#!/usr/bin/env python3
"""Exercise real fixed/vertex record validation with modeled resources."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_compute.c').read_text()
start = source.index('static int opengpu_validate_commands(')
end = source.index('static int opengpu_prepare_submit_objects(', start)
with tempfile.TemporaryDirectory(prefix='opengpu-render-compare-') as tmp:
    out = Path(tmp)
    (out / 'render_compare_under_test.inc').write_text(source[start:end])
    compiler = shlex.split(os.environ.get('CC', 'cc'))
    subprocess.run(compiler + ['-std=c11', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
        '-I', str(root / 'driver/tests/compute_submit_stubs'), '-I', str(root / 'driver'),
        '-I', tmp, str(root / 'driver/tests/opengpu_render_compare_fixture.c'),
        '-o', str(out / 'test')], check=True)
    subprocess.run([str(out / 'test')], check=True)
