#!/usr/bin/env python3
"""Fabricated CPU-only proof of the P12 texture oracle and its recipe gates."""
import copy
from pathlib import Path
import re
import struct
import tempfile
import unittest

from compute_owner_oracle import SparseMemory
from compute_p12_texture_cases import (CASES, GUARD, LOCAL, TEXTURE_COUNT, parameters, shader,
    write_sources)
from compute_p12_texture_source import (FIELDS, SCHEMA, TEXTURE_BACKING_BYTES,
    TEXTURE_VIEW_BYTES, UNIFORM_BYTES, recipe, ready, returned_outputs, texel_fetch_value,
    texture_value)
from owner_resource_contract import IMAGE_FLOAT_PROFILE, IMAGE_FLOAT_WORDS

ROOT = Path(__file__).resolve().parents[1]
CASE = CASES[0]
MASK = 0xffffffff


class P12TextureCases(unittest.TestCase):
    def test_case_registration_and_parameters(self):
        self.assertEqual(CASES, (CASE,))
        self.assertEqual(parameters(CASE), dict(case=CASE, groups=1, local=LOCAL, viewBytes=8192))
        with self.assertRaises(ValueError):
            parameters('p12-shared-coexist')

    def test_shader_declares_sixteen_textures_and_four_of_each_output(self):
        source = shader()
        self.assertEqual(source.count('uniform highp usamplerBuffer'), TEXTURE_COUNT)
        self.assertEqual(source.count('buffer Output%d {uint v[];}' % 0), 1)
        for index in range(TEXTURE_COUNT):
            self.assertIn('layout(binding=%d) uniform highp usamplerBuffer t%d;\n' % (index, index), source)
            self.assertIn('t^=texelFetch(t%d,int(id)).x;\n' % index, source)
        for index in range(4):
            self.assertIn('layout(std430,binding=%d) buffer Output%d {uint v[];} out%d;\n' %
                          (index, index, index), source)
            self.assertIn('layout(r32ui,binding=%d) writeonly uniform highp uimageBuffer image%d;\n' %
                          (index, index), source)
            self.assertIn('out%d.v[id]=t;\n' % index, source)
            self.assertIn('imageStore(image%d,int(id),uvec4(t));\n' % index, source)
        self.assertIn('layout(local_size_x=128,local_size_y=1,local_size_z=1) in;\n', source)
        self.assertIn('#extension GL_EXT_texture_buffer : require\n', source)
        self.assertIn('memoryBarrierBuffer();memoryBarrierImage();\n}\n', source)
        self.assertIsNone(re.search(r'\^\d+u', source), 'per-unit XOR constants exceed the word budget')
        for forbidden in ('atomic', 'shared ', 'uniform Block', 'if('):
            self.assertNotIn(forbidden, source)
        with tempfile.TemporaryDirectory(prefix='p12-texture-shader-') as temporary:
            out = Path(temporary)
            write_sources(out)
            self.assertEqual((out / (CASE + '.comp')).read_text(), source)

    def test_texel_fetch_value_is_deterministic_and_lane_dependent(self):
        for lane in range(LOCAL):
            expected = 0
            for unit in range(TEXTURE_COUNT):
                expected ^= texture_value(unit, lane)
            self.assertEqual(texel_fetch_value(lane), expected & MASK)
            self.assertEqual(texel_fetch_value(lane), texel_fetch_value(lane))
        self.assertEqual(len({texel_fetch_value(lane) for lane in range(LOCAL)}), LOCAL)
        self.assertNotEqual(texel_fetch_value(0), texel_fetch_value(1))
        self.assertTrue(all(0 <= texel_fetch_value(lane) <= MASK for lane in range(LOCAL)))

    def test_each_texture_unit_carries_distinct_data(self):
        for texel in (0, 17, 2047):
            values = [texture_value(unit, texel) for unit in range(TEXTURE_COUNT)]
            self.assertEqual(len(set(values)), TEXTURE_COUNT)
            self.assertTrue(all(0 <= value <= MASK for value in values))


def view(pa, binding, count, allocation):
    return dict(globalBinding=binding, allocationPhysical=pa, allocationBytes=allocation,
                offset=0, bytes=count)


def texture_view(pa, unit):
    slot = 3 + unit
    return dict(globalBinding=slot, allocationPhysical=pa, allocationBytes=TEXTURE_BACKING_BYTES,
                offset=0, bytes=TEXTURE_VIEW_BYTES,
                view=dict(slot=slot, target=5, format=14, bufferTexels=2048, bufferBytes=8192))


