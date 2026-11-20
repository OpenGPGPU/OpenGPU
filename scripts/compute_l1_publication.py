"""Continuous cross-command L1 cached-alias publication receipts for P11."""
import struct

import compute_l1_publication_cases as cases
import compute_l1_publication_source as source
from compute_cache_alias_observation import checked_observation
from compute_owner_protocol import required_flags, uint
from compute_owner_oracle import verify_byte_changes

CASES = cases.CASES
BASE = 0x01000000
PHASES = tuple(dict(index=index, case=case, role=cases.parameters(case)['role'],
                    base=BASE + index * 0x01000000)
               for index, case in enumerate(CASES))
ROUND_SCHEMA = 'opengpu-compute-l1-publication-round-v1'
PLAN_MAGIC = b'OGCLPM01'
TARGET_MAGIC = b'OGCLPT01'
CACHE_SCHEMA = 'opengpu-compute-l1-publication-cache-observation-v1'


def require(ok, message):
    if not ok:
        raise ValueError('L1 publication: ' + message)


def phase(index):
    require(type(index) is int and 0 <= index < len(PHASES), 'unknown L1 publication phase')
    return PHASES[index]


def matrix_plan():
    require(len(PHASES) == 3, 'original L1 publication phase matrix changed')
    return struct.pack('<8sIIQ', PLAN_MAGIC, 3, 1, BASE)


def retained_extents(start, end):
    require(type(start) is int and type(end) is int and 0 <= start < end <= (1 << 40) and
            not ((start | end) & 4095), 'invalid retained publication extent')
    return struct.pack('<QQ', start, end)


def source_launch(index, build, session, profile):
    stage = phase(index)
    inputs = build / 'inputs'
    return [str(build / 'compute_global_host'), str(session / ('round-' + str(index))),
            str(profile), str(inputs / (stage['case'] + '.bin')),
            str(inputs / (stage['case'] + '.kernarg')), stage['case'],
            '--cached-kernarg', '--uc-cache-alias-source', '--l1-publication-source',
            '--uc-cache-alias-live',
            '--physical-base', str(stage['base'])]


def check_source_launch(command, index, build, session, profile):
    require(command == source_launch(index, build, session, profile),
            'L1 publication source/order/profile/allocator changed')


def target_plan(description, index):
    phase(index)
    return struct.pack('<8sQ', TARGET_MAGIC, source.target_line(description))


def check_round(record, index, held, previous=None):
    stage = phase(index)
    expected = dict(schema=ROUND_SCHEMA, phase=index, role=stage['role'],
                    commandId=uint(held['commandId']), resetCount=1, ramReplaced=False,
                    externalDrained=True, sourceQualificationRequired=True,
                    independentBytesRequired=True)
    required_flags(record, expected)
    require(set(record) == set(expected) | {'startCycle', 'endCycle'},
            'foreign/missing L1 publication round fields')
    require((index == 0) == (previous is None), 'phase lacks its original predecessor')
    start, end = (uint(record[key], 64) for key in ('startCycle', 'endCycle'))
    require(end > start, 'phase lost the actual execution clock')
    if previous is not None:
        require(previous['phase'] == index - 1 and start >= previous['endCycle'] and
                record['commandId'] == previous['commandId'],
                'DUT clock/command identity restarted')
    return record


def verify_phase(initial, returned, description, role):
    require(role in ('warm', 'producer', 'reload'), 'unknown L1 publication role')
    changes = source.returned_outputs(initial, returned, description)
    verify_byte_changes(initial, returned, changes)


def check_cache(record, index, description, held, previous=None, done=None):
    start, end = (uint(record.get(key), 64) for key in ('startCycle', 'endCycle'))
    require(previous is None or start >= uint(previous['endCycle'], 64),
            'L1 observation clock restarted across commands')
    if done is not None:
        require(start == uint(done['startCycle'], 64) and end <= uint(done['endCycle'], 64),
                'L1 observation left its native publication round')
    expected = dict(schema=CACHE_SCHEMA, commandId=uint(held['commandId']), phase=uint(index),
                    targetLine=uint(source.target_line(description), 64),
                    startCycle=start, endCycle=end, warmL1Mask=3, warmL2=True,
                    invalidateMask=3, invalidateAckMask=3, refilledL1Mask=3, refilledL2=True,
                    ordinaryL1AliasRequired=True, uncachedStoragePath=True)
    required_flags(record, expected)
    cycles = ('warmCycle', 'invalidateCycle', 'invalidateAckCycle', 'firstTargetWriteCycle',
              'reloadReadRequestCycle', 'reloadReadResponseCycle', 'refillCycle')
    require(set(record) == set(expected) | set(cycles), 'foreign or missing L1 observation fields')
    warm, inv, ack, write, read, response, refill = (uint(record[key], 64) for key in cycles)
    require(start < warm <= inv < ack < write < read < response <= refill < end,
            'L1 warm/invalidate/ACK/write/reload/refill clock unordered')
    return record
