#!/usr/bin/env python3
from pathlib import Path
import re
import struct
import tempfile
import unittest

from native_joint_geometry_live_oracle import (
    GEOMETRY_BASE, MAGIC, PROFILE, TAIL_BASE, WORDS, address, capture, decode,
    profile_words, receipt_words, retired_receipt,
)

ROOT = Path(__file__).resolve().parents[1]


def profile():
    blocks = (
        (0x360, (0x47435201, 16, 0x8b67ff, 2, 16, 8, 128, 128, 0xffffffff, 16384, 16, 8,
                 0x10000000, 32, 131072, 4)),
        (0x160, (0x474e4305, 8, 0x474e4a02, 0x08000200, 0xc2100800, 0x08000800, 0xe4908000, 0x8813db02)),
        (0x340, (0x47434901, 8, 0x7f, 14, 12, 4, 0xffffffff, 128)),
        (0x240, (0x47515601, 8, 0x0b0a0908, 0x47514f01, 64, 8, 0x01001001, 31)),
        (0x260, (0x47464301, 8, 0x1003010c, 0x47544601, 512, 0x04400408, 69, 255)),
        (0x280, (0x47515001, 8, 0x0010040d, 0x47504f01, 64, 0x00204008, 0x8c88, 63)),
        (0x3a0, (0x47554301, 16, 1, 0x7f, 3, 12, 24, 16384, 1024, 1024, 256, 64,
                 0x0e00000b, 0x1c00002b, 0x1e00002b, 31)),
        (0x4e0, (0x474e5001, 8, 512, 2048, 2048, 512, 15, 0)),
        (0x300, (0x47534c01, 8, 31, 32, 1048576, 0x04051918, 0x320, 0x138)),
        (0x500, (0x47475301, 8, 0x1f, 0x474e4a02, 0x474e4603, 0x474e5902, 0xff840206, 0x04001000)),
        (0x600, PROFILE),
    )
    words = [dict(address=base + 4 * index, value=value)
             for base, values in blocks for index, value in enumerate(values)]
    registers = [dict(address=item[0], value=item[1]) for item in (
        (0, 0x47550002), (0x60, 0x06864060), (0x68, 0x160), (0x320, 1048576), (0x158, 0x02020810))]
    return dict(schema='opengpu-protected-joint-geometry-owner-profile-v1',
                resetCount=1, gpuExecution=False, words=words, registers=registers)


def held(row):
    detail = [MAGIC, 0x80, 0, 7, row['pass'], row['generation'], 1, 1] + [0] * 38
    return dict(phase='held', record=row['record'], commandId=5, completion=0x18705,
                gnz3=detail, physicalWriteFaults=0, physicalReadFaults=0,
                heldIrq=3, heldIrqAsserted=True, heldLegacyStatus=0, heldStatus=0x22,
                metadataReadBytes=64, heldCompletionDrained=True,
                readOnlyGuards=True, sourceQualificationRequired=True)


def packet(profile_value, row):
    detail = held(row)['gnz3']
    trace = [(1, 0xc4, 5, 0), (1, 0xc8, 15, 0), (1, 0xfc, 512, 0),
             (1, 0x108, 7, 0), (1, 0x10c, row['pass'], 0), (1, 0x110, row['generation'], 0),
             (1, 0x100, 0x474a0200 | (row['domain'] << 1), 0), (1, 0x11c, 1, 0)]
    checks = [(0x124, 0xffffffff, 0x18705)]
    checks += [(address(index), 0xffffffff if index < 8 else 0,
                detail[index] if index < 8 else 0) for index in range(WORDS)]
    data = bytearray(b'OGCAP007' + struct.pack('<QII', 1000000, 16, 5))
    for fmt, rows in (('II', profile_value.discovery), ('QQ', [(0x1000, 0x2000)]),
                      ('IIII', trace), ('III', checks), ('II', [])):
        data.extend(struct.pack('<I', len(rows)))
        for item in rows:
            data.extend(struct.pack('<' + fmt, *item))
    return bytes(data)


