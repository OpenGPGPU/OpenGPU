"""Same-GEM cached/UC binding oracle for complete-owner P11 L1 publication."""
import struct

import compute_l1_publication_cases as source
from compute_global_atomic_source import _recipe, _ready
from compute_counter_source import translate
from compute_indirect_oracle import capture
from compute_owner_protocol import required_flags, uint

SCHEMA = 'opengpu-compute-l1-publication-oracle-v1'
STORAGE_BASE = source.STORAGE_BASE
CORE = {'schema', 'sourceCase', 'viewBytes', 'groups', 'local', 'guard', 'context',
        'codePhysical', 'codeBytes', 'shader', 'kernarg'}
EXTRA = {'storageAlias', 'storageSlot', 'storageBinding', 'descriptorPhysical',
         'descriptorRoot', 'retainedObjects', 'temporaryMappings'}


def require(ok, message):
    if not ok:
        raise ValueError('L1 publication source: ' + message)


def recipe(memory, description):
    require(type(description) is dict and set(description) == CORE | EXTRA,
            'foreign or missing same-GEM cached/UC fields')
    required_flags(description, dict(schema=SCHEMA, storageSlot=13, storageBinding=0,
                                     retainedObjects=1, temporaryMappings=2))
    try:
        p, kernarg, code = _recipe(memory, {key: description[key] for key in CORE},
                                   source, SCHEMA)
    except ValueError as error:
        import hashlib
        case = description.get('sourceCase')
        actual = hashlib.sha256(memory.read(uint(description['kernarg']['allocationPhysical']), 8192)).hexdigest()
        expected = hashlib.sha256(source.initial_kernarg(case)).hexdigest()
        raise ValueError('%s actual=%s expected=%s role=%s' % (error, actual, expected,
                         source.parameters(case)['role'])) from error
    required_flags(description['storageAlias'], description['kernarg'])
    require(set(description['storageAlias']) == set(description['kernarg']),
            'UC storage does not retain the same original GEM and exact view')
    descriptor = uint(description['descriptorPhysical'])
    require(descriptor and not descriptor & 4095 and descriptor != description['codePhysical'] and
            any((a, b) == (descriptor, descriptor + 4096) for a, b, _ in memory.ranges) and
            (descriptor + 4096 <= kernarg or kernarg + 8192 <= descriptor) and
            descriptor != description['shader']['allocationPhysical'],
            'storage descriptors do not occupy a private original allocation')
    return p, kernarg, code


def ready(memory, description, data, code_words, profile):
    recipe(memory, description)
    _ready(memory, {key: description[key] for key in CORE}, data, code_words,
           profile, source, SCHEMA, True)
    _, args, writable, inactive, _, staged = capture(data, 0, profile)
    satp = staged[0x14c]
    descriptor = uint(description['descriptorPhysical'])
    root = uint(description['descriptorRoot'])
    context = description['context']
    kernarg = description['kernarg']['allocationPhysical']
    require([staged.get(0x420 + 4 * n) for n in range(7)] == [257, root, root + 256, 0, context, 0, 0],
            'original checked storage command or root differs')
    require(translate(memory, satp, root, 15) == descriptor,
            'private storage table translation differs')
    expected = (0x47534201, 64, 1, context, 0, 0, 0, 1, 1, 1,
                STORAGE_BASE, STORAGE_BASE + 8192, 0,
                STORAGE_BASE, STORAGE_BASE + 8192, 0)
    require(memory.read(descriptor, 64) == struct.pack('<16I', *expected) and
            memory.read(descriptor + 64, 192) == bytes(192),
            'same-GEM UC descriptor or unused rows differ')
    for offset in (0, 4095, 4096, 8191, source.DATA_OFFSET, source.FLAG_OFFSET):
        require(translate(memory, satp, STORAGE_BASE + offset, 15, cache_policy=2) == kernarg + offset and
                translate(memory, satp, source.CACHED_BASE + offset, 15, cache_policy=0) == kernarg + offset,
                'cached/UC aliases lost their exact shared physical word or cache policy')


def target_line(description):
    p = source.parameters(description.get('sourceCase'))
    kernarg = uint(description['kernarg']['allocationPhysical'], 64)
    return kernarg + (p['target_offset'] & ~63)


AMO_OLD = (0xfffffff0, 0x80000001, 0, 0x80000002, 2, 0x80000003, 4, 0x80000004)


def returned_outputs(initial, returned, description):
    p = source.parameters(description['sourceCase'])
    kernarg = uint(description['kernarg']['allocationPhysical'], 64)
    if p['role'] == 'producer':
        changes = [(kernarg + source.DATA_OFFSET, struct.pack('<I', source.PUBLISHED)),
                   (kernarg + 192, struct.pack('<I', 2))]
        for index, old in enumerate(AMO_OLD):
            changes.append((kernarg + source.OUTPUT_OFFSET + 4 * index, struct.pack('<I', old)))
        for group in range(p['groups']):
            changes += [(kernarg + source.WARM_OFFSET + 8 * group, struct.pack('<I', source.SEED)),
                        (kernarg + source.WARM_OFFSET + 8 * group + 4, struct.pack('<I', 0)),
                        (kernarg + source.REFETCH_OFFSET + 8 * group, struct.pack('<I', source.PUBLISHED)),
                        (kernarg + source.REFETCH_OFFSET + 8 * group + 4, struct.pack('<I', 0))]
    else:
        slot = source.WARM_OFFSET if p['role'] == 'warm' else source.REFETCH_OFFSET
        data, flag = (source.SEED if p['role'] == 'warm' else source.PUBLISHED), 0
        changes = []
        for group in range(p['groups']):
            changes += [(kernarg + slot + 8 * group, struct.pack('<I', data)),
                        (kernarg + slot + 8 * group + 4, struct.pack('<I', flag))]
        if p['role'] == 'reload':
            changes.append((kernarg + source.DATA_OFFSET, struct.pack('<I', source.PUBLISHED)))
    if p['role'] == 'producer':
        import sys
        base = uint(description['kernarg']['allocationPhysical'], 64)
        print('L1 producer diagnostic initial=%s returned=%s' % (
            [hex(int.from_bytes(initial.read(base + 4 * i, 4), 'little')) for i in range(8)],
            [hex(int.from_bytes(returned.read(base + 4 * i, 4), 'little')) for i in range(8)]),
            file=sys.stderr, flush=True)
    for address, expected in changes:
        actual = returned.read(address, len(expected))
        require(actual == expected,
                'L1 publication word at %#x differs: %r expected %r' % (address, actual, expected))
    return changes
