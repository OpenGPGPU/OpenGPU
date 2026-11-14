"""Two-owner ordinary ABI1 graphics receipts on the exact protected112 host.

This codec observes control values only. It executes no shader and grants no
cleanup permission until a caller has checked both independent image/GNY2
oracles. The single-record sample and old96 wires remain separate.
"""
import struct

from compute_owner_protocol import uint, required_flags, strict_json
from native_sample_owner_protocol import profile_bytes
from owner_resource_contract import SAMPLE_PROFILE

RECORDS = 2
PREFIX = 'opengpu-native-sample-graphics-'


def admission_bytes(data, record=1):
    """Caller must bind this capture to the checked original owner first."""
    if type(record) is not int or record != 1 or type(data) is not bytes or not 0 < len(data) <= 2097152:
        raise ValueError('ordinary112 admission requires the bounded second capture')
    return b'OGNGA001' + struct.pack('<II', record, len(data)) + data


def _payload(value, record):
    if type(record) is not int or not 0 <= record < RECORDS:
        raise ValueError('ordinary112 record must be zero or one')
    required_flags(value, dict(ownerProfile=SAMPLE_PROFILE, record=record,
        recordCount=RECORDS, resetCount=1, sourceQualificationRequired=True,
        heldCompletionDrained=True, readOnlyGuards=True, heldIrqAsserted=True,
        clockQualified=False, fpsQualified=False))
    command = uint(value.get('commandId'))
    completion = uint(value.get('completion'))
    append = uint(value.get('appendReadBytes'), 64)
    legacy, status, irq = (uint(value.get(k)) for k in
                           ('heldLegacyStatus', 'heldStatus', 'heldIrq'))
    gnx = value.get('gnx')
    if (not 0 < command < 256 or completion != (0x8700 | command) or
            append & 63 or append > 1 << 33 or legacy or not status & 2 or
            status & ~0x23 or irq != 3 or type(gnx) is not list or len(gnx) != 12):
        raise ValueError('invalid ordinary112 held completion')
    gnx = [uint(word) for word in gnx]
    if gnx[0] != 0x474e5801 or any(gnx[i] for i in (1, 4, 5, 9, 10, 11)):
        raise ValueError('ordinary112 GNX fault or reserved bits')
    return struct.pack('<15IQ4I', record, command, completion, *gnx,
                       append, legacy, status, irq, 1)


def held_bytes(value, record):
    required_flags(value, dict(schema=PREFIX+'held-v1', phase='held'))
    if any(k in value for k in ('retiredStatus', 'retiredIrq',
                                'retiredIrqAsserted', 'externalDrained')):
        raise ValueError('premature ordinary112 retirement')
    return b'OGNGH001' + struct.pack('<I', 1) + _payload(value, record)


def _retired_payload(value, held, record):
    held_bytes(held, record)
    required_flags(value, dict(schema=PREFIX+'retired-v1', phase='retired',
        externalDrained=True, retiredIrqAsserted=False, resets=1,
        resetsBetweenRecords=False, independentImagesRequired=True))
    payload = _payload(value, record)
    if payload != _payload(held, record):
        raise ValueError('ordinary112 retirement changed its held observation')
    status, irq = uint(value.get('retiredStatus')), uint(value.get('retiredIrq'))
    if status != 1 or irq != 1:
        raise ValueError('ordinary112 retirement must be idle and IRQ-free')
    return payload + struct.pack('<4I', status, irq, 0, 1)


def retired_bytes(value, held, record):
    return b'OGNGR001' + struct.pack('<I', 1) + _retired_payload(value, held, record)


def cleanup_authorization(retired, held):
    """Caller must first verify both independent image/GNY2 results.

    This validates the complete two-record control set; it cannot establish
    source/image/GNY2 correctness by itself.
    """
    _pair(retired, held)
    return b'OGNGB001' + struct.pack('<I', RECORDS)


def _pair(retired, held):
    if type(retired) is not list or type(held) is not list or len(retired) != RECORDS or len(held) != RECORDS:
        raise ValueError('ordinary112 requires exactly two held and retired receipts')
    payloads = [_retired_payload(retired[i], held[i], i) for i in range(RECORDS)]
    if retired[0]['commandId'] == retired[1]['commandId']:
        raise ValueError('ordinary112 command identity was reused')
    return payloads


def final_authorization(retired, held):
    """Encode the final observations only after actual cleanup was checked."""
    return b'OGNGF001' + struct.pack('<I', RECORDS) + b''.join(_pair(retired, held))


def checked_cleanup(value):
    required_flags(value, dict(phase='cleanup', ownerCleanupObserved=True,
        contextCleanupObserved=True, records=RECORDS, resetCount=1,
        ramReplaced=False, imagesPreserved=True, externalDrained=True,
        sourceQualificationRequired=True, clockQualified=False, fpsQualified=False))
    for key in ('batches', 'pteClears', 'shootdowns'):
        if uint(value.get(key), 64) == 0:
            raise ValueError('ordinary112 cleanup has no actual '+key)