class JointGeometryLiveOracle(unittest.TestCase):
    row = {'record': 0, 'domain': 0, 'pass': 11, 'generation': 41}

    def test_exact_profile_and_ognp0004_wire(self):
        value = profile_words(profile())
        self.assertEqual(len(value.words), 100)
        self.assertEqual(len(value.registers), 5)
        self.assertEqual(value.packet()[:8], b'OGNP0004')
        self.assertEqual(struct.unpack_from('<I', value.packet(), 8)[0], 100)
        self.assertEqual(tuple(dict(value.words)[0x600 + 4 * index] for index in range(4)), PROFILE)
        wrong = profile()
        wrong['words'][2]['value'] ^= 1 << 22
        with self.assertRaisesRegex(ValueError, 'exact protected geometry profile'):
            profile_words(wrong)

    def test_gnz3_address_map_and_padding(self):
        self.assertEqual([address(index) for index in range(16)],
                         list(range(0x180, 0x1b0, 4)) + list(range(TAIL_BASE, 0x550, 4)))
        self.assertEqual(address(16), GEOMETRY_BASE)
        self.assertEqual(address(45), 0x5d4)
        detail = held(self.row)['gnz3']
        self.assertEqual(len(decode(detail)), 46)
        for index, bit in ((20, 0x40000000), (26, 2), (32, 2), (36, 2), (41, 2), (45, 2)):
            wrong = detail.copy()
            wrong[index] |= bit
            with self.assertRaisesRegex(ValueError, 'padding'):
                decode(wrong)
        wrong = detail.copy()
        wrong[21] = 1
        with self.assertRaisesRegex(ValueError, 'admitted plus dropped'):
            decode(wrong)

    def test_held_and_retired_receipts_compare_all_46_words(self):
        value = held(self.row)
        payload = receipt_words(value, 'held', 5, 7, self.row)
        self.assertEqual(len(payload), 220)
        retired = dict(value, phase='retired', ownerReplay=True, retiredStatus=1, retiredIrq=1,
                       retiredIrqAsserted=False, externalDrained=True, resets=1,
                       resetsBetweenRecords=False, independentImagesRequired=True,
                       clockQualified=False, fpsQualified=False, serviceCycles=5000, writeBusBytes=256)
        self.assertEqual(retired_receipt(retired, payload, 5, 7, self.row),
                         b'OGJR0001' + payload + struct.pack('<4I', 1, 1, 0, 1))
        wrong = dict(value, gnz3=value['gnz3'][:45] + [2])
        with self.assertRaisesRegex(ValueError, 'padding'):
            receipt_words(wrong, 'held', 5, 7, self.row)
        changed_detail = retired['gnz3'].copy()
        changed_detail[8] = 1
        changed = dict(retired, gnz3=changed_detail)
        with self.assertRaisesRegex(ValueError, 'changed held GNZ3'):
            retired_receipt(changed, payload, 5, 7, self.row)

    def test_capture_requires_geometry_kind_and_complete_map(self):
        value = profile_words(profile())
        data = packet(value, self.row)
        command, regs, writable = capture(data, value.discovery, 7, self.row)
        self.assertEqual((command, regs[0x100], writable), (5, 0x474a0200, [(0x1000, 0x2000)]))
        with self.assertRaisesRegex(ValueError, 'format'):
            capture(b'OGCAP003' + data[8:], value.discovery, 7, self.row)
        wrong = bytearray(data)
        wrong[-4:] = struct.pack('<I', 1)
        with self.assertRaisesRegex(ValueError, 'trailing|witnesses|truncated'):
            capture(bytes(wrong), value.discovery, 7, self.row)

    def test_report_kind_and_public_ioctl_exclusion(self):
        header = (ROOT / 'driver/tests/opengpu_native_submit_fixture.h').read_text()
        fixture = (ROOT / 'driver/tests/opengpu_native_submit_fixture.c').read_text()
        mmio = (ROOT / 'driver/tests/opengpu_native_mmio_fixture.inc').read_text()
        kernel = (ROOT / 'scripts/native_joint_geometry_live_kernel.c').read_text()
        drm = (ROOT / 'driver/opengpu_drm_device.c').read_text()
        uapi = (ROOT / 'driver/opengpu_drm.h').read_text()
        submit = (ROOT / 'driver/opengpu_native_submit.c').read_text()
        self.assertIn('uint32_t gnz3[46]', header)
        self.assertIn('OPENGPU_TESSELLATION_JOINT_REPORT_GNZ3', fixture)
        self.assertIn('joint_geometry_pending_completion', mmio)
        self.assertIn('receipt.gnz3[i]', kernel)
        self.assertNotIn('OPENGPU_SUBMIT_TESSELLATION_JOINT_GEOMETRY', drm)
        self.assertNotIn('OPENGPU_SUBMIT_TESSELLATION_JOINT_GEOMETRY', uapi)
        self.assertNotIn('opengpu_protected_tessellation_joint_geometry_ioctl', submit)
        source = (ROOT / 'driver/opengpu_hw.c').read_text()
        mask = re.search(r'source_flags\s*=\s*gpu->hw\.compute_resource_flags\s*&\s*~\((.*?)\);', source, re.S)
        self.assertIsNotNone(mask)
        self.assertIn('OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_V1', mask.group(1))
        self.assertIn('OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_GEOMETRY_V1', mask.group(1))
        self.assertNotIn('OPENGPU_PARAM_TESS_JOINT_GEOMETRY', (ROOT / 'driver/opengpu_drm.h').read_text())

    def test_production_geometry_bodies_are_extracted(self):
        import test_native_mmio_capture as generator
        with tempfile.TemporaryDirectory(dir=ROOT / '.artifacts') as directory:
            generator.write_bodies(Path(directory))
            for name in ('joint_geometry_profile', 'joint_geometry_submit'):
                body = (Path(directory) / f'native_mmio_{name}_under_test.inc').read_text()
                self.assertIn('opengpu_hw_protected_tessellation_joint_geometry_', body)


if __name__ == '__main__':
    unittest.main()