def fixture():
    allocations = {0x1000: bytearray(4096), 0x41000: bytearray(4096),
                   0x42000: bytearray(4096), 0x70000: bytearray(4096),
                   0x80000: bytearray(4096), 0xe1000: bytearray(4096)}
    for address in (0x30000, 0x32000, 0x34000, 0x36000):
        allocations[address] = bytearray(struct.pack('<I', GUARD) * 2048)
    for index in range(4):
        allocations[0x90000 + 0x1000 * index] = bytearray(b'\x69' * 4096)
    for unit in range(TEXTURE_COUNT):
        texels = struct.pack('<%uI' % (TEXTURE_VIEW_BYTES // 4),
                             *(texture_value(unit, texel) for texel in range(TEXTURE_VIEW_BYTES // 4)))
        allocations[0xa0000 + 0x2000 * unit] = bytearray(texels)
    allocations[0x120000] = bytearray(UNIFORM_BYTES)
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

    context = 2
    outputs = [0x30000 + 0x2000 * index for index in range(4)]
    images = [0x90000 + 0x1000 * index for index in range(4)]
    textures = [0xa0000 + 0x2000 * unit for unit in range(TEXTURE_COUNT)]
    storage_root, image_root, uniform_root, texture_root = 0xb0c00000, 0xb1000000, 0xb1800000, 0xb1c00000
    map_page(storage_root, 0x41000, 15)
    map_page(image_root, 0x80000, 15)
    map_page(texture_root, 0xe1000, 15)
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
        descriptor[37:40] = [4, LOCAL, 512]
        struct.pack_into('<64I', allocations[0x80000], unit * 256, *descriptor)
        map_page(base, physical, 15)
    low = sum(index << (5 * index) for index in range(6))
    high = sum(index << (5 * (index - 6)) for index in range(6, 12))
    root_record = [0] * 16
    root_record[:15] = [0x47555201, 64, 1, context, 0, 0, 2, 0xfff, low, high,
                        uniform_root + 64, uniform_root + 64 + 24 * 64, 0, 0, 0]
    struct.pack_into('<16I', allocations[0x120000], 0, *root_record)
    for index in range(12):
        base = uniform_root + 4096 + 16384 * index
        record = [0] * 16
        record[:15] = [0x47554201, 64, 1, context, 0, 0, index, 4, base, base + 16384, 0,
                       base, base + 16384, 0, 1]
        struct.pack_into('<16I', allocations[0x120000], 64 + 64 * index, *record)
    for offset in range(0, UNIFORM_BYTES, 4096):
        map_page(uniform_root + offset, 0x120000 + offset, 3)
    root_table = tables[uniform_root >> 22]
    for index in range(UNIFORM_BYTES // 4096):
        pte = (((uniform_root >> 12) + index) & 1023) * 4
        value = struct.unpack_from('<I', allocations[root_table], pte)[0]
        struct.pack_into('<I', allocations[root_table], pte, value & ~0x80)
    map_page(0x80400000, 0x70000, 15)
    description = dict(schema=SCHEMA, sourceCase=CASE, groups=1, local=LOCAL, viewBytes=8192,
        guard=GUARD, context=context, kernargPhysical=0x42000, kernargBytes=4096,
        storageBindings=[0, 1, 2, 3],
        outputs=[view(address, index, 8192, 8192) for index, address in enumerate(outputs)],
        images=[view(address, index, 512, 4096) for index, address in enumerate(images)],
        imageBindings=[0, 1, 2, 3], imageRoot=image_root, imagePhysical=0x80000, imageBytes=1024,
        textures=[texture_view(address, unit) for unit, address in enumerate(textures)],
        textureBindings=[3 + unit for unit in range(TEXTURE_COUNT)], textureRoot=texture_root,
        texturePhysical=0xe1000, textureBytes=4096)
    discovery = [(word['address'], word['value']) for word in IMAGE_FLOAT_WORDS] + [
        (0, 0x47550002), (0x60, (1 << 6) | (1 << 22) | (1 << 23) | (1 << 25) | (1 << 29) | (128 << 8)),
        (0x68, 0x160), (0x320, 1048576), (0x158, 0x02020810), (0x494, 0x47544d01), (0x43c, 0x47534d01),
        (0x3fc, 0x47494d01), (0x4bc, 0x47414d01), (0x460, 0x47554d01)]
    writable = sorted([(0x42000, 0x43000)] +
                      [(address, address + 8192) for address in outputs] +
                      [(address, address + 512) for address in images])
    registers = {0xc8: 0, 0xd0: 0x80800000, 0x14c: 0x80800001, 0x150: 0x80800001,
                 0xd4: 1, 0xd8: 1, 0xdc: 1, 0xe0: 128, 0xe4: 1, 0xe8: 1}
    for base, root, size in ((0x420, storage_root, 256), (0x3e0, image_root, 1024)):
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

    return allocations, description, capture, outputs, images, textures


def emit(path, allocations):
    with path.open('wb') as stream:
        pages = sum(len(data) // 4096 for data in allocations.values())
        stream.write(b'OGMEM001' + struct.pack('<II', len(allocations), pages))
        for address, data in sorted(allocations.items()):
            stream.write(struct.pack('<QQII', address, address + len(data), 0, 0))
        for address, data in sorted(allocations.items()):
            for offset in range(0, len(data), 4096):
                stream.write(struct.pack('<Q', address + offset) + data[offset:offset + 4096])


class P12TextureOracle(unittest.TestCase):
    def test_recipe_accepts_original_allocations_and_maps_texture_slots(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='p12-texture-recipe-', dir=parent) as temporary:
            allocations, description, capture, outputs, images, textures = fixture()
            before = Path(temporary) / 'before'
            emit(before, allocations)
            with SparseMemory(before) as memory:
                actual_outputs, actual_images, actual_textures, slots = recipe(memory, description)
            self.assertEqual(actual_outputs, outputs)
            self.assertEqual(actual_images, images)
            self.assertEqual(actual_textures, textures)
            self.assertEqual(slots, {3 + unit: address for unit, address in enumerate(textures)})

    def test_fields_and_recipe_reject_foreign_or_missing_fields(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='p12-texture-gates-', dir=parent) as temporary:
            allocations, description, capture, outputs, images, textures = fixture()
            before = Path(temporary) / 'before'
            emit(before, allocations)
            with SparseMemory(before) as memory:
                self.assertEqual(FIELDS, {'schema', 'sourceCase', 'groups', 'local', 'viewBytes',
                    'guard', 'context', 'kernargPhysical', 'kernargBytes', 'outputs',
                    'storageBindings', 'images', 'imageBindings', 'imageRoot', 'imagePhysical',
                    'imageBytes', 'textures', 'textureBindings', 'textureRoot', 'texturePhysical',
                    'textureBytes'})
                for key in ('schema', 'sourceCase', 'groups', 'local', 'viewBytes', 'guard',
                            'textures', 'textureBindings', 'textureRoot', 'texturePhysical',
                            'textureBytes', 'outputs', 'images'):
                    wrong = copy.deepcopy(description)
                    del wrong[key]
                    with self.subTest(missing=key), self.assertRaises(ValueError):
                        recipe(memory, wrong)
                wrong = copy.deepcopy(description)
                wrong['schema'] = 'opengpu-compute-p12-shared-oracle-v1'
                with self.assertRaises(ValueError):
                    recipe(memory, wrong)
                wrong = copy.deepcopy(description)
                wrong['extra'] = 1
                with self.assertRaises(ValueError):
                    recipe(memory, wrong)
                wrong = copy.deepcopy(description)
                wrong['local'] = 4
                with self.assertRaises(ValueError):
                    recipe(memory, wrong)
                wrong = copy.deepcopy(description)
                wrong['viewBytes'] = 16384
                with self.assertRaises(ValueError):
                    recipe(memory, wrong)
                wrong = copy.deepcopy(description)
                wrong['textureBindings'] = list(range(TEXTURE_COUNT))
                with self.assertRaises(ValueError):
                    recipe(memory, wrong)
                wrong = copy.deepcopy(description)
                wrong['textures'][7]['view']['target'] = 4
                with self.assertRaises(ValueError):
                    recipe(memory, wrong)
                wrong = copy.deepcopy(description)
                wrong['textures'][7]['view']['bufferTexels'] = 1024
                with self.assertRaises(ValueError):
                    recipe(memory, wrong)
                wrong = copy.deepcopy(description)
                wrong['textures'][7]['view']['slot'] = 19
                with self.assertRaises(ValueError):
                    recipe(memory, wrong)

    def test_ready_capture_and_returned_bytes(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='p12-texture-oracle-', dir=parent) as temporary:
            directory = Path(temporary)
            before, after = directory / 'before', directory / 'after'
            allocations, description, capture, outputs, images, textures = fixture()
            emit(before, allocations)
            with SparseMemory(before) as memory:
                ready(memory, description, capture(), owner_profile=IMAGE_FLOAT_PROFILE)
            good = copy.deepcopy(allocations)
            data = struct.pack('<%uI' % LOCAL, *(texel_fetch_value(lane) for lane in range(LOCAL)))
            for address in outputs + images:
                good[address][0:LOCAL * 4] = data
            emit(after, good)
            with SparseMemory(before) as initial, SparseMemory(after) as returned:
                changed = returned_outputs(initial, returned, description)
                self.assertEqual([first for first, _ in changed], outputs + images)
                self.assertTrue(all(value == data for _, value in changed))
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
                        returned_outputs(initial, returned, description)

    def test_ready_rejects_foreign_capture_and_owner(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='p12-texture-ready-gates-', dir=parent) as temporary:
            before = Path(temporary) / 'before'
            allocations, description, capture, outputs, images, textures = fixture()
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
                wrong = copy.deepcopy(description)
                wrong['textures'][0]['view']['format'] = 3
                with self.assertRaises(ValueError):
                    ready(memory, wrong, capture(), owner_profile=IMAGE_FLOAT_PROFILE)


if __name__ == '__main__':
    unittest.main()
