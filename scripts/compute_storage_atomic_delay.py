"""Bind an actual delayed B response to the original source's atomic word."""
import struct
from compute_owner_protocol import required_flags, uint
from compute_storage_atomic_cases import parameters

SCHEMA = 'opengpu-compute-storage-ack-delay-v1'
HOLD_CYCLES = 37


def target_line(description):
    p = parameters(description['sourceCase'])
    target = description['target']
    first = uint(target['allocationPhysical'])
    offset = uint(description['targetOffset'])
    if (p['result_status'] or offset != p['target_offset'] or not first or first & 4095 or
            target['allocationBytes'] != 8192 or first + 8192 > 1 << 32):
        raise ValueError('delayed ACK requires the original writable atomic allocation')
    line = (first + offset) & ~63
    return line, line + 64


def plan_bytes(description):
    return struct.pack('<8sQQI', b'OGCSACK1', *target_line(description), HOLD_CYCLES)


def check_report(record, description, command_id):
    first, end = target_line(description)
    expected = dict(schema=SCHEMA, commandId=uint(command_id), targetLineStart=first,
        targetLineEnd=end, requestedHoldCycles=HOLD_CYCLES, externalDrained=True)
    required_flags(record, expected)
    if set(record) != set(expected) | {'heldWriteCycles', 'statusBeforeRelease'}:
        raise ValueError('foreign or missing storage ACK report fields')
    if not HOLD_CYCLES <= uint(record['heldWriteCycles'], 64) <= 4096:
        raise ValueError('actual due storage ACK was not held for the bounded interval')
    # GPU_UCMD_STATUS_COMPLETION is bit 1 in the original public ABI.
    if uint(record['statusBeforeRelease']) & 2:
        raise ValueError('storage atomic completed before ACK release')
