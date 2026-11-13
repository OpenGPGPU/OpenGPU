#!/usr/bin/env python3
"""CPU transport checks only; no compute instruction executes."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ComputeOwnerTests(unittest.TestCase):
    def test_compute_requires_the_complete_assertion_model(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-assertions-', dir=parent) as temporary:
            header = Path(temporary) / 'assertions.hpp'
            header.write_text('struct GpuHostSystemAxiDut { static constexpr unsigned '
                              'assertion_count=COMPUTE_TEST_ASSERTIONS; };\n')
            flags = [*shlex.split(os.environ.get('CXX', 'c++')), '-std=c++17', '-fsyntax-only',
                     '-I', str(ROOT / 'scripts/test_stubs/native_host'), '-I', str(ROOT / 'scripts'),
                     '-include', str(header), '-DOPENGPU_FLASHSIM_NATIVE',
                     str(ROOT / 'scripts/compute_owner_driver.cpp')]
            env = dict(os.environ, TMPDIR=temporary)
            for count, expected, message in ((0, 0, 'assertion-free'),
                                              (1, 2, 'wrong owner replay assertion model'),
                                              (1, 1, None)):
                result = subprocess.run([*flags, f'-DCOMPUTE_TEST_ASSERTIONS={count}',
                                         f'-DOPENGPU_EXPECTED_ASSERTIONS={expected}'],
                                        env=env, capture_output=True, text=True)
                if message:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(message, result.stderr)
                else:
                    self.assertEqual(result.returncode, 0, result.stderr)

    def test_compute_capture_and_actual_bus_observation_guards(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-owner-', dir=parent) as temporary:
            flags = ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            binary = Path(temporary) / 'test'
            env = dict(os.environ, TMPDIR=temporary)
            subprocess.run([*shlex.split(os.environ.get('CXX', 'c++')), *flags,
                            '-I', str(ROOT / 'scripts/test_stubs/native_host'), '-I', str(ROOT / 'scripts'),
                            str(ROOT / 'scripts/test_compute_owner.cpp'), '-o', str(binary)], env=env, check=True)
            result = subprocess.run([str(binary)], env=env, check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout, 'compute owner CPU protocol/guard checks: PASS\n')


if __name__ == '__main__':
    unittest.main()
