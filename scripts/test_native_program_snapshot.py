#!/usr/bin/env python3
"""Exercise actual native code admission/owner/MMU; optional real Mesa inputs."""
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
    parser.add_argument('--programs', type=Path,
                        help='active actual graphics fixture directory with four original code-GEM inputs')
    parser.add_argument('--sanitize', action='store_true')
    args = parser.parse_args()
    dependency = args.snapshot_root.resolve(strict=True)
    for name in ('driver/opengpu_private_snapshot.h', 'driver/tests/opengpu_private_snapshot_test.c'):
        if not (dependency / name).is_file():
            raise SystemExit('actual private owner prerequisite missing: ' + str(dependency / name))
    if args.programs:
        programs = args.programs.resolve(strict=True)
        for name in ('real-vs', 'real-fs', 'full-vs', 'full-fs'):
            if not (programs / ('native-' + name + '.words')).is_file():
                raise SystemExit('actual code-GEM input missing: native-' + name + '.words')
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='p', dir=temporary_root) as temporary:
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
                       [str(ROOT / 'driver/tests/opengpu_native_program_snapshot_test.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        subprocess.run([str(out / 'test')] + ([str(programs)] if args.programs else []),
                       check=True, env=environment)
    if args.programs:
        print('Actual Mesa original code-GEM inputs -> actual private RX owner/MMU: PASS (CPU only)')


if __name__ == '__main__':
    main()
