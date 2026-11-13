#!/usr/bin/env python3
"""Independent original-source hash and RGBA32UI/guard oracle; never renders."""
import argparse
import bisect
import copy
import json
import mmap
from pathlib import Path
import struct
import tempfile


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def integer(value, maximum, name, minimum=0):
    require(type(value) is int and minimum <= value <= maximum, 'invalid ' + name)
    return value


class SparseMemory:
    """Read the published sparse wire format without using the C++ AXI model."""
    def __init__(self, path):
        self.file = open(path, 'rb')
        self.mapping = None
        try:
            size = self.file.seek(0, 2)
            require(size >= 16, 'short OGMEM001 header')
            self.mapping = mmap.mmap(self.file.fileno(), 0, access=mmap.ACCESS_READ)
            data = self.mapping
            require(data[:8] == b'OGMEM001', 'unknown memory format')
            ranges, pages = struct.unpack_from('<II', data, 8)
            require(ranges <= 0x7fffffff and pages <= 1 << 20, 'invalid sparse counts')
            require(size == 16 + ranges * 24 + pages * 4104, 'sparse memory size mismatch')
            self.ranges, self.starts, self.pages = [], [], {}
            cursor, previous = 16, 0
            for _ in range(ranges):
                first, end, fill, reserved = struct.unpack_from('<QQII', data, cursor)
                require(previous <= first < end <= 1 << 32 and fill <= 255 and not reserved,
                        'invalid physical allocation')
                self.ranges.append((first, end, fill))
                self.starts.append(first)
                previous, cursor = end, cursor + 24
            previous = -1
            for _ in range(pages):
                first, = struct.unpack_from('<Q', data, cursor)
                require(first & 4095 == 0 and previous < first <= (1 << 32) - 4096,
                        'invalid sparse page')
                self.pages[first] = cursor + 8
                previous, cursor = first, cursor + 4104
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.mapping is not None:
            self.mapping.close()
            self.mapping = None
        self.file.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def read(self, address, size):
        integer(address, 1 << 32, 'physical address')
        integer(size, (1 << 32) - address, 'physical size')
        result = bytearray()
        while size:
            index = bisect.bisect_right(self.starts, address) - 1
            require(index >= 0 and address < self.ranges[index][1], 'read outside actual allocation')
            _, end, fill = self.ranges[index]
            page = address & ~4095
            count = min(size, end - address, page + 4096 - address)
            if page in self.pages:
                offset = self.pages[page] + address - page
                result.extend(self.mapping[offset:offset + count])
            else:
                result.extend(bytes([fill]) * count)
            address, size = address + count, size - count
        return bytes(result)


def source_window(source, required):
    integer(source['handle'], 0xffffffff, 'original GEM handle', 1)
    size = integer(source['allocation_bytes'], (1 << 64) - 1, 'source allocation', 1)
    offset = integer(source['offset'], size, 'source offset')
    count = integer(source['bytes'], size - offset, 'source window', required)
    require(not (offset | count) & 3 and count == required, 'incorrect source uniform window')
    return (integer(source['first'], 0xffffffff, 'first source word'),
            integer(source['last'], 0xffffffff, 'last source word'))


def expected_color(description):
    integer(description['version'], 1, 'source oracle version', 1)
    require(len(description['stages']) == 2, 'incorrect source stage count')
    sums = []
    for stage, inputs in enumerate(description['stages']):
        first, last = source_window(inputs['defaults'], 4096)
        value = first ^ last
        blocks = inputs['blocks']
        require(len(blocks) == 12, 'incomplete original shader blocks')
        bindings = [integer(block['binding'], 35, 'global binding') for block in blocks]
        require(set(bindings) == set(range(stage * 24, stage * 24 + 12)), 'incorrect original global bindings')
        # Original GLSL declaration/body order, independent of linked TGSI block
        # order, native remapping, instructions and private snapshot contents.
        for block in sorted(blocks, key=lambda block: block['binding']):
            first, last = source_window(block, 16384)
            value = ((value * 33) ^ first ^ last) & 0xffffffff
        sums.append(value)
    return (sums[0] ^ sums[1],) * 4


def allocation(description):
    integer(description['handle'], 0xffffffff, 'color GEM handle', 1)
    first = integer(description['physical_base'], (1 << 32) - 1, 'actual GEM physical base')
    size = integer(description['allocation_bytes'], (1 << 32) - first, 'actual GEM allocation', 1)
    return first, size


