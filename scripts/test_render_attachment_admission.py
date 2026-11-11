#!/usr/bin/env python3
"""Test real render admission and its GEM cleanup with mocked kernel objects."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_compute.c').read_text()
start = source.index('int opengpu_compute_drm_ioctl(')
end = source.index('    command_dma = to_drm_gem_dma_obj(command_object);', start)
cleanup = source.index('out_color_object4:\n', end)
finish = source.index('\nstatic int opengpu_command_events_init(', cleanup)
job_cleanup = source.index('out_job:\n    if (sched_job)', end)
job_cleanup_end = source.index('out_exec:', job_cleanup)
with tempfile.TemporaryDirectory(prefix='opengpu-render-admission-') as tmp:
    path = Path(tmp)
    # Continue to the real cleanup after the real admission and size checks.
    (path / 'render_admission_under_test.inc').write_text(
        source[start:end] + '    ret = 0;\n    goto out_color_object4;\n' + source[cleanup:finish])
    (path / 'render_failure_cleanup_under_test.inc').write_text(
        'static void test_failed_job_cleanup(struct opengpu_device *gpu, '
        'struct opengpu_sched_job *sched_job) { goto out_job;\n' +
        source[job_cleanup:job_cleanup_end] + '}\n')
    compiler = shlex.split(os.environ.get('CC', 'cc'))
    subprocess.run(compiler + ['-std=c11', '-O1', '-g', '-Wall', '-Wextra',
        '-Wno-unused-variable', '-Wno-parentheses', '-Werror',
        '-I', str(root / 'driver/tests/compute_submit_stubs'), '-I', str(root / 'driver'),
        '-I', tmp, str(root / 'driver/tests/opengpu_render_attachment_fixture.c'),
        '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
