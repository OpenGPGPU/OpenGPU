#!/usr/bin/env python3
"""Synthetic CPU MMIO/wire guards; no Mesa program or GPU execution."""
import copy
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest

import native_sample_graphics_protocol as protocol
import native_sample_owner_protocol as single
from test_graphics_sample_owner_ipc import build_codec
from test_native_sample_owner import profile

ROOT = Path(__file__).resolve().parents[1]


class Ordinary112OwnerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        cls.directory = tempfile.TemporaryDirectory(prefix='ordinary112-cpu-', dir=parent)
        cls.addClassCleanup(cls.directory.cleanup)
        cls.path = Path(cls.directory.name)
        cls.env = dict(os.environ, TMPDIR=cls.directory.name, PYTHONDONTWRITEBYTECODE='1')
        compiler = shlex.split(os.environ.get('CXX', 'c++'))
        flags = ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pedantic']
        if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        binary = cls.path/'peer'
        subprocess.run([*compiler, *flags, '-I', str(ROOT/'scripts/test_stubs/native_host'),
            str(ROOT/'scripts/test_native_sample_graphics_owner.cpp'), '-o', str(binary)],
            env=cls.env, check=True)
        output = subprocess.check_output([str(binary), '--json'], env=cls.env, text=True, timeout=60)
        def unique(pairs):
            value = {}
            for key, item in pairs:
                if key in value: raise ValueError('duplicate C++ receipt field')
                value[key] = item
            return value
        values = [json.loads(line, object_pairs_hook=unique) for line in output.splitlines()]
        if len(values) != 5: raise ValueError('ordinary112 CPU peer did not execute two records')
        cls.observed = values[0]
        cls.held = values[1::2]
        cls.retired = values[2::2]

    def test_two_records_actual_mmio_fields_and_new_wires(self):
        self.assertEqual(protocol.profile_bytes(self.observed), single.profile_bytes(profile()))
        for record in range(2):
            h, r = self.held[record], self.retired[record]
            held = protocol.held_bytes(h, record)
            retired = protocol.retired_bytes(r, h, record)
            self.assertEqual(held[:12], b'OGNGH001'+struct.pack('<I', 1))
            self.assertEqual(retired[:12], b'OGNGR001'+struct.pack('<I', 1))
            self.assertEqual((len(held), len(retired)), (96, 112))
            raw = struct.unpack('<15IQ4I', held[12:])
            self.assertEqual(raw[:3], (record, 41+record, 0x8729+record))
            self.assertEqual(raw[5:7], (0x12345678, 0x89abcdef))
            self.assertEqual(raw[15], 0x100000040)
        self.assertLess(self.retired[0]['cumulativeServiceCycles'], self.retired[1]['cumulativeServiceCycles'])
        self.assertEqual(protocol.cleanup_authorization(self.retired, self.held), b'OGNGB001\x02\x00\x00\x00')
        final = protocol.final_authorization(self.retired, self.held)
        self.assertEqual(final[:12], b'OGNGF001\x02\x00\x00\x00')
        self.assertEqual(len(final), 212)

    def test_single_record_and_old96_receipts_cannot_be_relabeled(self):
        for record in range(2):
            for key, value in [('schema', 'opengpu-native-sample-owner-held-v1'),
                    ('schema', 'opengpu-native-owner-held-v1'), ('recordCount', 1),
                    ('recordCount', True), ('record', 1-record), ('record', bool(record)),
                    ('ownerProfile', 'two-cu-vga60-owner-v1'), ('resetCount', 2)]:
                bad = copy.deepcopy(self.held[record]); bad[key] = value
                with self.assertRaises(ValueError): protocol.held_bytes(bad, record)
            with self.assertRaises(ValueError): single.held_bytes(self.held[record])
        for value in (-1, 2, True, None):
            with self.assertRaises(ValueError): protocol.held_bytes(self.held[0], value)

    def test_all_raw_held_fields_must_survive_retirement(self):
        for record in range(2):
            h, r = self.held[record], self.retired[record]
            for key, value in [('appendReadBytes', 1), ('appendReadBytes', (1<<33)+64),
                    ('commandId', 0), ('commandId', 256), ('completion', 0x8629),
                    ('heldLegacyStatus', 1), ('heldStatus', 6), ('heldIrq', 1),
                    ('heldIrqAsserted', 1), ('heldCompletionDrained', False),
                    ('readOnlyGuards', False), ('clockQualified', True), ('fpsQualified', True),
                    ('retiredStatus', 1), ('externalDrained', True)]:
                bad = copy.deepcopy(h); bad[key] = value
                with self.assertRaises(ValueError): protocol.held_bytes(bad, record)
            for index in range(12):
                bad = copy.deepcopy(r); bad['gnx'][index] ^= 1
                with self.assertRaises(ValueError): protocol.retired_bytes(bad, h, record)
                bad = copy.deepcopy(h); bad['gnx'][index] = True
                with self.assertRaises(ValueError): protocol.held_bytes(bad, record)
            for key, value in [('appendReadBytes', 64), ('heldStatus', 0x23),
                    ('retiredStatus', 3), ('retiredIrq', 3), ('retiredIrqAsserted', True),
                    ('externalDrained', False), ('resetsBetweenRecords', True),
                    ('independentImagesRequired', False)]:
                bad = copy.deepcopy(r); bad[key] = value
                with self.assertRaises(ValueError): protocol.retired_bytes(bad, h, record)

    def test_complete_ordered_pair_required_for_cleanup_and_final(self):
        for function in (protocol.cleanup_authorization, protocol.final_authorization):
            for retired, held in [(self.retired[:1], self.held[:1]),
                    (self.retired[::-1], self.held[::-1]), (self.retired, self.held[:1]),
                    (tuple(self.retired), self.held)]:
                with self.assertRaises(ValueError): function(retired, held)
            held, retired = copy.deepcopy(self.held), copy.deepcopy(self.retired)
            for value in (held[1], retired[1]):
                value['commandId'] = held[0]['commandId']; value['completion'] = held[0]['completion']
            with self.assertRaises(ValueError): function(retired, held)

    def test_cpp_observations_python_encoding_and_independent_c_reader(self):
        binary = build_codec(self.path)
        names = dict(held='record-%u.held.bin', retired='record-%u.response.bin',
                     cleanup='cleanup.authorize.bin', final='response.bin', profile='profile.bin')
        def decode(kind, wire, record=0, valid=True):
            name = names[kind] % record if '%u' in names[kind] else names[kind]
            (self.path/name).write_bytes(wire)
            result = subprocess.run([binary, kind, self.path, str(record), 'ok' if valid else 'bad'],
                check=True, env=self.env, capture_output=True, text=True, timeout=10)
            return [json.loads(line) for line in result.stdout.splitlines()]
        self.assertEqual(decode('profile', protocol.profile_bytes(self.observed)),
                         [dict(profileWords=112, registers=5)])
        for record in range(2):
            h, r = self.held[record], self.retired[record]
            for kind, wire, original in [('held', protocol.held_bytes(h, record), h),
                    ('retired', protocol.retired_bytes(r, h, record), r)]:
                decoded = decode(kind, wire, record)[0]
                for key, value in decoded.items():
                    if kind == 'held' and key in ('retiredStatus', 'retiredIrq', 'retiredIrqAsserted', 'externalDrained'):
                        self.assertEqual(value, 0)
                    else: self.assertEqual(value, original[key])
                decode(kind, wire, 1-record, valid=False)
                decode(kind, wire+b'x', record, valid=False)
        decode('cleanup', protocol.cleanup_authorization(self.retired, self.held))
        final = protocol.final_authorization(self.retired, self.held)
        decoded = decode('final', final)
        for record in range(2):
            for key, value in decoded[record].items(): self.assertEqual(value, self.retired[record][key])
        decode('final', final[:12]+final[112:]+final[12:112], valid=False)
        bad = bytearray(final); struct.pack_into('<I', bad, 12+100+96, 0)
        decode('final', bad, valid=False)

    def test_cleanup_requires_both_actual_owners_without_ram_replacement_or_reset(self):
        value = dict(phase='cleanup', ownerCleanupObserved=True, contextCleanupObserved=True,
            records=2, resetCount=1, ramReplaced=False, imagesPreserved=True,
            externalDrained=True, sourceQualificationRequired=True, clockQualified=False,
            fpsQualified=False, batches=3, pteClears=100, shootdowns=3)
        protocol.checked_cleanup(value)
        for key, wrong in [('records',1),('records',True),('resetCount',2),
                ('ownerCleanupObserved',False),('contextCleanupObserved',False),
                ('ramReplaced',True),('imagesPreserved',False),('externalDrained',False),
                ('clockQualified',True),('fpsQualified',True),('batches',0),
                ('pteClears',0),('shootdowns',0),('batches',True),('pteClears',-1)]:
            bad=dict(value);bad[key]=wrong
            with self.assertRaises(ValueError):protocol.checked_cleanup(bad)

    def test_admission_binds_the_complete_second_capture_without_accepting_old_response_wires(self):
        capture=b'OGCAP002'+bytes(range(256))
        self.assertEqual(protocol.admission_bytes(capture),b'OGNGA001'+struct.pack('<II',1,len(capture))+capture)
        for record,data in [(0,capture),(2,capture),(True,capture),(1,b''),(1,bytearray(capture)),(1,bytes(2097153))]:
            with self.assertRaises(ValueError):protocol.admission_bytes(data,record)


if __name__ == '__main__':
    unittest.main()
