#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'docs/abi/protected-tessellation-joint-geometry-v1.json'
OUTPUT = ROOT / 'driver/opengpu_protected_tessellation_joint_geometry_abi.h'
PREFIX = 'OPENGPU_JOINT_GEOMETRY_'

EXPECTED_HOST_SUBMISSION = {
    'commandTag': '0x474a0200', 'opcode': 15, 'rootBytes': 512,
    'rootAllocationBytes': 4096, 'sourceAlignmentBytes': 4096, 'domainCount': 3,
    'ownerLimitBits': 33, 'ownerLimitMaximum': '0x100000000',
    'pattern': {'tagShift': 3, 'tagBits': 29, 'domainShift': 1, 'domainBits': 2,
                'ownerLimitHighShift': 0},
    'registers': {'sourceBase': 'SOURCE', 'ownerBase': 'DESTINATION',
                  'ownerLimitLow': 'WIDTH', 'bytes': 'BYTES', 'context': 'HEIGHT',
                  'pass': 'SOURCE_STRIDE', 'generation': 'DEST_STRIDE'},
    'rejectedFlags': ['waitForDma', 'waitForEvent', 'signalEvent', 'rasterDiscard',
                      'feedbackCapture'],
    'rules': [
        'opcode, tag and domain are exact; domain 3 is rejected',
        'source is 4096-byte aligned and its 4096-byte allocation may end at 4 GiB',
        'owner exclusive limit is greater than base and at most 4 GiB',
        'all rejected flags are zero',
    ],
    'byteOrder': 'little-endian',
}

EXPECTED_COMPLETION = {
    'magic': '0x474e5a03', 'words': 46, 'byteOrder': 'little-endian',
    'prefixBase': '0x180', 'prefixWords': 16, 'legacyTailBase': '0x540',
    'geometryBase': '0x560', 'geometryWords': 30, 'geometryEnd': '0x5d8',
    'reservedBase': '0x5d8', 'reservedWords': 4, 'end': '0x5e8',
    'packedFields': [
        {'name': 'STATUS', 'word': 1, 'shift': 0, 'bits': 3},
        {'name': 'FAULT_STAGE', 'word': 1, 'shift': 3, 'bits': 3},
        {'name': 'CLEAR_ONLY', 'word': 1, 'shift': 6, 'bits': 1},
        {'name': 'GEOMETRY_REPORT_VALID', 'word': 1, 'shift': 7, 'bits': 1},
    ],
    'fields': [
        {'name': 'MAGIC', 'word': 0, 'bits': 32},
        {'name': 'FAULT_ADDRESS', 'word': 2, 'bits': 32},
        {'name': 'CONTEXT', 'word': 3, 'bits': 32},
        {'name': 'PASS', 'word': 4, 'bits': 32},
        {'name': 'GENERATION', 'word': 5, 'bits': 32},
        {'name': 'CAPTURED_ENTRIES', 'word': 6, 'bits': 32},
        {'name': 'RETIRED_RANGES', 'word': 7, 'bits': 32},
        {'name': 'RETIRED_PATCHES', 'word': 8, 'bits': 64, 'words': 2},
        {'name': 'PUBLISHED', 'word': 10, 'bits': 64, 'words': 2},
        {'name': 'STORED_BYTES', 'word': 12, 'bits': 64, 'words': 2},
        {'name': 'RESOLVED_BYTES', 'word': 14, 'bits': 64, 'words': 2},
        {'name': 'ACTUAL_GS_PUBLISHED', 'word': 16, 'bits': 158, 'words': 5},
        {'name': 'RECEIVED', 'word': 21, 'bits': 161, 'words': 6},
        {'name': 'DROPPED', 'word': 27, 'bits': 161, 'words': 6},
        {'name': 'ADMITTED', 'word': 33, 'bits': 97, 'words': 4},
        {'name': 'GEOMETRY_TASKS', 'word': 37, 'bits': 64, 'words': 2},
        {'name': 'NEXT_GS_SEQUENCE_EXCLUSIVE', 'word': 39, 'bits': 65, 'words': 3},
        {'name': 'NEXT_GS_SEQUENCE', 'word': 42, 'bits': 64, 'words': 2},
        {'name': 'NEXT_BIN_SEQUENCE', 'word': 44, 'bits': 33, 'words': 2},
    ],
    'rules': [
        'all unused high bits are zero',
        'nextGsSequence equals the low 64 bits of nextGsSequenceExclusive',
        'received equals admitted plus dropped without truncation',
        'geometryReportValid false forces words16..45 to zero',
        'status zero requires faultStage and faultAddress zero',
        'faultStage zero forces faultAddress zero; nonzero faultStage requires nonzero status',
        'identity and status must match the unified completion',
        '2^64 is a legal nextGsSequenceExclusive value',
        '0x5d8..0x5e7 is readable zero padding outside the 46 GNZ3 words',
    ],
}

