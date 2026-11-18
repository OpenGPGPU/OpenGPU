#!/usr/bin/env python3
"""CPU mutation checks for continuous transport/oracles; never GPU proof."""
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from compute_indirect_oracle import Originals, RetainedMemory, equal_bytes, executable_physical, retained_callers
from compute_indirect_phase_protocol import execution, cleanup_authorization, phase_response, execution_result
import prepare_owner_executor as owner


class Memory:
    def __init__(self, values):
        self.values = dict(values)
        self.ranges = [(a, a + len(data), 0) for a, data in sorted(values.items())]

    def read(self, address, size):
        for first, data in self.values.items():
            if first <= address and address + size <= first + len(data):
                return data[address-first:address-first+size]
        raise ValueError('CPU fixture read outside allocation')


def originals(command=17, opcode=14, epoch=1, objects=((1, 0x1000, bytes(8192)),)):
    wire = b'OGCOB001' + struct.pack('<4I', command, opcode, epoch, len(objects))
    return wire + b''.join(struct.pack('<IIQQ', handle, 0, pa, len(data)) + data
                           for handle, pa, data in objects)


class TransportTests(unittest.TestCase):
    def test_caller_gems_survive_while_completed_context_gems_may_close(self):
        before = Originals(originals(objects=((1, 0x1000, bytes(8192)), (2, 0x5000, bytes(4096)))))
        caller = {1: before.objects[1].extent}
        retained_callers(Originals(originals()), caller)
        for objects in ((), ((2, 0x5000, bytes(4096)),), ((1, 0x7000, bytes(8192)),)):
            if not objects:
                continue  # Empty complete exports are independently rejected by the wire reader.
            with self.subTest(objects=len(objects)), self.assertRaises(ValueError):
                retained_callers(Originals(originals(objects=objects)), caller)

    def test_executable_uses_exact_production_cached_mapping_not_snapshot_policy(self):
        root = struct.pack('<I', (3 << 10) | 1) + bytes(4092)
        for flags in (0xcf, 0x24b, 0x2cf, 0xc7, 0x43):
            leaf = struct.pack('<I', (4 << 10) | flags) + bytes(4092)
            memory = Memory({0x2000: root, 0x3000: leaf})
            if flags == 0xcf:
                self.assertEqual(executable_physical(memory, 0x80800002, 12), 0x400c)
            else:
                with self.subTest(flags=flags), self.assertRaises(ValueError):
                    executable_physical(memory, 0x80800002, 12)

    def test_original_bytes_bind_all_identities_and_whole_allocation(self):
        data = b'\xa7' * 8192
        wire = originals(objects=((1, 0x1000, data),))
        parsed = Originals(wire)
        parsed.verify(Memory({0x1000: data}), 1, 14, 17)
        for expected in ((0, 14, 17), (1, 0, 17), (1, 14, 18)):
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                parsed.verify(Memory({0x1000: data}), *expected)
        changed = bytearray(data); changed[-1] ^= 1
        with self.assertRaises(ValueError):
            parsed.verify(Memory({0x1000: bytes(changed)}), 1, 14, 17)

    def test_original_wire_rejects_truncation_aliasing_and_forged_sizes(self):
        wire = originals()
        bad = [wire[:-1], wire + b'x', b'BADMAGIC' + wire[8:]]
        bad += [originals(objects=((1, 0x1000, bytes(8192)), (2, 0x2000, bytes(4096)))),
                originals(objects=((2, 0x1000, bytes(4096)), (1, 0x5000, bytes(4096)))),
                originals(objects=((1, 0x1001, bytes(4096)),))]
        size = bytearray(wire); struct.pack_into('<Q', size, 40, 1 << 63); bad.append(size)
        for data in bad:
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                Originals(data)

    def test_retained_upload_preserves_old_bytes_and_rejects_replacement(self):
        live = Memory({0x1000: b'A' * 4096, 0x5000: b'B' * 4096})
        upload = Memory({0x1000: b'A' * 4096, 0x7000: b'C' * 4096})
        merged = RetainedMemory(live, upload, {(0x1000, 0x2000)})
        self.assertEqual(merged.read(0x5000, 4096), b'B' * 4096)
        self.assertEqual(merged.read(0x7000, 4096), b'C' * 4096)
        for changed, allowed in ((upload, set()),
            (Memory({0x1000: b'D' * 4096}), {(0x1000, 0x2000)}),
            (Memory({0x1000: b'A' * 8192}), {(0x1000, 0x3000)}),
            (Memory({0x1800: b'A' * 4096}), {(0x1800, 0x2800)})):
            with self.subTest(allowed=allowed), self.assertRaises(ValueError):
                RetainedMemory(live, changed, allowed)
        self.assertEqual(live.read(0x1000, 4096), b'A' * 4096)

    def test_essl_count_store_crosses_pages_without_authorizing_neighbors(self):
        original = b'\x6b' * 8192
        expected = struct.pack('<3I', 2, 2, 2)
        changed = bytearray(original); changed[4092:4104] = expected
        before = Memory({0x1000: original})
        equal_bytes(before, Memory({0x1000: bytes(changed)}), [(0x1ffc, expected)])
        for at in (0, 4091, 4104, 8191):
            bad = bytearray(changed); bad[at] ^= 1
            with self.subTest(at=at), self.assertRaises(ValueError):
                equal_bytes(before, Memory({0x1000: bytes(bad)}), [(0x1ffc, expected)])
        with self.assertRaises(ValueError):
            equal_bytes(before, before, [(0x1ffc, expected)])

    def test_failed_and_zero_consumers_preserve_every_output_byte(self):
        before = Memory({0x1000: b'\xa7' * 8192, 0x5000: b'K' * 4096})
        equal_bytes(before, before)
        for address in (0x1000, 0x5000):
            changed = dict(before.values)
            changed[address] = b'\0' + changed[address][1:]
            with self.subTest(address=address), self.assertRaises(ValueError):
                equal_bytes(before, Memory(changed))

    def test_consumer_3d_num_workgroups_and_all_64_invocations(self):
        before = Memory({0x1000: b'\xa7' * 8192})
        expected = b''.join(struct.pack('<4I', 2, 2, 2, n) for n in range(64))
        actual = Memory({0x1000: expected + b'\xa7' * (8192 - len(expected))})
        equal_bytes(before, actual, [(0x1000, expected)])
        changed = dict(actual.values)
        data = bytearray(changed[0x1000]); struct.pack_into('<I', data, 63*16+12, 0)
        changed[0x1000] = bytes(data)
        with self.assertRaises(ValueError):
            equal_bytes(before, Memory(changed), [(0x1000, expected)])


