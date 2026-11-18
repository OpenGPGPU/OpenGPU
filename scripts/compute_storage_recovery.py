"""Original-source atomic fault/reuse semantics and continuous RAM receipts."""
import struct
from compute_atomic_order import linearization
from compute_owner_protocol import required_flags, uint
from compute_owner_oracle import verify_byte_changes
from compute_storage_atomic_cases import CASES, parameters, invocations
from compute_storage_atomic_delay import target_line
from compute_storage_atomic_source import recipe

BASE = 0x01000000
STEP = 0x01000000
SOURCES = tuple(case for case in CASES if not parameters(case)['result_status'])
PHASES = tuple(dict(index=4*n+k, case=case, mode=mode, base=BASE+(4*n+k)*STEP)
    for n, case in enumerate(SOURCES) for k, mode in enumerate(('read','healthy','write','healthy')))
ROUND_SCHEMA = 'opengpu-compute-storage-recovery-round-v1'
COUNTERS = ('physicalReadFaults','injectedReadFaults','physicalWriteFaults','injectedWriteFaults')


def require(ok, message):
    if not ok:
        raise ValueError('storage recovery: '+message)


def phase(index):
    require(type(index) is int and 0 <= index < len(PHASES), 'unknown original phase')
    return PHASES[index]


def matrix_plan():
    require(len(SOURCES)==15 and len(PHASES)==60, 'original recovery source matrix changed')
    return struct.pack('<8sIIQQ', b'OGCSRM01', 60, 4, BASE, STEP)


def check_source_launch(command, index, build, session, profile):
    stage=phase(index)
    expected=[str(build/'compute_host_frontend'),str(profile),str(session/('round-'+str(index))),
        str(build/'glsl'/parameters(stage['case'])['source']),stage['case']]
    if stage['mode']!='healthy':
        expected+=['--expect-storage-fault',stage['mode']]
    expected+=['--physical-base',str(stage['base'])]
    require(command==expected, 'original source/order/profile/session/fault/allocator changed')


def fault_plan(description, mode):
    require(mode in ('read','write'), 'unknown physical response fault')
    return struct.pack('<8sQQI', b'OGCSF001', *target_line(description), 1 if mode=='read' else 2)


def history(record):
    if record is None:
        return 0, 0
    r, ir, w, iw = (uint(record.get(key), 64) for key in COUNTERS)
    require(r==ir and w==iw, 'unattributed physical response error')
    return r, w


def fault_receipt(record, index, previous=None):
    stage=phase(index)
    require(stage['mode'] in ('read','write'), 'healthy phase cannot supply failed receipts')
    prior_r, prior_w=history(previous)
    r, w=history(record)
    if stage['mode']=='read':
        require(r>prior_r and w==prior_w and uint(record.get('faultTargetWriteAttempts'),64)==0,
                'missing original read error or unexpected target write')
    else:
        require(r==prior_r and w==prior_w+1 and uint(record.get('faultTargetWriteAttempts'),64)==5,
                'missing one-shot B error, precise target attempts or unexpected read error')
    return r, w


def check_round(record, index, held, previous=None):
    stage=phase(index)
    expected=dict(schema=ROUND_SCHEMA, phase=index, mode=stage['mode'],
        commandId=uint(held['commandId']), resetCount=1, ramReplaced=False,
        externalDrained=True, sourceQualificationRequired=True, independentBytesRequired=True)
    required_flags(record, expected)
    require(set(record)==set(expected)|set(COUNTERS)|{'startCycle','endCycle'}, 'foreign/missing round fields')
    require((index==0)==(previous is None), 'phase lacks its original predecessor')
    before=history(previous)
    actual=history(record)
    wanted=before if stage['mode']=='healthy' else fault_receipt(held,index,previous)
    require(actual==wanted, 'fault history changed through retirement or cleanup')
    start,end=(uint(record[key],64) for key in ('startCycle','endCycle'))
    require(end>start, 'phase lost the actual execution clock')
    if previous is not None:
        require(previous['phase']==index-1 and start>=previous['endCycle'] and
            record['commandId']==previous['commandId'], 'DUT clock/command identity restarted')
    return record


def verify_fault(initial, returned, description, mode):
    p,target,output=recipe(initial,description)
    require(not p['result_status'] and mode in ('read','write'), 'foreign fault recipe')
    if mode=='read':
        verify_byte_changes(initial,returned,[])
        return
    words=struct.unpack('<8I',returned.read(output,32))
    active=[n for n,word in enumerate(words) if word!=0xa7a7a7a7]
    require(active in ([0,1,2,3],[4,5,6,7]),
            'write fault did not retain exactly one healthy workgroup: active=%r old=%r'%(active,words))
    final=returned.read(target+p['target_offset'],4)
    operations=invocations(p['case'])
    try:
        linearization(p['seed'],struct.unpack('<I',final)[0],[operations[n] for n in active],[words[n] for n in active])
    except ValueError as error:
        raise ValueError('nonlinearizable partial source=%s old=%r final=%#x'%(
            p['case'],words,struct.unpack('<I',final)[0])) from error
    changes=[(target+p['target_offset'],final)]
    changes.extend((output+4*n,struct.pack('<I',words[n])) for n in active)
    verify_byte_changes(initial,returned,changes)
