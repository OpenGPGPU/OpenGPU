#!/usr/bin/env python3
"""Independent CPU topology/image checks; optional retained-DUT byte audit."""
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import unittest

from test_native_binned_line_loop_fixture import (
    NativeLineLoopFixtureTest, diamond, expected_image, fields, word,
)

ROOT = Path(__file__).resolve().parents[1]
POSITIONS = ((1, 1), (7, 1), (1, 7), (7, 7), (1, 3), (5, 1), (3, 7), (7, 5))


def segments(mode):
    split = 3 if mode in (1, 5) else 4
    return tuple(range(split)), tuple(range(split, 8))


def primitives(mode, groups, alternate=True):
    result = []
    for group in groups:
        if mode == 0:
            result.extend((a,) for a in group)
        elif mode == 1:
            result.extend(tuple(group[i:i+2]) for i in range(0, len(group)-1, 2))
        elif mode in (2, 3):
            result.extend(zip(group, group[1:]))
            if mode == 2 and len(group) > 1:
                result.append((group[-1], group[0]))
        elif mode == 4:
            result.extend(tuple(group[i:i+3]) for i in range(0, len(group)-2, 3))
        elif mode == 5:
            result.extend((group[i+1], group[i], group[i+2]) if alternate and i % 2
                          else (group[i], group[i+1], group[i+2]) for i in range(len(group)-2))
        elif mode == 6:
            result.extend((group[0], group[i], group[i+1]) for i in range(1, len(group)-1))
        else:
            raise ValueError('unknown topology')
    return result


def cross(a, b, c):
    return (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0])


def covered(primitive, center, cull=False):
    points = [POSITIONS[i] for i in primitive]
    if len(points) == 1:
        return points[0] == center  # Unit point centered on a half-pixel.
    if len(points) == 2:
        return diamond(points[0], points[1], center)
    area = cross(*points)
    if area == 0 or (cull and area < 0):
        return False
    sign = 1 if area > 0 else -1
    return all(cross(a, b, center)*sign > 0 or
               (cross(a, b, center) == 0 and
                ((a[1]-b[1])*sign < 0 or (a[1] == b[1] and (b[0]-a[0])*sign < 0)))
               for a, b in zip(points, points[1:]+points[:1]))


def coverage(mode, groups=None, alternate=True):
    assembled = primitives(mode, segments(mode) if groups is None else groups, alternate)
    return tuple(sum(covered(p, (2*x+1, 2*y+1), mode == 5) for p in assembled)
                 for y in range(4) for x in range(4))


def sparse(path):
    data = path.read_bytes()
    if data[:8] != b'OGMEM001':
        raise ValueError('invalid memory magic')
    ranges, count = struct.unpack_from('<II', data, 8)
    if len(data) != 16 + 24*ranges + 4104*count:
        raise ValueError('invalid memory length')
    allocations, pages, at = [], {}, 16
    for _ in range(ranges):
        start, end, fill, reserved = struct.unpack_from('<QQII', data, at)
        if not start < end <= 2**32 or fill > 255 or reserved or (allocations and allocations[-1][1] > start):
            raise ValueError('invalid allocation')
        allocations.append((start, end, fill)); at += 24
    previous = -1
    for _ in range(count):
        address, = struct.unpack_from('<Q', data, at)
        if address % 4096 or address <= previous:
            raise ValueError('invalid memory page')
        pages[address] = data[at+8:at+4104]; previous = address; at += 4104

    def byte(address):
        if address & ~4095 in pages:
            return pages[address & ~4095][address & 4095]
        return next((fill for start, end, fill in allocations if start <= address < end), 0)
    return allocations, byte


class NativeTopologyFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Reuse the strict C11 exporter, not a simulator or generated RTL.
        NativeLineLoopFixtureTest.setUpClass()
        cls.directory = NativeLineLoopFixtureTest.directory
        cls.binary = NativeLineLoopFixtureTest.binary
        cls.compiler = NativeLineLoopFixtureTest.compiler
        cls.flags = NativeLineLoopFixtureTest.flags
        cls.export = cls.directory/'topologies'; cls.export.mkdir()
        subprocess.run([str(cls.binary), '--export-topologies', str(cls.export)], check=True)
        cls.manifest = json.loads((cls.export/'fixture.json').read_text())

    @classmethod
    def tearDownClass(cls):
        NativeLineLoopFixtureTest.tearDownClass()

    def test_index_records_restart_and_distinct_lifetimes(self):
        draw = fields(json.loads((ROOT/'docs/abi/native-binned-graphics-v1.json').read_text())['draw'])
        self.assertTrue(self.manifest['cpuPreparedOnly'])
        self.assertEqual(len(self.manifest['submissions']), 9)
        for case in self.manifest['submissions']:
            i, mode = case['index'], case['topology']
            self.assertEqual(mode, i if i < 7 else 4 if i == 7 else 5)
            fmt = 3 if i >= 7 else 1+i % 3
            memory = (self.export/case['memoryFile']).read_bytes()
            def get(name): return word(memory, 0x2000, draw['draw.'+name])
            self.assertEqual(get('vertex.geometry.topology'), mode)
            self.assertEqual(get('vertex.geometry.vertex.input.indexFormat'), fmt)
            self.assertEqual(get('vertex.geometry.vertex.input.count'), 9)
            self.assertEqual(get('vertex.geometry.vertex.input.restart'), 1)
            self.assertEqual(get('raster.cullMode'), int(mode == 5))
            size = 1 << (fmt-1)
            indices = tuple(int.from_bytes(memory[0x4000+n*size:0x4000+(n+1)*size], 'little') for n in range(9))
            a, b = segments(mode)
            self.assertEqual(indices, a+((1 << (size*8))-1,)+b)
            self.assertEqual(get('vertex.geometry.vertex.input.indices.limit')-get('vertex.geometry.vertex.input.indices.base'),
                             9*size-int(i == 7))
            clip = struct.unpack('<32f', memory[0x5000:0x5080])
            for n, position in enumerate(POSITIONS):
                self.assertEqual(tuple(value*4+4 for value in clip[4*n:4*n+2]), position)
                self.assertEqual(clip[4*n+2:4*n+4], (0, 1))
            self.assertEqual((case['base'], case['generation'], case['commandId']),
                             (0x100000+(i+3)*0x200000, 12+i, 44+i))

    def test_exact_images_and_fault_oracle(self):
        for case in self.manifest['submissions']:
            i = case['index']
            expected = expected_image(coverage(case['topology']), i == 7, 64 if i == 8 else 32)
            self.assertEqual((self.export/case['expectedImageFile']).read_bytes(), expected)
            memory = bytearray((self.export/case['memoryFile']).read_bytes())
            memory[0xd0000:0xd0080] = expected
            path = self.directory/'returned.bin'; path.write_bytes(memory)
            subprocess.run([str(self.binary), '--verify-topology-memory', str(i), str(path)], check=True)
            for offset in (0xd0000, 0xd0003, 0xd0013, 0xd007f, 0x6000):
                wrong = bytearray(memory); wrong[offset] ^= 1; path.write_bytes(wrong)
                self.assertNotEqual(subprocess.run([str(self.binary), '--verify-topology-memory', str(i), str(path)]).returncode, 0)
            self.assertEqual(case['expectedStatus'], 2 if i == 7 else 0)

    def test_restart_connectivity_fan_anchor_and_strip_parity_witnesses(self):
        for mode in range(1, 7):
            self.assertNotEqual(coverage(mode), coverage(mode, (tuple(range(8)),)), mode)
        self.assertNotEqual(coverage(2), coverage(3), 'loop closing edges are observable')
        self.assertNotEqual(coverage(5), coverage(5, alternate=False), 'strip alternating winding is observable')
        a, b = segments(5)
        wrong = primitives(5, (a,)) + [tuple(reversed(p[:2]))+p[2:] for p in primitives(5, (b,))]
        counts = tuple(sum(covered(p, (2*x+1, 2*y+1), True) for p in wrong) for y in range(4) for x in range(4))
        self.assertNotEqual(coverage(5), counts, 'restart must reset strip parity')
        self.assertNotEqual(coverage(6), coverage(5, segments(6)), 'fan anchor is observable')

    def test_sanitized_export_is_byte_identical(self):
        binary = self.directory/'topology-asan'
        subprocess.run(self.compiler+['-O1', '-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                                     *self.flags, '-o', str(binary)], check=True)
        export = self.directory/'topology-asan-export'; export.mkdir()
        subprocess.run([str(binary), '--export-topologies', str(export)], check=True)
        for path in self.export.iterdir():
            self.assertEqual(path.read_bytes(), (export/path.name).read_bytes(), path.name)


def verify_run(run):
    """Audit bytes against fresh CPU inputs; does not qualify DUT provenance."""
    NativeTopologyFixtureTest.setUpClass()
    try:
        cls = NativeTopologyFixtureTest
        # The four-engine extension words are defined in the C ABI headers;
        # the original two-engine JSON layouts omit these reserved-tail words.
        def offsets(name):
            return {key: int(value) for key, value in re.findall(
                r'^#define (\w+)_WORD (\d+)u$', (ROOT/'driver'/name).read_text(), re.M)}
        frame = offsets('opengpu_native_binned_abi.h')
        state = offsets('opengpu_typed_fragment_state_abi.h')
        rows = []
        for case in cls.manifest['submissions']:
            i, base = case['index'], case['base']
            reference = bytearray((cls.export/case['memoryFile']).read_bytes())
            def put(offset, field, value): struct.pack_into('<I', reference, offset+4*field, value)
            put(0x3000, state['OPENGPU_TYPED_FRAGMENT_STATE_ENGINECOUNT'], 4)
            for engine in (2, 3):
                start, end = base+0x40000+engine*0x10000, base+0x50000+engine*0x10000
                for name, value in (('BASE', start), ('LIMIT', end)):
                    put(0x1000, frame[f'OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH{engine}_{name}'], value)
                for name, value in (('BASE', start), ('LIMIT', end), ('ALLOCATIONBASE', start), ('ALLOCATIONLIMIT', end)):
                    put(0x3000, state[f'OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH{engine-1}_{name}'], value)
            before, input_byte = sparse(run/f'input-{i}.memory')
            after, actual_byte = sparse(run/f'returned-{i}.memory')
            allocations = sorted([(r['address'], r['address']+r['bytes'], 0) for r in case['regions']]
                                 + [(base+0x60000, base+0x70000, 0), (base+0x70000, base+0x80000, 0)])
            if before != allocations or after != allocations:
                raise ValueError(f'allocation mismatch in submission {i}')
            for region in case['regions']:
                address, count, offset = region['address'], region['bytes'], region['offset']
                expected = reference[offset:offset+count]
                if bytes(input_byte(address+n) for n in range(count)) != expected:
                    raise ValueError(f'CPU input mismatch in {i}: {region["name"]}')
                if not region['writable'] and bytes(actual_byte(address+n) for n in range(count)) != expected:
                    raise ValueError(f'immutable input changed in {i}: {region["name"]}')
            expected = expected_image(coverage(case['topology']), i == 7, 64 if i == 8 else 32)
            if bytes(actual_byte(base+0xd0000+n) for n in range(128)) != expected:
                raise ValueError(f'independent image or guard mismatch in {i}')
            rows.append({'submission': i, 'topology': case['topology'], 'exactImageAndGuards': True})
        print(json.dumps({'scope': 'CPU inputs and nine 4x4 returned images; model provenance/completion required separately', 'submissions': rows}))
    finally:
        NativeTopologyFixtureTest.tearDownClass()


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--verify-run':
        verify_run(Path(sys.argv[2]))
    else:
        unittest.main()