EXPECTED_PROFILE = {
    'magic': '0x474a5002', 'base': '0x600', 'end': '0x610', 'words': 4,
    'gcr1PresenceBit': 23, 'requiredOwnerMask': '0x7f', 'targetFlags': '0x387f',
    'flags': [
        {'name': 'OWNED_VERTEX_SOURCE', 'bit': 0, 'required': True},
        {'name': 'PATCH_ZERO_INITIALIZED', 'bit': 1, 'required': True},
        {'name': 'OWNED_PATCH_READS', 'bit': 2, 'required': True},
        {'name': 'OWNED_TESSELLATION_INFO', 'bit': 3, 'required': True},
        {'name': 'GEOMETRY_INPUTS', 'bit': 4, 'required': True},
        {'name': 'JOINT_DIRECTORY_ROUTE', 'bit': 5, 'required': True},
        {'name': 'WHOLE_OWNER_RETIREMENT', 'bit': 6, 'required': True},
        {'name': 'SCALAR_FPU', 'bit': 7, 'required': False},
        {'name': 'ADVANCED_BLEND', 'bit': 8, 'required': False},
        {'name': 'SAMPLE_INPUTS', 'bit': 9, 'required': False},
        {'name': 'FEEDBACK', 'bit': 10, 'required': False},
        {'name': 'GEOMETRY_ROUTE', 'bit': 11, 'required': True},
        {'name': 'GEOMETRY_UNIFORMS', 'bit': 12, 'required': True},
        {'name': 'SHARED_THREE_DOMAIN_OWNER', 'bit': 13, 'required': True},
    ],
    'shape': {'domainsMask': 7, 'computeUnits': 2, 'lanes': 16, 'warps': 8},
    'capacities': {'engines': 4, 'payloadOwners': 5, 'payloadPorts': 12,
                   'imageProducerPins': 34, 'singleDomainTotalPins': 37,
                   'payloadBytes': 524288, 'maxPayloadBytes': 69632},
    'capacityEncoding': [
        {'name': 'ENGINES', 'source': 'engines', 'shift': 0, 'bits': 3},
        {'name': 'PAYLOAD_OWNERS', 'source': 'payloadOwners', 'shift': 3, 'bits': 3},
        {'name': 'PAYLOAD_PORTS', 'source': 'payloadPorts', 'shift': 6, 'bits': 4},
        {'name': 'IMAGE_PRODUCER_PINS', 'source': 'imageProducerPins', 'shift': 10, 'bits': 6},
        {'name': 'SINGLE_DOMAIN_TOTAL_PINS', 'source': 'singleDomainTotalPins', 'shift': 16, 'bits': 6},
        {'name': 'PAYLOAD_BYTES', 'source': 'payloadBytes', 'shift': 22, 'bits': 5,
         'encoding': 'log2'},
        {'name': 'MAX_PAYLOAD_BYTES', 'source': 'maxPayloadBytes', 'shift': 27, 'bits': 5,
         'encoding': '4KiB'},
    ],
    'provider': 'two-cu-vga60-owner-v3-five-stage',
    'rules': [
        'GCR1 bit23 is the only presence contract',
        'bits0..6 are the required owner mask 0x7f',
        'feedback bit10 must be zero',
        'the four words are an internal read-only probe',
        'no driver feature, GET_PARAM or public discovery is defined',
    ],
}


def define(name, value):
    text = value if isinstance(value, str) else hex(value)
    suffix = 'ULL' if int(text, 0) > 0xffffffff else 'u'
    return f'#define {PREFIX}{name} {text}{suffix}'


