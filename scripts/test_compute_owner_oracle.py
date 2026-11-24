#!/usr/bin/env python3
"""CPU-only oracle rejection tests. These fabricated bytes never qualify a DUT."""
from pathlib import Path
import struct
import tempfile
import unittest
from compute_owner_cases import (COMPUTE_LOOP_SCHEMAS, LOOP_BARRIER_CASE, SHARED_BARRIER_MASK_CASE,
                                 STORAGE_LOOP_CASE, STORAGE_LOOP_SCHEMA, WORKGROUP_BARRIER_CASES)
from compute_owner_oracle import SparseMemory, expected_shared, verify, union_original_memory

ROOT = Path(__file__).resolve().parents[1]


def memory(path, output=None, corrupt=()):
    pages = {0x1000: bytearray(4096), 0x2000: bytearray(struct.pack('<I', 0xdeadbeef) * 1024),
             0x3000: bytearray(struct.pack('<I', 0xdeadbeef) * 1024)}
    if output is not None:
        for offset in range(0, len(output), 4096):
            chunk = output[offset:offset + 4096]
            pages[0x2000 + offset][:len(chunk)] = chunk
    for address, value in corrupt:
        pages[address & ~4095][address & 4095] ^= value
    with path.open('wb') as f:
        f.write(b'OGMEM001' + struct.pack('<II', 2, 3))
        f.write(struct.pack('<QQII', 0x1000, 0x2000, 0, 0))
        f.write(struct.pack('<QQII', 0x2000, 0x4000, 0, 0))
        for address, page in pages.items():
            f.write(struct.pack('<Q', address) + page)


