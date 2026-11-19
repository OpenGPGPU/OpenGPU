"""Bind original atomic-counter publication sources to captured mappings."""
import struct
from compute_owner_protocol import required_flags, uint, profile_bytes
from compute_counter_publication_cases import CASES, parameters, COUNTER_COUNT, COUNTER_OFFSET
from compute_indirect_oracle import capture, executable_physical
from compute_counter_source import translate
from owner_resource_contract import IMAGE_FLOAT_PROFILE

SCHEMA = 'opengpu-compute-counter-publication-oracle-v1'
VIEW_BYTES = 8192
COUNTER_BYTES = COUNTER_COUNT * 4


def require(ok, message):
    if not ok:
        raise ValueError('counter publication source: ' + message)


def recipe(memory, description):
    p = parameters(description.get('sourceCase'))
    expected = dict(
        schema=SCHEMA, sourceCase=p['case'], role=p['role'], epoch=p['epoch'],
        groups=1, local=1, viewBytes=VIEW_BYTES, guard=0xdeadbeef,
        expectedData=p['expected_data'], expectedFlag=p['expected_flag'],
        count=COUNTER_COUNT, offset=COUNTER_OFFSET)
    required_flags(description, expected)
    require(set(description) == set(expected) | {
        'context', 'counterHandle', 'counter', 'output', 'storageBinding',
        'descriptorPhysical', 'descriptorRoot', 'storagePhysical', 'storageRoot',
        'kernargPhysical', 'kernargBytes', 'codePhysical', 'codeBytes'},
        'foreign or missing counter publication recipe fields')
    require(0 < uint(description['context']) < 512 and 0 < uint(description['counterHandle']) < 512,
            'invalid original counter owner')
    require(uint(description['storageBinding']) == 0, 'original output binding differs')
    counter = description['counter']
    required_flags(counter, dict(globalBinding=0, allocationBytes=VIEW_BYTES,
                                 offset=COUNTER_OFFSET, bytes=COUNTER_BYTES))
    require(set(counter) == {
        'globalBinding', 'allocationPhysical', 'allocationBytes', 'offset', 'bytes'},
        'foreign original counter view fields')
    target = uint(counter['allocationPhysical'])
    require(target and not target & 4095 and
            any((a, b) == (target, target + VIEW_BYTES) for a, b, _ in memory.ranges),
            'counter GEM lacks its exact original physical allocation')
    output_view = description['output']
    required_flags(output_view, dict(globalBinding=0, allocationBytes=VIEW_BYTES, offset=0,
                                     bytes=VIEW_BYTES))
    require(set(output_view) == {
        'globalBinding', 'allocationPhysical', 'allocationBytes', 'offset', 'bytes'},
        'foreign original output view fields')
    output = uint(output_view['allocationPhysical'])
    require(output and not output & 4095 and
            any((a, b) == (output, output + VIEW_BYTES) for a, b, _ in memory.ranges),
            'output lacks its exact original physical allocation')
    require(target + VIEW_BYTES <= output or output + VIEW_BYTES <= target,
            'original counter GEM/output alias')
    if p['role'] == 'warm':
        initial = bytearray(b'\x6b' * VIEW_BYTES)
        struct.pack_into('<I', initial, COUNTER_OFFSET, 0)
        struct.pack_into('<I', initial, COUNTER_OFFSET + 4, 0)
        require(memory.read(target, VIEW_BYTES) == initial and
                memory.read(output, VIEW_BYTES) == b'\xa7' * VIEW_BYTES,
                'warm phase lost its original zero data/flag seed')
    return p, target, output