def profile_words(profile):
    capacities = profile['capacities']
    shape = profile['shape']
    flags = sum(1 << item['bit'] for item in profile['flags'] if item['required'])
    if flags & int(profile['requiredOwnerMask'], 16) != int(profile['requiredOwnerMask'], 16):
        raise ValueError('wrong joint geometry required owner mask')
    if flags != int(profile['targetFlags'], 16):
        raise ValueError('wrong joint geometry target flags')
    flags |= 1 << profile['gcr1PresenceBit']
    shape_word = ((shape['warps'] << 24) | (shape['lanes'] << 16) |
                  (shape['computeUnits'] << 8) | shape['domainsMask'])
    capacity_word = 0
    for item in profile['capacityEncoding']:
        value = capacities[item['source']]
        if item.get('encoding') == 'log2':
            value = value.bit_length() - 1
        elif item.get('encoding') == '4KiB':
            value //= 4096
        capacity_word |= value << item['shift']
    return [int(profile['magic'], 16), flags, shape_word, capacity_word]



def generate():
    s = json.loads((ROOT/'docs/abi/protected-tessellation-joint-geometry-v1.json').read_text())
    root, attachment = s['root'], s['attachment']
    if (root['bytes'], root['version'], root['domain_magics']) != (
            512, 3, [hex(0x474e5107+i) for i in range(3)]):
        raise ValueError('wrong joint geometry root')
    expected_root = {
        'geometry_version': 36, 'geometry_base': 37, 'geometry_count': 40,
        'geometry_entry_bytes': 41, 'geometry_bytes': 42,
    }
    if any(root[k] != v for k, v in expected_root.items()):
        raise ValueError('changed joint geometry root fields')
    if root['geometry_limit'] != {'word': 38, 'bits': 33} or root['reserved_zero'] != {'word': 43, 'count': 85}:
        raise ValueError('changed joint geometry root bounds')
    if (attachment['magic'], attachment['version'], attachment['header_bytes'], attachment['entry_bytes']) != (
            '0x47474101', 1, 64, 1024):
        raise ValueError('changed joint geometry attachment')
    if attachment['header'] != {
        'magic': 0, 'bytes': 1, 'version': 2, 'entry_bytes': 3, 'count': 4,
        'context': 5, 'pass': 6, 'generation': 7, 'reserved_zero': {'word': 8, 'count': 8},
    }:
        raise ValueError('changed joint geometry header')
    expected_entry = {
        'sequence_index': 0, 'state_handle': 1, 'domain': 2, 'flags': 3,
        'context': 4, 'pass': 5, 'generation': 6, 'command_id': 7,
        'gs_task_id_base': 8, 'first_input_primitive_id': 9,
        'first_output_sequence': {'word': 10, 'bits': 64},
        'gng_base': 12, 'gng_limit': {'word': 13, 'bits': 33}, 'gng_bytes': 15,
        'evaluation_components': {'word': 16, 'count': 64, 'bits': 8,
                                  'encoding': 'valid=bit7; source=bits0..6; invalid must zero'},
        'feedback_reserved_zero': {'word': 80, 'count': 100},
        'raster_discard': 180,
        'reserved_zero': [{'word': 181, 'count': 43}, {'word': 226, 'count': 30}],
        'input_sequence': {'word': 224, 'bits': 64},
    }
    if attachment['entry'] != expected_entry:
        raise ValueError('changed joint geometry entry')
    if s['source_rules'] != [
        'all raw addresses zero; typed original views only',
        'flags and words80..179 are zero; no transform-feedback descriptor, resource or permission is admitted',
        'GS program permissions are independent verified kernel profile inputs',
        'full physical cache-line alias exclusion across five stages, VS scratch and frame resources',
        'GNT7/8/9 and GVM2 bytes remain unchanged; geometry table is separate',
        'linked fragment shaders use GS-aware ABI5 or ABI7 only',
    ]:
        raise ValueError('changed joint geometry source rules')
    host, completion, profile = s['hostSubmission'], s['completion'], s['profile']
    if host != EXPECTED_HOST_SUBMISSION:
        raise ValueError('changed joint geometry host submission')
    if completion != EXPECTED_COMPLETION:
        raise ValueError('changed joint geometry completion')
    if profile != EXPECTED_PROFILE:
        raise ValueError('changed joint geometry profile')
    if profile_words(profile) != [0x474a5002, 0x80387f, 0x08100207, 0x8ce58b2c]:
        raise ValueError('wrong joint geometry profile words')

    out = [
        '/* Generated by scripts/generate_protected_tessellation_joint_geometry_abi.py. */',
        '#ifndef OPENGPU_PROTECTED_TESSELLATION_JOINT_GEOMETRY_ABI_H',
        '#define OPENGPU_PROTECTED_TESSELLATION_JOINT_GEOMETRY_ABI_H',
    ]
    values = [
        ('ROOT_BYTES', root['bytes']), ('ROOT_VERSION', root['version']),
        ('ROOT_ISOLINE_MAGIC', root['domain_magics'][0]),
        ('ROOT_TRIANGLE_MAGIC', root['domain_magics'][1]),
        ('ROOT_QUAD_MAGIC', root['domain_magics'][2]),
        ('MAGIC', attachment['magic']), ('VERSION', attachment['version']),
        ('HEADER_BYTES', attachment['header_bytes']), ('ENTRY_BYTES', attachment['entry_bytes']),
    ]
    for name, value in values:
        out.append(f'#define {PREFIX}{name} {value}u')
    for name, key in [('VERSION', 'geometry_version'), ('BASE', 'geometry_base'),
                      ('COUNT', 'geometry_count'), ('ENTRY_BYTES', 'geometry_entry_bytes'),
                      ('BYTES', 'geometry_bytes')]:
        out.append(f'#define {PREFIX}ROOT_GEOMETRY_{name}_WORD {root[key]}u')
    out += [
        f'#define {PREFIX}ROOT_GEOMETRY_LIMIT_WORD {root["geometry_limit"]["word"]}u',
        f'#define {PREFIX}ROOT_GEOMETRY_LIMIT_BITS {root["geometry_limit"]["bits"]}u',
        f'#define {PREFIX}ROOT_RESERVED_ZERO_WORD {root["reserved_zero"]["word"]}u',
        f'#define {PREFIX}ROOT_RESERVED_ZERO_COUNT {root["reserved_zero"]["count"]}u',
    ]
    for name, value in attachment['header'].items():
        if name != 'reserved_zero':
            out.append(f'#define {PREFIX}HEADER_{name.upper()}_WORD {value}u')
    out += [
        f'#define {PREFIX}HEADER_RESERVED_ZERO_WORD {attachment["header"]["reserved_zero"]["word"]}u',
        f'#define {PREFIX}HEADER_RESERVED_ZERO_COUNT {attachment["header"]["reserved_zero"]["count"]}u',
    ]
    entry = attachment['entry']
    for name, value in entry.items():
        if isinstance(value, int):
            out.append(f'#define {PREFIX}ENTRY_{name.upper()}_WORD {value}u')
        elif isinstance(value, list):
            for i, item in enumerate(value):
                out.append(f'#define {PREFIX}ENTRY_RESERVED_ZERO{i}_WORD {item["word"]}u')
                out.append(f'#define {PREFIX}ENTRY_RESERVED_ZERO{i}_COUNT {item["count"]}u')
        elif name not in ('evaluation_components', 'feedback_reserved_zero'):
            out.append(f'#define {PREFIX}ENTRY_{name.upper()}_WORD {value["word"]}u')
            if 'bits' in value:
                out.append(f'#define {PREFIX}ENTRY_{name.upper()}_BITS {value["bits"]}u')
    out += [
        f'#define {PREFIX}ENTRY_FEEDBACK_RESERVED_ZERO_WORD {entry["feedback_reserved_zero"]["word"]}u',
        f'#define {PREFIX}ENTRY_FEEDBACK_RESERVED_ZERO_COUNT {entry["feedback_reserved_zero"]["count"]}u',
        f'#define {PREFIX}ENTRY_EVALUATION_COMPONENTS_WORD {entry["evaluation_components"]["word"]}u',
        f'#define {PREFIX}ENTRY_EVALUATION_COMPONENTS_BITS {entry["evaluation_components"]["bits"]}u',
        f'#define {PREFIX}ENTRY_EVALUATION_COMPONENTS_COUNT {entry["evaluation_components"]["count"]}u',
    ]
    out += [
        define('HOST_COMMAND_TAG', host['commandTag']),
        define('HOST_OPCODE', host['opcode']),
        define('HOST_ROOT_BYTES', host['rootBytes']),
        define('HOST_ROOT_ALLOCATION_BYTES', host['rootAllocationBytes']),
        define('HOST_SOURCE_ALIGNMENT_BYTES', host['sourceAlignmentBytes']),
        define('HOST_DOMAIN_COUNT', host['domainCount']),
        define('HOST_OWNER_LIMIT_BITS', host['ownerLimitBits']),
        define('HOST_OWNER_LIMIT_MAXIMUM', host['ownerLimitMaximum']),
        define('HOST_PATTERN_TAG_SHIFT', host['pattern']['tagShift']),
        define('HOST_PATTERN_TAG_BITS', host['pattern']['tagBits']),
        define('HOST_PATTERN_DOMAIN_SHIFT', host['pattern']['domainShift']),
        define('HOST_PATTERN_DOMAIN_BITS', host['pattern']['domainBits']),
        define('HOST_PATTERN_OWNER_LIMIT_HIGH_SHIFT', host['pattern']['ownerLimitHighShift']),
    ]
    out += [define('HOST_REJECT_' + flag.upper(), 1) for flag in host['rejectedFlags']]
    out += [
        define('COMPLETION_MAGIC', completion['magic']),
        define('COMPLETION_WORDS', completion['words']),
        define('COMPLETION_PREFIX_BASE', completion['prefixBase']),
        define('COMPLETION_PREFIX_WORDS', completion['prefixWords']),
        define('COMPLETION_LEGACY_TAIL_BASE', completion['legacyTailBase']),
        define('COMPLETION_GEOMETRY_BASE', completion['geometryBase']),
        define('COMPLETION_GEOMETRY_WORDS', completion['geometryWords']),
        define('COMPLETION_GEOMETRY_END', completion['geometryEnd']),
        define('COMPLETION_RESERVED_BASE', completion['reservedBase']),
        define('COMPLETION_RESERVED_WORDS', completion['reservedWords']),
        define('COMPLETION_END', completion['end']),
    ]
    for field in completion['packedFields']:
        name = field['name']
        out += [
            define(f'COMPLETION_{name}_WORD', field['word']),
            define(f'COMPLETION_{name}_SHIFT', field['shift']),
            define(f'COMPLETION_{name}_BITS', field['bits']),
        ]
    for field in completion['fields']:
        name = field['name']
        out += [
            define(f'COMPLETION_{name}_WORD', field['word']),
            define(f'COMPLETION_{name}_BITS', field['bits']),
        ]
        if 'words' in field:
            out.append(define(f'COMPLETION_{name}_WORDS', field['words']))
    words = profile_words(profile)
    out += [
        define('PROFILE_MAGIC', profile['magic']),
        define('PROFILE_BASE', profile['base']),
        define('PROFILE_END', profile['end']),
        define('PROFILE_WORDS', profile['words']),
        define('PROFILE_GCR1_PRESENCE_BIT', profile['gcr1PresenceBit']),
        define('PROFILE_REQUIRED_OWNER_MASK', profile['requiredOwnerMask']),
        define('PROFILE_TARGET_FLAGS', profile['targetFlags']),
        define('PROFILE_FLAGS', words[1]),
        define('PROFILE_SHAPE_WORD', words[2]),
        define('PROFILE_CAPACITY_WORD', words[3]),
        f'#define {PREFIX}PROFILE_PROVIDER "{profile["provider"]}"',
    ]
    for flag in profile['flags']:
        out += [
            define(f'PROFILE_FLAG_{flag["name"]}_BIT', flag['bit']),
            define(f'PROFILE_FLAG_{flag["name"]}_REQUIRED', int(flag['required'])),
        ]
    for name, value in profile['shape'].items():
        out.append(define('PROFILE_SHAPE_' + name.upper(), value))
    for name, value in profile['capacities'].items():
        out.append(define('PROFILE_' + name.upper(), value))
    for field in profile['capacityEncoding']:
        out += [
            define(f'PROFILE_CAPACITY_{field["name"]}_SHIFT', field['shift']),
            define(f'PROFILE_CAPACITY_{field["name"]}_BITS', field['bits']),
        ]
    return '\n'.join(out + ['#endif', ''])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    content = generate()
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text() != content:
            raise SystemExit('joint geometry ABI generated header is stale')
    else:
        OUTPUT.write_text(content)
    print('GNQ7/8/9 geometry joint ABI: ' + ('checked' if args.check else 'generated'))


if __name__ == '__main__':
    main()
