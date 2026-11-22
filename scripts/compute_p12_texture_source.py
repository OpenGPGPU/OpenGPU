"""Strict P12 full-resource texture oracle: actual GEMs, READY and returned bytes."""
import struct

from compute_counter_source import translate
from compute_owner_protocol import required_flags, uint, profile_bytes
from compute_owner_oracle import allocation_window
from owner_resource_contract import SAMPLE_FACTORY_CONTRACTS, IMAGE_FLOAT_PROFILE, FULL_COMPUTE_SCHEMA

SCHEMA = 'opengpu-compute-p12-texture-oracle-v1'
GROUPS = 1
LOCAL = 128
GUARD = 0xdeadbeef
TEXTURE_COUNT = 16
OUTPUT_BYTES = 8192
IMAGE_VIEW_BYTES = 512
IMAGE_BACKING_BYTES = 4096
TEXTURE_VIEW_BYTES = 8192
TEXTURE_BACKING_BYTES = 8192
TEXTURE_TEXELS = TEXTURE_VIEW_BYTES // 4
TEXTURE_SLOT_BASE = 3
IMAGE_FORMAT = 14
UBO_COUNT = 12
UBO_BYTES = 16384
UNIFORM_ROOT = 0xb1800000
UNIFORM_BYTES = 4096 + UBO_COUNT * UBO_BYTES
WINDOW_FIELDS = {'globalBinding', 'allocationPhysical', 'allocationBytes', 'offset', 'bytes'}
TEXTURE_VIEW_FIELDS = {'slot', 'target', 'format', 'bufferTexels', 'bufferBytes'}
FIELDS = {'schema', 'sourceCase', 'groups', 'local', 'viewBytes', 'guard', 'context',
          'kernargPhysical', 'kernargBytes', 'outputs', 'storageBindings', 'images', 'imageBindings',
          'imageRoot', 'imagePhysical', 'imageBytes', 'textures', 'textureBindings', 'textureRoot',
          'texturePhysical', 'textureBytes'}


def require(ok, message):
    if not ok:
        raise ValueError('P12 texture coexist source: ' + message)


def texture_value(unit, texel):
    return ((0x13579bdf + 0x01020304 + unit * 0x00718293 + texel * 0x9e3779b9) ^
            ((unit + 7) << (texel % 19))) & 0xffffffff


def texture_block(unit):
    return struct.pack('<%uI' % TEXTURE_TEXELS,
                       *(texture_value(unit, texel) for texel in range(TEXTURE_TEXELS)))


def texel_fetch_value(lane):
    value = 0
    for unit in range(TEXTURE_COUNT):
        value ^= texture_value(unit, lane)
    return value & 0xffffffff


