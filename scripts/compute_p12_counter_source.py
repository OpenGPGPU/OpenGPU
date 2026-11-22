"""Strict P12 full-resource counter oracle: actual GEMs, READY and returned bytes."""
import struct

from compute_p12_counter_cases import COMBINED, COUNTER_OFFSET, DEFAULT_COMPONENTS
from compute_p12_texture_source import TEXTURE_FIELDS, texel_fetch_value, texture_ready, texture_recipe

from compute_counter_source import translate
from compute_owner_protocol import required_flags, profile_bytes, uint
from compute_owner_oracle import allocation_window
from owner_resource_contract import SAMPLE_FACTORY_CONTRACTS, IMAGE_FLOAT_PROFILE, FULL_COMPUTE_SCHEMA

SCHEMA = 'opengpu-compute-p12-counter-oracle-v1'
GROUPS = 1
LOCAL = 128
GUARD = 0xdeadbeef
SEED = 0xfffffffe
COUNTER_COUNT = 8
COUNTER_BYTES = 32
INCREMENTS = LOCAL // COUNTER_COUNT
OUTPUT_BYTES = 8192
IMAGE_VIEW_BYTES = 512
IMAGE_BACKING_BYTES = 4096
UBO_COUNT = 12
UBO_BYTES = 16384
UNIFORM_ROOT = 0xb1800000
UNIFORM_BYTES = 4096 + UBO_COUNT * UBO_BYTES
FIELDS = {'schema', 'sourceCase', 'groups', 'local', 'viewBytes', 'guard', 'seed', 'count',
          'counterHandle', 'context', 'descriptorPhysical', 'descriptorRoot', 'kernargPhysical', 'kernargBytes',
          'counter', 'outputs', 'storageBindings', 'images', 'imageBindings', 'imageRoot', 'imagePhysical',
          'imageBytes', 'ubos', 'uboBindings', 'uniformPhysical'}


def require(ok, message):
    if not ok:
        raise ValueError('P12 counter coexist source: ' + message)


def uniform_value(slot, word):
    return ((0x4ab319d7 + 0x01020304 + slot * 0x00718293 + word * 0x9e3779b9) ^
            ((slot + 13) << (word % 23))) & 0xffffffff


def seed_oracle():
    seed = 0
    for block in range(UBO_COUNT):
        seed = ((seed * 33) ^ uniform_value(block + 1, 0) ^ uniform_value(block + 1, 4095)) & 0xffffffff
    return seed


