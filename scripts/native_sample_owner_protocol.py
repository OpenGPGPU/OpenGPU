#!/usr/bin/env python3
"""Exact sample-host discovery and actual native held/retired receipt wires."""
import struct

from compute_owner_protocol import uint, required_flags, strict_json, checked_cleanup
from owner_resource_contract import SAMPLE_PROFILE, SAMPLE_STAGE, SAMPLE_WORDS

SCHEMA = 'opengpu-native-sample-owner-profile-v1'
AUXILIARY = (0, 0x60, 0x68, 0x320, 0x158)


def profile_bytes(profile):
    required_flags(profile, dict(schema=SCHEMA, ownerProfile=SAMPLE_PROFILE,
        ownerResourceStage=SAMPLE_STAGE, ownerResourceVersion=2, fullResourceQualification=False,
        resetCount=1, sourceQualificationRequired=True, gpuExecution=False,
        clockQualified=False, fpsQualified=False))
    words, registers = profile.get('words'), profile.get('registers')
    if type(words) is not list or len(words) != 112 or type(registers) is not list or len(registers) != 5:
        raise ValueError('sample owner requires 112 discovery and five actual auxiliary observations')
    for actual, expected in zip(words, SAMPLE_WORDS):
        if (type(actual) is not dict or set(actual) != {'address', 'value'} or
                uint(actual['address']) != expected['address'] or uint(actual['value']) != expected['value']):
            raise ValueError('wrong or reordered complete sample factory')
    values = []
    for item, address in zip(registers, AUXILIARY):
        if type(item) is not dict or set(item) != {'address', 'value'} or uint(item['address']) != address:
            raise ValueError('missing, reordered or duplicate sample auxiliary register')
        values.append(uint(item['value']))
    device, caps, pointer, quota, shader = values
    required_caps = sum(1 << bit for bit in (6, 22, 23, 25, 29))
    lanes, warps, banks, cus = (shader >> shift & 255 for shift in (0, 8, 16, 24))
    if (device & 0xffff0000 != 0x47550000 or device & 0xffff < 2 or caps & required_caps != required_caps or
            pointer != 0x160 or quota != 1048576 or (lanes, warps, cus) != (16, 8, 2) or
            banks < 2 or banks & 1 or caps >> 8 & 255 != lanes * warps):
        raise ValueError('incoherent complete native sample profile')
    output = bytearray(b'OGNP0002' + struct.pack('<I', 112))
    for item in words:
        output.extend(struct.pack('<II', item['address'], item['value']))
    output.extend(struct.pack('<I', 5))
    for item in registers:
        output.extend(struct.pack('<II', item['address'], item['value']))
    return bytes(output)


def _held_payload(value):
    required_flags(value, dict(ownerProfile=SAMPLE_PROFILE, record=0, resetCount=1,
        sourceQualificationRequired=True, heldCompletionDrained=True, readOnlyGuards=True,
        heldIrqAsserted=True, clockQualified=False, fpsQualified=False))
    command = uint(value.get('commandId'))
    completion, append = uint(value.get('completion')), uint(value.get('appendReadBytes'), 64)
    legacy, status, irq = (uint(value.get(key)) for key in ('heldLegacyStatus', 'heldStatus', 'heldIrq'))
    gnx = value.get('gnx')
    if (not 0 < command < 256 or completion != 0x8700 | command or append & 63 or append > 1 << 33 or
            legacy or not status & 2 or status & ~0x23 or irq != 3 or type(gnx) is not list or len(gnx) != 12):
        raise ValueError('wrong sample native held completion or identity')
    gnx = [uint(word) for word in gnx]
    if gnx[0] != 0x474e5801 or any(gnx[i] for i in (1, 4, 5, 9, 10, 11)):
        raise ValueError('sample native completion has fault, nested or reserved bits')
    return struct.pack('<15IQ4I', 0, command, completion, *gnx, append, legacy, status, irq, 1)


def held_bytes(value):
    required_flags(value, dict(schema='opengpu-native-sample-owner-held-v1', phase='held'))
    if any(key in value for key in ('retiredStatus', 'retiredIrq', 'retiredIrqAsserted')):
        raise ValueError('premature native sample retirement')
    return b'OGNSH001' + _held_payload(value)


def retired_bytes(value, held):
    # Revalidate the original receipt rather than trusting a previously encoded
    # mutable dictionary, and preserve every raw successful GNX reader field.
    held_bytes(held)
    required_flags(value, dict(schema='opengpu-native-sample-owner-retired-v1', phase='retired',
        externalDrained=True, retiredIrqAsserted=False))
    payload = _held_payload(value)
    if payload != _held_payload(held):
        raise ValueError('sample retirement changed the original held receipt')
    status, irq = uint(value.get('retiredStatus')), uint(value.get('retiredIrq'))
    if status != 1 or irq != 1:
        raise ValueError('sample native retirement is not idle and IRQ-free')
    return b'OGNSR001' + payload + struct.pack('<4I', status, irq, 0, 1)


def cleanup_authorization():
    return b'OGNSBA01' + struct.pack('<I', 1)


def final_authorization():
    return b'OGNSOK01' + struct.pack('<I', 1)
