#!/usr/bin/env python3
"""CPU protocol guards only; this does not execute a GPU or native owner."""
import copy
import struct
import os
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

from native_graphics_owner_bridge import profile_bytes, response_bytes, strict_json, ready_files, held_bytes, cleanup_authorization, checked_cleanup

ROOT = Path(__file__).resolve().parents[1]
GPU = next(parent for parent in (ROOT, *ROOT.parents) if (parent / '.git').is_dir())


def profile():
    return dict(schema='opengpu-native-owner-profile-v1', resetCount=1,
                sourceQualificationRequired=True, gpuExecution=False, clockQualified=False,
                fpsQualified=False, words=[dict(address=n * 4, value=n) for n in range(96)],
                registers=[dict(address=address, value=0) for address in (0, 0x60, 0x68, 0x320, 0x158)])


def receipt(index):
    return dict(phase="retired", ownerReplay=True, record=index, commandId=12 + index, completion=0x103,
                gnx=[0x80000000 + n for n in range(12)], appendReadBytes=(1 << 32) + index,
                heldLegacyStatus=0x21, heldStatus=0x801, heldIrq=3, heldIrqAsserted=True, retiredStatus=0x800,
                retiredIrq=0, retiredIrqAsserted=False, externalDrained=True, resets=1,
                resetsBetweenRecords=False, heldCompletionDrained=True, readOnlyGuards=True,
                independentImagesRequired=True, sourceQualificationRequired=True,
                clockQualified=False, fpsQualified=False)


