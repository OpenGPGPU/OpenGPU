#!/usr/bin/env python3
"""CPU-only native GNB fixture/oracle checks. Never elaborates or runs a DUT."""
from fractions import Fraction
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
ENDPOINTS = ((1, 1), (7, 1), (7, 7), (1, 7), (1, 3), (5, 7))
EDGES = ((0, 1), (1, 2), (2, 0), (3, 4), (4, 5), (5, 3))
LITERAL = (1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1)


def diamond(start, end, center):
    """Exact independent line/diamond intersection, with a tiny perturbation.

    Coordinates are twice window pixels. This follows the existing arbitrary-
    precision PointLineReference; it does not inspect RTL or simulator output.
    """
    scale, epsilon = 1 << 128, 1 << 64
    ax, ay = start[0] * scale - epsilon, start[1] * scale - 1
    bx, by = end[0] * scale - epsilon, end[1] * scale - 1
    lower, upper, end_inside = Fraction(0), Fraction(1), True
    for first, last, middle in ((ax + ay, bx + by, sum(center) * scale),
                                (ax - ay, bx - by, (center[0] - center[1]) * scale)):
        low, high = middle - scale, middle + scale
        end_inside &= low < last < high
        if first == last:
            if not low < first < high:
                return False
        else:
            entry, leave = sorted((Fraction(low - first, last - first),
                                   Fraction(high - first, last - first)))
            lower, upper = max(lower, entry), min(upper, leave)
    return not end_inside and lower < upper


def coverage(edges=EDGES):
    return tuple(sum(diamond(ENDPOINTS[a], ENDPOINTS[b], (2*x+1, 2*y+1))
                     for a, b in edges) for y in range(4) for x in range(4))