def verify(initial, final, description, previous=None):
    rgba = expected_color(description)
    width = integer(description['width'], 640, 'qualification width', 1)
    height = integer(description['height'], 480, 'qualification height', 1)
    color = description['color']
    first, size = allocation(color)
    offset = integer(color['offset'], size, 'color offset')
    view = integer(color['bytes'], size - offset, 'color window', 1)
    pitch = integer(color['row_pitch'], 0xffffffff, 'color row pitch', width * 16)
    require((height - 1) * pitch + width * 16 <= view, 'incomplete color window')
    # Also inspect allocation padding and bytes outside the declared view. A
    # changed shader value cannot accidentally authorize a guard write.
    pixels = struct.pack('<4I', *rgba) * width
    for chunk in range(0, size, 4096):
        count = min(4096, size - chunk)
        expected = bytearray(initial.read(first + chunk, count))
        for row in range(height):
            start = offset + row * pitch
            low, high = max(start, chunk), min(start + len(pixels), chunk + count)
            if low < high:
                expected[low - chunk:high - chunk] = pixels[low - start:high - start]
        actual = final.read(first + chunk, count)
        if actual != expected:
            changed = next(i for i, (a, b) in enumerate(zip(actual, expected)) if a != b)
            raise ValueError('RGBA32UI pixel/guard mismatch at original GEM +0x%x' % (chunk + changed))
    for prior in description.get('preserve', []):
        require(previous is not None, 'prior output requires previous record memory')
        prior_first, prior_size = allocation(prior)
        require(first + size <= prior_first or prior_first + prior_size <= first, 'overlapping output allocations')
        for chunk in range(0, prior_size, 4096):
            count = min(4096, prior_size - chunk)
            require(final.read(prior_first + chunk, count) == previous.read(prior_first + chunk, count),
                    'previous record output changed')
    return rgba


def self_test(parent=None):
    """Exercise rejection/guards using protocol data, without claiming a GPU."""
    def source(handle, size, first, last):
        return dict(handle=handle, allocation_bytes=size, offset=0, bytes=size, first=first, last=last)
    stages = []
    for stage in range(2):
        defaults = source(1 + stage, 4096, 7 if stage else 1, 11 if stage else 2)
        blocks = []
        for binding in range(stage * 24, stage * 24 + 12):
            block = source(3 + binding, 16384, binding + 5, binding + 9)
            block['binding'] = binding
            blocks.append(block)
        stages.append(dict(defaults=defaults, blocks=blocks))
    for inputs in stages:
        inputs['blocks'].reverse()
    description = dict(version=1, width=2, height=2, stages=stages,
        color=dict(handle=41, physical_base=4096, allocation_bytes=512, offset=64, bytes=80, row_pitch=48))
    require(expected_color(description) == (0x690148bf,) * 4, 'hash reference mismatch')
    def write(path, pages):
        data = b'OGMEM001' + struct.pack('<IIQQII', 1, len(pages), 4096, 12288, 0x5a, 0)
        for base, contents in sorted(pages.items()):
            data += struct.pack('<Q', base) + contents
        path.write_bytes(data)
    root = Path(parent) if parent is not None else Path(__file__).resolve().parents[1] / '.artifacts'
    root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='native-oracle-', dir=root) as temporary:
        directory = Path(temporary)
        before, after, previous = (directory / name for name in ('before', 'after', 'previous'))
        page = bytearray([0x5a] * 4096)
        for row in range(2):
            page[64 + row * 48:96 + row * 48] = struct.pack('<4I', *expected_color(description)) * 2
        write(before, {})
        write(after, {4096: page})
        with SparseMemory(before) as a, SparseMemory(after) as b:
            verify(a, b, description)
        for offset in (0, 64, 100, 511):
            bad = bytearray(page)
            bad[offset] ^= 1
            write(after, {4096: bad})
            with SparseMemory(before) as a, SparseMemory(after) as b:
                try:
                    verify(a, b, description)
                except ValueError:
                    pass
                else:
                    raise ValueError('accepted wrong pixel/prefix/padding/suffix')
        prior = bytes([0x37] * 4096)
        write(previous, {8192: prior})
        write(after, {4096: page, 8192: prior})
        description['preserve'] = [dict(handle=42, physical_base=8192, allocation_bytes=512)]
        with SparseMemory(before) as a, SparseMemory(after) as b, SparseMemory(previous) as p:
            verify(a, b, description, p)
        write(after, {4096: page, 8192: bytes([0x38]) + prior[1:]})
        with SparseMemory(before) as a, SparseMemory(after) as b, SparseMemory(previous) as p:
            try:
                verify(a, b, description, p)
            except ValueError:
                pass
            else:
                raise ValueError('accepted changed previous output')
        bad = copy.deepcopy(description)
        bad['stages'][0]['blocks'][0]['binding'] = 1
        try:
            expected_color(bad)
        except ValueError:
            pass
        else:
            raise ValueError('accepted duplicate original binding')
        after.write_bytes(after.read_bytes()[:-1])
        try:
            with SparseMemory(after):
                pass
        except ValueError:
            pass
        else:
            raise ValueError('accepted truncated sparse memory')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initial', type=Path)
    parser.add_argument('--final', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--previous', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print('native graphics color oracle CPU self-test: PASS (no GPU execution)')
        return
    if not all((args.initial, args.final, args.source)):
        parser.error('--initial, --final and --source are required')
    description = json.loads(args.source.read_text())
    with SparseMemory(args.initial) as initial, SparseMemory(args.final) as final:
        if args.previous:
            with SparseMemory(args.previous) as previous:
                rgba = verify(initial, final, description, previous)
        else:
            rgba = verify(initial, final, description)
    print('native graphics RGBA32UI/original-GEM guards: PASS rgba=' + ','.join('%08x' % n for n in rgba))


if __name__ == '__main__':
    main()