def ready(memory, description, data, code_words, profile):
    require(profile.get('ownerProfile') == IMAGE_FLOAT_PROFILE, 'exact complete float factory required')
    profile_bytes(profile)
    p, target, output = recipe(memory, description)
    _, args, writable, inactive, actions, staged = capture(data, 0, profile)
    require(args is None and [staged.get(0xd4 + 4 * n) for n in range(3)] == [1, 1, 1] and
            [staged.get(0xe0 + 4 * n) for n in range(3)] == [1, 1, 1],
            'original publication group/local shape differs')
    satp = staged.get(0x14c, 0)
    require(satp == staged.get(0x150), 'instruction/data namespace differs')
    arg = uint(description['kernargPhysical'])
    argbytes = uint(description['kernargBytes'])
    require(argbytes and translate(memory, satp, staged.get(0xd0, 0), 15) == arg and
            any(a <= arg < arg + argbytes <= b for a, b, _ in memory.ranges),
            'original kernarg mapping differs')
    context = description['context']
    root, physical = uint(description['descriptorRoot']), uint(description['descriptorPhysical'])
    require([staged.get(0x4a0 + 4 * n) for n in range(7)] == [257, root, root + 64, 0, context, 0, 0],
            'counter GAM1 context/view was changed')
    require(translate(memory, satp, root, 15) == physical, 'counter descriptor private mapping differs')
    source = target + COUNTER_OFFSET
    va = 0xb0000000 + 7 * 0x400000 + (COUNTER_OFFSET & 4095)
    require(translate(memory, satp, va, 15) == source and
            translate(memory, satp, va + COUNTER_BYTES - 1, 15) == source + COUNTER_BYTES - 1,
            'counter original source mapping differs')
    expected_descriptor = (0x47534201, 64, 1, context, 0, 0, 0, 1, 1, 1, va, va + COUNTER_BYTES, 0,
                           va & ~63, (va + COUNTER_BYTES + 63) & ~63, 0)
    require(memory.read(physical, 64) == struct.pack('<16I', *expected_descriptor),
            'counter GSB1 permissions/bounds differ')
    root, physical = uint(description['storageRoot']), uint(description['storagePhysical'])
    require([staged.get(0x420 + 4 * n) for n in range(7)] == [257, root, root + 256, 0, context, 0, 0],
            'original storage command/root identity differs')
    require(translate(memory, satp, root, 15) == physical, 'storage descriptor private mapping differs')
    storage_base = 0xcfffc000
    expected = (0x47534201, 64, 1, context, 0, 0, 0, 1, 1, 1,
                storage_base, storage_base + VIEW_BYTES, 0, storage_base, storage_base + VIEW_BYTES, 0)
    require(memory.read(physical, 64) == struct.pack('<16I', *expected) and
            translate(memory, satp, storage_base, 15) == output and
            translate(memory, satp, storage_base + VIEW_BYTES - 1, 15) == output + VIEW_BYTES - 1,
            'actual output descriptor or GEM mapping differs')
    require(memory.read(physical + 64, 192) == bytes(192), 'unused storage descriptors are enabled')
    import re
    require(code_words and all(re.fullmatch('[0-9a-f]{8}', word) for word in code_words),
            'invalid actual code words')
    code = b''.join(struct.pack('<I', int(word, 16)) for word in code_words)
    require(len(code) == uint(description['codeBytes'], 64) and
            memory.read(uint(description['codePhysical']), len(code)) == code,
            'compiler code snapshot differs')
    for offset in range(0, len(code), 4):
        address = executable_physical(memory, satp, staged.get(0xcc, 0) + offset)
        require(memory.read(address, 4) == code[offset:offset + 4],
                'staged executable differs from actual Mesa code')
    return target, output


def returned_outputs(initial, returned, description):
    p, target, output = recipe(initial, description)
    data_off = target + COUNTER_OFFSET + 4
    flag_off = target + COUNTER_OFFSET
    if p['role'] == 'warm':
        require(returned.read(data_off, 4) == struct.pack('<I', 0) and
                returned.read(flag_off, 4) == struct.pack('<I', 0) and
                returned.read(output, 8) == struct.pack('<II', 0, 0),
                'warm consumer observed non-zero publication words')
        return [(output, struct.pack('<II', 0, 0))]
    expected = struct.pack('<II', p['expected_data'], p['expected_flag'])
    require(returned.read(data_off, 4) + returned.read(flag_off, 4) == expected,
            'published data/flag words differ')
    require(returned.read(output, 8) == expected, 'publication output mirror differs')
    return [
        (data_off, struct.pack('<I', p['expected_data'])),
        (flag_off, struct.pack('<I', p['expected_flag'])),
        (output, expected),
    ]
