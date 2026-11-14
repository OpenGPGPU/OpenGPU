#!/usr/bin/env python3
"""CPU parser/MMIO/receipt tests; no shader or generated RTL executes."""
import copy
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest

import native_sample_owner_protocol as protocol
from owner_resource_contract import SAMPLE_PROFILE, SAMPLE_STAGE, SAMPLE_WORDS

ROOT = Path(__file__).resolve().parents[1]


def profile():
    """Explicit CPU fixture. Never passed off as a live DUT probe."""
    caps = sum(1 << bit for bit in (6, 22, 23, 25, 29)) | 128 << 8
    return dict(schema=protocol.SCHEMA, ownerProfile=SAMPLE_PROFILE,
        ownerResourceStage=SAMPLE_STAGE, ownerResourceVersion=2, fullResourceQualification=False,
        resetCount=1, sourceQualificationRequired=True, gpuExecution=False,
        clockQualified=False, fpsQualified=False, words=copy.deepcopy(SAMPLE_WORDS),
        registers=[dict(address=a, value=v) for a, v in
            zip(protocol.AUXILIARY, (0x47550002, caps, 0x160, 1048576, 0x02040810))])


class SampleProtocolTests(unittest.TestCase):
    def test_exact_profile_and_no_cross_factory_relabel(self):
        good = profile()
        wire = protocol.profile_bytes(good)
        self.assertEqual(wire[:12], b'OGNP0002' + struct.pack('<I', 112))
        self.assertEqual(len(wire), 952)
        for n in range(112):
            bad = copy.deepcopy(good); bad['words'][n]['value'] ^= 1
            with self.assertRaises(ValueError): protocol.profile_bytes(bad)
        for key, value in [('schema', 'opengpu-compute-owner-profile-v3'),
                ('ownerProfile', 'two-cu-vga60-owner-v2'), ('ownerResourceStage', 'geometry-compute'),
                ('fullResourceQualification', True), ('gpuExecution', True), ('resetCount', True)]:
            bad = copy.deepcopy(good); bad[key] = value
            with self.assertRaises(ValueError): protocol.profile_bytes(bad)
        for field in ('words', 'registers'):
            bad = copy.deepcopy(good); bad[field].reverse()
            with self.assertRaises(ValueError): protocol.profile_bytes(bad)
        for n in range(5):
            bad = copy.deepcopy(good); bad['registers'][n]['value'] = 0
            with self.assertRaises(ValueError): protocol.profile_bytes(bad)

    def test_cpp_actual_mmio_observation_and_wire_interoperation(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='sample-owner-cpu-', dir=parent) as temporary:
            compiler = shlex.split(os.environ.get('CXX', 'c++'))
            flags = ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pedantic']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            binary = Path(temporary) / 'peer'
            env = dict(os.environ, TMPDIR=temporary)
            subprocess.run([*compiler, *flags, '-I', str(ROOT/'scripts/test_stubs/native_host'),
                str(ROOT/'scripts/test_native_sample_owner.cpp'), '-o', str(binary)], env=env, check=True)
            output = subprocess.check_output([str(binary), '--json'], env=env, text=True, timeout=60)
            def unique(pairs):
                result = {}
                for key, value in pairs:
                    self.assertNotIn(key, result, 'duplicate C++ receipt key')
                    result[key] = value
                return result
            observed, held, retired = [json.loads(line, object_pairs_hook=unique) for line in output.splitlines()]
            self.assertEqual(protocol.profile_bytes(observed), protocol.profile_bytes(profile()))
            held_wire, retired_wire = protocol.held_bytes(held), protocol.retired_bytes(retired, held)
            self.assertEqual((len(held_wire), len(retired_wire)), (92, 108))
            self.assertEqual(held_wire[:8], b'OGNSH001')
            self.assertEqual(retired_wire[:8], b'OGNSR001')
            words = struct.unpack('<15IQ4I', held_wire[8:])
            self.assertEqual(words[5:7], (0x12345678, 0x89abcdef))
            self.assertEqual(words[15], 0x100000040)
            for n in range(12):
                bad = copy.deepcopy(retired); bad['gnx'][n] ^= 1
                with self.assertRaises(ValueError): protocol.retired_bytes(bad, held)
            for key, value in [('appendReadBytes', 1), ('heldStatus', 2|4), ('heldIrqAsserted', 1),
                    ('ownerProfile', 'two-cu-vga60-owner-v1'), ('clockQualified', True),
                    ('schema', 'opengpu-compute-owner-held-v1')]:
                bad = copy.deepcopy(held); bad[key] = value
                with self.assertRaises(ValueError): protocol.held_bytes(bad)
            self.assertNotEqual(protocol.cleanup_authorization()[:8], held_wire[:8])
            self.assertEqual(protocol.final_authorization(), b'OGNSOK01\x01\x00\x00\x00')
            subprocess.run([*compiler, '-std=c++17', '-Wall', '-Wextra', '-Werror', '-pedantic',
                '-fsyntax-only', '-I', str(ROOT/'scripts/test_stubs/native_host'),
                str(ROOT/'scripts/native_sample_owner_driver.cpp')], env=env, check=True)


if __name__ == '__main__':
    unittest.main()
