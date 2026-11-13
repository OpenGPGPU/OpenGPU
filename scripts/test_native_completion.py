#!/usr/bin/env python3
"""Kernel/user GNX1 decoding and held-window protocol tests; no GPU executes."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='native-completion-', dir=parent) as temporary:
        env = dict(os.environ, TMPDIR=temporary)
        compiler = shlex.split(os.environ.get('CC', 'cc'))
        for define in ([], ['-D__KERNEL__', '-nostdinc']):
            subprocess.run([*compiler, '-std=c11', '-Wall', '-Wextra', '-Werror',
                            *define, '-fsyntax-only',
                            str(ROOT / 'driver/tests/opengpu_native_binned_kernel_abi_test.c')],
                           env=env, check=True)
        # The userspace header needs stdint.h; test its ordinary system include
        # path separately instead of supplying a substitute type definition.
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        binary = Path(temporary) / 'test'
        subprocess.run([*compiler, *flags, str(ROOT / 'driver/tests/opengpu_native_completion_test.c'),
                        '-o', str(binary)], env=env, check=True)
        subprocess.run([str(binary)], env=env, check=True)
    print('native GNX1: held success/fault snapshots, identity, validity and dynamic telemetry PASS (CPU only)')


if __name__ == '__main__':
    main()
