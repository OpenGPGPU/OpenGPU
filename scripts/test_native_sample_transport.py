#!/usr/bin/env python3
"""CPU-only native sample discovery and shader layout contract checks."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SampleTransportTests(unittest.TestCase):
    def test_sample_and_frozen_geometry_discovery(self):
        temporary_root = ROOT / '.artifacts'
        temporary_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='sample-contract-', dir=temporary_root) as directory:
            environment = dict(os.environ, TMPDIR=directory)
            compiler = shlex.split(os.environ.get('CC', 'cc'))
            flags = ['-std=c11', '-Wall', '-Wextra', '-Werror']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            for name in ('native_sample_transport', 'native_geometry_transport_discovery'):
                binary = Path(directory) / name
                subprocess.run([*compiler, *flags, str(ROOT / 'driver/tests' / f'opengpu_{name}_test.c'),
                                '-o', str(binary)], check=True, env=environment)
                subprocess.run([str(binary)], check=True, env=environment)


if __name__ == '__main__':
    unittest.main()