def recipe(memory, value):
    require(type(value) is dict and set(value) == FIELDS, 'foreign or missing P12 texture fields')
    from compute_p12_texture_cases import parameters
    parameters(value.get('sourceCase'))
    required_flags(value, dict(schema=SCHEMA, sourceCase='p12-texture-coexist', groups=GROUPS,
        local=LOCAL, viewBytes=OUTPUT_BYTES, guard=GUARD))
    require(0 < uint(value['context']) < 512, 'invalid original P12 texture owner')
    bindings = value['storageBindings']
    require(type(bindings) is list and all(type(i) is int for i in bindings) and sorted(bindings) == [0, 1, 2, 3],
            'actual Mesa storage bindings must be a complete permutation')
    require(type(value['outputs']) is list and len(value['outputs']) == 4, 'four original SSBOs required')
    outputs = []
    allocations = []
    for binding, view in enumerate(value['outputs']):
        first = allocation_window(memory, view, OUTPUT_BYTES, binding)
        required_flags(view, dict(offset=0, allocationBytes=OUTPUT_BYTES))
        require(memory.read(first, OUTPUT_BYTES) == struct.pack('<I', GUARD) * (OUTPUT_BYTES // 4),
                'P12 texture output precomputed or unguarded')
        outputs.append(first)
        allocations.append((first, first + OUTPUT_BYTES))
    bindings = value['imageBindings']
    require(type(bindings) is list and all(type(i) is int for i in bindings) and sorted(bindings) == [0, 1, 2, 3],
            'actual Mesa image bindings must be a complete permutation')
    require(type(value['images']) is list and len(value['images']) == 4, 'four original buffer images required')
    images = []
    for binding, view in enumerate(value['images']):
        first = allocation_window(memory, view, IMAGE_VIEW_BYTES, binding)
        required_flags(view, dict(offset=0, allocationBytes=IMAGE_BACKING_BYTES))
        require(memory.read(first, IMAGE_BACKING_BYTES) == b'\x69' * IMAGE_BACKING_BYTES,
                'original P12 image backing precomputed or unguarded')
        images.append(first)
        allocations.append((first, first + IMAGE_BACKING_BYTES))
    require(uint(value['imageRoot']) == 0xb1000000 and uint(value['imagePhysical']) > 0 and
            value['imagePhysical'] % 4096 == 0 and uint(value['imageBytes']) == 1024,
            'P12 image descriptor table bounds differ')
    bindings = value['textureBindings']
    require(type(bindings) is list and all(type(i) is int for i in bindings) and
            bindings == [TEXTURE_SLOT_BASE + unit for unit in range(TEXTURE_COUNT)],
            'original texture slots must be 3..18 in native unit order')
    require(type(value['textures']) is list and len(value['textures']) == TEXTURE_COUNT,
            'sixteen original buffer textures required')
    textures = []
    texture_allocations = {}
    for unit, view in enumerate(value['textures']):
        slot = TEXTURE_SLOT_BASE + unit
        require(type(view) is dict and set(view) == WINDOW_FIELDS | {'view'},
                'foreign or missing original texture GEM view fields')
        require(type(view['view']) is dict and set(view['view']) == TEXTURE_VIEW_FIELDS,
                'foreign or missing original texture view fields')
        required_flags(view['view'], dict(slot=slot, target=5, format=IMAGE_FORMAT,
            bufferTexels=TEXTURE_TEXELS, bufferBytes=TEXTURE_VIEW_BYTES))
        window = {key: view[key] for key in WINDOW_FIELDS}
        first = allocation_window(memory, window, TEXTURE_VIEW_BYTES, slot)
        required_flags(window, dict(offset=0, allocationBytes=TEXTURE_BACKING_BYTES))
        require(memory.read(first, TEXTURE_BACKING_BYTES) == texture_block(unit),
                'original P12 texture texels precomputed or guarded differently')
        textures.append(first)
        texture_allocations[slot] = first
        allocations.append((first, first + TEXTURE_BACKING_BYTES))
    require(uint(value['textureRoot']) > 0 and value['textureRoot'] % 4096 == 0 and
            uint(value['texturePhysical']) > 0 and value['texturePhysical'] % 4096 == 0 and
            uint(value['textureBytes']) >= 4096 and value['textureBytes'] % 4096 == 0 and
            (value['texturePhysical'], value['texturePhysical'] + value['textureBytes']) in
            [(a, b) for a, b, _ in memory.ranges],
            'P12 texture descriptor table bounds differ')
    allocations.sort()
    require(all(a[1] <= b[0] for a, b in zip(allocations, allocations[1:])), 'P12 original GEM aliases another resource')
    for key in ('kernargPhysical', 'kernargBytes'):
        uint(value[key])
    require(0 < value['kernargBytes'] <= 4096, 'invalid kernarg view')
    return outputs, images, textures, texture_allocations


def returned_outputs(initial, returned, description):
    outputs, images, textures, texture_allocations = recipe(initial, description)
    expected = struct.pack('<%uI' % LOCAL, *(texel_fetch_value(lane) for lane in range(LOCAL)))
    result = []
    for first in outputs + images:
        data = returned.read(first, LOCAL * 4)
        require(data == expected, 'P12 texture SSBO/image returned value differs')
        result.append((first, data))
    return result


def ready(memory, description, data, *, owner_profile=IMAGE_FLOAT_PROFILE):
    """Bind READY metadata to full112 float discovery, MMIO, GSM1/GUM1 and PTEs."""
    require(owner_profile == IMAGE_FLOAT_PROFILE and owner_profile in SAMPLE_FACTORY_CONTRACTS,
            'P12 texture coexist requires the complete float owner')
    stage, expected_words = SAMPLE_FACTORY_CONTRACTS[owner_profile]
    outputs, images, textures, texture_allocations = recipe(memory, description)
    require(data[:8] == b'OGCMP003', 'P12 texture requires full112 capture with original inactive contexts')
    cursor = 8

    def take(fmt):
        nonlocal cursor
        size = struct.calcsize(fmt)
        require(cursor + size <= len(data), 'truncated P12 capture')
        result = struct.unpack_from(fmt, data, cursor)
        cursor += size
        return result

    budget, hold, command, count = take('<QIII')
    require(0 < budget <= 1000000000 and 8 <= hold <= 4096 and 0 < command < 256 and count == 122,
            'P12 capture bounds or complete factory differ')
    discovery = [take('<II') for _ in range(count)]
    require(discovery[:112] == [(word['address'], word['value']) for word in expected_words],
            'P12 needs exact qualified final112 float factory')
    profile_bytes(dict(schema=FULL_COMPUTE_SCHEMA, ownerProfile=owner_profile, ownerResourceStage=stage,
        ownerResourceVersion=2, fullResourceQualification=False, resetCount=1, sourceQualificationRequired=True,
        gpuExecution=False, clockQualified=False, fpsQualified=False,
        words=[dict(address=a, value=v) for a, v in discovery[:112]],
        registers=[dict(address=a, value=v) for a, v in discovery[112:]]))
    count, = take('<I')
    require(count == 9, 'P12 texture capture writable view count differs')
    writable = [take('<QQ') for _ in range(count)]
    arg, argbytes = description['kernargPhysical'], description['kernargBytes']
    require(any(a <= arg < arg + argbytes <= b for a, b, _ in memory.ranges), 'kernarg outside original allocation')
    expected = [(arg, arg + argbytes)]
    expected += [(first, first + OUTPUT_BYTES) for first in outputs]
    expected += [(first, first + IMAGE_VIEW_BYTES) for first in images]
    expected.sort()
    require(writable == expected and all(a[1] <= b[0] for a, b in zip(expected, expected[1:])),
            'P12 capture writable windows widened, aliased or missing')
    count, = take('<I')
    require(count == 1, 'P12 compute requires its separate inactive graphics context')
    inactive_context, inactive_satp = take('<II')
    require(0 < inactive_context < 512 and inactive_context != description['context'], 'inactive context identity differs')
    count, = take('<I')
    require(0 < count <= 65536, 'invalid P12 MMIO action count')
    staged, submissions, last_submit, satps = {}, [], None, set()
    for action in range(count):
        kind, address, value, mask = take('<IIII')
        if kind == 1:
            require(mask == 0, 'masked P12 write')
            staged[address] = value
            if address in (0x14c, 0x150) and value & 0x80000000:
                satps.add(value)
            if address == 0x11c:
                require(value == 1, 'P12 submit must publish one command')
                submissions.append(dict(staged))
                last_submit = action
    require(cursor == len(data) and submissions and submissions[-1].get(0xc8) == 0 and
            last_submit == count - 1 and all(s.get(0xc8) == 5 for s in submissions[:-1]),
            'P12 capture requires one final kernel after only publication invalidates')
    r = submissions[-1]
    require([r.get(0xd4 + 4 * i) for i in range(3)] == [GROUPS, 1, 1] and
            [r.get(0xe0 + 4 * i) for i in range(3)] == [LOCAL, 1, 1],
            'P12 grid differs from its original recipe')
    satp = r.get(0x14c, 0)
    require(r.get(0x150) == satp, 'P12 instruction/data owners differ')
    inactive_root = (inactive_satp & 0x3fffff) << 12
    inactive_asid = (inactive_satp >> 22) & 511
    require(inactive_satp & 0x80000000 and inactive_asid and
            all(((active >> 22) & 511) != inactive_asid and ((active & 0x3fffff) << 12) != inactive_root
                for active in satps), 'inactive context aliases an active owner ASID/root')
    require(any(a == inactive_root and b == inactive_root + 4096 for a, b, _ in memory.ranges) and
            not any(inactive_root < b and a < inactive_root + 4096 for a, b in expected) and
            memory.read(inactive_root, 4096) == bytes(4096),
            'inactive graphics context is not an original separate empty root')
    require(translate(memory, satp, r.get(0xd0, 0), 15) == arg, 'P12 kernarg mapping differs')
    table = r.get(0x424, 0)
    require([r.get(0x420 + 4 * i) for i in range(7)] == [257, table, table + 256, 0, description['context'], 0, 0],
            'P12 GSM1 four-storage command context differs')
    context = description['context']
    for binding, global_binding in enumerate(description['storageBindings']):
        output = outputs[global_binding]
        descriptor = struct.unpack('<16I', memory.read(translate(memory, satp, table + 64 * binding, 15), 64))
        base = 0xcfffc000 + binding * (128 * 1024 * 1024 + 4096)
        expected_record = (0x47534201, 64, 1, context, 0, 0, binding, 1, 1, 1,
                           base, base + OUTPUT_BYTES, 0, base, base + OUTPUT_BYTES, 0)
        require(descriptor == expected_record and translate(memory, satp, base, 15) == output and
                translate(memory, satp, base + OUTPUT_BYTES - 1, 15) == output + OUTPUT_BYTES - 1,
                'P12 SSBO descriptor/source mapping differs')
    table, physical = description['imageRoot'], description['imagePhysical']
    require([r.get(0x3e0 + 4 * i) for i in range(7)] == [257, table, table + 1024, 0, context, 0, 0],
            'P12 four-image command context differs')
    require(translate(memory, satp, table, 15) == physical and translate(memory, satp, table + 1023, 15) == physical + 1023,
            'P12 image descriptors are not their original private mapping')
    for unit, global_binding in enumerate(description['imageBindings']):
        base = 0xc7ffb000 if unit == 3 else 0x87ffd000 + unit * (128 * 1024 * 1024 + 4096)
        descriptor = [0] * 64
        descriptor[:14] = [0x47495501, 256, 1, context, 0, 0, unit, 1, 1, 4, 0, 1, 14, 14]
        descriptor[20] = 5
        descriptor[22] = base
        descriptor[23] = base + IMAGE_BACKING_BYTES
        descriptor[25] = base
        descriptor[26:30] = [1, 1, 1, 1]
        descriptor[37:40] = [4, LOCAL, IMAGE_VIEW_BYTES]
        require(memory.read(physical + unit * 256, 256) == struct.pack('<64I', *descriptor),
                'P12 R32UI buffer-image descriptor/access/bounds differ')
        output = images[global_binding]
        require(translate(memory, satp, base, 15) == output and
                translate(memory, satp, base + IMAGE_VIEW_BYTES - 1, 15) == output + IMAGE_VIEW_BYTES - 1,
                'P12 image original GEM mapping differs')
    uniform_root = r.get(0x444, 0)
    require(uniform_root == UNIFORM_ROOT and
            [r.get(0x440 + 4 * i) for i in range(8)] ==
            [257, uniform_root, context, 0, 0, r.get(0xd0, 0), r.get(0xd0, 0) + argbytes, 0],
            'P12 GUM1 uniform/private-prefix command differs')
    uniform_physical = translate(memory, satp, uniform_root, 3)
    require(uniform_physical > 0 and uniform_physical % 4096 == 0 and
            translate(memory, satp, uniform_root + UNIFORM_BYTES - 1, 3) == uniform_physical + UNIFORM_BYTES - 1 and
            any(a <= uniform_physical < uniform_physical + UNIFORM_BYTES <= b for a, b, _ in memory.ranges),
            'P12 uniform snapshot is not its original read-only private mapping')
    low = sum(index << (5 * index) for index in range(6))
    high = sum(index << (5 * (index - 6)) for index in range(6, UBO_COUNT))
    root_record = [0] * 16
    root_record[:15] = [0x47555201, 64, 1, context, 0, 0, 2, 0xfff, low, high,
                        uniform_root + 64, uniform_root + 64 + 24 * 64, 0, 0, 0]
    require(memory.read(uniform_physical, 64) == struct.pack('<16I', *root_record),
            'P12 GUR1 active mask/mapping differs')
    require(memory.read(uniform_physical + 64 + UBO_COUNT * 64,
                        4096 - 64 - UBO_COUNT * 64) == bytes(4096 - 64 - UBO_COUNT * 64),
            'P12 unused uniform descriptor rows are enabled')
    for index in range(UBO_COUNT):
        base = uniform_root + 4096 + UBO_BYTES * index
        record = [0] * 16
        record[:15] = [0x47554201, 64, 1, context, 0, 0, index, 4, base, base + UBO_BYTES, 0,
                       base, base + UBO_BYTES, 0, 1]
        actual = struct.unpack('<16I', memory.read(uniform_physical + 64 + 64 * index, 64))
        require(actual == tuple(record),
                'P12 GUB1 UBO descriptor differs')
