#!/usr/bin/env python3
"""Exercise the separate geometry uniform encoder with the real owner and MMU."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sanitize', action='store_true')
    args = parser.parse_args()
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='j', dir=temporary_root) as temporary:
        out = Path(temporary)
        source = (ROOT / 'driver/opengpu_hw.c').read_text()
        first = source.index('int opengpu_hw_snapshot_submission_lock(')
        last = source.index('\n}\n', first) + 2
        (out / 'snapshot_submission_under_test.inc').write_text(source[first:last])
        flags = ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror']
        if args.sanitize or os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT / 'driver/tests', ROOT / 'driver',
                    ROOT / 'driver/tests/private_snapshot_stubs',
                    ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver/tests/compute_submit_stubs']
        environment = dict(os.environ, TMPDIR=temporary)
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(path) for path in includes] +
                       [str(ROOT / 'driver/tests/opengpu_geometry_uniform_snapshot_test.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        subprocess.run([str(out / 'test')], check=True, env=environment)
        probe = out / 'abi.c'
        probe.write_text('#include "opengpu_geometry_uniform_abi.h"\n'
                         '_Static_assert(sizeof(struct opengpu_geometry_uniform_root)==64,"root");\n'
                         '_Static_assert(sizeof(struct opengpu_geometry_uniform_binding)==64,"binding");\n'
                         '_Static_assert(OPENGPU_GEOMETRY_UNIFORM_VERSION==2 && OPENGPU_GEOMETRY_UNIFORM_STAGE==3 && OPENGPU_GEOMETRY_UNIFORM_STAGE_MASK==8,"stage");\n'
                         '_Static_assert(OPENGPU_GEOMETRY_UNIFORM_ROOT_BINDINGSLIMIT_WORD==11 && OPENGPU_GEOMETRY_UNIFORM_BINDING_ALLOCATIONLIMIT_WORD==12,"limit33");\n')
        for kernel in (False, True):
            subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                           (['-D__KERNEL__', '-nostdinc', '-I' + str(ROOT / 'driver/tests/tessellation_record_kernel_stubs')] if kernel else []) +
                           ['-I' + str(ROOT / 'driver'), '-fsyntax-only', str(probe)],
                           check=True, env=environment)



if __name__ == '__main__':
    main()
