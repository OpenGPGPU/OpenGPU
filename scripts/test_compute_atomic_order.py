#!/usr/bin/env python3
"""Independent source-order and whole-byte mutations; CPU only, no shader VM."""
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from compute_atomic_order import linearization
from compute_storage_atomic_cases import CASES, parameters, invocations, write_sources
from compute_storage_atomic_source import SCHEMA
from compute_owner_oracle import verify


class Memory:
    def __init__(self, allocations):
        self.data = dict(allocations)
        self.ranges = [(first, first + len(value), 0) for first, value in sorted(allocations.items())]

    def read(self, first, count):
        for address, data in self.data.items():
            if address <= first and first + count <= address + len(data):
                return data[first-address:first-address+count]
        raise ValueError('read outside explicit CPU fixture allocation')


def operation(name, operand, compare=0):
    return dict(operation=name, operand=operand, compare=compare)


class AtomicOrder(unittest.TestCase):
    def test_wrapping_add_accepts_reordering_and_rejects_lost_or_new_returns(self):
        ops = [operation('add', 5)] * 8
        old = [0xfffffff0, 0xfffffff5, 0xfffffffa, 0xffffffff, 4, 9, 14, 19]
        observed = [old[index] for index in (7, 1, 5, 3, 0, 6, 2, 4)]
        self.assertEqual(len(linearization(0xfffffff0, 24, ops, observed)), 8)
        for changed, final in ((observed, 19), ([word + 5 & 0xffffffff for word in observed], 24),
                               ([observed[0]] * 8, 24)):
            with self.subTest(final=final), self.assertRaises(ValueError):
                linearization(0xfffffff0, final, ops, changed)

    def test_each_constant_operation_and_signed_unsigned_boundaries(self):
        # Explicit ESSL results, independently specified rather than generated
        # by the transition routine being tested.
        fixtures = (
            ('exchange', 10, 7, 7), ('or', 0x80000000, 0x80000007, 0x80000007),
            ('and', 0xffffffff, 0x80000007, 0x80000007),
            ('signed-min', 0x7fffffff, 0xfffffff9, 0xfffffff9),
            ('signed-max', 0x80000000, 7, 7),
            ('unsigned-min', 0xffffffff, 7, 7),
            ('unsigned-max', 0x7fffffff, 0x80000007, 0x80000007))
        for name, seed, operand, final in fixtures:
            values = [final, final, seed, final, final, final, final, final]
            with self.subTest(name=name):
                linearization(seed, final, [operation(name, operand)] * 8, values)
                with self.assertRaises(ValueError):
                    linearization(seed, seed, [operation(name, operand)] * 8, values)
                with self.assertRaises(ValueError):
                    linearization(seed, final, [operation(name, operand)] * 8, [final] * 8)
        linearization(10, 10, [operation('xor', 0x80000007)] * 8,
                      [10, 0x8000000d, 10, 0x8000000d, 10, 0x8000000d, 10, 0x8000000d])

    def test_cas_match_miss_and_winner_identity(self):
        linearization(10, 7, [operation('compare-swap', 7, 10)] * 8, [7, 7, 10, 7, 7, 7, 7, 7])
        linearization(10, 10, [operation('compare-swap', 7, 11)] * 8, [10] * 8)
        ops = [operation('compare-swap', n + 1) for n in range(3)]
        linearization(0, 2, ops, [2, 0, 2])
        with self.assertRaises(ValueError):
            linearization(0, 2, ops, [0, 2, 2])  # Wrong invocation claims the successful CAS.
        with self.assertRaises(ValueError):
            linearization(10, 7, [operation('compare-swap', 7, 11)] * 8, [10] * 8)

    def test_exchange_edges_must_form_one_complete_old_value_chain(self):
        ops = [operation('exchange', n + 1) for n in range(3)]
        self.assertEqual(linearization(0, 2, ops, [0, 3, 1]), (0, 2, 1))
        for observed, final in (([0, 1, 1], 3), ([2, 3, 1], 2), ([0, 3, 1], 3)):
            with self.subTest(observed=observed), self.assertRaises(ValueError):
                linearization(0, final, ops, observed)

    def test_source_program_order_is_preserved_without_ordering_other_invocations(self):
        ops = [operation('add', 1)] * 2
        linearization(0, 2, ops, [1, 0])
        with self.assertRaises(ValueError):
            linearization(0, 2, ops, [1, 0], (0, 1))
        linearization(0, 2, ops, [0, 1], (0, 1))
        with self.assertRaises(ValueError):
            linearization(0, 2, ops, [0, 1], (2, 1))  # Cyclic order has no valid execution.

    def test_bounded_strict_inputs(self):
        valid = operation('add', 1)
        for ops, old, predecessors in (([], [], None), ([valid] * 17, [0] * 17, None),
            ([valid], [True], None), ([dict(valid, extra=0)], [0], None),
            ([operation('unchecked-global', 1)], [0], None), ([valid], [0], (1,)),
            ([valid], [0], (2,)), ([dict(valid, operand=-1)], [0], None)):
            with self.subTest(ops=len(ops)), self.assertRaises(ValueError):
                linearization(0, 1, ops, old, predecessors)


