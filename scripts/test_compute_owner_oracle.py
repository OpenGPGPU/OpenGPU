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
        pages[0x2000][:len(output)] = output
    for address, value in corrupt:
        pages[address & ~4095][address & 4095] ^= value
    with path.open('wb') as f:
        f.write(b'OGMEM001' + struct.pack('<II', 2, 3))
        f.write(struct.pack('<QQII', 0x1000, 0x2000, 0, 0))
        f.write(struct.pack('<QQII', 0x2000, 0x4000, 0, 0))
        for address, page in pages.items():
            f.write(struct.pack('<Q', address) + page)


class OracleTests(unittest.TestCase):
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