def uniform_block(slot):
    return struct.pack('<%uI' % (UBO_BYTES // 4),
                       *(uniform_value(slot, word) for word in range(UBO_BYTES // 4)))


def uniform_slots():
    return {uniform_block(slot): slot for slot in range(1, UBO_COUNT + 1)}


def snapshot_seed(memory, description):
    uniform_physical = uint(description['uniformPhysical'])
    seed = 0
    for index in range(UBO_COUNT):
        block = uniform_physical + 4096 + UBO_BYTES * index
        word0 = struct.unpack('<I', memory.read(block, 4))[0]
        wordlast = struct.unpack('<I', memory.read(block + 4095 * 4, 4))[0]
        seed = ((seed * 33) ^ word0 ^ wordlast) & 0xffffffff
    return seed


def counter_seed(index):
    return (SEED + index * 0x10203) & 0xffffffff


def counter_old_values(seed):
    return [(seed + step) & 0xffffffff for step in range(INCREMENTS)]


def recipe(memory, value):
    require(type(value) is dict, 'invalid P12 counter description')
    from compute_p12_counter_cases import parameters
    case = parameters(value.get('sourceCase'))['case']
    fields = FIELDS | TEXTURE_FIELDS if case == COMBINED else FIELDS
    require(set(value) == fields, 'foreign or missing P12 counter fields')
    required_flags(value, dict(schema=SCHEMA, sourceCase=case, groups=GROUPS,
        local=LOCAL, viewBytes=OUTPUT_BYTES, guard=GUARD, seed=SEED, count=COUNTER_COUNT))
    require(0 < uint(value['counterHandle']) < 512 and 0 < uint(value['context']) < 512,
            'invalid original P12 counter owner')
    source = allocation_window(memory, value['counter'], COUNTER_BYTES, 0)
    required_flags(value['counter'], dict(offset=COUNTER_OFFSET, allocationBytes=8192))
    bindings = value['storageBindings']
    require(type(bindings) is list and all(type(i) is int for i in bindings) and sorted(bindings) == [0, 1, 2, 3],
            'actual Mesa storage bindings must be a complete permutation')
    require(type(value['outputs']) is list and len(value['outputs']) == 4, 'four original SSBOs required')
    outputs = []
    allocations = [(source - value['counter']['offset'], source - value['counter']['offset'] + 8192)]
    for binding, view in enumerate(value['outputs']):
        first = allocation_window(memory, view, OUTPUT_BYTES, binding)
        required_flags(view, dict(offset=0, allocationBytes=OUTPUT_BYTES))
        require(memory.read(first, OUTPUT_BYTES) == struct.pack('<I', GUARD) * (OUTPUT_BYTES // 4),
                'P12 counter output precomputed or unguarded')
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
    require(uint(value['uniformPhysical']) > 0 and value['uniformPhysical'] % 4096 == 0,
            'P12 uniform snapshot physical address differs')
    ubo_bindings = value['uboBindings']
    require(type(ubo_bindings) is list and all(type(i) is int for i in ubo_bindings) and
            ubo_bindings == [24 + i for i in range(UBO_COUNT)], 'original UBO bindings must be 24..35')
    require(type(value['ubos']) is list and len(value['ubos']) == UBO_COUNT, 'twelve original UBOs required')
    slots = uniform_slots()
    used = set()
    ubos = []
    for index, view in enumerate(value['ubos']):
        first = allocation_window(memory, view, UBO_BYTES, 24 + index)
        required_flags(view, dict(offset=0, allocationBytes=UBO_BYTES))
        slot = slots.get(memory.read(first, UBO_BYTES))
        require(slot is not None and slot not in used, 'original P12 UBO bytes or guard differ')
        used.add(slot)
        ubos.append(first)
        allocations.append((first, first + UBO_BYTES))
    require(used == set(range(1, UBO_COUNT + 1)), 'original P12 UBO blocks are not a complete permutation')
    if case == COMBINED:
        texture_recipe(memory, value, allocations)
    allocations.sort()
    require(all(a[1] <= b[0] for a, b in zip(allocations, allocations[1:])), 'P12 original GEM aliases another resource')
    original = bytearray(b'\x5a' * 8192)
    for index in range(COUNTER_COUNT):
        struct.pack_into('<I', original, COUNTER_OFFSET + index * 4, counter_seed(index))
    require(memory.read(source - value['counter']['offset'], 8192) == original,
            'P12 counter original source/guard initialization differs')
    for key in ('descriptorPhysical', 'descriptorRoot', 'kernargPhysical', 'kernargBytes'):
        uint(value[key])
    require(value['descriptorPhysical'] % 64 == value['descriptorRoot'] % 64 == 0 and
            0 < value['kernargBytes'] <= 4096, 'invalid descriptor/kernarg view')
    return source, outputs, images, ubos


def final_counter_bytes():
    return struct.pack('<%uI' % COUNTER_COUNT,
                        *((counter_seed(index) + INCREMENTS) & 0xffffffff for index in range(COUNTER_COUNT)))


def combined_tags():
    seed = uniform_value(0, 0) ^ uniform_value(0, DEFAULT_COMPONENTS - 1)
    for slot in range(1, UBO_COUNT + 1):
        seed ^= uniform_value(slot, 4095)
    return [seed ^ (lane * 3) ^ texel_fetch_value(lane) for lane in range(LOCAL)]


def verify_write_denial(initial, returned, description):
    from compute_owner_oracle import verify_byte_changes
    require(description.get('sourceCase') == COMBINED, 'wrong combined permission recipe')
    source, outputs, _, _ = recipe(initial, description)
    tags = combined_tags()
    old_values = [set(counter_old_values(counter_seed(index))) for index in range(COUNTER_COUNT)]
    written = {}
    expected = [(source, final_counter_bytes())]
    for output in outputs:
        first = output + OUTPUT_BYTES - LOCAL * 4
        data = returned.read(first, LOCAL * 4)
        for lane, value in enumerate(struct.unpack('<%uI' % LOCAL, data)):
            if value == GUARD:
                continue
            old = value ^ tags[lane]
            require(old in old_values[lane % COUNTER_COUNT] and written.setdefault(lane, old) == old,
                    'combined write denial published an invalid or inconsistent SSBO lane')
        expected.append((first, data))
    for index in range(COUNTER_COUNT):
        values = [old for lane, old in written.items() if lane % COUNTER_COUNT == index]
        require(len(values) == len(set(values)), 'combined write denial duplicated counter old values')
    require(written, 'combined write denial preceded all SSBO stores')
    verify_byte_changes(initial, returned, expected)


def returned_outputs(initial, returned, description):
    source, outputs, images, ubos = recipe(initial, description)
    combined = description['sourceCase'] == COMBINED
    if combined:
        tags = combined_tags()
        outputs = [first + OUTPUT_BYTES - LOCAL * 4 for first in outputs]
    else:
        seed = seed_oracle()
    olds = [counter_old_values(counter_seed(index)) for index in range(COUNTER_COUNT)]
    result = []
    for addresses, multiplier in ((outputs, 0x10203), (images, 0x20406)):
        for binding, first in enumerate(addresses):
            data = returned.read(first, LOCAL * 4)
            actual = struct.unpack('<%uI' % LOCAL, data)
            if combined:
                require(not result or data == result[0][1], 'P12 combined output lanes differ across views')
                actual = tuple(value ^ tag for value, tag in zip(actual, tags))
            for index, values in enumerate(olds):
                expected = sorted(values) if combined else sorted(old ^ seed ^ (binding * multiplier) for old in values)
                require(sorted(actual[index::COUNTER_COUNT]) == expected,
                        'P12 counter returned values differ for slot %u' % index)
            result.append((first, data))
    final = final_counter_bytes()
    require(returned.read(source, COUNTER_BYTES) == final, 'P12 counter final values or wrap differ')
    result.append((source, final))
    return result


def ready(memory, description, data, *, owner_profile=IMAGE_FLOAT_PROFILE, deny_image_write=False):
    """Bind READY metadata to full112 float discovery, MMIO, GSB1/GUM1 and PTEs."""
    require(owner_profile == IMAGE_FLOAT_PROFILE and owner_profile in SAMPLE_FACTORY_CONTRACTS,
            'P12 counter coexist requires the complete float owner')
    stage, expected_words = SAMPLE_FACTORY_CONTRACTS[owner_profile]
    source, outputs, images, ubos = recipe(memory, description)
    require(data[:8] == b'OGCMP003', 'P12 counter requires full112 capture with original inactive contexts')
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
    from owner_resource_contract import FULL_COMPUTE_SCHEMA
    profile_bytes(dict(schema=FULL_COMPUTE_SCHEMA, ownerProfile=owner_profile, ownerResourceStage=stage,
        ownerResourceVersion=2, fullResourceQualification=False, resetCount=1, sourceQualificationRequired=True,
        gpuExecution=False, clockQualified=False, fpsQualified=False,
        words=[dict(address=a, value=v) for a, v in discovery[:112]],
        registers=[dict(address=a, value=v) for a, v in discovery[112:]]))
    count, = take('<I')
    require(count == (6 if deny_image_write else 10), 'P12 counter capture writable view count differs')
    writable = [take('<QQ') for _ in range(count)]
    arg, argbytes = description['kernargPhysical'], description['kernargBytes']
    require(any(a <= arg < arg + argbytes <= b for a, b, _ in memory.ranges), 'kernarg outside original allocation')
    expected = [(arg, arg + argbytes), (source, source + COUNTER_BYTES)]
    expected += [(first, first + OUTPUT_BYTES) for first in outputs]
    if not deny_image_write:
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
    root, context = description['descriptorRoot'], description['context']
    require([r.get(0x4a0 + 4 * i) for i in range(7)] == [257, root, root + 64, 0, context, 0, 0],
            'P12 GAM1 counter context/view was changed')
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
    physical = translate(memory, satp, root, 15)
    require(physical == description['descriptorPhysical'], 'P12 counter descriptor binding differs')
    va = 0xb0000000 + 7 * 0x400000 + (COUNTER_OFFSET & 4095)
    require(translate(memory, satp, va, 15) == source and
            translate(memory, satp, va + COUNTER_BYTES - 1, 15) == source + COUNTER_BYTES - 1,
            'P12 counter original source mapping differs')
    record = [0x47534201, 64, 1, context, 0, 0, 0, 1, 1, 1, va, va + COUNTER_BYTES, 0,
              va & ~63, (va + COUNTER_BYTES + 63) & ~63, 0]
    require(memory.read(physical, 64) == struct.pack('<16I', *record), 'P12 GSB1 permissions/bounds differ')
    require(translate(memory, satp, r.get(0xd0, 0), 15) == arg, 'P12 kernarg mapping differs')
    table = r.get(0x424, 0)
    require([r.get(0x420 + 4 * i) for i in range(7)] == [257, table, table + 256, 0, context, 0, 0],
            'P12 four-storage command context differs')
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
        if deny_image_write:
            descriptor[10:12] = [1, 0]
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
    combined = description['sourceCase'] == COMBINED
    uniform_bytes = UNIFORM_BYTES + (4096 if combined else 0)
    uniform_physical = translate(memory, satp, uniform_root, 3)
    require(uniform_physical > 0 and uniform_physical % 4096 == 0 and
            uniform_physical == uint(description['uniformPhysical']) and
            translate(memory, satp, uniform_root + uniform_bytes - 1, 3) == uniform_physical + uniform_bytes - 1 and
            any(a <= uniform_physical < uniform_physical + uniform_bytes <= b for a, b, _ in memory.ranges),
            'P12 uniform snapshot is not its original read-only private mapping')
    low = sum(index << (5 * index) for index in range(6))
    high = sum(index << (5 * (index - 6)) for index in range(6, UBO_COUNT))
    root_record = [0] * 16
    root_record[:15] = [0x47555201, 64, 1, context, 0, 0, 2, 0xfff, low, high,
                        uniform_root + 64, uniform_root + 64 + 24 * 64, 0, 0, 0]
    if combined:
        root_record[13:15] = [uniform_root + 64 + 24 * 64, 1]
    require(memory.read(uniform_physical, 64) == struct.pack('<16I', *root_record),
            'P12 GUR1 active mask/mapping differs')
    unused = (24 - UBO_COUNT) * 64
    require(memory.read(uniform_physical + 64 + UBO_COUNT * 64, unused) == bytes(unused),
            'P12 unused uniform descriptor rows are enabled')
    default_offset = 64 + 24 * 64
    if combined:
        base = uniform_root + UNIFORM_BYTES
        limit = base + DEFAULT_COMPONENTS * 4
        default_record = (0x47554201, 64, 1, context, 0, 0, 31, 4, base, limit, 0,
                          base, base + 4096, 0, 1, 0)
        require(memory.read(uniform_physical + default_offset, 64) == struct.pack('<16I', *default_record),
                'P12 default uniform descriptor differs')
        expected = struct.pack('<%uI' % DEFAULT_COMPONENTS,
                               *(uniform_value(0, word) for word in range(DEFAULT_COMPONENTS)))
        require(memory.read(uniform_physical + UNIFORM_BYTES, 4096) ==
                expected + bytes(4096 - len(expected)), 'P12 default uniform snapshot differs')
        default_offset += 64
        _, textures = texture_recipe(memory, description, [])
        texture_ready(memory, description, satp, r, textures)
    require(memory.read(uniform_physical + default_offset, 4096 - default_offset) ==
            bytes(4096 - default_offset), 'P12 unexpected uniform metadata')
    for index in range(UBO_COUNT):
        base = uniform_root + 4096 + UBO_BYTES * index
        record = [0] * 16
        record[:15] = [0x47554201, 64, 1, context, 0, 0, index, 4, base, base + UBO_BYTES, 0,
                       base, base + UBO_BYTES, 0, 1]
        actual = struct.unpack('<16I', memory.read(uniform_physical + 64 + 64 * index, 64))
        require(actual == tuple(record),
                'P12 GUB1 UBO descriptor differs')
    copied_blocks = [memory.read(uniform_physical + 4096 + UBO_BYTES * index, UBO_BYTES)
                     for index in range(UBO_COUNT)]
    original_blocks = [memory.read(ubo, UBO_BYTES) for ubo in ubos]
    require(sorted(copied_blocks) == sorted(original_blocks),
            'P12 copied UBO data differs from the bound GEMs')
