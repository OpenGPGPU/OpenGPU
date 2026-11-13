#!/usr/bin/env python3
"""Qualify actual typed input relocations with the actual private owner/MMU."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot-root', type=Path, default=ROOT,
                        help='source tree containing the actual private owner/MMU implementation')
    parser.add_argument('--sanitize', action='store_true')
    args = parser.parse_args()
    dependency = args.snapshot_root.resolve(strict=True)
    for name in ('driver/opengpu_private_snapshot.h', 'driver/opengpu_native_frame_snapshot.h',
                 'driver/tests/opengpu_private_snapshot_test.c'):
        if not (dependency / name).is_file():
            raise SystemExit('actual input-owner prerequisite missing: ' + str(dependency / name))
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='i', dir=temporary_root) as temporary:
        out = Path(temporary)
        source = (dependency / 'driver/opengpu_hw.c').read_text()
        first = source.index('int opengpu_hw_snapshot_submission_lock(')
        last = source.index('\n}\n', first) + 2
        (out / 'snapshot_submission_under_test.inc').write_text(source[first:last])
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if args.sanitize:
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, dependency / 'driver/tests', dependency / 'driver',
                    dependency / 'driver/tests/private_snapshot_stubs',
                    dependency / 'driver/tests/mmu_stubs', dependency / 'driver/tests/compute_submit_stubs']
        environment = dict(os.environ, TMPDIR=temporary)
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(path) for path in includes] +
                       [str(ROOT / 'driver/tests/opengpu_native_input_snapshot_test.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        subprocess.run([str(out / 'test')], check=True, env=environment)


if __name__ == '__main__':
    main()
