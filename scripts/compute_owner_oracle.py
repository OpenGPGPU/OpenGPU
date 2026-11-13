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


def allocation_window(memory, value, count, binding):
    if type(value) is not dict or set(value) != {'globalBinding', 'allocationPhysical', 'allocationBytes', 'offset', 'bytes'}:
        raise ValueError('invalid original GEM source/view description')
    required_flags(value, dict(globalBinding=binding, bytes=count))
    physical, size, offset = (uint(value.get(key)) for key in ('allocationPhysical', 'allocationBytes', 'offset'))
    if (physical & 4095 or size & 4095 or offset & 3 or offset > size or count > size - offset or
            (physical, physical + size) not in [(a, b) for a, b, _ in memory.ranges]):
        raise ValueError('view does not match an actual original GEM allocation')
    return physical + offset


def verify(initial, returned, description):
    if [(a, b) for a, b, _ in initial.ranges] != [(a, b) for a, b, _ in returned.ranges]:
        raise ValueError('DUT memory replaced original physical allocations')
    required_flags(description, dict(viewBytes=8192, groups=4, local=128, guard=0xdeadbeef))
    if description.get('schema') == 'opengpu-compute-shared-oracle-v1':
        required_flags(description, dict(seed=0xa1000000))
        first, allocation = uint(description.get('outputPhysical')), uint(description.get('allocationBytes'))
        if first & 4095 or allocation < 8192 or allocation & 4095:
            raise ValueError('output must identify an actual page-rounded original GEM')
        if (first, first + allocation) not in [(a, b) for a, b, _ in initial.ranges]:
            raise ValueError('output does not match the actual original allocation')
        expected = b''.join(struct.pack('<I', expected_shared(group, lane))
                            for group in range(4) for lane in range(128))
        outputs = [(first, expected)]
    elif description.get('schema') == 'opengpu-compute-uniform-oracle-v1':
        defaults = allocation_window(initial, description.get('defaults'), 4096, 0)
        blocks, output_views = description.get('blocks'), description.get('outputs')
        if type(blocks) is not list or len(blocks) != 12 or type(output_views) is not list or len(output_views) != 4:
            raise ValueError('all twelve original UBOs/defaults and four outputs are required')
        sources = [(defaults, defaults + 4096)]
        def word(address):
            return struct.unpack('<I', initial.read(address, 4))[0]
        accumulator = word(defaults + 8) ^ word(defaults + 4092)
        # Original ESSL binding24..35 body order, independent of TGSI/native
        # slot remapping, descriptors, snapshots and instruction words.
        for index, block in enumerate(blocks):
            first = allocation_window(initial, block, 16384, 24 + index)
            sources.append((first, first + 16384))
            accumulator = ((accumulator * 33) ^ word(first) ^ word(first + 16380)) & 0xffffffff
        outputs = []
        for binding, view in enumerate(output_views):
            first = allocation_window(initial, view, 8192, binding)
            sources.append((first, first + 8192))
            outputs.append((first, b''.join(struct.pack('<I', accumulator ^ index ^ (binding * 0x10203))
                                          for index in range(512))))
        # This full-capacity fixture owns distinct original sources and outputs.
        # General alias admission remains the production driver's contract.
        sources.sort()
        if any(a[1] > b[0] for a, b in zip(sources, sources[1:])):
            raise ValueError('full original uniform/output fixture was aliased')
    else:
        raise ValueError('unknown original ESSL byte oracle')
    for first, expected in outputs:
        if initial.read(first, 8192) != struct.pack('<I', 0xdeadbeef) * 2048:
            raise ValueError('output was not independently guard-initialized')
        if returned.read(first, len(expected)) != expected:
            raise ValueError('returned original ESSL result differs from independent source arithmetic')
    for begin, end, _ in initial.ranges:
        for address in range(begin, end, 4096):
            size = min(4096, end - address)
            before, after = initial.read(address, size), returned.read(address, size)
            # Compare every byte except the precisely expected result words.
            kept = bytearray(b'\x01' * size)
            for first, expected in outputs:
                lo, hi = max(address, first) - address, min(address + size, first + len(expected)) - address
                if lo < hi:
                    kept[lo:hi] = b'\x00' * (hi - lo)
            if any(before[i] != after[i] for i in range(size) if kept[i]):
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
    print('original ESSL returned bytes and all physical guards: PASS')


if __name__ == '__main__':
    main()
