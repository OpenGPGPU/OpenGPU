#!/usr/bin/env python3
"""Actual native Linux submit/scheduler/owner/MMU with modeled OS primitives."""
from pathlib import Path
import os,shlex,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]

def write_bodies(out):
    hw=(ROOT/'driver/opengpu_hw.c').read_text()
    for name,start in [('snapshot_submission','int opengpu_hw_snapshot_submission_lock('),
                       ('native_enqueue','int opengpu_hw_native_binned_async(')]:
        first=hw.index(start);last=hw.index('\n}\n',first)+2
        (out/(name+'_under_test.inc')).write_text(hw[first:last])
    sched=(ROOT/'driver/opengpu_scheduler.c').read_text()
    first=sched.index('static void opengpu_sched_free_job(')
    last=sched.index('static const struct drm_sched_backend_ops',first)
    (out/'native_scheduler_under_test.inc').write_text(sched[first:last])
    admission=(ROOT/'driver/opengpu_admission.c').read_text()
    (out/'native_admission_under_test.inc').write_text('\n'.join(l for l in admission.splitlines() if not l.startswith('#include ')))
    submit=(ROOT/'driver/opengpu_native_submit.c').read_text()
    (out/'native_submit_under_test.inc').write_text('\n'.join(l for l in submit.splitlines() if not l.startswith('#include ')))

if __name__=='__main__':
    parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='native-submit-',dir=parent) as directory:
        out=Path(directory);write_bodies(out)
        flags=['-std=c11','-O1','-Wall','-Wextra','-Werror']
        if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
        includes=[out,ROOT/'driver',ROOT/'driver/tests',ROOT/'driver/tests/private_snapshot_stubs',
                  ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs']
        env=dict(os.environ,TMPDIR=directory)
        subprocess.run(shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(p) for p in includes]+[
            str(ROOT/'driver/tests/opengpu_native_submit_fixture.c'),str(ROOT/'driver/opengpu_native_frame.c'),'-o',str(out/'test')],env=env,check=True)
        subprocess.run([str(out/'test')],env=env,check=True)
