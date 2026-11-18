"""Strict opcode14 observations. Metadata validation is not GPU execution."""
import struct
from compute_owner_protocol import uint, required_flags

OPCODE = 14
RESULTS = (0, 1, 12, 13)
FLAGS = dict(record=0, opcode=OPCODE, privateCountsRequired=True,
             sourceQualificationRequired=True, heldCompletionDrained=True,
             readOnlyGuards=True, independentBytesRequired=True, resetCount=1,
             clockQualified=False, fpsQualified=False)
FIELDS = {'schema', 'phase', 'commandId', 'resultStatus', 'completion', 'processedBytes',
          'heldLegacyStatus', 'heldStatus', 'heldIrq', 'heldIrqAsserted', *FLAGS}
METRICS = {'serviceCycles', 'readBusBytes', 'writeBusBytes', 'physicalReadFaults',
           'injectedReadFaults', 'physicalWriteFaults'}
RETIRED = {'retiredStatus', 'retiredIrq', 'retiredIrqAsserted', 'externalDrained'}


def completion(command, status=0):
    command, status = uint(command), uint(status)
    if not 0 < command < 256 or status not in RESULTS:
        raise ValueError('invalid opcode14 identity/result')
    return ((OPCODE & 7) << 8 | (OPCODE >> 3) << 16 | status << 11 |
            (0 if status else 0x8000) | command)


def fields(value, phase):
    required_flags(value, dict(FLAGS, schema='opengpu-compute-indirect-' + phase + '-v1', phase=phase))
    mandatory = FIELDS | (RETIRED if phase == 'retired' else set())
    if not mandatory <= set(value) or set(value) - mandatory - METRICS:
        raise ValueError('missing/foreign indirect observation fields')
    for key in METRICS & set(value):
        uint(value[key], 64)
    command, status = uint(value['commandId']), uint(value['resultStatus'])
    expected = completion(command, status)
    if uint(value['completion']) != expected or uint(value['processedBytes'], 64) or uint(value['heldLegacyStatus']):
        raise ValueError('wrong opcode14 completion/telemetry')
    held_status = uint(value['heldStatus'])
    if not held_status & 2 or held_status & ~0x23 or uint(value['heldIrq']) != 3 or value['heldIrqAsserted'] is not True:
        raise ValueError('indirect held IRQ/drain observation required')
    return struct.pack('<IIIIQIIII', command, OPCODE, status, expected, 0, 0, held_status, 3, 1)


def held_bytes(value):
    return b'OGCIH001' + fields(value, 'held')


def retired_bytes(value, held):
    original = fields(held, 'held')
    payload = fields(value, 'retired')
    if payload != original:
        raise ValueError('indirect retirement changed its held observation')
    if (uint(value['retiredStatus']) != 1 or uint(value['retiredIrq']) != 1 or
            value['retiredIrqAsserted'] is not False or value['externalDrained'] is not True):
        raise ValueError('indirect retirement must be drained and idle')
    return b'OGCIR001' + payload + struct.pack('<IIII', 1, 1, 0, 1)
