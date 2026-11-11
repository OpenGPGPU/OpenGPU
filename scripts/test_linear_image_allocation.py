#!/usr/bin/env python3
"""Exercise the actual linear image allocator with a mocked allocation."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'userspace/pipe_opengpu.c').read_text()
start = source.index('struct pipe_opengpu_resource *pipe_opengpu_resource_create_linear(')
end = source.index('\nstruct pipe_opengpu_resource *pipe_opengpu_resource_from_handle(', start)
with tempfile.TemporaryDirectory(prefix='opengpu-linear-allocation-') as tmp:
    out = Path(tmp)
    (out / 'linear_allocation_under_test.inc').write_text(source[start:end])
    compiler = shlex.split(os.environ.get('CC', 'cc'))
    subprocess.run(compiler + ['-std=c11', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
        '-I', tmp, str(root / 'userspace/tests/pipe_linear_allocation_fixture.c'),
        '-o', str(out / 'test')], check=True)
    subprocess.run([str(out / 'test')], check=True)
