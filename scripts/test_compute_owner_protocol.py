#!/usr/bin/env python3
"""CPU wire/schema rejection checks; no modeled completion is GPU evidence."""
from copy import deepcopy
import re
from pathlib import Path
import struct
import unittest
from compute_owner_protocol import (AUXILIARY, MAGICS, SCHEMA, profile_bytes,
    held_bytes, retired_bytes, cleanup_authorization, checked_cleanup)
from prepare_native_owner_flashsim import EXPECTED_WORDS
from owner_resource_contract import COMPUTE_SCHEMA, COMPUTE_STAGE, COMPUTE_WORDS


def profile():
    abi = (Path(__file__).resolve().parents[1] / 'driver/gpu_abi.h').read_text()
    caps = 0
    for name in ('UNIFIED_COMMANDS', 'PROTECTED_VM_STATE', 'COMPUTE_SCALAR_FPU', 'SHADER_CONFIG_INFO', 'NATIVE_PIXEL_FORMATS'):
        caps |= 1 << int(re.search(r'^#define GPU_CAP_' + name + r'\s+\(1u << (\d+)\)', abi, re.M).group(1))
    batch_shift = int(re.search(r'^#define GPU_CAP_FRAGMENT_BATCH_SHIFT\s+(\d+)u', abi, re.M).group(1))
    caps |= 128 << batch_shift
    values = (0x47550002, caps, 0x160, 1048576, 0x02020810) + MAGICS
    return dict(schema=SCHEMA, words=deepcopy(EXPECTED_WORDS),
                registers=[dict(address=a, value=v) for a, v in zip(AUXILIARY, values)],
                resetCount=1, sourceQualificationRequired=True, gpuExecution=False,
                clockQualified=False, fpsQualified=False)


def held():
    return dict(schema='opengpu-compute-owner-held-v1', phase='held', record=0, commandId=11,
        completion=0x800b, processedBytes=0, heldLegacyStatus=0, heldStatus=34, heldIrq=3,
        heldIrqAsserted=True, sourceQualificationRequired=True, heldCompletionDrained=True, readOnlyGuards=True, independentBytesRequired=True,
        resetCount=1, clockQualified=False, fpsQualified=False)


