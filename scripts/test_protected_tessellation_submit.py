#!/usr/bin/env python3
"""Actual protected Linux ownership; test-only profile, unavailable hardware route."""
import argparse
import os
import re
import shlex
import subprocess
import tempfile
from pathlib import Path
from test_native_submit import write_bodies
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--negative-controls', action='store_true')
    args = parser.parse_args()
    parent = ROOT / '.artifacts'
    parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='protected-tess-submit-', dir=parent) as directory:
        out = Path(directory)
        write_bodies(out)
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT/'driver', ROOT/'driver/tests', ROOT/'driver/tests/private_snapshot_stubs',
                    ROOT/'driver/tests/mmu_stubs', ROOT/'driver/tests/compute_submit_stubs']
        env = dict(os.environ, TMPDIR=directory)
        env.setdefault('ASAN_OPTIONS', 'quarantine_size_mb=16')
        command = shlex.split(os.environ.get('CC', 'cc')) + flags + ['-I'+str(p) for p in includes] + [
            str(ROOT/'driver/tests/opengpu_protected_tessellation_submit_test.c'),
            str(ROOT/'driver/opengpu_native_frame.c'), '-o', str(out/'test')]
        subprocess.run(command, env=env, check=True)
        subprocess.run([str(out/'test')], env=env, check=True)
        if args.negative_controls:
            mutations = {
                'producer-wait': ('native_submit_under_test.inc',
                    'ret=opengpu_hw_fence_wait(gpu,producer,OPENGPU_DRAW_WAIT_MS+100,true);if(ret)goto out_exec;',
                    'ret=0;(void)producer;'),
                'legacy-job-kind': ('native_submit_under_test.inc',
                    'job->type=OPENGPU_SCHED_PROTECTED_TESSELLATION;', 'job->type=OPENGPU_SCHED_NATIVE_BINNED;'),
                'execution-needs-retirement': ('native_admission_under_test.inc',
                    'job->type==OPENGPU_SCHED_PROTECTED_TESSELLATION)?', 'false)?'),
                'ordinary-private-credit': ('native_admission_under_test.inc',
                    ' && job->type!=OPENGPU_SCHED_PROTECTED_TESSELLATION', ''),
                'early-quarantine-reference-release': ('native_scheduler_under_test.inc',
                    'WRITE_ONCE(job->gpu->hw.wedged, true);\n            drm_sched_job_cleanup(base);',
                    'WRITE_ONCE(job->gpu->hw.wedged, true);\n            for(u32 n=0;n<job->object_count;++n)drm_gem_object_put(job->objects[n]);\n            drm_sched_job_cleanup(base);'),
                'pre-enqueue-reference-release': ('native_submit_under_test.inc',
                    'job->native_quarantine_refs=sources.refs;job->native_quarantine_refcount=sources.count;',
                    'for(u32 n=0;n<sources.count;++n)drm_gem_object_put(sources.refs[n].object);\n            job->native_quarantine_refs=sources.refs;job->native_quarantine_refcount=sources.count;'),
                'frame-read-dependency': ('native_submit_under_test.inc',
                    'ret=native_retain(sources,r->gem_handle,!!(r->access&OPENGPU_NATIVE_FRAME_RESOURCE_WRITE));if(ret)return ret;',
                    'ret=native_retain(sources,r->gem_handle,false);if(ret)return ret;'),
            }
            for name, (filename, before, after) in mutations.items():
                write_bodies(out)
                path = out / filename
                source = path.read_text()
                assert source.count(before) == 1, name
                path.write_text(source.replace(before, after))
                subprocess.run(command, env=env, check=True)
                result = subprocess.run([str(out/'test')], env=env, capture_output=True, text=True)
                if result.returncode == 0 or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('semantic negative control not rejected: ' + name + '\n' + result.stderr)
                print('protected Linux semantic negative control rejected: ' + name, flush=True)


if __name__ == '__main__':
    main()
