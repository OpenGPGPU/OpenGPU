#!/usr/bin/env python3
"""CPU-only oracle rejection tests. These fabricated bytes never qualify a DUT."""
from pathlib import Path
import struct
import tempfile
import unittest
from compute_owner_oracle import SparseMemory, expected_shared, verify

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
