"""Strict original-GEM counter recipe/READY binding checks; never executes code."""
import struct
from compute_owner_protocol import required_flags, uint
from owner_resource_contract import SAMPLE_WORDS

SCHEMA = 'opengpu-compute-counter-oracle-v1'
CASE = 'counter-increment'
FIELDS = {'schema', 'sourceCase', 'groups', 'local', 'viewBytes', 'guard', 'seed', 'index',
          'count', 'counterHandle', 'context', 'descriptorPhysical', 'descriptorRoot',
          'kernargPhysical', 'kernargBytes', 'counter', 'outputs'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def recipe(memory, value):
    from compute_owner_oracle import allocation_window
    require(type(value) is dict and set(value) == FIELDS, 'invalid counter source metadata fields')
    required_flags(value, dict(schema=SCHEMA, sourceCase=CASE, groups=4, local=4, viewBytes=8192,
                              guard=0xdeadbeef, seed=0xfffffffe, index=7, count=8))
    require(0 < uint(value['counterHandle']) < 512 and 0 < uint(value['context']), 'invalid original counter owner')
    source = allocation_window(memory, value['counter'], 32, 0)
    required_flags(value['counter'], dict(offset=4080, allocationBytes=8192))
    require(type(value['outputs']) is list and len(value['outputs']) == 4, 'four original SSBOs required')
    outputs = []
    allocations = [(source - 4080, source - 4080 + 8192)]
    for binding, view in enumerate(value['outputs']):
        first = allocation_window(memory, view, 8192, binding)
        required_flags(view, dict(offset=0, allocationBytes=8192))
        require(memory.read(first, 8192) == struct.pack('<I', 0xdeadbeef) * 2048, 'counter output precomputed or unguarded')
        outputs.append(first); allocations.append((first, first + 8192))
    allocations.sort()
    require(all(a[1] <= b[0] for a, b in zip(allocations, allocations[1:])), 'counter original GEM aliases an output')
    original = bytearray(b'\x5a' * 8192)
    for i in range(8):
        struct.pack_into('<I', original, 4080 + i * 4, (0xfffffffe + i * 0x10203) & 0xffffffff)
    struct.pack_into('<I', original, 4080 + 28, 0xfffffffe)
    require(memory.read(source - 4080, 8192) == original, 'counter original source/guard initialization differs')
    for key in ('descriptorPhysical', 'descriptorRoot', 'kernargPhysical', 'kernargBytes'):
        uint(value[key])
    require(value['descriptorPhysical'] % 64 == value['descriptorRoot'] % 64 == 0 and
            0 < value['kernargBytes'] <= 4096, 'invalid descriptor/kernarg view')
    return source, outputs


def returned_outputs(initial, returned, description):
    source, outputs = recipe(initial, description)
    values = [returned.read(first, 64) for first in outputs]
    require(all(data == values[0] for data in values), 'four counter SSBOs disagree per invocation')
    actual = struct.unpack('<16I', values[0])
    require(sorted(actual) == sorted((0xfffffffe + i) & 0xffffffff for i in range(16)),
            'counter returned old-value multiset differs')
    require(returned.read(source + 28, 4) == struct.pack('<I', 14), 'counter final increment/wrap differs')
    return list(zip(outputs, values)) + [(source + 28, struct.pack('<I', 14))]


def translate(memory, satp, va, permissions):
    require(satp & 0x80000000 and (satp >> 22) & 511, 'counter requires isolated Sv32')
    root = (satp & 0x3fffff) << 12
    def word(pa):
        return struct.unpack('<I', memory.read(pa, 4))[0]
    first = word(root + ((va >> 22) & 1023) * 4)
    require(first & 15 == 1, 'counter Sv32 root is not a nonleaf')
    second = word((first >> 10) * 4096 + ((va >> 12) & 1023) * 4)
    require(second & 15 == permissions, 'counter Sv32 leaf permissions differ')
    return (second >> 10) * 4096 + (va & 4095)


def ready(memory, description, data):
    """Bind READY metadata to full112 discovery, actual MMIO, GSB1 and PTEs."""
    source, outputs = recipe(memory, description)
    require(data[:8] == b'OGCMP002', 'counter requires full112 capture wire')
    cursor = 8
    def take(fmt):
        nonlocal cursor
        size = struct.calcsize(fmt)
        require(cursor + size <= len(data), 'truncated counter capture')
        result = struct.unpack_from(fmt, data, cursor); cursor += size
        return result
    budget, hold, command, count = take('<QIII')
    require(0 < budget <= 1000000000 and 8 <= hold <= 4096 and 0 < command < 256 and count == 122,
            'counter capture bounds or complete factory differ')
    discovery = [take('<II') for _ in range(count)]
    require(discovery[:112] == [(w['address'], w['value']) for w in SAMPLE_WORDS], 'counter needs exact final112 factory')
    # Validate the ten auxiliary observations with the shared strict public wire contract.
    from compute_owner_protocol import profile_bytes
    from owner_resource_contract import FULL_COMPUTE_SCHEMA, SAMPLE_PROFILE, SAMPLE_STAGE
    profile_bytes(dict(schema=FULL_COMPUTE_SCHEMA, ownerProfile=SAMPLE_PROFILE, ownerResourceStage=SAMPLE_STAGE,
        ownerResourceVersion=2, fullResourceQualification=False, resetCount=1, sourceQualificationRequired=True,
        gpuExecution=False, clockQualified=False, fpsQualified=False,
        words=[dict(address=a, value=v) for a, v in discovery[:112]],
        registers=[dict(address=a, value=v) for a, v in discovery[112:]]))
    count, = take('<I'); require(count == 6, 'counter capture must have six precise writable views')
    writable = [take('<QQ') for _ in range(count)]
    arg, argbytes = description['kernargPhysical'], description['kernargBytes']
    require(any(a <= arg < arg + argbytes <= b for a, b, _ in memory.ranges), 'kernarg outside original allocation')
    expected = sorted([(arg, arg + argbytes), (source, source + 32)] + [(p, p + 8192) for p in outputs])
    require(writable == expected and all(a[1] <= b[0] for a, b in zip(expected, expected[1:])),
            'counter capture writable windows widened, aliased or missing')
    count, = take('<I'); require(0 < count <= 65536, 'invalid counter MMIO action count')
    staged, submissions = {}, []
    for _ in range(count):
        kind, address, value, mask = take('<IIII')
        if kind == 1:
            require(mask == 0, 'masked counter write')
            staged[address] = value
            if address == 0x11c:
                submissions.append(dict(staged))
    require(cursor == len(data) and submissions and submissions[-1].get(0xc8) == 0,
            'counter capture must end with actual opcode0')
    r = submissions[-1]
    require([r.get(0xd4 + 4*i) for i in range(3)] == [4, 1, 1] and
            [r.get(0xe0 + 4*i) for i in range(3)] == [4, 1, 1], 'counter grid differs from original 16 invocations')
    root, context = description['descriptorRoot'], description['context']
    require([r.get(0x4a0 + 4*i) for i in range(7)] == [257, root, root + 64, 0, context, 0, 0],
            'counter GAM1 context/view was changed')
    satp = r.get(0x14c, 0)
    require(r.get(0x150) == satp, 'counter instruction/data owners differ')
    physical = translate(memory, satp, root, 15)
    require(physical == description['descriptorPhysical'], 'counter descriptor original binding differs')
    va = 0xb0000000 + 7 * 0x400000 + 4080
    require(translate(memory, satp, va, 15) == source and translate(memory, satp, va + 31, 15) == source + 31,
            'counter original cross-page source mapping differs')
    expected_descriptor = (0x47534201, 64, 1, context, 0, 0, 0, 1, 1, 1, va, va + 32, 0,
                           va & ~63, (va + 95) & ~63, 0)
    require(memory.read(physical, 64) == struct.pack('<16I', *expected_descriptor), 'counter GSB1 permissions/bounds differ')
    require(translate(memory, satp, r.get(0xd0, 0), 7) == arg, 'counter kernarg mapping differs')
    # Four actual GSM1 output descriptors must map the declared original GEMs.
    table = r.get(0x424, 0)
    require([r.get(0x420 + 4*i) for i in range(7)] == [257, table, table + 256, 0, context, 0, 0],
            'counter four-storage command context differs')
    for binding, output in enumerate(outputs):
        record = struct.unpack('<16I', memory.read(translate(memory, satp, table + 64*binding, 15), 64))
        base = 0xcfffc000 + binding * (128*1024*1024 + 4096)
        expected_record = (0x47534201, 64, 1, context, 0, 0, binding, 1, 1, 1,
                           base, base + 8192, 0, base, base + 8192, 0)
        require(record == expected_record and translate(memory, satp, base, 7) == output and
                translate(memory, satp, base + 8191, 7) == output + 8191, 'counter SSBO descriptor/source mapping differs')