class ProtocolTests(unittest.TestCase):
    def test_typed_phase_receipt_cannot_close_whole_session_or_skip_publication(self):
        value = dict(schema='opengpu-compute-phase-cleanup-v1', phase='phase-cleanup',
            commandId=17, contextId=2, epoch=1, batches=4, pteClears=3, shootdowns=4,
            publications=1, status=1, irq=1, irqAsserted=False, externalDrained=True,
            quietCycles=32, readOnlyGuards=True, privateMappingsRevoked=True,
            namespaceRetired=True, resetCount=1, ramReplaced=False, sourceQualificationRequired=True,
            clockQualified=False, fpsQualified=False)
        expected = b'OGCPE001' + struct.pack('<10I', 17, 2, 1, 4, 1, 1, 0, 1, 32, 1)
        self.assertEqual(phase_response(value, command=17, context=2, index=1), expected)
        for key, replacement in (('commandId', 18), ('contextId', 3), ('epoch', 2),
            ('schema', 'opengpu-compute-cleanup-v1'), ('batches', 0), ('pteClears', 0),
            ('shootdowns', 0), ('publications', 0), ('quietCycles', 31), ('resetCount', 2),
            ('ramReplaced', True), ('irqAsserted', True), ('readOnlyGuards', 1),
            ('namespaceRetired', False), ('externalDrained', False), ('foreign', 0)):
            changed = dict(value); changed[key] = replacement
            with self.subTest(key=key), self.assertRaises(ValueError):
                phase_response(changed, command=17, context=2, index=1)
        with self.assertRaises(ValueError):
            phase_response(value, command=17, context=2, index=9)

    def test_execution_and_cleanup_authorizations_are_phase_bound(self):
        for n in range(10):
            self.assertEqual(execution(n), b'OGCIEX01' + struct.pack('<I', n))
            self.assertEqual(cleanup_authorization(n), b'OGCIBA01' + struct.pack('<I', n))
        for n in (-1, 10, True, 1.5):
            with self.subTest(n=n), self.assertRaises(ValueError):
                execution(n)

    def test_full_result_requires_one_reset_retained_ram_and_actual_faults(self):
        value = dict(schema='opengpu-compute-indirect-continuous-v1', phases=10, resetCount=1,
            ramReplaced=False, externalDrained=True, physicalReadFaults=2,
            sourceQualificationRequired=True, independentBytesRequired=True,
            clockQualified=False, fpsQualified=False)
        execution_result(value, 2)
        for key, replacement in (('phases', 9), ('resetCount', 10), ('ramReplaced', True),
            ('physicalReadFaults', 0), ('sourceQualificationRequired', False)):
            changed = dict(value); changed[key] = replacement
            with self.subTest(key=key), self.assertRaises(ValueError):
                execution_result(changed, 2)
        with self.assertRaises(ValueError):
            execution_result(dict(value, physicalReadFaults=0), 0)


