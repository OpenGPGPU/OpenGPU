"""Original scalar AMO physical faults and continuous complete-owner receipts."""
import struct
from compute_atomic_order import linearization
from compute_global_atomic_cases import CASES, events, predecessors, OUTPUT_GUARD, OUTPUT_OFFSET
from compute_global_atomic_source import recipe
from compute_owner_oracle import verify_byte_changes
from compute_owner_protocol import required_flags, uint
from compute_storage_atomic_delay import target_line

BASE, STEP = 0x01000000, 0x01000000
PHASES = tuple(dict(index=4*n+k, case=case, mode=mode, base=BASE+(4*n+k)*STEP)
    for n, case in enumerate(CASES) for k, mode in enumerate(('read', 'healthy', 'write', 'healthy')))
ROUND_SCHEMA = 'opengpu-compute-global-recovery-round-v1'
COUNTERS = ('physicalReadFaults', 'injectedReadFaults', 'physicalWriteFaults', 'injectedWriteFaults')


def require(ok, message):
    if not ok:
        raise ValueError('global recovery: '+message)


def phase(index):
    require(type(index) is int and 0 <= index < len(PHASES), 'unknown original phase')
    return PHASES[index]


def matrix_plan():
    require(len(CASES)==24 and len(PHASES)==96, 'original global recovery matrix changed')
    return struct.pack('<8sIIQQ', b'OGCGRM01', 96, 4, BASE, STEP)


def source_launch(index, build, session, profile):
    stage=phase(index)
    inputs=build/'inputs'
    command=[str(build/'compute_global_host'), str(session/('round-'+str(index))), str(profile),
        str(inputs/(stage['case']+'.bin')), str(inputs/(stage['case']+'.kernarg')), stage['case']]
    if stage['mode']!='healthy':
        command+=['--expect-global-fault',stage['mode']]
    return command+['--physical-base',str(stage['base'])]


def check_source_launch(command, index, build, session, profile):
    require(command==source_launch(index,build,session,profile),
            'original source/order/profile/session/fault/allocator changed')


def fault_plan(description, mode):
    require(mode in ('read','write'), 'unknown physical response fault')
    return struct.pack('<8sQQI', b'OGCGF001', *target_line(description), 1 if mode=='read' else 2)


def history(record):
    if record is None:
        return 0,0
    r,ir,w,iw=(uint(record.get(key),64) for key in COUNTERS)
    require(r==ir and w==iw, 'unattributed physical response error')
    return r,w


def fault_receipt(record, index, previous=None):
    stage=phase(index)
    require(stage['mode'] in ('read','write'), 'healthy phase cannot supply failed receipts')
    prior_r,prior_w=history(previous)
    r,w=history(record)
    attempts=uint(record.get('faultTargetWriteAttempts'),64)
    if stage['mode']=='read':
        require(r>prior_r and w==prior_w and attempts==0,
                'missing original read error or unexpected target write')
    else:
        # One failed first AMO plus two successful AMOs from each remaining
        # scalar caller. L2 also acknowledges lower writes for CAS misses.
        require(r==prior_r and w==prior_w+1 and attempts==7,
                'missing one-shot B error, exact seven attempts or unexpected read error')
    return r,w


def check_round(record, index, held, previous=None):
    stage=phase(index)
    expected=dict(schema=ROUND_SCHEMA, phase=index, mode=stage['mode'],
        commandId=uint(held['commandId']), resetCount=1, ramReplaced=False,
        externalDrained=True, sourceQualificationRequired=True, independentBytesRequired=True)
    required_flags(record,expected)
    require(set(record)==set(expected)|set(COUNTERS)|{'startCycle','endCycle'}, 'foreign/missing round fields')
    require((index==0)==(previous is None), 'phase lacks its original predecessor')
    before=history(previous)
    actual=history(record)
    wanted=before if stage['mode']=='healthy' else fault_receipt(held,index,previous)
    require(actual==wanted, 'fault history changed through retirement or cleanup')
    start,end=(uint(record[key],64) for key in ('startCycle','endCycle'))
    require(end>start, 'phase lost its actual execution clock')
    if previous is not None:
        require(previous['phase']==index-1 and start>=previous['endCycle'] and
                record['commandId']==previous['commandId'], 'DUT clock/command identity restarted')
    return record


def verify_fault(initial, returned, description, mode):
    p,kernarg,_=recipe(initial,description)
    require(mode in ('read','write'), 'foreign global fault recipe')
    if mode=='read':
        verify_byte_changes(initial,returned,[])
        return
    words=struct.unpack('<8I',returned.read(kernarg+OUTPUT_OFFSET,32))
    failed=[n for n,word in enumerate(words) if word==OUTPUT_GUARD]
    require(failed in ([0,1],[2,3],[4,5],[6,7]),
            'write fault must retain exactly one failed scalar caller: old=%r'%(words,))
    active=[n for n in range(8) if n not in failed]
    original=predecessors(p['case'])
    order=[sum(1<<j for j,old in enumerate(active) if original[n] & (1<<old)) for n in active]
    final=returned.read(kernarg+p['target_offset'],4)
    calls=events(p['case'])
    linearization(p['seed'],struct.unpack('<I',final)[0],
                  [calls[n] for n in active],[words[n] for n in active],order)
    changes=[(kernarg+p['target_offset'],final)]
    changes.extend((kernarg+OUTPUT_OFFSET+4*n,struct.pack('<I',words[n])) for n in active)
    verify_byte_changes(initial,returned,changes)
