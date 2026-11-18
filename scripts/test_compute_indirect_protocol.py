#!/usr/bin/env python3
"""CPU-only opcode14 protocol rejection tests; no synthetic GPU results."""
import copy
import struct
import unittest
from compute_indirect_protocol import FLAGS, completion, held_bytes, retired_bytes
from compute_owner_protocol import held_bytes as direct_held_bytes, failed_held_bytes


def observation(status=0):
    return dict(FLAGS, schema='opengpu-compute-indirect-held-v1', phase='held',
                commandId=17, resultStatus=status, completion=completion(17, status),
                processedBytes=0, heldLegacyStatus=0, heldStatus=34, heldIrq=3, heldIrqAsserted=True)


class IndirectProtocolTests(unittest.TestCase):
    def test_extended_opcode_and_all_result_spaces(self):
        for status, expected in ((0, 0x18611), (1, 0x10e11), (12, 0x16611), (13, 0x16e11)):
            self.assertEqual(completion(17, status), expected)
            held = observation(status)
            wire = held_bytes(held)
            self.assertEqual(wire[:8], b'OGCIH001')
            self.assertEqual(struct.unpack('<IIIIQIIII', wire[8:]), (17, 14, status, expected, 0, 0, 34, 3, 1))
            retired = dict(held, schema='opengpu-compute-indirect-retired-v1', phase='retired',
                           retiredStatus=1, retiredIrq=1, retiredIrqAsserted=False, externalDrained=True)
            self.assertEqual(retired_bytes(retired, held), b'OGCIR001' + wire[8:] + struct.pack('<IIII', 1, 1, 0, 1))
            with self.assertRaises(ValueError): direct_held_bytes(held)
            with self.assertRaises(ValueError): failed_held_bytes(held)

    def test_wrong_or_mixed_held_receipts(self):
        for key, bad in (
                ('schema', 'opengpu-compute-owner-held-v1'), ('opcode', 0), ('opcode', True),
                ('commandId', 0), ('commandId', 256), ('commandId', True), ('resultStatus', 2),
                ('resultStatus', True), ('completion', 0x8611), ('completion', 0x8e11),
                ('completion', 0x10611), ('processedBytes', 1), ('heldLegacyStatus', 1),
                ('heldStatus', 1), ('heldStatus', 38), ('heldStatus', 42), ('heldStatus', 98),
                ('heldIrq', 1), ('heldIrqAsserted', 1), ('privateCountsRequired', False),
                ('resetCount', 2), ('readOnlyGuards', False), ('fpsQualified', True)):
            with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                held_bytes(dict(observation(), **{key: bad}))
        for extra in ({'gnx': {}}, {'retiredStatus': 1}, {'serviceCycles': True}):
            with self.assertRaises(ValueError): held_bytes(dict(observation(), **extra))
        for key in observation():
            value = observation(); del value[key]
            with self.subTest(missing=key), self.assertRaises(ValueError): held_bytes(value)

    def test_retirement_binds_status_despite_equal_linux_errno(self):
        held = observation(1)
        original = copy.deepcopy(held)
        retired = dict(held, schema='opengpu-compute-indirect-retired-v1', phase='retired',
                       retiredStatus=1, retiredIrq=1, retiredIrqAsserted=False, externalDrained=True)
        changed = dict(retired, resultStatus=13, completion=completion(17, 13))
        with self.assertRaises(ValueError): retired_bytes(changed, held)
        for key, bad in (('commandId', 18), ('retiredStatus', 33), ('retiredIrq', 3),
                         ('retiredIrqAsserted', 0), ('externalDrained', 1)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                retired_bytes(dict(retired, **{key: bad}), held)
        self.assertEqual(held, original)


if __name__ == '__main__':
    unittest.main()