class ExecutorTests(unittest.TestCase):
    def test_indirect_kind_only_accepts_full_float_factory(self):
        for profile in (owner.owner.PROFILE, owner.COMPUTE_STAGE, *owner.FULL_COMPUTE_CONTRACTS):
            if profile == owner.IMAGE_FLOAT_PROFILE:
                self.assertEqual(owner.profile_contract(profile, owner.INDIRECT_KIND)['profile'], profile)
            else:
                with self.subTest(profile=profile), self.assertRaises(RuntimeError):
                    owner.driver_files(owner.INDIRECT_KIND, profile)
        files = owner.driver_files(owner.INDIRECT_KIND, owner.IMAGE_FLOAT_PROFILE)
        self.assertEqual(files[0], 'scripts/compute_indirect_owner_driver.cpp')
        self.assertIn('scripts/compute_indirect_capture.hpp', files)
        checkers = owner.checker_files(owner.INDIRECT_KIND)
        self.assertIn('userspace/tests/compute_indirect_frontend.c', checkers)
        self.assertIn('driver/tests/opengpu_compute_host_phase.inc', checkers)
        for name in owner.INDIRECT_CHECKERS:
            self.assertIn(name, checkers)

    def test_missing_indirect_oracle_cannot_qualify_partial_integration(self):
        root = owner.ROOT
        with tempfile.TemporaryDirectory(prefix='indirect-input-', dir=root / '.artifacts') as temporary:
            target = Path(temporary)
            # The required consumers are checked before producer scanning.
            for name in (*owner.CHECKERS, *owner.COMPUTE_CHECKERS, 'scripts/compute_indirect_sources.py',
                         *owner.INDIRECT_CHECKERS):
                if name == 'scripts/compute_indirect_oracle.py':
                    continue
                path = target / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((root / name).read_bytes())
            with patch.object(owner, 'ROOT', target), self.assertRaisesRegex(RuntimeError, 'indirect_oracle'):
                owner.checker_files(owner.INDIRECT_KIND)


if __name__ == '__main__':
    unittest.main()
