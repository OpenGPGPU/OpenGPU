"""Original raw RV32/kernel-argument ownership and actual-byte checks.

No instruction execution: source recipes define bounded AMO events, and the
oracle accepts only their returned old words and a legal final shared word.
"""
import re
import struct

from compute_global_atomic_cases import (bound_words, check_results, initial_kernarg,
                                         parameters, OUTPUT_OFFSET)
from compute_counter_source import translate
from compute_indirect_oracle import capture, executable_physical
from compute_owner_protocol import profile_bytes, required_flags, uint
from owner_resource_contract import IMAGE_FLOAT_PROFILE

SCHEMA = 'opengpu-compute-global-atomic-oracle-v1'


def require(ok, message):
    if not ok:
        raise ValueError('global atomic source: ' + message)


def recipe(memory, description):
    require(type(description) is dict, 'original source metadata must be an object')
    p = parameters(description.get('sourceCase'))
    required_flags(description, dict(schema=SCHEMA, sourceCase=p['case'], viewBytes=8192,
                                    groups=4, local=16, guard=0xdeadbeef, context=1))
    require(set(description) == {'schema', 'sourceCase', 'viewBytes', 'groups', 'local',
            'guard', 'context', 'codePhysical', 'codeBytes', 'shader', 'kernarg'},
            'foreign or missing original recipe fields')
    code = struct.pack('<%uI' % len(bound_words(p['case'])), *bound_words(p['case']))
    addresses = []
    for name, handle, size, allocation in (('shader', 1, len(code), 4096), ('kernarg', 2, 8192, 8192)):
        view = description[name]
        required_flags(view, dict(handle=handle, allocationBytes=allocation, offset=0, bytes=size))
        require(set(view) == {'handle', 'allocationPhysical', 'allocationBytes', 'offset', 'bytes'},
                'foreign original GEM fields')
        address = uint(view['allocationPhysical'])
        require(address and not address & 4095 and
                any((a, b) == (address, address + allocation) for a, b, _ in memory.ranges),
                'missing exact original GEM allocation')
        addresses.append(address)
    shader, kernarg = addresses
    require(shader + 4096 <= kernarg or kernarg + 8192 <= shader, 'original source/kernarg alias')
    require(memory.read(kernarg, 8192) == initial_kernarg(p['case']), 'changed or prefilled original arguments')
    require(memory.read(shader, len(code)) == code, 'original RV32 source code differs')
    physical = uint(description['codePhysical'])
    require(physical and not physical & 4095 and physical != shader and
            (physical + 4096 <= kernarg or kernarg + 8192 <= physical) and
            any((a, b) == (physical, physical + 4096) for a, b, _ in memory.ranges),
            'code is not an independent original private snapshot')
    require(uint(description['codeBytes']) == len(code) and memory.read(physical, len(code)) == code,
            'private snapshot differs from original RV32 program/padding')
    return p, kernarg, code


def ready(memory, description, data, code_words, profile):
    require(profile.get('ownerProfile') == IMAGE_FLOAT_PROFILE, 'complete float owner required')
    profile_bytes(profile)
    p, kernarg, code = recipe(memory, description)
    _, args, writable, inactive, _, staged = capture(data, 0, profile)
    require(args is None and not inactive and writable == [(kernarg, kernarg + 8192)],
            'source command has foreign argument owners or writable grants')
    require([staged.get(0xd4 + 4 * n) for n in range(3)] == [4, 1, 1] and
            [staged.get(0xe0 + 4 * n) for n in range(3)] == [16, 1, 1],
            'original scalar-caller launch shape differs')
    require(staged.get(0xcc) == 0xc0400000 and staged.get(0xd0) == 0x80800000,
            'original shader/kernarg resource-slot windows differ')
    satp = staged.get(0x14c, 0)
    require(satp == staged.get(0x150), 'instruction/data namespace differs')
    for offset in (0, 4095, 4096, 8191):
        # The production kernarg leaf is exact UC2/RWX/A/D (0x2cf).
        # Preserve the original in-page offset on both source pages.
        require(translate(memory, satp, 0x80800000 + offset, 15) == kernarg + offset,
                'original uncached kernarg translation differs')
    require(type(code_words) is list and
            all(type(word) is str and re.fullmatch('[0-9a-f]{8}', word) for word in code_words) and
            code_words == ['%08x' % word for word in bound_words(p['case'])],
            'captured native words differ from original RV32 source')
    for offset in range(0, len(code), 4):
        physical = executable_physical(memory, satp, staged[0xcc] + offset)
        require(physical == description['codePhysical'] + offset and memory.read(physical, 4) == code[offset:offset + 4],
                'private executable mapping differs from original code snapshot')


def returned_outputs(initial, returned, description):
    p, kernarg, _ = recipe(initial, description)
    actual = returned.read(kernarg + OUTPUT_OFFSET, 32)
    old = struct.unpack('<8I', actual)
    final = returned.read(kernarg + p['target_offset'], 4)
    value, = struct.unpack('<I', final)
    try:
        check_results(p['case'], old, value)
    except ValueError as error:
        raise ValueError('%s: actual old words %s final %08x: %s' %
                         (p['case'], ['%08x' % word for word in old], value, error)) from error
    return [(kernarg + OUTPUT_OFFSET, actual), (kernarg + p['target_offset'], final)]
