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
    p, kernarg, code = _recipe(memory, {key: description[key] for key in CORE},
                               source, SCHEMA)
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


def changes(description, role):
    """Exact physical words the independent byte oracle must see change."""
    p = source.parameters(description['sourceCase'])
    kernarg = uint(description['kernarg']['allocationPhysical'], 64)
    warm = source.WARM_OFFSET
    refetch = source.REFETCH_OFFSET
    output = source.OUTPUT_OFFSET
    if role == 'warm':
        return [item for group in range(p['groups']) for item in (
            (kernarg + warm + 4 * group, struct.pack('<I', 0)),
            (kernarg + warm + 4 * group + 4, struct.pack('<I', 0)))]
    if role == 'producer':
        return [(kernarg + source.DATA_OFFSET, struct.pack('<I', source.DATA_WORD)),
                (kernarg + source.FLAG_OFFSET, struct.pack('<I', source.FLAG_WORD)),
                (kernarg + output, struct.pack('<I', 0)),
                (kernarg + output + 4, struct.pack('<I', 0))]
    return [item for group in range(p['groups']) for item in (
        (kernarg + refetch + 4 * group, struct.pack('<I', source.DATA_WORD)),
        (kernarg + refetch + 4 * group + 4, struct.pack('<I', source.FLAG_WORD)))]


def returned_outputs(initial, returned, description):
    p = source.parameters(description['sourceCase'])
    kernarg = uint(description['kernarg']['allocationPhysical'], 64)
    if p['role'] == 'producer':
        data = struct.unpack('<I', returned.read(kernarg + source.DATA_OFFSET, 4))[0]
        flag = struct.unpack('<I', returned.read(kernarg + source.FLAG_OFFSET, 4))[0]
        require(data == source.DATA_WORD and flag == source.FLAG_WORD,
                'producer did not publish the original data/flag words')
        output = struct.unpack('<2I', returned.read(kernarg + source.OUTPUT_OFFSET, 8))
        require(output == (0, 0),
                'producer output window must retain the original AMO old values')
    else:
        slot = p['warm_offset'] if p['role'] == 'warm' else p['refetch_offset']
        data = 0 if p['role'] == 'warm' else source.DATA_WORD
        flag = 0 if p['role'] == 'warm' else source.FLAG_WORD
        for group in range(p['groups']):
            pair = struct.unpack('<2I', returned.read(kernarg + slot + 4 * group, 8))
            require(pair == (data, flag), 'ordinary cached consumer missed the retained word pair')
    return changes(description, p['role'])
