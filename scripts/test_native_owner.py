#!/usr/bin/env python3
"""Capture parsing/AXI stimulus tests only; no GPU model executes."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class NativeOwnerTests(unittest.TestCase):
    def test_capture_protocol_mmio_polling_and_physical_guards(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='owner-cpu-', dir=parent) as temporary:
            flags = ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            binary = Path(temporary) / 'test'
            env = dict(os.environ, TMPDIR=temporary)
            subprocess.run([*shlex.split(os.environ.get('CXX', 'c++')), *flags,
                            '-I', str(ROOT / 'scripts/test_stubs/native_host'), '-I', str(ROOT / 'scripts'),
                            str(ROOT / 'scripts/test_native_owner.cpp'), '-o', str(binary)], env=env, check=True)
            result = subprocess.run([str(binary)], env=env, check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout, 'native owner capture CPU protocol/guard checks: PASS\n')


if __name__ == '__main__':
    unittest.main()
