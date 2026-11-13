#!/usr/bin/env python3
"""Independent returned-byte oracle for the actual full shared-memory source.

Expected arithmetic follows the original ESSL stores/barriers/peer reads. No
instruction, descriptor, private copy or CPU shader execution is consulted.
"""
import bisect
import mmap
from pathlib import Path
import struct
from compute_owner_protocol import required_flags, strict_json, uint


class SparseMemory:
    def __init__(self, path):
        self.file = Path(path).open('rb')
        self.mapping = None
        try:
            self.mapping = mmap.mmap(self.file.fileno(), 0, access=mmap.ACCESS_READ)
            data = self.mapping
            if len(data) < 16 or data[:8] != b'OGMEM001':
                raise ValueError('invalid physical memory header')
            count, pages = struct.unpack_from('<II', data, 8)
            if pages > 1 << 20 or len(data) != 16 + count * 24 + pages * 4104:
                raise ValueError('invalid physical memory length')
            self.ranges, self.starts, self.pages = [], [], {}
            cursor, previous = 16, 0
            for _ in range(count):
                first, end, fill, reserved = struct.unpack_from('<QQII', data, cursor)
                if not previous <= first < end <= 1 << 32 or fill > 255 or reserved:
                    raise ValueError('invalid actual allocation')
                self.ranges.append((first, end, fill)); self.starts.append(first)
                previous, cursor = end, cursor + 24
            previous = -1
            for _ in range(pages):
                first, = struct.unpack_from('<Q', data, cursor)
                index = bisect.bisect_right(self.starts, first) - 1
                if (first & 4095 or not previous < first <= (1 << 32) - 4096 or index < 0 or
                        first + 4096 > self.ranges[index][1]):
                    raise ValueError('invalid actual allocation page')
                self.pages[first] = cursor + 8
                previous, cursor = first, cursor + 4104
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.mapping is not None:
            self.mapping.close()
        self.file.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def read(self, first, count):
        uint(first); uint(count)
        if first + count > 1 << 32:
            raise ValueError('out-of-range physical read')
        result = bytearray()
        while count:
            index = bisect.bisect_right(self.starts, first) - 1
            if index < 0 or first >= self.ranges[index][1]:
                raise ValueError('read outside original physical allocation')
            _, end, fill = self.ranges[index]
            page = first & ~4095
            size = min(count, end - first, page + 4096 - first)
            if page in self.pages:
                offset = self.pages[page] + first - page
                result += self.mapping[offset:offset + size]
            else:
                result += bytes([fill]) * size
            first, count = first + size, count - size
        return bytes(result)


def expected_shared(group, lane):
    # ESSL: data[id+128*r]=0xa1000000+(group<<16)+id+128*r;
    # after converged barriers each warp reads the peer (id^16) stripe.
    return sum(0xa1000000 + (group << 16) + (lane ^ 16) + 128 * r
               for r in range(32)) & 0xffffffff


def verify(initial, returned, description):
    required_flags(description, dict(schema='opengpu-compute-shared-oracle-v1',
        viewBytes=8192, groups=4, local=128, seed=0xa1000000, guard=0xdeadbeef))
    first, allocation = uint(description.get('outputPhysical')), uint(description.get('allocationBytes'))
    if first & 4095 or allocation < 8192 or allocation & 4095:
        raise ValueError('output must identify an actual page-rounded original GEM')
    if (first, first + allocation) not in [(a, b) for a, b, _ in initial.ranges]:
        raise ValueError('output does not match the actual original allocation')
    if [(a, b) for a, b, _ in initial.ranges] != [(a, b) for a, b, _ in returned.ranges]:
        raise ValueError('DUT memory replaced original physical allocations')
    guard = struct.pack('<I', 0xdeadbeef)
    if initial.read(first, 8192) != guard * 2048:
        raise ValueError('output was not independently guard-initialized')
    expected = b''.join(struct.pack('<I', expected_shared(group, lane))
                        for group in range(4) for lane in range(128))
    if returned.read(first, len(expected)) != expected:
        raise ValueError('returned full shared/barrier result differs from original ESSL arithmetic')
    for begin, end, _ in initial.ranges:
        for address in range(begin, end, 4096):
            size = min(4096, end - address)
            before, after = initial.read(address, size), returned.read(address, size)
            skip_begin, skip_end = max(address, first), min(address + size, first + len(expected))
            if skip_begin < skip_end:
                lo, hi = skip_begin - address, skip_end - address
                before, after = before[:lo] + before[hi:], after[:lo] + after[hi:]
            if before != after:
                raise ValueError('returned source/argument/descriptor/PT/output guard bytes changed')


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('initial', type=Path)
    parser.add_argument('returned', type=Path)
    parser.add_argument('description', type=Path)
    args = parser.parse_args()
    with SparseMemory(args.initial) as before, SparseMemory(args.returned) as after:
        verify(before, after, strict_json(args.description))
    print('original ESSL full-shared returned bytes and all physical guards: PASS')


if __name__ == '__main__':
    main()
