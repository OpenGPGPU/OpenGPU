#!/usr/bin/env python3
"""Fabricated CPU-only proof of the P12 counter oracle and its qualification wiring."""
import copy
from pathlib import Path
import struct
import tempfile
import unittest

from compute_owner_cases import CASES, COUNTER_CASES, P12_COUNTER_CASES, counter_parameters, p12_counter_parameters
from compute_p12_counter_cases import LOCAL, COUNTER_INDEX, COUNTER_BYTES, COUNTER_OFFSET, parameters, shader, write_sources
from compute_p12_counter_source import (SCHEMA, UNIFORM_BYTES, ready, recipe, returned_outputs,
    seed_oracle, snapshot_seed, uniform_value, counter_old_values)
from compute_owner_oracle import SparseMemory, verify
from owner_resource_contract import IMAGE_FLOAT_PROFILE, IMAGE_FLOAT_WORDS

ROOT = Path(__file__).resolve().parents[1]
CASE = P12_COUNTER_CASES[0]


class P12Counter(unittest.TestCase):
    def test_case_registration_and_parameters(self):
        self.assertEqual(P12_COUNTER_CASES, (CASE,))
        self.assertIn(CASE, COUNTER_CASES)
        self.assertIn(CASE, CASES)
        self.assertEqual(counter_parameters(CASE), p12_counter_parameters(CASE))
        self.assertEqual(counter_parameters(CASE), dict(source=CASE + '.comp', operation='increment',
            groups=1, local=LOCAL, dynamic=False, seed=0xfffffffe, index=COUNTER_INDEX, offset=COUNTER_OFFSET, count=8))
        with self.assertRaises(ValueError):
            p12_counter_parameters('counter-increment')

    def test_shader_declares_every_resource_and_no_shared_memory(self):
        source = shader()
        self.assertIn('layout(local_size_x=128,local_size_y=1,local_size_z=1) in;\n', source)
        self.assertIn('layout(binding=0,offset=0) uniform atomic_uint counters[8];\n', source)
        for index in range(12):
            self.assertIn('layout(std140,binding=%d) uniform Block%d {uvec4 v[1024];} b%d;\n' %
                          (24 + index, index, index), source)
            self.assertIn('seed=seed*33u^b%d.v[0].x^b%d.v[1023].w;\n' % (index, index), source)
        for index in range(4):
            self.assertIn('layout(std430,binding=%d) buffer Output%d {uint v[];} out%d;\n' %
                          (index, index, index), source)
            self.assertIn('layout(r32ui,binding=%d) writeonly uniform highp uimageBuffer image%d;\n' %
                          (index, index), source)
            self.assertIn('out%d.v[id]=value^%du;\n' % (index, index * 0x10203), source)
            self.assertIn('imageStore(image%d,int(id),uvec4(value^%du));\n' %
                          (index, index * 0x20406), source)
        self.assertIn('uint value=atomicCounterIncrement(counters[7])^seed;\n', source)
        for barrier in ('memoryBarrierBuffer()', 'memoryBarrierImage()', 'memoryBarrierAtomicCounter()'):
            self.assertIn(barrier, source)
        self.assertNotIn('shared ', source)
        self.assertNotIn('defaults', source)
        with tempfile.TemporaryDirectory(prefix='p12-shader-') as temporary:
            out = Path(temporary)
            write_sources(out)
            self.assertEqual((out / (CASE + '.comp')).read_text(), source)

    def test_uniform_value_and_seed_match_the_c_frontend_recipe(self):
        mask = 0xffffffff
        for slot in (1, 7, 12):
            for word in (0, 23, 4095):
                expected = ((0x4ab319d7 + 0x01020304 + slot * 0x00718293 + word * 0x9e3779b9) ^
                            ((slot + 13) << (word % 23))) & mask
                self.assertEqual(uniform_value(slot, word), expected)
        seed = 0
        for block in range(12):
            seed = ((seed * 33) ^ uniform_value(block + 1, 0) ^ uniform_value(block + 1, 4095)) & mask
        self.assertEqual(seed_oracle(), seed)
        self.assertEqual(len(counter_old_values(seed)), LOCAL)
        self.assertEqual(counter_old_values(seed)[0], seed)
        self.assertEqual(counter_old_values(seed)[-1], (seed + LOCAL - 1) & mask)

    def test_returned_value_multisets(self):
        seed = seed_oracle()
        olds = counter_old_values(seed)
        for binding in range(4):
            ssbo = sorted((old ^ seed ^ (binding * 0x10203)) & 0xffffffff for old in olds)
            image = sorted((old ^ seed ^ (binding * 0x20406)) & 0xffffffff for old in olds)
            self.assertEqual(len(ssbo), 128)
            self.assertEqual(len(image), 128)
            if binding == 0:
                self.assertEqual(ssbo, image)
            else:
                self.assertNotEqual(ssbo, image)
                self.assertNotEqual(ssbo, sorted((old ^ seed ^ (binding * 0x20406)) & 0xffffffff
                                                  for old in olds))
        self.assertEqual(sorted(olds), list(range(seed, seed + 128)) if seed + 128 <= 0xffffffff else
                         sorted((seed + step) & 0xffffffff for step in range(128)))

    def test_qualify_wiring_pins_case_and_float_owner(self):
        import qualify_compute_owner as qualifier
        self.assertIn(CASE, qualifier.CASES)
        self.assertIn(CASE, qualifier.COUNTER_CASES)
        self.assertIn(CASE, qualifier.P12_COUNTER_CASES)
        self.assertIn(CASE, getattr(qualifier, 'CASES'))
        self.assertTrue(hasattr(qualifier, 'IMAGE_FLOAT_PROFILE'))
        self.assertTrue(hasattr(qualifier, 'P12_COUNTER_CASES'))