def expected_image(counts, rejected=False, red_quantum=32):
    result = bytearray([0xA7] * 128)
    if not rejected:
        for pixel, count in enumerate(counts):
            offset = 3 + (pixel // 4) * 21 + (pixel % 4) * 4
            # ONE+ONE RGBA8 blending: 1/8 red rounds to 32 on each store.
            result[offset:offset+4] = bytes((red_quantum*count, 0, 0, 255 if count else 0))
    return result


def fields(record):
    return {entry['name']: entry for entry in record['fields']}


def word(memory, base, field):
    offset = base + field['word'] * 4
    count = field.get('words', (field['bits'] + 31) // 32)
    return int.from_bytes(memory[offset:offset+count*4], 'little')


def execute_copy_shader(code, memory, scratch, active):
    """Small CPU ISA check of fixture words, not a GPU execution model.

    Independent decoding is restricted to this straight-line integer/address,
    broadcast and e32 load/store subset. It checks encoded private-array
    addresses and values before asking for any real model execution.
    """
    registers = [0] * 32
    registers[1] = scratch
    vectors = [[0] * active for _ in range(32)]
    stores = {}
    for instruction in code:
        opcode, rd, rs1, rs2 = instruction & 127, (instruction >> 7) & 31, (instruction >> 15) & 31, (instruction >> 20) & 31
        if instruction == 0x30500073:
            return stores
        if opcode == 0x37:
            registers[rd] = instruction & 0xfffff000
        elif opcode == 0x13:
            assert (instruction >> 12) & 7 in (0, 1)
            if (instruction >> 12) & 7 == 1:
                registers[rd] = registers[rs1] << ((instruction >> 20) & 31)
            else:
                immediate = instruction >> 20
                if immediate & 2048:
                    immediate -= 4096
                registers[rd] = (registers[rs1] + immediate) & 0xffffffff
        elif opcode == 0x33:
            assert instruction >> 25 == 0 and (instruction >> 12) & 7 == 0
            registers[rd] = (registers[rs1] + registers[rs2]) & 0xffffffff
        elif instruction == 0xc1087057:
            pass  # Fixture capacity is 16 lanes; no data operation.
        elif opcode == 0x57:
            assert instruction >> 26 == 0x17 and (instruction >> 12) & 7 == 4
            vectors[rd] = [registers[rs1]] * active
        elif opcode in (7, 0x27):
            assert instruction >> 25 == 1 and (instruction >> 12) & 7 == 6
            for lane in range(active):
                address = registers[rs1] + 4*lane
                if opcode == 7:
                    vectors[rd][lane] = memory[address]
                else:
                    stores[address] = vectors[rd][lane]
        else:
            raise AssertionError(f'unexpected fixture instruction {instruction:08x}')
        registers[0] = 0
    raise AssertionError('fixture shader did not terminate')


class NativeLineLoopFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='opengpu-line-loop-cpu-')
        cls.directory = Path(cls.temp.name)
        cls.compiler = shlex.split(os.environ.get('CC', 'cc'))
        cls.flags = ['-std=c11', '-Wall', '-Wextra', '-Werror', '-I', str(ROOT/'driver'),
                     '-I', str(ROOT/'driver/tests'), str(ROOT/'driver/tests/opengpu_native_binned_line_loop_model_test.c')]
        cls.binary = cls.directory/'fixture'
        subprocess.run(cls.compiler + ['-O2', *cls.flags, '-o', str(cls.binary)], check=True)
        subprocess.run([str(cls.binary)], check=True)
        cls.export = cls.directory/'export'
        cls.export.mkdir()
        subprocess.run([str(cls.binary), '--export', str(cls.export)], check=True)
        cls.manifest = json.loads((cls.export/'fixture.json').read_text())

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_exact_diamond_literal_and_both_closure_witnesses(self):
        self.assertEqual(coverage(), LITERAL)
        for close, witnesses in (((2, 0), (5, 10, 15)), ((5, 3), (13, 14))):
            wrong = coverage(tuple(edge for edge in EDGES if edge != close))
            self.assertEqual(tuple(i for i in range(16) if wrong[i] != LITERAL[i]), witnesses)
            self.assertNotEqual(expected_image(wrong), expected_image(LITERAL))
        bridge = coverage(EDGES + ((2, 3),))
        self.assertEqual(tuple(i for i in range(16) if bridge[i] != LITERAL[i]), (13, 14, 15))
        self.assertEqual([bridge[i] for i in (13, 14, 15)], [2, 2, 2])
        self.assertNotEqual(expected_image(bridge), expected_image(LITERAL))

    def test_native_wire_records_ranges_and_short_index_bound(self):
        layout = json.loads((ROOT/'docs/abi/native-binned-graphics-v1.json').read_text())
        self.assertTrue(self.manifest['cpuPreparedOnly'])
        self.assertEqual((self.manifest['width'], self.manifest['height'], self.manifest['samples']), (4, 4, 1))
        self.assertEqual((self.manifest['lanes'], self.manifest['warps'], self.manifest['attributes'], self.manifest['varyings'], self.manifest['colors']), (16, 8, 16, 16, 4))
        for submission in self.manifest['submissions']:
            memory = (self.export/submission['memoryFile']).read_bytes()
            self.assertEqual(len(memory), submission['memoryBytes'])
            base, index = submission['base'], submission['index']
            draw = fields(layout['draw'])
            for name, expected in [('draw.vertex.geometry.topology', 2), ('draw.vertex.geometry.vertex.input.indexFormat', 2),
                                   ('draw.vertex.geometry.vertex.input.restart', 1), ('draw.vertex.geometry.vertex.input.count', 7),
                                   ('draw.vertex.geometry.vertex.input.instances', 1), ('draw.raster.lineWidth', 0x3f800000)]:
                self.assertEqual(word(memory, 0x2000, draw[name]), expected, name)
            view_base = word(memory, 0x2000, draw['draw.vertex.geometry.vertex.input.indices.base'])
            view_limit = word(memory, 0x2000, draw['draw.vertex.geometry.vertex.input.indices.limit'])
            self.assertEqual((view_base, view_limit), (base+0x4000, base+0x4000+(13 if index == 1 else 14)))
            self.assertEqual(struct.unpack('<7H', memory[0x4000:0x400e]), (0, 1, 2, 65535, 3, 4, 5))
            end = 0
            for region in submission['regions']:
                self.assertGreaterEqual(region['offset'], end)
                self.assertEqual((region['offset'] % 64, region['bytes'] % 64), (0, 0))
                self.assertEqual(region['address'], base + region['offset'])
                end = region['offset'] + region['bytes']
                self.assertLessEqual(end, len(memory))
            self.assertEqual(submission['expectedStatus'], 2 if index == 1 else 0)
            self.assertEqual(submission['expectedFaultStage'], 3 if index == 1 else 0)
            self.assertEqual(submission['redQuantum'], 64 if index == 2 else 32)

    def test_encoded_shader_addresses_and_constant_outputs(self):
        submission = self.manifest['submissions'][0]
        memory = (self.export/submission['memoryFile']).read_bytes()
        clip = [struct.unpack('<4I', memory[0x5000+i*16:0x5010+i*16]) for i in range(6)]
        for point, doubled in zip(clip, ENDPOINTS):
            floats = struct.unpack('<4f', struct.pack('<4I', *point))
            self.assertEqual(tuple(Fraction(x)*4+4 for x in floats[:2]), doubled)
            self.assertEqual(floats[2:], (0, 1))
        code = struct.unpack(f"<{submission['vertexWords']}I", memory[0x6000:0x6000+4*submission['vertexWords']])
        scratch = submission['base']+0x10000
        inputs = {scratch+c*512+lane*4: clip[lane][c] for c in range(4) for lane in range(6)}
        actual = execute_copy_shader(code, inputs, scratch, 6)
        self.assertEqual(actual, {scratch+(7+c)*512+lane*4: clip[lane][c] for c in range(4) for lane in range(6)})
        for index, red_bits in ((0, 0x3e000000), (2, 0x3e800000)):
            submission = self.manifest['submissions'][index]
            memory = (self.export/submission['memoryFile']).read_bytes()
            code = struct.unpack(f"<{submission['fragmentWords']}I", memory[0x7000:0x7000+4*submission['fragmentWords']])
            scratch = submission['base']+0x40000
            actual = execute_copy_shader(code, {}, scratch, 16)
            expected = {scratch+(10+location*4+c)*512+lane*4: (red_bits if c == 0 else 0x3f800000 if c == 3 else 0)
                        for location in range(4) for c in range(4) for lane in range(16)}
            expected.update({scratch+27*512+lane*4: 61 for lane in range(16)})
            self.assertEqual(actual, expected)

    def test_all_pixels_padding_and_returned_byte_checker(self):
        for submission in self.manifest['submissions']:
            index = submission['index']
            oracle = expected_image(LITERAL, rejected=index == 1, red_quantum=64 if index == 2 else 32)
            self.assertEqual((self.export/submission['expectedImageFile']).read_bytes(), oracle)
            memory = bytearray((self.export/submission['memoryFile']).read_bytes())
            memory[0xd0000:0xd0080] = oracle
            result = self.directory/f'returned-{index}.bin'
            result.write_bytes(memory)
            subprocess.run([str(self.binary), '--verify-memory', str(index), str(result)], check=True)
            # Cover one rendered pixel, the sole clear pixel, prefix/row/tail guards,
            # and immutable shader data. No forged file is called a DUT result.
            for position in (0xd0000, 0xd0003, 0xd0020, 0xd0013, 0xd007f, 0x6000):
                bad = bytearray(memory);bad[position] ^= 1;result.write_bytes(bad)
                self.assertNotEqual(subprocess.run([str(self.binary), '--verify-memory', str(index), str(result)]).returncode, 0)

    def test_sanitized_export_is_byte_identical(self):
        binary = self.directory/'fixture-asan'
        subprocess.run(self.compiler + ['-O1', '-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                                       *self.flags, '-o', str(binary)], check=True)
        export = self.directory/'export-asan';export.mkdir()
        subprocess.run([str(binary), '--export', str(export)], check=True)
        for path in self.export.iterdir():
            self.assertEqual(path.read_bytes(), (export/path.name).read_bytes(), path.name)


if __name__ == '__main__':
    unittest.main()
