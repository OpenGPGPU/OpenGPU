"""Continuous same-GEM buffer-texture publication receipts for complete-owner P11."""
import struct
from compute_owner_protocol import required_flags, uint
from compute_owner_oracle import verify_byte_changes
from compute_texture_publication_cases import CASES, parameters

BASE = 0x01000000
PHASES = tuple(
    dict(index=index, case=case, role=parameters(case)['role'], base=BASE)
    for index, case in enumerate(CASES)
)
ROUND_SCHEMA = 'opengpu-compute-texture-publication-round-v1'
PLAN_MAGIC = b'OGCTPM01'


def require(ok, message):
    if not ok:
        raise ValueError('texture publication: ' + message)


def phase(index):
    require(type(index) is int and 0 <= index < len(PHASES), 'unknown original phase')
    return PHASES[index]


def matrix_plan():
    require(len(PHASES) == 3 and all(stage['base'] == BASE for stage in PHASES),
            'original texture publication phase matrix changed')
    return struct.pack('<8sIIQ', PLAN_MAGIC, 3, 1, BASE)


def retained_extents(start, end):
    require(type(start) is int and type(end) is int and 0 <= start < end <= (1 << 40) and
            not ((start | end) & 4095), 'invalid retained texture extent')
    return struct.pack('<QQ', start, end)


def check_source_launch(command, index, build, session, profile):
    stage = phase(index)
    expected = [
        str(build / 'compute_host_frontend'), str(profile),
        str(session / ('round-' + str(index))),
        str(build / 'glsl' / parameters(stage['case'])['source']),
        stage['case'], '--physical-base', str(stage['base']),
    ]
    require(command == expected, 'original source/order/profile/session/allocator changed')


def check_round(record, index, held, previous=None):
    stage = phase(index)
    expected = dict(
        schema=ROUND_SCHEMA, phase=index, role=stage['role'],
        commandId=uint(held['commandId']), resetCount=1, ramReplaced=False,
        externalDrained=True, sourceQualificationRequired=True,
        independentBytesRequired=True)
    required_flags(record, expected)
    require(set(record) == set(expected) | {'startCycle', 'endCycle'},
            'foreign/missing texture publication round fields')
    require((index == 0) == (previous is None), 'phase lacks its original predecessor')
    start, end = (uint(record[key], 64) for key in ('startCycle', 'endCycle'))
    require(end > start, 'phase lost the actual execution clock')
    if previous is not None:
        require(previous['phase'] == index - 1 and start >= previous['endCycle'] and
                record['commandId'] == previous['commandId'],
                'DUT clock/command identity restarted')
    return record


def verify_phase(initial, returned, description, role):
    require(role in ('warm', 'producer', 'reload'), 'unknown publication role')
    published = description['image'] if role == 'producer' else description['texture']
    output = description['output']
    data_off = uint(published['allocationPhysical']) + 4
    flag_off = uint(published['allocationPhysical']) + 17 * 4
    out0 = uint(output['allocationPhysical']) + uint(output['offset'])
    out1 = out0 + 4
    if role == 'warm':
        require(struct.unpack('<I', returned.read(out0, 4))[0] == 0 and
                struct.unpack('<I', returned.read(out1, 4))[0] == 0,
                'warm consumer missed the original zero data/flag words')
        verify_byte_changes(initial, returned, [
            (out0, struct.pack('<I', 0)), (out1, struct.pack('<I', 0))])
        return
    expected_data = parameters(CASES[1])['expected_data']
    expected_flag = parameters(CASES[1])['expected_flag']
    require(struct.unpack('<I', returned.read(data_off, 4))[0] == expected_data and
            struct.unpack('<I', returned.read(flag_off, 4))[0] == expected_flag,
            'publication data/flag words differ from the original Mesa source')
    changes = [
        (data_off, struct.pack('<I', expected_data)),
        (flag_off, struct.pack('<I', expected_flag)),
    ]
    require(struct.unpack('<I', returned.read(out0, 4))[0] == expected_data and
            struct.unpack('<I', returned.read(out1, 4))[0] == expected_flag,
            'publication output mirror missed published data/flag words')
    changes += [
        (out0, struct.pack('<I', expected_data)),
        (out1, struct.pack('<I', expected_flag)),
    ]
    verify_byte_changes(initial, returned, changes)