def fixture():
    from owner_resource_contract import IMAGE_FLOAT_PROFILE
    allocations = {0x1000: bytearray(4096), 0x42000: bytearray(4096),
                   0x20000: bytearray(b'\x5a' * 8192), 0x70000: bytearray(4096)}
    for address in (0x30000, 0x32000, 0x34000, 0x36000):
        allocations[address] = bytearray(struct.pack('<I', 0xdeadbeef) * 2048)
    for address in (0x40000, 0x41000, 0x80000):
        allocations[address] = bytearray(4096)
    for index in range(4):
        allocations[0x90000 + 0x1000 * index] = bytearray(b'\x69' * 4096)
    ubo_pas = [0xa0000 + 0x4000 * index for index in range(12)]
    for physical in ubo_pas:
        allocations[physical] = bytearray(16384)
    uniform_physical = 0xe0000
    allocations[uniform_physical] = bytearray(UNIFORM_BYTES)
    tables = {}

    def word(pa, value):
        for first, data in allocations.items():
            if first <= pa <= first + len(data) - 4:
                struct.pack_into('<I', data, pa - first, value)
                return
        raise AssertionError(hex(pa))

    def map_page(va, pa, flags):
        upper = va >> 22
        if upper not in tables:
            table = 0x50000 + len(tables) * 4096
            allocations[table] = bytearray(4096)
            tables[upper] = table
            word(0x1000 + upper * 4, (table >> 12) * 1024 + 1)
        word(tables[upper] + ((va >> 12) & 1023) * 4, (pa >> 12) * 1024 + flags + 704)

    def view(pa, binding, count, allocation, offset=0):
        return dict(globalBinding=binding, allocationPhysical=pa, allocationBytes=allocation,
                    offset=offset, bytes=count)

    context = 2
    seed = 0xfffffffe
    for index in range(8):
        word(0x20000 + COUNTER_OFFSET + index * 4, (seed + index * 0x10203) & 0xffffffff)
    word(0x20000 + COUNTER_OFFSET + COUNTER_INDEX * 4, seed)
    for index, physical in enumerate(ubo_pas):
        data = struct.pack('<4096I', *(uniform_value(index + 1, w) for w in range(4096)))
        allocations[physical][:] = data
    outputs = [0x30000 + 0x2000 * index for index in range(4)]
    images = [0x90000 + 0x1000 * index for index in range(4)]
    descriptor_root, storage_root, image_root, uniform_root = 0xb1400000, 0xb0c00000, 0xb1000000, 0xb1800000
    counter_va = 0xb0000000 + 7 * 0x400000 + (COUNTER_OFFSET & 4095)
    record = [0x47534201, 64, 1, context, 0, 0, 0, 1, 1, 1, counter_va, counter_va + COUNTER_BYTES, 0,
              counter_va & ~63, (counter_va + COUNTER_BYTES + 63) & ~63, 0]
    for index, value in enumerate(record):
        word(0x40000 + index * 4, value)
    map_page(descriptor_root, 0x40000, 15)
    map_page(counter_va, 0x20000 + (COUNTER_OFFSET & ~4095), 15)
    map_page(storage_root, 0x41000, 15)
    map_page(image_root, 0x80000, 15)
    map_page(0x80800000, 0x42000, 15)
    for index, physical in enumerate(outputs):
        base = 0xcfffc000 + index * (128 * 1024 * 1024 + 4096)
        expected = (0x47534201, 64, 1, context, 0, 0, index, 1, 1, 1,
                    base, base + 8192, 0, base, base + 8192, 0)
        for word_index, value in enumerate(expected):
            word(0x41000 + index * 64 + word_index * 4, value)
        map_page(base, physical, 15)
        map_page(base + 4096, physical + 4096, 15)
    for unit, physical in enumerate(images):
        base = 0xc7ffb000 if unit == 3 else 0x87ffd000 + unit * (128 * 1024 * 1024 + 4096)
        descriptor = [0] * 64
        descriptor[:14] = [0x47495501, 256, 1, context, 0, 0, unit, 1, 1, 4, 0, 1, 14, 14]
        descriptor[20] = 5
        descriptor[22] = base
        descriptor[23] = base + 4096
        descriptor[25] = base
        descriptor[26:30] = [1, 1, 1, 1]
        descriptor[37:40] = [4, 128, 512]
        struct.pack_into('<64I', allocations[0x80000], unit * 256, *descriptor)
        map_page(base, physical, 15)
    low = sum(index << (5 * index) for index in range(6))
    high = sum(index << (5 * (index - 6)) for index in range(6, 12))
    root_record = [0] * 16
    root_record[:15] = [0x47555201, 64, 1, context, 0, 0, 2, 0xfff, low, high,
                        uniform_root + 64, uniform_root + 64 + 24 * 64, 0, 0, 0]
    struct.pack_into('<16I', allocations[uniform_physical], 0, *root_record)
    for index, physical in enumerate(ubo_pas):
        base = uniform_root + 4096 + 16384 * index
        record = [0] * 16
        record[:15] = [0x47554201, 64, 1, context, 0, 0, index, 4, base, base + 16384, 0,
                       base, base + 16384, 0, 1]
        struct.pack_into('<16I', allocations[uniform_physical], 64 + 64 * index, *record)
        allocations[uniform_physical][4096 + 16384 * index:4096 + 16384 * (index + 1)] = \
            allocations[physical][:16384]
    for offset in range(0, UNIFORM_BYTES, 4096):
        map_page(uniform_root + offset, uniform_physical + offset, 3)
    root_table = tables[uniform_root >> 22]
    for index in range(UNIFORM_BYTES // 4096):
        pte = (((uniform_root >> 12) + index) & 1023) * 4
        value = struct.unpack_from('<I', allocations[root_table], pte)[0]
        struct.pack_into('<I', allocations[root_table], pte, value & ~0x80)
    map_page(0x80400000, 0x70000, 15)
    description = dict(schema=SCHEMA, sourceCase=CASE, groups=1, local=128, viewBytes=8192, guard=0xdeadbeef,
        seed=seed, index=COUNTER_INDEX, count=8, counterHandle=4, context=context,
        descriptorPhysical=0x40000, descriptorRoot=descriptor_root, kernargPhysical=0x42000, kernargBytes=4096,
        counter=view(0x20000, 0, COUNTER_BYTES, 8192, COUNTER_OFFSET), storageBindings=[0, 1, 2, 3],
        outputs=[view(address, index, 8192, 8192) for index, address in enumerate(outputs)],
        images=[view(address, index, 512, 4096) for index, address in enumerate(images)],
        imageBindings=[0, 1, 2, 3], imageRoot=image_root, imagePhysical=0x80000, imageBytes=1024,
        ubos=[view(address, 24 + index, 16384, 16384) for index, address in enumerate(ubo_pas)],
        uboBindings=[24 + index for index in range(12)], uniformPhysical=uniform_physical)
    discovery = [(word['address'], word['value']) for word in IMAGE_FLOAT_WORDS] + [
        (0, 0x47550002), (0x60, (1 << 6) | (1 << 22) | (1 << 23) | (1 << 25) | (1 << 29) | (128 << 8)),
        (0x68, 0x160), (0x320, 1048576), (0x158, 0x02020810), (0x494, 0x47544d01), (0x43c, 0x47534d01),
        (0x3fc, 0x47494d01), (0x4bc, 0x47414d01), (0x460, 0x47554d01)]
    writable = sorted([(0x42000, 0x43000), (0x20000 + COUNTER_OFFSET, 0x20000 + COUNTER_OFFSET + COUNTER_BYTES)] +
                      [(address, address + 8192) for address in outputs] +
                      [(address, address + 512) for address in images])
    registers = {0xc8: 0, 0xd0: 0x80800000, 0x14c: 0x80800001, 0x150: 0x80800001,
                 0xd4: 1, 0xd8: 1, 0xdc: 1, 0xe0: 128, 0xe4: 1, 0xe8: 1}
    for base, root, size in ((0x4a0, descriptor_root, 64), (0x420, storage_root, 256),
                             (0x3e0, image_root, 1024)):
        registers.update({base + 4 * index: value for index, value in
                          enumerate((257, root, root + size, 0, context, 0, 0))})
    registers.update({0x440 + 4 * index: value for index, value in
                      enumerate((257, uniform_root, context, 0, 0, 0x80800000, 0x80801000, 0))})

    def capture(discovery=discovery, writable=writable, registers=registers,
                inactive=((1, 0x80400070),)):
        actions = [(1, address, value, 0) for address, value in registers.items() if address != 0x11c]
        actions.append((1, 0x11c, 1, 0))
        return (b'OGCMP003' + struct.pack('<QIII', 1000000, 37, 11, len(discovery)) +
                b''.join(struct.pack('<II', *entry) for entry in discovery) +
                struct.pack('<I', len(writable)) +
                b''.join(struct.pack('<QQ', *entry) for entry in writable) +
                struct.pack('<I', len(inactive)) +
                b''.join(struct.pack('<II', *entry) for entry in inactive) +
                struct.pack('<I', len(actions)) +
                b''.join(struct.pack('<IIII', *entry) for entry in actions))

    return allocations, description, capture


def emit(path, allocations):
    with path.open('wb') as stream:
        pages = sum(len(data) // 4096 for data in allocations.values())
        stream.write(b'OGMEM001' + struct.pack('<II', len(allocations), pages))
        for address, data in sorted(allocations.items()):
            stream.write(struct.pack('<QQII', address, address + len(data), 0, 0))
        for address, data in sorted(allocations.items()):
            for offset in range(0, len(data), 4096):
                stream.write(struct.pack('<Q', address + offset) + data[offset:offset + 4096])


class P12CounterOracle(unittest.TestCase):
    def test_ready_capture_and_returned_bytes(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='p12-counter-oracle-', dir=parent) as temporary:
            directory = Path(temporary)
            before, after = directory / 'before', directory / 'after'
            allocations, description, capture = fixture()
            emit(before, allocations)
            with SparseMemory(before) as memory:
                ready(memory, description, capture(), owner_profile=IMAGE_FLOAT_PROFILE)
                source, outputs, images, ubos = recipe(memory, description)
                self.assertEqual(len(outputs), 4)
                self.assertEqual(len(images), 4)
                self.assertEqual(len(ubos), 12)
                self.assertEqual(snapshot_seed(memory, description), seed_oracle())
            seed = seed_oracle()
            good = copy.deepcopy(allocations)
            olds = counter_old_values(0xfffffffe)
            for binding, address in enumerate(outputs):
                values = [(old ^ seed ^ (binding * 0x10203)) & 0xffffffff for old in olds]
                struct.pack_into('<128I', good[address], 0, *values)
            for binding, address in enumerate(images):
                values = [(old ^ seed ^ (binding * 0x20406)) & 0xffffffff for old in olds]
                struct.pack_into('<128I', good[address], 0, *values)
            struct.pack_into('<I', good[0x20000], COUNTER_OFFSET + COUNTER_INDEX * 4, (0xfffffffe + 128) & 0xffffffff)
            emit(after, good)
            with SparseMemory(before) as initial, SparseMemory(after) as returned:
                changed = returned_outputs(initial, returned, description)
                self.assertEqual(len(changed), 9)
                verify(initial, returned, description)
            emit(after, allocations)
            with SparseMemory(before) as initial, SparseMemory(after) as returned:
                with self.assertRaises(ValueError):
                    returned_outputs(initial, returned, description)
            for address in outputs + images:
                wrong = copy.deepcopy(good)
                wrong[address][0] ^= 1
                emit(after, wrong)
                with SparseMemory(before) as initial, SparseMemory(after) as returned:
                    with self.subTest(address=hex(address)), self.assertRaises(ValueError):
                        verify(initial, returned, description)

    def test_ready_rejects_foreign_capture_and_owner(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='p12-counter-gates-', dir=parent) as temporary:
            directory = Path(temporary)
            before = directory / 'before'
            allocations, description, capture = fixture()
            emit(before, allocations)
            with SparseMemory(before) as memory:
                with self.assertRaises(ValueError):
                    ready(memory, description, capture(), owner_profile='two-cu-vga60-owner-v2-sample-stage')
                with self.assertRaises(ValueError):
                    ready(memory, description, capture(discovery=[]), owner_profile=IMAGE_FLOAT_PROFILE)
                wrong = copy.deepcopy(description)
                wrong['local'] = 4
                with self.assertRaises(ValueError):
                    ready(memory, wrong, capture(), owner_profile=IMAGE_FLOAT_PROFILE)


if __name__ == '__main__':
    unittest.main()
