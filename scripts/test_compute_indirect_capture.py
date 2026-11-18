#!/usr/bin/env python3
"""Actual Linux opcode14 capture and independent canonical parser; CPU only."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
from test_compute_host import write_bodies
from owner_resource_contract import IMAGE_FLOAT_PROFILE

ROOT = Path(__file__).resolve().parents[1]


def main():
    subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/test_compute_indirect_protocol.py')], check=True)
    parent = ROOT / '.artifacts'
    parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='indirect-capture-', dir=parent) as temporary:
        out = Path(temporary)
        write_bodies(out, full_profile=IMAGE_FLOAT_PROFILE)
        env = dict(os.environ, TMPDIR=temporary, OPENGPU_COMPUTE_HOST_TEST_INDIRECT='1',
                   ASAN_OPTIONS='detect_leaks=%u:halt_on_error=1' % (sys.platform == 'linux'),
                   UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1')
        san = ['-fsanitize=address,undefined', '-fno-omit-frame-pointer'] if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1' else []
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror', '-Wno-unused-function',
                 '-D_POSIX_C_SOURCE=200809L', '-DOPENGPU_COMPUTE_HOST_TEST_MAIN=1']
        include = [out, ROOT / 'driver', ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver/tests/compute_submit_stubs']
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags + san +
                       ['-I' + str(p) for p in include] +
                       [str(ROOT / 'driver/tests/opengpu_compute_host_fixture.c'), '-o', str(out / 'source')],
                       env=env, check=True)
        subprocess.run([str(out / 'source'), str(out)], env=env, check=True)
        # Compile the active C boundary without any CPU-peer test main.
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) +
                       [flag for flag in flags if flag != '-DOPENGPU_COMPUTE_HOST_TEST_MAIN=1'] + san +
                       ['-I' + str(p) for p in include] + ['-c', str(ROOT / 'driver/tests/opengpu_compute_host_fixture.c'),
                        '-o', str(out / 'boundary.o')], env=env, check=True)
        subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) +
                       ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-DOPENGPU_OWNER_IMAGE_FLOAT_STAGE=1'] + san +
                       ['-I' + str(ROOT / 'scripts/test_stubs/native_host'), '-I' + str(ROOT / 'scripts'),
                        str(ROOT / 'scripts/test_compute_indirect_capture.cpp'), '-o', str(out / 'canonical')],
                       env=env, check=True)
        subprocess.run([str(out / 'canonical'), str(out)], env=env, check=True)


if __name__ == '__main__':
    main()
