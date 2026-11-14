#!/usr/bin/env python3
"""CPU transport checks only; no compute instruction executes."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import sys
import unittest
from compute_owner_protocol import profile_bytes, strict_json

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
                     '-include', str(header), '-DOPENGPU_FLASHSIM_NATIVE']
            env = dict(os.environ, TMPDIR=temporary)
            for driver in ('compute_owner_driver.cpp', 'compute_stage_owner_driver.cpp',
                           'geometry_compute_owner_driver.cpp', 'geometry_compute_sample_owner_driver.cpp'):
                for count, expected, message in ((0, 0, 'assertion-free'),
                                                  (1, 2, 'wrong owner replay assertion model'),
                                                  (1, 1, None)):
                    result = subprocess.run([*flags, f'-DCOMPUTE_TEST_ASSERTIONS={count}',
                        f'-DOPENGPU_EXPECTED_ASSERTIONS={expected}', str(ROOT / 'scripts' / driver)],
                        env=env, capture_output=True, text=True)
                    if message:
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn(message, result.stderr)
                    else:
                        self.assertEqual(result.returncode, 0, result.stderr)
            for driver, selection in (
                    ('geometry_compute_owner_driver.cpp', '-DOPENGPU_OWNER_COMPUTE_STAGE=1'),
                    ('geometry_compute_sample_owner_driver.cpp', '-DOPENGPU_OWNER_GEOMETRY_COMPUTE=1')):
                result = subprocess.run([*flags, '-DCOMPUTE_TEST_ASSERTIONS=1',
                    '-DOPENGPU_EXPECTED_ASSERTIONS=1', selection, str(ROOT / 'scripts' / driver)],
                    env=env, capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('mutually exclusive', result.stderr)

    def test_compute_capture_and_actual_bus_observation_guards(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-owner-', dir=parent) as temporary:
            flags = ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            for name, selection, magic in (
                    ('v1', [], b'OGCP0001'),
                    ('stage', ['-DOPENGPU_OWNER_COMPUTE_STAGE=1'], b'OGCP0002'),
                    ('geometry', ['-DOPENGPU_OWNER_GEOMETRY_COMPUTE=1'], b'OGCP0003'),
                    ('sample', ['-DOPENGPU_OWNER_GEOMETRY_COMPUTE_SAMPLE=1'], b'OGCP0003')):
                directory = Path(temporary) / name
                directory.mkdir()
                binary = directory / 'test'
                env = dict(os.environ, TMPDIR=str(directory))
                subprocess.run([*shlex.split(os.environ.get('CXX', 'c++')), *flags, *selection,
                            '-I', str(ROOT / 'scripts/test_stubs/native_host'), '-I', str(ROOT / 'scripts'),
                            str(ROOT / 'scripts/test_compute_owner.cpp'), '-o', str(binary)], env=env, check=True)
                result = subprocess.run([str(binary)], env=env, check=True, capture_output=True, text=True)
                self.assertEqual(result.stdout, 'compute owner CPU protocol/guard checks: PASS\n')
                wire = profile_bytes(strict_json(directory / 'profile.json'))
                self.assertEqual(wire[:8], magic)

    def test_full_factory_linux_capture_reaches_its_fixed_cpp_parser(self):
        for option in ('--geometry-compute', '--sample-stage'):
            subprocess.run([sys.executable, str(ROOT / 'scripts/test_compute_host.py'),
                '--canonical-root', str(ROOT), option], check=True,
                env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))


if __name__ == '__main__':
    unittest.main()
