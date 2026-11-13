#!/usr/bin/env python3
"""Strict compute-only controller records. No CPU instruction execution."""
import json
import struct
from prepare_native_owner_flashsim import EXPECTED_WORDS

SCHEMA = 'opengpu-compute-owner-profile-v1'
AUXILIARY = (0, 0x60, 0x68, 0x320, 0x158, 0x494, 0x43c, 0x3fc, 0x4bc, 0x460)
MAGICS = (0x47544d01, 0x47534d01, 0x47494d01, 0x47414d01, 0x47554d01)


def uint(value, bits=32):
    if type(value) is not int or value < 0 or value >= 1 << bits:
        raise ValueError('unsigned integer required')
    return value


def strict_json(path):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ValueError('duplicate JSON key')
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def required_flags(value, flags):
    if type(value) is not dict:
        raise ValueError('record must be an object')
    for key, expected in flags.items():
        if type(value.get(key)) is not type(expected) or value[key] != expected:
            raise ValueError('invalid ' + key)


def profile_bytes(profile):
    required_flags(profile, dict(schema=SCHEMA, resetCount=1,
        sourceQualificationRequired=True, gpuExecution=False,
        clockQualified=False, fpsQualified=False))
    words, registers = profile.get('words'), profile.get('registers')
    if type(words) is not list or len(words) != 96 or type(registers) is not list or len(registers) != 10:
        raise ValueError('complete 96-word compute profile and ten actual auxiliary reads required')
    for actual, expected in zip(words, EXPECTED_WORDS):
        if type(actual) is not dict or set(actual) != {'address', 'value'}:
            raise ValueError('malformed discovery word')
        if uint(actual['address']) != expected['address'] or uint(actual['value']) != expected['value']:
            raise ValueError('mismatched complete owner factory')
    values = []
    for item, address in zip(registers, AUXILIARY):
        if type(item) is not dict or set(item) != {'address', 'value'} or uint(item['address']) != address:
            raise ValueError('missing, reordered or duplicate auxiliary register')
        values.append(uint(item['value']))
    device, caps, pointer, quota, shader = values[:5]
    # Use the generated ABI; never infer transport capability from a CPU mode.
    from pathlib import Path
    import re
    abi = (Path(__file__).resolve().parents[1] / 'driver/gpu_abi.h').read_text()
    names = ('UNIFIED_COMMANDS', 'PROTECTED_VM_STATE', 'COMPUTE_SCALAR_FPU', 'SHADER_CONFIG_INFO')
    required_caps = 0
    for name in names:
        match = re.search(r'^#define GPU_CAP_' + name + r'\s+\(1u << (\d+)\)', abi, re.M)
        if not match:
            raise ValueError('unsupported capability ABI expression')
        required_caps |= 1 << int(match.group(1))
    if (device & 0xffff0000 != 0x47550000 or device & 0xffff < 2 or
            caps & required_caps != required_caps or pointer != 0x160 or quota != 1048576 or
            shader & 255 != 16 or shader >> 8 & 255 != 8 or not (shader >> 16 & 255) or shader >> 24 != 2 or
            tuple(values[5:]) != MAGICS):
        raise ValueError('incoherent actual compute discovery')
    output = bytearray(b'OGCP0001' + struct.pack('<I', 96))
    for item in words:
        output.extend(struct.pack('<II', item['address'], item['value']))
    output.extend(struct.pack('<I', 10))
    for item in registers:
        output.extend(struct.pack('<II', item['address'], item['value']))
    return bytes(output)


def held_payload(value):
    required_flags(value, dict(schema='opengpu-compute-owner-held-v1', phase='held', record=0,
        sourceQualificationRequired=True, heldCompletionDrained=True, readOnlyGuards=True))
    command = uint(value.get('commandId'))
    completion, processed = uint(value.get('completion')), uint(value.get('processedBytes'), 64)
    legacy, status, irq = (uint(value.get(key)) for key in ('heldLegacyStatus', 'heldStatus', 'heldIrq'))
    if not 0 < command < 256 or completion != 0x8000 | command or processed or not status & 2:
        raise ValueError('wrong compute completion or identity')
    if type(value.get('heldIrqAsserted')) is not bool or not value['heldIrqAsserted'] or irq != 3:
        raise ValueError('held completion IRQ required')
    if 'gnx' in value or 'retiredStatus' in value or 'retiredIrq' in value:
        raise ValueError('native or premature retired observation in compute held phase')
    return struct.pack('<IIQIIII', command, completion, processed, legacy, status, irq, 1)


def held_bytes(value):
    return b'OGCMH001' + held_payload(value)


def retired_bytes(value, held):
    required_flags(value, dict(schema='opengpu-compute-owner-retired-v1', phase='retired', record=0,
        sourceQualificationRequired=True, externalDrained=True))
    for key in ('commandId', 'completion', 'processedBytes', 'heldLegacyStatus', 'heldStatus',
                'heldIrq', 'heldIrqAsserted'):
        if type(value.get(key)) is not type(held.get(key)) or value[key] != held[key]:
            raise ValueError('retired observation changed the held receipt')
    status, irq = uint(value.get('retiredStatus')), uint(value.get('retiredIrq'))
    if status & (2 | 8 | 32) or not status & 1 or irq != 1 or value.get('retiredIrqAsserted') is not False:
        raise ValueError('compute retirement must be drained and idle')
    return b'OGCMR001' + held_payload(held) + struct.pack('<IIII', status, irq, 0, 1)


def cleanup_authorization():
    # Distinct from execution/retirement receipts. The controller writes it
    # only after independent output/source/guard oracles all pass.
    return b'OGCMBA01' + struct.pack('<I', 1)


def checked_cleanup(value):
    required_flags(value, dict(phase='cleanup', ownerCleanupObserved=True, contextCleanupObserved=True,
        records=1, resetCount=1, ramReplaced=False, imagesPreserved=True,
        externalDrained=True, sourceQualificationRequired=True,
        clockQualified=False, fpsQualified=False))
    for key in ('batches', 'pteClears', 'shootdowns'):
        if uint(value.get(key)) == 0:
            raise ValueError('empty cleanup ' + key)