class Protocol(unittest.TestCase):
    def test_shared_enabled_stage_has_a_distinct_exact_profile_and_wire(self):
        value = profile()
        value.update(schema=COMPUTE_SCHEMA, ownerProfile=COMPUTE_STAGE,
            ownerResourceStage='compute', ownerResourceVersion=2,
            fullResourceQualification=False, words=deepcopy(COMPUTE_WORDS))
        wire = profile_bytes(value)
        self.assertEqual(wire[:12], b'OGCP0002' + struct.pack('<I', len(COMPUTE_WORDS)))
        self.assertEqual([w for w in COMPUTE_WORDS if w['address'] != 0x368],
                         [w for w in EXPECTED_WORDS if w['address'] != 0x368])
        for key, bad in [('schema', SCHEMA), ('ownerProfile', 'two-cu-vga60-owner-v2'),
                         ('ownerResourceStage', 'final'), ('ownerResourceVersion', True),
                         ('fullResourceQualification', True), ('words', EXPECTED_WORDS)]:
            wrong = deepcopy(value); wrong[key] = bad
            with self.assertRaises(ValueError): profile_bytes(wrong)
        for index in range(len(COMPUTE_WORDS)):
            wrong = deepcopy(value); wrong['words'][index]['value'] ^= 1
            with self.assertRaises(ValueError): profile_bytes(wrong)

    def test_actual_profile_shape_and_each_raw_auxiliary(self):
        value = profile()
        data = profile_bytes(value)
        self.assertEqual(data[:12], b'OGCP0001' + struct.pack('<I', 96))
        self.assertEqual(len(data), 8+4+96*8+4+10*8)
        for index in range(10):
            wrong = deepcopy(value)
            wrong['registers'][index]['address'] ^= 4
            with self.assertRaises(ValueError): profile_bytes(wrong)
            for malformed in (-1, 1 << 32, True, 1.0):
                wrong = deepcopy(value); wrong['registers'][index]['value'] = malformed
                with self.assertRaises(ValueError): profile_bytes(wrong)
        for index in range(5, 10):
            wrong = deepcopy(value); wrong['registers'][index]['value'] ^= 1
            with self.assertRaises(ValueError): profile_bytes(wrong)
        for index in range(96):
            wrong = deepcopy(value); wrong['words'][index]['value'] ^= 1
            with self.assertRaises(ValueError): profile_bytes(wrong)

    def test_shader_resource_capabilities_are_consistent(self):
        value = profile()
        for caps in (value['registers'][1]['value'] & ~(255 << 8),
                     value['registers'][1]['value'] ^ (1 << 8),
                     value['registers'][1]['value'] & ~(1 << 25)):
            wrong = deepcopy(value); wrong['registers'][1]['value'] = caps
            with self.assertRaises(ValueError): profile_bytes(wrong)
        for banks in (0, 1, 3):
            wrong = deepcopy(value)
            wrong['registers'][4]['value'] = (wrong['registers'][4]['value'] & ~(255 << 16)) | (banks << 16)
            with self.assertRaises(ValueError): profile_bytes(wrong)
        valid = deepcopy(value)
        valid['registers'][4]['value'] = (valid['registers'][4]['value'] & ~(255 << 16)) | (4 << 16)
        profile_bytes(valid)

    def test_profile_cannot_relabel_native_or_execution(self):
        for key, bad in [('schema', 'opengpu-native-owner-profile-v1'), ('gpuExecution', True),
                         ('resetCount', True), ('sourceQualificationRequired', 1),
                         ('clockQualified', True), ('fpsQualified', True)]:
            wrong = profile(); wrong[key] = bad
            with self.assertRaises(ValueError): profile_bytes(wrong)
        wrong = profile(); wrong['registers'] = wrong['registers'][:5]
        with self.assertRaises(ValueError): profile_bytes(wrong)
        wrong = profile(); wrong['words'] = wrong['words'][:72]
        with self.assertRaises(ValueError): profile_bytes(wrong)

    def test_held_retired_are_compute_only_and_distinct(self):
        first = held(); self.assertEqual(len(held_bytes(first)), 40)
        retired = dict(first, schema='opengpu-compute-owner-retired-v1', phase='retired',
                       retiredStatus=1, retiredIrq=1, retiredIrqAsserted=False, externalDrained=True)
        self.assertEqual(len(retired_bytes(retired, first)), 56)
        for key, bad in [('completion', 0x870b), ('processedBytes', 1), ('heldIrqAsserted', 1),
                         ('gnx', [0]*12), ('retiredStatus', 1), ('commandId', True), ('heldLegacyStatus', 2),
                         ('clockQualified', True), ('fpsQualified', True), ('resetCount', 2), ('independentBytesRequired', False)]:
            wrong = dict(first, **{key: bad})
            with self.assertRaises(ValueError): held_bytes(wrong)
        for key, bad in [('completion', 0x800c), ('externalDrained', False), ('retiredIrqAsserted', 0),
                         ('retiredStatus', 3), ('retiredStatus', 9), ('retiredStatus', 33)]:
            wrong = dict(retired, **{key: bad})
            with self.assertRaises(ValueError): retired_bytes(wrong, first)
        self.assertEqual(cleanup_authorization(), b'OGCMBA01\1\0\0\0')
        self.assertNotEqual(cleanup_authorization()[:8], held_bytes(first)[:8])

    def test_cleanup_requires_real_context_shutdown_too(self):
        value = dict(phase='cleanup', ownerCleanupObserved=True, contextCleanupObserved=True,
            records=1, resetCount=1, ramReplaced=False, imagesPreserved=True, externalDrained=True,
            sourceQualificationRequired=True, clockQualified=False, fpsQualified=False,
            batches=9, pteClears=60, shootdowns=9)
        checked_cleanup(value)
        for key in value:
            wrong = dict(value); wrong.pop(key)
            with self.assertRaises(ValueError): checked_cleanup(wrong)
        for key in ('ownerCleanupObserved', 'contextCleanupObserved', 'externalDrained'):
            with self.assertRaises(ValueError): checked_cleanup(dict(value, **{key: False}))
        for key in ('batches', 'pteClears', 'shootdowns'):
            with self.assertRaises(ValueError): checked_cleanup(dict(value, **{key: 0}))


if __name__ == '__main__':
    unittest.main()
