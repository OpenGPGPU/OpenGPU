"""Strict original-GEM counter recipe/READY binding checks; never executes code."""
import struct
from compute_owner_protocol import required_flags, uint
from owner_resource_contract import SAMPLE_WORDS
from compute_owner_cases import counter_parameters

SCHEMA = 'opengpu-compute-counter-oracle-v1'
CASE = 'counter-increment'
FIELDS = {'schema', 'sourceCase', 'groups', 'local', 'viewBytes', 'guard', 'seed', 'index',
          'count', 'counterHandle', 'context', 'descriptorPhysical', 'descriptorRoot',
          'kernargPhysical', 'kernargBytes', 'counter', 'outputs', 'storageBindings'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def recipe(memory, value):
    from compute_owner_oracle import allocation_window
    require(type(value) is dict, 'counter source metadata must be an object')
    parameters=counter_parameters(value.get('sourceCase'))
    fields=FIELDS|({'indexUniform','uniformRoot','uniformPhysical','uniformBytes'} if parameters['dynamic'] else set())
    require(set(value)==fields, 'invalid counter source metadata fields')
    required_flags(value, dict(schema=SCHEMA, groups=parameters['groups'], local=4, viewBytes=8192,
                              guard=0xdeadbeef, seed=parameters['seed'], index=parameters['index'], count=8))
    require(0 < uint(value['counterHandle']) < 512 and 0 < uint(value['context']), 'invalid original counter owner')
    bindings=value['storageBindings']
    require(type(bindings) is list and all(type(i) is int for i in bindings) and sorted(bindings)==[0,1,2,3],
            'actual Mesa storage bindings must be a complete permutation')
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
    if parameters['dynamic']:
        uniform=allocation_window(memory,value['indexUniform'],16,35)
        required_flags(value['indexUniform'],dict(offset=0,allocationBytes=4096))
        original_uniform=struct.pack('<4I',parameters['index'],0x13579bdf,0x2468ace0,0x11223344)+b'\x6d'*(4096-16)
        require(memory.read(uniform,4096)==original_uniform, 'dynamic original index UBO or guard differs')
        allocations.append((uniform,uniform+4096))
        require(uint(value['uniformRoot'])==0xb1800000 and uint(value['uniformBytes'])==8192 and
                uint(value['uniformPhysical'])%4096==0, 'dynamic uniform snapshot bounds differ')
    allocations.sort()
    require(all(a[1] <= b[0] for a, b in zip(allocations, allocations[1:])), 'counter original GEM aliases an output')
    original = bytearray(b'\x5a' * 8192)
    for i in range(8):
        struct.pack_into('<I', original, 4080 + i * 4, (parameters['seed'] + (i+int(parameters['dynamic'])) * 0x10203) & 0xffffffff)
    struct.pack_into('<I', original, 4080 + 28, parameters['seed'])
    require(memory.read(source - 4080, 8192) == original, 'counter original source/guard initialization differs')
    for key in ('descriptorPhysical', 'descriptorRoot', 'kernargPhysical', 'kernargBytes'):
        uint(value[key])
    require(value['descriptorPhysical'] % 64 == value['descriptorRoot'] % 64 == 0 and
            0 < value['kernargBytes'] <= 4096, 'invalid descriptor/kernarg view')
    return source, outputs


def returned_outputs(initial, returned, description):
    source, outputs = recipe(initial, description)
    parameters=counter_parameters(description['sourceCase']);seed=parameters['seed'];op=parameters['operation']
    invocations=parameters['groups']*4
    values = [returned.read(first, invocations*4) for first in outputs]
    require(all(data == values[0] for data in values), 'four counter SSBOs disagree per invocation')
    actual = struct.unpack('<%uI'%invocations, values[0])
    finals=[]
    if parameters['dynamic']:
        expected=[]
        for lane in range(invocations):
            index=parameters['index']+lane
            old=seed if index==7 else (seed+(index+1)*0x10203)&0xffffffff
            result=(old-1 if op=='decrement' else old)&0xffffffff
            final=(old+1 if op=='increment' else old-1 if op=='decrement' else old)&0xffffffff
            expected.append(result);finals.append((source+index*4,struct.pack('<I',final)))
        require(list(actual)==expected, 'dynamic original counter index/ESSL return value differs')
    else:
        expected = [(seed + i if op == 'increment' else seed-i-1 if op == 'decrement' else seed) & 0xffffffff
                    for i in range(invocations)]
        require(sorted(actual) == sorted(expected), 'counter returned ESSL value multiset differs')
        final=(seed+invocations if op=='increment' else seed-invocations if op=='decrement' else seed)&0xffffffff
        finals=[(source+28,struct.pack('<I',final))]
    for address,data in finals:
        require(returned.read(address,4)==data, 'counter final value/wrap differs')
    return list(zip(outputs,values))+finals


def translate(memory, satp, va, permissions):
    require(satp & 0x80000000 and (satp >> 22) & 511, 'counter requires isolated Sv32')
    root = (satp & 0x3fffff) << 12
    def word(pa):
        return struct.unpack('<I', memory.read(pa, 4))[0]
    first = word(root + ((va >> 22) & 1023) * 4)
    require(first & 1023 == 1, 'counter Sv32 root is not a nonleaf')
    second = word((first >> 10) * 4096 + ((va >> 12) & 1023) * 4)
    # Production private pages are uncached, accessed/dirty, supervisor-only.
    require(second & 1023 == (0x240 | permissions | (0x80 if permissions&4 else 0)), 'counter Sv32 leaf permissions differ')
    return (second >> 10) * 4096 + (va & 4095)


def ready(memory, description, data):
    """Bind READY metadata to full112 discovery, actual MMIO, GSB1 and PTEs."""
    source, outputs = recipe(memory, description)
    require(data[:8] == b'OGCMP003', 'counter requires full112 capture with original inactive contexts')
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
    count, = take('<I');require(count==1, 'original Mesa compute requires its separate inactive graphics context')
    inactive_context,inactive_satp=take('<II')
    require(0<inactive_context<512 and inactive_context!=description['context'], 'inactive context identity differs')
    count, = take('<I'); require(0 < count <= 65536, 'invalid counter MMIO action count')
    staged, submissions, last_submit, satps = {}, [], None, set()
    for action in range(count):
        kind, address, value, mask = take('<IIII')
        if kind == 1:
            require(mask == 0, 'masked counter write')
            staged[address] = value
            if address in (0x14c,0x150) and value&0x80000000:satps.add(value)
            if address == 0x11c:
                require(value==1, 'counter submit must publish one command')
                submissions.append(dict(staged));last_submit=action
    require(cursor == len(data) and submissions and submissions[-1].get(0xc8) == 0 and
            last_submit==count-1 and all(s.get(0xc8)==5 for s in submissions[:-1]),
            'counter capture requires one final kernel after only publication invalidates')
    r = submissions[-1]
    parameters=counter_parameters(description['sourceCase'])
    require([r.get(0xd4 + 4*i) for i in range(3)] == [parameters['groups'], 1, 1] and
            [r.get(0xe0 + 4*i) for i in range(3)] == [4, 1, 1], 'counter grid differs from original source recipe')
    root, context = description['descriptorRoot'], description['context']
    require([r.get(0x4a0 + 4*i) for i in range(7)] == [257, root, root + 64, 0, context, 0, 0],
            'counter GAM1 context/view was changed')
    satp = r.get(0x14c, 0)
    require(r.get(0x150) == satp, 'counter instruction/data owners differ')
    inactive_root=(inactive_satp&0x3fffff)<<12;inactive_asid=(inactive_satp>>22)&511
    require(inactive_satp&0x80000000 and inactive_asid and
            all(((active>>22)&511)!=inactive_asid and ((active&0x3fffff)<<12)!=inactive_root for active in satps),
            'inactive context aliases an active owner ASID/root')
    require(any(a==inactive_root and b==inactive_root+4096 for a,b,_ in memory.ranges) and
            not any(inactive_root<b and a<inactive_root+4096 for a,b in expected) and
            memory.read(inactive_root,4096)==bytes(4096), 'inactive graphics context is not an original separate empty root')
    physical = translate(memory, satp, root, 15)
    require(physical == description['descriptorPhysical'], 'counter descriptor original binding differs')
    va = 0xb0000000 + 7 * 0x400000 + 4080
    require(translate(memory, satp, va, 15) == source and translate(memory, satp, va + 31, 15) == source + 31,
            'counter original cross-page source mapping differs')
    expected_descriptor = (0x47534201, 64, 1, context, 0, 0, 0, 1, 1, 1, va, va + 32, 0,
                           va & ~63, (va + 95) & ~63, 0)
    require(memory.read(physical, 64) == struct.pack('<16I', *expected_descriptor), 'counter GSB1 permissions/bounds differ')
    require(translate(memory, satp, r.get(0xd0, 0), 15) == arg, 'counter kernarg mapping differs')
    # Four actual GSM1 output descriptors must map the declared original GEMs.
    table = r.get(0x424, 0)
    require([r.get(0x420 + 4*i) for i in range(7)] == [257, table, table + 256, 0, context, 0, 0],
            'counter four-storage command context differs')
    for binding, global_binding in enumerate(description['storageBindings']):
        output=outputs[global_binding]
        record = struct.unpack('<16I', memory.read(translate(memory, satp, table + 64*binding, 15), 64))
        base = 0xcfffc000 + binding * (128*1024*1024 + 4096)
        expected_record = (0x47534201, 64, 1, context, 0, 0, binding, 1, 1, 1,
                           base, base + 8192, 0, base, base + 8192, 0)
        require(record == expected_record and translate(memory, satp, base, 15) == output and
                translate(memory, satp, base + 8191, 15) == output + 8191, 'counter SSBO descriptor/source mapping differs')

    if parameters['dynamic']:
        root=description['uniformRoot'];physical=description['uniformPhysical']
        argva=r.get(0xd0,0)
        require([r.get(0x440+4*i) for i in range(8)]==[257,root,context,0,0,argva,argva+argbytes,0],
                'dynamic GUM1 uniform/private-prefix command differs')
        require(translate(memory,satp,root,3)==physical and translate(memory,satp,root+8191,3)==physical+8191,
                'dynamic uniform snapshot is not its read-only original private mapping')
        expected=bytearray(8192)
        struct.pack_into('<16I',expected,0,0x47555201,64,1,context,0,0,2,1,0,0,root+64,root+1600,0,0,0,0)
        struct.pack_into('<16I',expected,64,0x47554201,64,1,context,0,0,0,4,root+4096,root+4112,0,root+4096,root+4160,0,1,0)
        original=description['indexUniform']['allocationPhysical']
        expected[4096:4112]=memory.read(original,16)
        require(memory.read(physical,8192)==expected, 'dynamic GUR1/GUB1/index bytes or reserved padding differ')
    else:
        require(r.get(0x440)==0, 'static counter unexpectedly enabled a uniform source')
