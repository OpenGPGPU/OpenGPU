#!/usr/bin/env python3
"""Private raw nativeFS -> protected Linux ioctl/RX CPU bridge; no GPU peer."""
import argparse
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest

from owner_resource_contract import FULL_WORDS, SAMPLE_WORDS, V1_WORDS
from test_native_submit import write_bodies

ROOT = Path(__file__).resolve().parents[1]


def build_bridge(out: Path, sanitize: bool = False) -> Path:
    write_bodies(out)
    # One source-fixed registry supplies every table word. This compilation
    # input is private qualification scaffolding, never observed DUT discovery.
    auxiliary = [(0, 0x47550002), (0x60, 'GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS'),
                 (0x68, 0x160), (0x320, 1048576), (0x158, 0x02020810)]
    tables = []
    for name, source in (('sample', SAMPLE_WORDS), ('layered', FULL_WORDS), ('legacy', V1_WORDS)):
        words = [(w['address'], w['value']) for w in source] + auxiliary
        body = ',\n'.join('{%#xu,%s}' % (a, v if isinstance(v, str) else '%#xu' % v) for a, v in words)
        tables.append('static const struct native_kernel_profile_word %s_expected_profile[%d]={\n%s\n};\n'
                      % (name, len(words), body))
    (out / 'native_sample_expected_profile.inc').write_text(''.join(tables))
    flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
    if sanitize:
        flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
    includes = [out, ROOT/'driver', ROOT/'driver/tests', ROOT/'driver/tests/private_snapshot_stubs',
                ROOT/'driver/tests/mmu_stubs', ROOT/'driver/tests/compute_submit_stubs']
    binary = out / 'native-sample-submit-producer'
    subprocess.run([*shlex.split(os.environ.get('CC', 'cc')), *flags,
                    *['-I'+str(p) for p in includes], str(ROOT/'scripts/native_sample_submit_producer.c'),
                    str(ROOT/'driver/opengpu_native_frame.c'), '-o', str(binary)], check=True)
    return binary


def run_bridge(binary: Path, program: Path, samples=1, profile=112):
    return subprocess.run([str(binary), str(program), str(samples), str(profile)],
                          text=True, capture_output=True)


class SampleSubmitBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.tmp = tempfile.TemporaryDirectory(prefix='sample-submit-', dir=ROOT/'.artifacts')
        cls.out = Path(cls.tmp.name)
        cls.binary = build_bridge(cls.out, os.environ.get('OPENGPU_TEST_SANITIZERS') == '1')

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def fixture(self, **changes):
        # Parser/ownership unit input ONLY; CEASE is not an ESSL compilation or
        # rendered result. The separate --compiled-directory path consumes the
        # actual frontend artifacts without substituting this synthetic code.
        meta = [1, 6, 75, 512, 93*512, 0, 15, 0, 0, 1, 0, 128, 16, 4, 1]
        for key, value in changes.items():
            meta[int(key)] = value
        path = self.out / 'unit.ognsf'
        path.write_bytes(b'OGNSF001' + struct.pack('<15I', *meta) + struct.pack('<I', 0x30500073))
        return path

    def test_ordinary_full_capacity_ownership(self):
        for samples in (1, 2, 4):
            result = run_bridge(self.binary, self.fixture(), samples)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('CPU modeled final kick', result.stdout)
            self.assertIn('no GPU execution', result.stdout)

    def test_profile_and_layout_gates(self):
        for profile in (96, 104):
            result = run_bridge(self.binary, self.fixture(), profile=profile)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('profile gate rejected', result.stderr)
        for index, value in ((0, 2), (1, 1), (1, 7), (2, 77), (3, 256), (4, 95*512),
                             (6, 16), (7, 2), (8, 2), (9, 0), (10, 1), (11, 64),
                             (12, 15), (13, 3)):
            result = run_bridge(self.binary, self.fixture(**{str(index): value}))
            self.assertNotEqual(result.returncode, 0, (index, value))
            self.assertIn('invalid ordinary ABI6', result.stderr)

    def test_strict_container_and_actual_validator(self):
        source = self.fixture().read_bytes()
        for data in (source[:-1], source+b'\0', b'X'+source[1:], source[:64]+struct.pack('<I', 513)+source[68:]):
            path = self.out/'bad.ognsf';path.write_bytes(data)
            self.assertNotEqual(run_bridge(self.binary, path).returncode, 0)
        path = self.fixture()
        data = bytearray(path.read_bytes());struct.pack_into('<I', data, 68, 0xffffffff);path.write_bytes(data)
        result = run_bridge(self.binary, path)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('protected ioctl program rejected', result.stderr)


def compiled_directory(directory: Path):
    files = sorted(directory.glob('*.ognsf'))
    if not files:
        raise SystemExit('no actual compiled .ognsf files')
    (ROOT/'.artifacts').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='sample-submit-compiled-', dir=ROOT/'.artifacts') as temp:
        binary = build_bridge(Path(temp), os.environ.get('OPENGPU_TEST_SANITIZERS') == '1')
        ordinary = 0
        for program in files:
            data = program.read_bytes()
            abi = struct.unpack_from('<I', data, 12)[0]
            for samples in (1, 2, 4):
                result = run_bridge(binary, program, samples)
                if abi == 7:
                    if result.returncode == 0:
                        raise SystemExit('ABI7 accepted without real GS owner')
                elif result.returncode:
                    raise SystemExit(result.stderr)
                else:
                    ordinary += 1
                    print(program.name, result.stdout.strip())
        if not ordinary:
            raise SystemExit('no actual ABI6 ESSL program traversed RX')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiled-directory', type=Path)
    args, rest = parser.parse_known_args()
    if args.compiled_directory:
        compiled_directory(args.compiled_directory)
    else:
        unittest.main(argv=[__file__, *rest])
