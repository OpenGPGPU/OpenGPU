"""Bind an actual delayed B response to the original source's atomic word."""
import struct
from compute_owner_protocol import required_flags, uint
from compute_storage_atomic_cases import parameters
from compute_global_atomic_cases import CASES as GLOBAL_CASES, parameters as global_parameters, OUTPUT_OFFSET
from compute_global_atomic_source import SCHEMA as GLOBAL_SOURCE_SCHEMA

SCHEMA = 'opengpu-compute-storage-ack-delay-v1'
HOLD_CYCLES = 37
GLOBAL_SCHEMA = 'opengpu-compute-global-ack-delay-v1'
GLOBAL_HOLD_CYCLES = 257


def global_outputs(description):
    required_flags(description,dict(schema=GLOBAL_SOURCE_SCHEMA,groups=4,local=16,
        viewBytes=8192,guard=0xdeadbeef,context=1))
    view=description['kernarg']
    required_flags(view,dict(handle=2,allocationBytes=8192,offset=0,bytes=8192))
    first=uint(view['allocationPhysical'])
    if not first or first&4095 or first+8192>1<<32:
        raise ValueError('global ACK requires the exact original writable kernarg')
    return first+OUTPUT_OFFSET,first+OUTPUT_OFFSET+32,0xdeadbeef


def target_line(description):
    if description.get('sourceCase') in GLOBAL_CASES:
        p=global_parameters(description['sourceCase'])
        first=global_outputs(description)[0]-OUTPUT_OFFSET
        line=(first+p['target_offset'])&~63
        return line,line+64
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
    if description.get('sourceCase') in GLOBAL_CASES:
        return struct.pack('<8sQQIQQI',b'OGCGACK1',*target_line(description),GLOBAL_HOLD_CYCLES,
                           *global_outputs(description))
    return struct.pack('<8sQQI', b'OGCSACK1', *target_line(description), HOLD_CYCLES)


def check_report(record, description, command_id):
    first, end = target_line(description)
    global_mode=description.get('sourceCase') in GLOBAL_CASES
    hold=GLOBAL_HOLD_CYCLES if global_mode else HOLD_CYCLES
    expected = dict(schema=GLOBAL_SCHEMA if global_mode else SCHEMA, commandId=uint(command_id), targetLineStart=first,
        targetLineEnd=end, requestedHoldCycles=hold, externalDrained=True)
    if global_mode:
        old_first,old_end,guard=global_outputs(description)
        expected.update(oldOutputStart=old_first,oldOutputEnd=old_end,oldOutputGuard=guard,oldValuesHeldUntilAck=True)
    required_flags(record, expected)
    if set(record) != set(expected) | {'heldWriteCycles', 'statusBeforeRelease'}:
        raise ValueError('foreign or missing storage ACK report fields')
    if not hold <= uint(record['heldWriteCycles'], 64) <= 4096:
        raise ValueError('actual due storage ACK was not held for the bounded interval')
    # GPU_UCMD_STATUS_COMPLETION is bit 1 in the original public ABI.
    if uint(record['statusBeforeRelease']) & 2:
        raise ValueError('storage atomic completed before ACK release')