class SourceBytes(unittest.TestCase):
    def fixture(self):
        case = 'storage-atomic-compare-lane';p = parameters(case)
        target = bytearray(b'\x6b' * 8192);struct.pack_into('<I', target, 0, p['seed'])
        before = Memory({0x1000: bytes(target), 0x5000: b'\xa7' * 8192, 0x9000: b'K' * 4096})
        description = dict(schema=SCHEMA, sourceCase=case, groups=2, local=4, viewBytes=8192,
            guard=0xdeadbeef, seed=0, targetOffset=0, targetAccess=3, resultStatus=0, atomicOffset=0, context=2,
            storageBindings=[0, 1], descriptorPhysical=0xa000, descriptorRoot=0xb0000000,
            codePhysical=0xc000, codeBytes=128,
            target=dict(globalBinding=0, allocationPhysical=0x1000, allocationBytes=8192, offset=0, bytes=8192),
            output=dict(globalBinding=1, allocationPhysical=0x5000, allocationBytes=8192, offset=0, bytes=8192))
        final = bytearray(target);struct.pack_into('<I', final, 0, 4)
        values = [4] * 8;values[3] = 0
        after = Memory({0x1000: bytes(final), 0x5000: struct.pack('<8I', *values) + b'\xa7' * (8192 - 32),
                        0x9000: b'K' * 4096})
        return before, after, description

    def test_exact_cas_old_words_and_every_unrelated_physical_byte(self):
        before, after, description = self.fixture()
        verify(before, after, description)
        for address, offset in ((0x1000, 4), (0x1000, 8191), (0x5000, 32), (0x9000, 4095)):
            values = dict(after.data);changed = bytearray(values[address]);changed[offset] ^= 1;values[address] = bytes(changed)
            with self.subTest(address=address, offset=offset), self.assertRaises(ValueError):
                verify(before, Memory(values), description)

    def test_forged_recipe_prefill_and_wrong_cas_lane_are_rejected(self):
        before, after, description = self.fixture()
        for key, value in (('targetAccess', 1), ('seed', 1), ('groups', 1),
                           ('sourceCase', 'counter-increment'), ('storageBindings', [0, 0])):
            changed = dict(description);changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify(before, after, changed)
        wrong = dict(after.data);data = bytearray(wrong[0x5000]);struct.pack_into('<I', data, 0, 0)
        struct.pack_into('<I', data, 12, 4);wrong[0x5000] = bytes(data)
        with self.assertRaises(ValueError):
            verify(before, Memory(wrong), description)
        with self.assertRaises(ValueError):
            verify(after, after, description)

    def test_all_source_recipes_are_distinct_and_original_inputs_stay_identical(self):
        from test_gallium_compute_atomics import write_sources as original
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix='atomic-sources-', dir=root / '.artifacts') as temporary:
            a, b = Path(temporary) / 'original', Path(temporary) / 'extended';a.mkdir();b.mkdir()
            original(a);write_sources(b)
            self.assertEqual(len(CASES), 17)
            for case in CASES:
                p = parameters(case)
                self.assertEqual(len(invocations(case)), 8)
                self.assertTrue((b / p['source']).is_file())
                if (a / p['source']).is_file():
                    self.assertEqual((a / p['source']).read_bytes(), (b / p['source']).read_bytes())

    def test_checked_view_failures_preserve_every_byte_and_last_valid_word_succeeds(self):
        _, _, template = self.fixture()
        from compute_storage_atomic_cases import READONLY_CASE, BOUNDS_CASE, ENDPOINT_CASE
        for case in (READONLY_CASE, BOUNDS_CASE, ENDPOINT_CASE):
            p=parameters(case);target=bytearray(b'\x6b'*8192)
            struct.pack_into('<I',target,p['target_offset'],p['seed'])
            before=Memory({0x1000:bytes(target),0x5000:b'\xa7'*8192,0x9000:b'K'*4096})
            description=dict(template,sourceCase=case,seed=p['seed'],targetOffset=p['target_offset'],
                targetAccess=p['target_access'],resultStatus=p['result_status'],atomicOffset=p['access_offset'])
            if p['result_status']:
                verify(before,before,description)
                changed=dict(before.data);value=bytearray(changed[0x1000]);value[0]^=1;changed[0x1000]=bytes(value)
                with self.subTest(case=case),self.assertRaises(ValueError):
                    verify(before,Memory(changed),description)
            else:
                final=bytearray(target);struct.pack_into('<I',final,8188,24)
                old=(0xfffffff0,0xfffffff5,0xfffffffa,0xffffffff,4,9,14,19)
                after=Memory({0x1000:bytes(final),0x5000:struct.pack('<8I',*old)+b'\xa7'*(8192-32),0x9000:b'K'*4096})
                verify(before,after,description)
                final[8187]^=1
                changed=dict(after.data);changed[0x1000]=bytes(final)
                with self.assertRaises(ValueError):
                    verify(before,Memory(changed),description)

    def test_qualification_requires_all_new_consumers_and_the_full_factory(self):
        import prepare_owner_executor as owner
        from types import SimpleNamespace
        from qualify_compute_owner import checked_inputs
        from unittest.mock import Mock
        fake = Mock();fake.is_file.return_value = True;fake.is_symlink.return_value = False
        args = SimpleNamespace(executor=fake, executor_qualification=fake,
            executor_checker='original', executor_checker_python='python', case='storage-atomics')
        record = dict(schema='opengpu-owner-executor-v1', kind='compute', profile=owner.SAMPLE_PROFILE)
        with patch('qualify_compute_owner.subprocess.run'), patch('qualify_compute_owner.strict_json', return_value=record):
            with self.assertRaisesRegex(ValueError, 'complete float owner'):
                checked_inputs(args)
        names = owner.checker_files('compute')
        for path in ('scripts/compute_atomic_order.py', 'scripts/compute_storage_atomic_source.py',
                     'scripts/compute_storage_atomic_controller.py', 'scripts/compute_indirect_oracle.py',
                     'userspace/tests/compute_host_storage_atomics.h'):
            self.assertIn(path, names)


if __name__ == '__main__':
    unittest.main()