class Protocol(unittest.TestCase):
    def test_profile_raw_words_and_rejects(self):
        original = profile()
        binary = profile_bytes(original)
        self.assertEqual(binary[:12], b'OGPF0001' + struct.pack('<I', 96))
        self.assertEqual(len(binary), 12 + 96 * 8 + 4 + 5 * 8)
        self.assertEqual(struct.unpack_from('<II', binary, 12 + 95 * 8), (380, 95))
        broken = []
        for key in ('sourceQualificationRequired', 'gpuExecution', 'clockQualified', 'fpsQualified'):
            case = copy.deepcopy(original)
            case[key] = int(case[key])
            broken.append(case)
            case = copy.deepcopy(original)
            case[key] = not case[key]
            broken.append(case)
        for key, value in (('schema', 'unknown'), ('resetCount', True), ('resetCount', 2)):
            broken.append(dict(original, **{key: value}))
        broken.append(dict(original, words=original['words'][:-1]))
        broken.append(dict(original, registers=original['registers'][:-1]))
        case = copy.deepcopy(original)
        case['registers'][1]['address'] = 4
        broken.append(case)
        for key, value in (('address', 3), ('address', 1 << 32), ('address', True),
                           ('value', -1), ('value', 1 << 32), ('value', 1.0)):
            case = copy.deepcopy(original)
            case['words'][1][key] = value
            broken.append(case)
        case = copy.deepcopy(original)
        case['words'][1]['address'] = case['words'][0]['address']
        broken.append(case)
        for case in broken:
            with self.subTest(case=case):
                with self.assertRaises(ValueError):
                    profile_bytes(case)

    def test_receipt_raw_64bit_and_boolean_guards(self):
        original = [receipt(0), receipt(1)]
        binary = response_bytes(original)
        self.assertEqual(binary[:12], b'OGRSP001' + struct.pack('<I', 2))
        self.assertEqual(len(binary), 12 + 2 * 100)
        self.assertEqual(struct.unpack_from('<Q', binary, 12 + 60)[0], 1 << 32)
        self.assertEqual(struct.unpack_from('<12I', binary, 12 + 12), tuple(original[0]['gnx']))
        self.assertEqual(len(response_bytes(original[1:], 1)), 12 + 100)
        broken = [[], list(reversed(original))]
        for key, value in (('externalDrained', False), ('retiredIrqAsserted', True),
                           ('heldCompletionDrained', False), ('readOnlyGuards', 1),
                           ('resets', True), ('resetsBetweenRecords', True),
                           ('gnx', [0] * 11), ('appendReadBytes', 1 << 64),
                           ('completion', True), ('heldIrqAsserted', 1)):
            case = copy.deepcopy(original)
            case[0][key] = value
            broken.append(case)
        for case in broken:
            with self.subTest(case=case):
                with self.assertRaises(ValueError):
                    response_bytes(case)

    def test_held_has_no_retirement_authority(self):
        original = receipt(0)
        for key in ('retiredStatus', 'retiredIrq', 'retiredIrqAsserted'):
            del original[key]
        binary = held_bytes(original, 0)
        self.assertEqual(len(binary), 12 + 84)
        self.assertEqual(binary[:12], b'OGHLD001' + struct.pack('<I', 1))
        for case in (dict(original, retiredStatus=0), dict(original, heldCompletionDrained=False),
                     dict(original, heldIrqAsserted=1), dict(original, record=1)):
            with self.assertRaises(ValueError):
                held_bytes(case, 0)

    def test_cleanup_requires_separate_authority_and_actual_receipt(self):
        records = [receipt(0), receipt(1)]
        self.assertEqual(cleanup_authorization(records), b'OGCBA001' + struct.pack('<I', 2))
        for incomplete in ([], records[:1], list(reversed(records))):
            with self.assertRaises(ValueError):
                cleanup_authorization(incomplete)
        result = dict(phase='cleanup', ownerCleanupObserved=True, records=2,
                      batches=3, pteClears=9, shootdowns=2, resetCount=1, ramReplaced=False,
                      imagesPreserved=True, externalDrained=True, sourceQualificationRequired=True,
                      clockQualified=False, fpsQualified=False)
        checked_cleanup(result)
        # Missing fields, integer booleans, reset/RAM replacement and empty
        # cleanup telemetry cannot authorize the final response.
        for field in result:
            case = dict(result)
            del case[field]
            with self.subTest(missing=field), self.assertRaises(ValueError):
                checked_cleanup(case)
        for field, value in (('phase', 'retired'), ('ownerCleanupObserved', 1),
                             ('records', 1), ('records', True), ('resetCount', 2),
                             ('ramReplaced', True), ('imagesPreserved', False),
                             ('externalDrained', False), ('sourceQualificationRequired', False),
                             ('clockQualified', True), ('fpsQualified', True),
                             ('batches', 0), ('pteClears', -1), ('shootdowns', True),
                             ('pteClears', 1 << 64)):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                checked_cleanup(dict(result, **{field: value}))
        with self.assertRaises(ValueError):
            checked_cleanup(receipt(1))

    def test_actual_c_codec(self):
        with tempfile.TemporaryDirectory(prefix='g', dir=GPU / '.artifacts') as temporary:
            directory = Path(temporary)
            (directory / 'profile.bin').write_bytes(profile_bytes(profile()))
            (directory / 'bad.profile').write_bytes(profile_bytes(profile()) + b'forbidden tail')
            records = [receipt(0), receipt(1)]
            held = dict(records[0])
            for key in ('retiredStatus', 'retiredIrq', 'retiredIrqAsserted'):
                del held[key]
            (directory / 'record-0.held.bin').write_bytes(held_bytes(held, 0))
            invalid = bytearray(held_bytes(held, 0))
            struct.pack_into('<I', invalid, 12, 1)
            struct.pack_into('<I', invalid, 12 + 80, 2)
            (directory / 'record-1.held.bin').write_bytes(invalid)
            (directory / 'record-0.response.bin').write_bytes(response_bytes(records[:1]))
            (directory / 'response.bin').write_bytes(response_bytes(records))
            (directory / 'cleanup.authorize.bin').write_bytes(cleanup_authorization(records))
            for name, data in (('no-cleanup', None), ('bad-cleanup', b'OGCBA001' + struct.pack('<I', 1)),
                               ('wrong-cleanup', response_bytes(records)),
                               ('tail-cleanup', cleanup_authorization(records) + b'x')):
                child = directory / name
                child.mkdir()
                (child / 'response.bin').write_bytes(response_bytes(records))
                if data is not None:
                    (child / 'cleanup.authorize.bin').write_bytes(data)
            flags = ['-std=c11', '-Wall', '-Wextra', '-Werror', '-D_POSIX_C_SOURCE=200809L']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            env = dict(os.environ, TMPDIR=temporary)
            sources = [ROOT / 'userspace/tests' / name for name in
                       ('graphics_native_owner_ipc.c', 'graphics_native_owner_ipc_test.c')]
            subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags + list(map(str, sources)) +
                           ['-o', str(directory / 'codec')], env=env, check=True)
            subprocess.run([directory / 'codec', directory], env=env, check=True)

    def test_ready_files_and_duplicate_json(self):
        with tempfile.TemporaryDirectory(prefix='g', dir=GPU / '.artifacts') as temporary:
            directory = Path(temporary)
            duplicate = directory / 'duplicate.json'
            duplicate.write_text('{"gpuExecution":false,"gpuExecution":true}')
            with self.assertRaises(ValueError):
                strict_json(duplicate)
            names = ['union.memory', 'record-0.capture', 'record-1.capture', '0.source', '1.source']
            for name in names:
                (directory / name).write_bytes(b'active fixture')
            ready = directory / 'ready.json'
            import json
            value = dict(schema='opengpu-native-owner-ready-v1', memory=names[0],
                         captures=names[1:3], sources=names[3:])
            ready.write_text(json.dumps(value))
            self.assertEqual(ready_files(directory)[0], directory / names[0])
            for name in ('../escape', '/escape', names[1]):
                ready.write_text(json.dumps(dict(value, memory=name)))
                with self.assertRaises(ValueError):
                    ready_files(directory)
            ready.write_text(json.dumps(value))
            (directory / names[0]).unlink()
            (directory / names[0]).symlink_to(directory / names[1])
            with self.assertRaises(ValueError):
                ready_files(directory)


if __name__ == '__main__':
    unittest.main()
