"""Bind original SSBO publication sources to captured mappings and bytes."""
import struct
from compute_owner_protocol import required_flags, uint, profile_bytes
from compute_storage_publication_cases import CASES, parameters
from compute_indirect_oracle import capture, executable_physical
from compute_counter_source import translate
from owner_resource_contract import IMAGE_FLOAT_PROFILE

SCHEMA = 'opengpu-compute-storage-publication-oracle-v1'


def require(ok, message):
    if not ok:
        raise ValueError('storage publication source: ' + message)


def recipe(memory, description):
    p = parameters(description.get('sourceCase'))
    expected = dict(
        schema=SCHEMA, sourceCase=p['case'], role=p['role'], epoch=p['epoch'],
        groups=1, local=1, viewBytes=8192, guard=0xdeadbeef,
        expectedData=p['expected_data'], expectedFlag=p['expected_flag'])
    required_flags(description, expected)
    require(set(description) == set(expected) | {
        'context', 'target', 'output', 'storageBindings',
        'descriptorPhysical', 'descriptorRoot', 'codePhysical', 'codeBytes'},
        'foreign or missing publication recipe fields')
    require(0 < uint(description['context']) < 512, 'invalid original context')
    bindings = description['storageBindings']
    require(type(bindings) is list and len(bindings) == 2 and
            all(type(b) is int for b in bindings) and sorted(bindings) == [0, 1],
            'invalid actual SSBO remapping')
    addresses = []
    for name, binding in (('target', 0), ('output', 1)):
        view = description[name]
        required_flags(view, dict(globalBinding=binding, allocationBytes=8192, offset=0, bytes=8192))
        require(set(view) == {'globalBinding', 'allocationPhysical', 'allocationBytes', 'offset', 'bytes'},
                'foreign original storage view fields')
        address = uint(view['allocationPhysical'])
        require(address and not address & 4095 and
                any((a, b) == (address, address + 8192) for a, b, _ in memory.ranges),
                'storage view lacks its exact original physical allocation')
        addresses.append(address)
    target, output = addresses
    require(target + 8192 <= output or output + 8192 <= target, 'original target/output alias')
    if p['role'] == 'warm':
        initial = bytearray(b'\x6b' * 8192)
        struct.pack_into('<I', initial, 4, 0)
        struct.pack_into('<I', initial, 17 * 4, 0)
        require(memory.read(target, 8192) == initial and memory.read(output, 8192) == b'\xa7' * 8192,
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
    root, physical = uint(description['descriptorRoot']), uint(description['descriptorPhysical'])
    context = description['context']
    require([staged.get(0x420 + 4 * n) for n in range(7)] == [257, root, root + 256, 0, context, 0, 0],
            'original storage command/root identity differs')
    require(translate(memory, satp, root, 15) == physical, 'storage descriptor private mapping differs')
    for unit, binding in enumerate(description['storageBindings']):
        address = (target, output)[binding]
        base = 0xcfffc000 + unit * (128 * 1024 * 1024 + 4096)
        expected = (0x47534201, 64, 1, context, 0, 0, unit, 1, 1, 1,
                    base, base + 8192, 0, base, base + 8192, 0)
        require(memory.read(physical + unit * 64, 64) == struct.pack('<16I', *expected) and
                translate(memory, satp, base, 15) == address and
                translate(memory, satp, base + 8191, 15) == address + 8191,
                'actual target/output descriptor or GEM mapping differs')
    require(memory.read(physical + 128, 128) == bytes(128), 'unused storage descriptors are enabled')
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
    data_off = target + 4
    flag_off = target + 17 * 4
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
