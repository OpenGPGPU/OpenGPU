"""Bind original storage atomic sources to captured mappings and actual bytes."""
import struct
from compute_owner_protocol import required_flags, uint, profile_bytes
from compute_storage_atomic_cases import CASES, parameters, invocations
from compute_atomic_order import linearization
from compute_indirect_oracle import capture, executable_physical
from compute_counter_source import translate
from owner_resource_contract import IMAGE_FLOAT_PROFILE

SCHEMA = 'opengpu-compute-storage-atomic-oracle-v1'


def require(ok, message):
    if not ok:
        raise ValueError('storage atomic source: ' + message)


def recipe(memory, description):
    p = parameters(description.get('sourceCase'))
    expected = dict(schema=SCHEMA, sourceCase=p['case'], groups=2, local=4, viewBytes=8192,
        guard=0xdeadbeef, seed=p['seed'], targetOffset=p['target_offset'], targetAccess=p['target_access'],
        resultStatus=p['result_status'], atomicOffset=p['access_offset'])
    required_flags(description, expected)
    require(set(description) == set(expected) | {'context', 'target', 'output', 'storageBindings',
            'descriptorPhysical', 'descriptorRoot', 'codePhysical', 'codeBytes'}, 'foreign or missing recipe fields')
    require(0 < uint(description['context']) < 512, 'invalid original context')
    bindings = description['storageBindings']
    require(type(bindings) is list and len(bindings) == 2 and
            all(type(b) is int for b in bindings) and sorted(bindings) == [0, 1], 'invalid actual SSBO remapping')
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
    initial = bytearray(b'\x6b' * 8192)
    struct.pack_into('<I', initial, p['target_offset'], p['seed'])
    require(memory.read(target, 8192) == initial and memory.read(output, 8192) == b'\xa7' * 8192,
            'original target/output seed or guard was changed/prefilled')
    return p, target, output


def ready(memory, description, data, code_words, profile):
    require(profile.get('ownerProfile') == IMAGE_FLOAT_PROFILE, 'exact complete float factory required')
    profile_bytes(profile)
    p, target, output = recipe(memory, description)
    _, args, writable, inactive, actions, staged = capture(data, 0, profile)
    require(args is None and [staged.get(0xd4 + 4*n) for n in range(3)] == [2, 1, 1] and
            [staged.get(0xe0 + 4*n) for n in range(3)] == [4, 1, 1], 'original group/local shape differs')
    satp = staged.get(0x14c, 0)
    require(satp == staged.get(0x150), 'instruction/data namespace differs')
    root, physical = uint(description['descriptorRoot']), uint(description['descriptorPhysical'])
    context = description['context']
    require([staged.get(0x420 + 4*n) for n in range(7)] == [257, root, root + 256, 0, context, 0, 0],
            'original storage command/root identity differs')
    require(translate(memory, satp, root, 15) == physical, 'storage descriptor private mapping differs')
    for unit, binding in enumerate(description['storageBindings']):
        address = (target, output)[binding]
        base = 0xcfffc000 + unit * (128 * 1024 * 1024 + 4096)
        writable = int(bool(p['target_access'] & 2)) if binding == 0 else 1
        expected = (0x47534201, 64, 1, context, 0, 0, unit, 1, 1, writable,
                    base, base + 8192, 0, base, base + 8192, 0)
        require(memory.read(physical + unit * 64, 64) == struct.pack('<16I', *expected) and
                translate(memory, satp, base, 15) == address and
                translate(memory, satp, base + 8191, 15) == address + 8191,
                'actual target/output descriptor or GEM mapping differs')
    require(memory.read(physical + 128, 128) == bytes(128), 'unused storage descriptors are enabled')
    import re
    require(code_words and all(re.fullmatch('[0-9a-f]{8}', word) for word in code_words), 'invalid actual code words')
    code = b''.join(struct.pack('<I', int(word, 16)) for word in code_words)
    require(len(code) == uint(description['codeBytes'], 64) and
            memory.read(uint(description['codePhysical']), len(code)) == code, 'compiler code snapshot differs')
    for offset in range(0, len(code), 4):
        address = executable_physical(memory, satp, staged.get(0xcc, 0) + offset)
        require(memory.read(address, 4) == code[offset:offset + 4], 'staged executable differs from actual Mesa code')


def returned_outputs(initial, returned, description):
    p, target, output = recipe(initial, description)
    if p['result_status']:
        require(returned.read(target,8192)==initial.read(target,8192) and
                returned.read(output,8192)==initial.read(output,8192), 'failed checked atomic changed original bytes')
        return []
    actual = returned.read(output, p['invocations'] * 4)
    observed = struct.unpack('<%uI' % p['invocations'], actual)
    final = returned.read(target + p['target_offset'], 4)
    value, = struct.unpack('<I', final)
    linearization(p['seed'], value, invocations(p['case']), observed)
    # The semantic witness above constrains every actual old word and the
    # final target; the caller's whole-RAM oracle guards every other byte.
    return [(target + p['target_offset'], final), (output, actual)]
