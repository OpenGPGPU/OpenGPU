"""Continuous original-GEM phase receipts; separate from whole-MMU cleanup."""
import struct
from compute_owner_protocol import required_flags, uint

KIND = 'compute-indirect'
PHASES = 10


def execution(index):
    if uint(index) >= PHASES:
        raise ValueError('invalid continuous phase')
    return b'OGCIEX01' + struct.pack('<I', index)


def cleanup_authorization(index):
    execution(index)
    return b'OGCIBA01' + struct.pack('<I', index)


def phase_response(value, *, command, context, index):
    execution(index)
    if not 0 < uint(command) < 256 or not 0 < uint(context) < 512:
        raise ValueError('invalid original phase command/context')
    if index == PHASES - 1:
        raise ValueError('final phase requires whole-MMU cleanup')
    expected = dict(schema='opengpu-compute-phase-cleanup-v1', phase='phase-cleanup',
        commandId=uint(command), contextId=uint(context), epoch=index,
        status=1, irq=1, irqAsserted=False, externalDrained=True, quietCycles=32,
        readOnlyGuards=True, privateMappingsRevoked=True, namespaceRetired=True,
        resetCount=1, ramReplaced=False, sourceQualificationRequired=True,
        clockQualified=False, fpsQualified=False)
    required_flags(value, expected)
    if set(value) != set(expected) | {'batches', 'pteClears', 'shootdowns', 'publications'}:
        raise ValueError('foreign or missing continuous cleanup fields')
    for key in ('batches', 'pteClears', 'shootdowns'):
        if not 0 < uint(value[key]) < 65536:
            raise ValueError('missing actual phase revocation ' + key)
    publications = uint(value['publications'])
    if index in (0, 1, 3, 4, 6, 8) and not publications:
        raise ValueError('successful writer lacks actual publication')
    return b'OGCPE001' + struct.pack('<10I', command, context, index, value['batches'],
                                    1, 1, 0, 1, 32, 1)


def execution_result(value, faults):
    required_flags(value, dict(schema='opengpu-compute-indirect-continuous-v1',
        phases=PHASES, resetCount=1, ramReplaced=False, externalDrained=True,
        physicalReadFaults=uint(faults, 64), sourceQualificationRequired=True,
        independentBytesRequired=True, clockQualified=False, fpsQualified=False))
    if not faults:
        raise ValueError('continuous execution lacks actual injected read failure')