class OracleTests(unittest.TestCase):
    def test_workgroup_barriers_check_distinct_groups_and_guard(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='workgroup-barrier-oracle-', dir=parent) as directory:
            initial, returned = (Path(directory) / name for name in ('initial', 'returned'))
            memory(initial)
            for case, first, second in zip(WORKGROUP_BARRIER_CASES, (100, 103), (103, 106)):
                with self.subTest(case=case):
                    description = dict(schema=COMPUTE_LOOP_SCHEMAS[case], sourceCase=case,
                        groups=2, local=128, viewBytes=8192, guard=0xdeadbeef,
                        output=dict(globalBinding=0, allocationPhysical=0x2000,
                                    allocationBytes=8192, offset=0, bytes=8192))
                    expected = struct.pack('<256I', *range(first, first + 128), *range(second, second + 128))
                    memory(returned, expected)
                    with SparseMemory(initial) as before, SparseMemory(returned) as after:
                        verify(before, after, description)
                    for word in (0, 128, 256):
                        memory(returned, expected, [(0x2000 + 4 * word, 1)])
                        with SparseMemory(initial) as before, SparseMemory(returned) as after:
                            with self.assertRaises(ValueError):
                                verify(before, after, description)

    def test_shared_barrier_mask_checks_peer_warp_and_lane_masks(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='shared-mask-oracle-', dir=parent) as directory:
            initial, returned = (Path(directory) / name for name in ('initial', 'returned'))
            memory(initial)
            description = dict(schema=COMPUTE_LOOP_SCHEMAS[SHARED_BARRIER_MASK_CASE],
                sourceCase=SHARED_BARRIER_MASK_CASE, groups=1, local=128, viewBytes=8192,
                guard=0xdeadbeef, output=dict(globalBinding=0, allocationPhysical=0x2000,
                                             allocationBytes=8192, offset=0, bytes=8192))
            expected = [0xdeadbeef] * 2048
            peers = [0xa1000000 + warp * 16 + lane for warp in (1, 0, 3, 2, 5, 4, 7, 6)
                     for lane in range(16)]
            expected[512:640] = range(0xa1000003, 0xa1000003 + 128)
            expected[1:128:2] = [peer + 1 for peer in peers[1::2]]
            expected[128:256:2] = [peer ^ 7 for peer in peers[::2]]
            data = struct.pack('<2048I', *expected)
            memory(returned, data)
            with SparseMemory(initial) as before, SparseMemory(returned) as after:
                verify(before, after, description)
            for offset, mask in ((0, 1), (4, 16), (512 * 4, 16), (8191, 1)):
                memory(returned, data, [(0x2000 + offset, mask)])
                with SparseMemory(initial) as before, SparseMemory(returned) as after:
                    with self.assertRaises(ValueError):
                        verify(before, after, description)

    def test_masked_loop_fence_preserves_skipped_words(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='loop-fence-oracle-', dir=parent) as directory:
            initial, returned = (Path(directory) / name for name in ('initial', 'returned'))
            memory(initial)
            description = dict(schema=COMPUTE_LOOP_SCHEMAS[LOOP_BARRIER_CASE],
                sourceCase=LOOP_BARRIER_CASE, groups=1, local=4, viewBytes=8192, guard=0xdeadbeef,
                output=dict(globalBinding=0, allocationPhysical=0x2000, allocationBytes=8192,
                            offset=0, bytes=8192))
            expected = bytearray(struct.pack('<I', 0xdeadbeef) * 2048)
            for word, value in ((1, 1), (3, 3), (5, 4), (7, 6), (16, 1), (18, 3),
                                (20, 4), (22, 6), (33, 3), (35, 1), (37, 6), (39, 4)):
                struct.pack_into('<I', expected, word * 4, value)
            memory(returned, expected)
            with SparseMemory(initial) as before, SparseMemory(returned) as after:
                verify(before, after, description)
            for word in (0, 5):
                memory(returned, expected, [(0x2000 + 4 * word, 1)])
                with SparseMemory(initial) as before, SparseMemory(returned) as after:
                    with self.assertRaises(ValueError):
                        verify(before, after, description)

    def test_storage_loop_rejects_wrong_lane_and_guard_changes(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='storage-loop-oracle-', dir=parent) as temporary:
            initial_path, returned_path = (Path(temporary) / name for name in ('initial', 'returned'))
            original = Path(temporary) / 'original'
            memory(original)
            fixture = bytearray(original.read_bytes())
            # Extend the source allocation to two pages and initialize distinct input words.
            struct.pack_into('<II', fixture, 8, 2, 4)
            struct.pack_into('<QQ', fixture, 16, 0, 8192)
            guard = struct.pack('<I', 0xdeadbeef)
            values = tuple(range(0x1000, 0x1080))
            fixture[64:64 + 4104] = (struct.pack('<Q128I', 0, *values) + guard * 896 +
                                      struct.pack('<Q', 4096) + guard * 1024)
            initial_path.write_bytes(fixture)
            view = dict(allocationBytes=8192, offset=0, bytes=8192)
            description = dict(schema=STORAGE_LOOP_SCHEMA, sourceCase=STORAGE_LOOP_CASE,
                viewBytes=8192, groups=1, local=128, guard=0xdeadbeef,
                input=dict(view, globalBinding=1, allocationPhysical=0),
                output=dict(view, globalBinding=0, allocationPhysical=8192))
            start = 64 + 2 * 4104 + 8
            result = struct.pack('<384I', *values, *(value ^ 1 for value in values),
                                 *(value ^ 2 for value in values))
            fixture[start:start + len(result)] = result
            returned_path.write_bytes(fixture)
            with SparseMemory(initial_path) as initial:
                with SparseMemory(returned_path) as returned:
                    verify(initial, returned, description)
                for offset in (72, start + 4, start + len(result)):
                    corrupted = bytearray(fixture)
                    corrupted[offset] ^= 1
                    returned_path.write_bytes(corrupted)
                    with SparseMemory(returned_path) as returned, self.assertRaises(ValueError):
                        verify(initial, returned, description)

    def test_union_retains_old_bytes_and_new_original_uploads_without_aliases(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='retained-input-union-',dir=parent) as d:
            root=Path(d);old=root/'old';upload=root/'upload';merged=root/'merged'
            memory(old)
            data=bytearray(old.read_bytes())
            for index in range(2):
                a,b=struct.unpack_from('<QQ',data,16+24*index)
                struct.pack_into('<QQ',data,16+24*index,a+0x10000,b+0x10000)
            cursor=16+2*24
            for _ in range(3):
                a,=struct.unpack_from('<Q',data,cursor);struct.pack_into('<Q',data,cursor,a+0x10000)
                cursor+=4104
            upload.write_bytes(data)
            with SparseMemory(old) as previous,SparseMemory(upload) as new:
                merged.write_bytes(union_original_memory(previous,new))
                with SparseMemory(merged) as combined:
                    self.assertEqual(combined.ranges,sorted(previous.ranges+new.ranges))
                    for source in (previous,new):
                        for a,b,_ in source.ranges:self.assertEqual(source.read(a,b-a),combined.read(a,b-a))
                with self.assertRaises(ValueError):union_original_memory(previous,previous)

    def test_nonuniform_add_and_cas_match_miss_reject_lost_xor_and_permuted_addresses(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='address-oracle-', dir=parent) as temporary:
            root = Path(temporary); initial = root / 'initial'; returned = root / 'returned'
            memory(initial)
            for kind in ('add', 'cas-match', 'cas-miss'):
                description = dict(schema='opengpu-compute-shared-address-oracle-v1',
                    sourceCase='shared-address-' + kind, outputPhysical=0x2000,
                    allocationBytes=8192, viewBytes=8192, groups=4, local=128,
                    seedBase=0xa1000000, groupShift=16, addend=257, replaceValue=0xfedcba98,
                    compareXor=int(kind == 'cas-miss'), guard=0xdeadbeef)
                def output(wrong=None, reverse=False):
                    words = [0] * 2048
                    for group in range(4):
                        # Derive the synthetic oracle-test bytes directly from
                        # the authored source, without decoding/evaluating its
                        # shader or importing the production expected values.
                        seeds = [0xa1000000 + group * 65536 + address for address in range(4096)]
                        final = list(seeds)
                        for lane in range(0, 128, 2):
                            target = lane ^ 16
                            if wrong == 'first-permutation': target ^= 2
                            words[group * 512 + lane] = seeds[target]
                            if kind == 'add': final[target] += 257
                            elif kind == 'cas-match': final[target] = 0xfedcba98
                        for lane in range(1, 128, 2): words[group * 512 + lane] = 0xfeedface
                        for bucket in range(16):
                            target = 4080 + (bucket ^ 8 if wrong == 'tail-permutation' else bucket)
                            lanes = [lane for lane in range(128) if
                                     (lane ^ (0 if wrong == 'lost-group-xor' else group)) & 15 == bucket]
                            value = seeds[target]
                            if kind == 'add':
                                old = [value + n * 257 for n in range(8)]; final[target] = value + 8 * 257
                            elif kind == 'cas-match':
                                final[target] = 0xfedcba98; old = [value] + [final[target]] * 7
                            else: old = [value] * 8
                            for lane, value in zip(lanes, reversed(old) if reverse else old):
                                words[group * 512 + 128 + lane] = value
                        for lane in range(128):
                            words[group * 512 + 256 + lane] = final[lane]
                            words[group * 512 + 384 + lane] = final[3968 + lane]
                    return struct.pack('<2048I', *words)
                for reverse in (False, True):
                    memory(returned, output(reverse=reverse))
                    with SparseMemory(initial) as before, SparseMemory(returned) as after:
                        verify(before, after, description)
                for wrong in ('lost-group-xor', 'tail-permutation', 'first-permutation'):
                    memory(returned, output(wrong))
                    with SparseMemory(initial) as before, SparseMemory(returned) as after:
                        with self.subTest(kind=kind, wrong=wrong), self.assertRaises(ValueError):
                            verify(before, after, description)
                for data, corruption in ((None, ()), (output(), ((0x1000, 1),)), (output(), ((0x3fff, 1),))):
                    memory(returned, data, corruption)
                    with SparseMemory(initial) as before, SparseMemory(returned) as after:
                        with self.assertRaises(ValueError): verify(before, after, description)

    def test_all_dynamic_shared_overloads_old_values_final_planes_and_guards(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='atomic-oracle-', dir=parent) as temporary:
            root = Path(temporary); initial = root / 'initial'; returned = root / 'returned'
            memory(initial)
            for operation, signed in [(i, i in (5, 6)) for i in range(10)] + [(i, True) for i in (0, 1, 2, 3, 4, 9)]:
                seed, mask = 0x80000000, 0xffffffff
                if operation == 1: history = [seed - i for i in range(8)]; final = seed - 8
                elif operation == 2: history = [seed, seed ^ mask] * 4; final = seed
                elif operation in (4, 5, 7): history = [seed] * 8; final = seed
                else: history = [seed] + [mask] * 7; final = mask
                words = [0] * 2048
                for group in range(4):
                    for lane in range(128):
                        words[group * 512 + lane] = seed if lane % 2 == 0 else 0xfeedface
                        words[group * 512 + 256 + lane] = history[1] if lane % 2 == 0 else seed
                        words[group * 512 + 384 + lane] = final if lane >= 112 else seed
                    for bucket in range(16):
                        lanes = [lane for lane in range(128) if (lane ^ group) % 16 == bucket]
                        # Either legal per-address order must pass, not a CPU
                        # reconstruction of the CU's chosen arbitration.
                        for lane, value in zip(lanes, reversed(history)):
                            words[group * 512 + 128 + lane] = value
                output = struct.pack('<2048I', *words)
                description = dict(schema='opengpu-compute-shared-dynamic-oracle-v1',
                    outputPhysical=0x2000, allocationBytes=8192, viewBytes=8192,
                    groups=4, local=128, seed=seed, operand=mask, compare=seed,
                    operation=operation, signed=signed, guard=0xdeadbeef)
                memory(returned, output)
                with SparseMemory(initial) as before, SparseMemory(returned) as after:
                    verify(before, after, description)
                for address in (0x2000, 0x2004, 0x2200, 0x2400, 0x2404, 0x27fc, 0x1004):
                    memory(returned, output, corrupt=((address, 1),))
                    with SparseMemory(initial) as before, SparseMemory(returned) as after:
                        with self.assertRaises(ValueError): verify(before, after, description)

    def test_all_original_uniform_sources_and_four_outputs(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-uniform-oracle-', dir=parent) as temporary:
            root = Path(temporary)
            initial, returned = root / 'initial', root / 'returned'
            allocations, cursor = [], 0x1000
            for binding, size in [(0, 4096)] + [(i + 24, 16384) for i in range(12)] + [(i, 8192) for i in range(4)]:
                data = bytearray(struct.pack('<I', 0xdeadbeef) * (size // 4))
                if len(allocations) < 13:
                    data[:] = b''.join(struct.pack('<I', (cursor * 17 + i * 0x1234567) & 0xffffffff)
                                     for i in range(size // 4))
                allocations.append((cursor, data, dict(globalBinding=binding, allocationPhysical=cursor,
                    allocationBytes=size, offset=0, bytes=size)))
                cursor += size
            description = dict(schema='opengpu-compute-uniform-oracle-v1', viewBytes=8192, groups=4,
                local=128, guard=0xdeadbeef, defaults=allocations[0][2],
                blocks=[a[2] for a in allocations[1:13]], outputs=[a[2] for a in allocations[13:]])
            def emit(path, compute=False, corrupt=None):
                accumulator = struct.unpack_from('<I', allocations[0][1], 8)[0] ^ struct.unpack_from('<I', allocations[0][1], 4092)[0]
                for _, data, _ in allocations[1:13]:
                    accumulator = ((accumulator * 33) ^ struct.unpack_from('<I', data)[0] ^ struct.unpack_from('<I', data, 16380)[0]) & 0xffffffff
                pages = []
                for index, (address, original, _) in enumerate(allocations):
                    data = bytearray(original)
                    if compute and index >= 13:
                        data[:2048] = b''.join(struct.pack('<I', accumulator ^ lane ^ ((index - 13) * 0x10203)) for lane in range(512))
                    if corrupt and index == corrupt[0]:
                        data[corrupt[1]] ^= 1
                    pages.extend((address + offset, data[offset:offset + 4096]) for offset in range(0, len(data), 4096))
                with path.open('wb') as stream:
                    stream.write(b'OGMEM001' + struct.pack('<II', len(allocations), len(pages)))
                    for address, data, _ in allocations:
                        stream.write(struct.pack('<QQII', address, address + len(data), 0, 0))
                    for address, page in pages:
                        stream.write(struct.pack('<Q', address) + page)
            emit(initial); emit(returned, True)
            with SparseMemory(initial) as before, SparseMemory(returned) as after:
                verify(before, after, description)
            for computed, corruption in ((False, None), (True, (0, 0)), (True, (12, 16380)),
                                        (True, (13, 0)), (True, (16, 8191))):
                emit(returned, computed, corruption)
                with SparseMemory(initial) as before, SparseMemory(returned) as after:
                    with self.assertRaises(ValueError):
                        verify(before, after, description)
            emit(returned, True)
            for key, replacement in (('blocks', description['blocks'][:-1]), ('outputs', description['outputs'][:-1]),
                                     ('blocks', list(reversed(description['blocks'])))):
                with SparseMemory(initial) as before, SparseMemory(returned) as after:
                    with self.assertRaises(ValueError):
                        verify(before, after, dict(description, **{key: replacement}))

    def test_full_shared_and_every_other_byte_guard(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-oracle-', dir=parent) as temporary:
            root = Path(temporary)
            initial, returned = root / 'initial', root / 'returned'
            description = dict(schema='opengpu-compute-shared-oracle-v1', outputPhysical=0x2000,
                allocationBytes=8192, viewBytes=8192, groups=4, local=128, seed=0xa1000000, guard=0xdeadbeef)
            output = b''.join(struct.pack('<I', expected_shared(g, lane)) for g in range(4) for lane in range(128))
            memory(initial); memory(returned, output)
            with SparseMemory(initial) as before, SparseMemory(returned) as after:
                verify(before, after, description)
            # Bad returned arithmetic, skipped peer exchange, untouched output,
            # argument/PT/source mutation and writes past the actual results.
            wrong_peer = b''.join(struct.pack('<I', expected_shared(g, lane ^ 16))
                                  for g in range(4) for lane in range(128))
            for result, corruption in ((None, ()), (wrong_peer, ()), (output, ((0x2000, 1),)),
                                       (output, ((0x1000, 1),)), (output, ((0x2800, 1),)),
                                       (output, ((0x3fff, 1),))):
                memory(returned, result, corruption)
                with SparseMemory(initial) as before, SparseMemory(returned) as after:
                    with self.assertRaises(ValueError):
                        verify(before, after, description)
            memory(returned, output)
            for key, value in (('outputPhysical', 0x2004), ('allocationBytes', 4096), ('groups', 2), ('local', 16)):
                wrong = dict(description, **{key: value})
                with SparseMemory(initial) as before, SparseMemory(returned) as after:
                    with self.assertRaises(ValueError):
                        verify(before, after, wrong)
            # Pre-computing the right output on the producer side is forbidden.
            memory(initial, output)
            with SparseMemory(initial) as before, SparseMemory(returned) as after:
                with self.assertRaises(ValueError):
                    verify(before, after, description)


if __name__ == '__main__':
    unittest.main()
