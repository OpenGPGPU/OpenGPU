#!/usr/bin/env python3
"""Test the production compute job path with mock mapping and submission."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_compute.c').read_text()
start = source.index('struct dma_fence *opengpu_job_run(struct opengpu_sched_job *job)')
end = source.index('    if (job->type == OPENGPU_SCHED_BLIT)', start)
with tempfile.TemporaryDirectory(prefix='opengpu-compute-job-') as tmp:
    path = Path(tmp)
    (path / 'job_under_test.inc').write_text(source[start:end] + '    return ERR_PTR(-EINVAL);\n}\n')
    cc = shlex.split(os.environ.get('CC', 'cc'))
    subprocess.run(cc + ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
        '-I', str(root / 'driver'), '-I', tmp,
        str(root / 'driver/tests/opengpu_compute_texture_job_fixture.c'),
        '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
