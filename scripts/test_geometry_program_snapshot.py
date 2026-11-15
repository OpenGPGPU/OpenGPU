#!/usr/bin/env python3
"""Exercise the opt-in scalar GS validator and private RX code with the real owner and MMU."""
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
    parser.add_argument('--program',type=Path,help='offline original LE GS words, 1..512 words; no ISA execution')
    parser.add_argument('--owned-uniforms',action='store_true',help='explicit synthetic CPU profile permission for this program')
    args = parser.parse_args()
    if args.owned_uniforms and not args.program:parser.error('--owned-uniforms requires --program')
    if args.program and (args.program.is_symlink() or not args.program.is_file()):parser.error('--program must be a regular nonsymlink file')
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
                       [str(ROOT / 'driver/tests/opengpu_geometry_program_snapshot_test.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        command=[str(out / 'test')]
        if args.program:command += [str(args.program.resolve()),'1' if args.owned_uniforms else '0']
        subprocess.run(command, check=True, env=environment)


if __name__ == '__main__':
    main()
