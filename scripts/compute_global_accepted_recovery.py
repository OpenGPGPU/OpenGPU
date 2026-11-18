"""Committed first-AMO write with a failed B response, then healthy reuse."""
import struct
import sys
from compute_atomic_order import linearization, result
from compute_global_atomic_cases import CASES, events, predecessors, OUTPUT_OFFSET
from compute_global_atomic_source import recipe
from compute_global_recovery import BASE, STEP, require, surviving_groups
from compute_owner_oracle import verify_byte_changes
from compute_owner_protocol import required_flags, uint
from compute_storage_atomic_delay import target_line

PHASES=tuple(dict(index=2*n+k,case=case,mode=mode,base=BASE+(2*n+k)*STEP)
    for n,case in enumerate(CASES) for k,mode in enumerate(('accepted-write','healthy')))
ROUND_SCHEMA='opengpu-compute-global-accepted-recovery-round-v1'
COUNTERS=('physicalReadFaults','injectedReadFaults','physicalWriteFaults','injectedWriteFaults',
          'acceptedWriteFaults')
PLAN_NAME='global-accepted-recovery.plan'
RUNNER_FLAG='--active-global-accepted-recovery-matrix'
LABEL='global accepted-write recovery'
INACTIVE_CONTEXTS=0


def phase(index):
    require(type(index) is int and 0<=index<len(PHASES), 'unknown accepted-write phase')
    return PHASES[index]


def matrix_plan():
    require(len(CASES)==24 and len(PHASES)==48, 'original accepted-write matrix changed')
    return struct.pack('<8sIIQQ',b'OGCGAM01',48,2,BASE,STEP)


def source_launch(index,build,session,profile):
    stage=phase(index);inputs=build/'inputs'
    command=[str(build/'compute_global_host'),str(session/('round-'+str(index))),str(profile),
        str(inputs/(stage['case']+'.bin')),str(inputs/(stage['case']+'.kernarg')),stage['case']]
    if stage['mode']=='accepted-write':command+=['--expect-global-fault','write']
    return command+['--physical-base',str(stage['base'])]


def check_source_launch(command,index,build,session,profile):
    require(command==source_launch(index,build,session,profile),
            'accepted-write source/order/profile/session/fault/allocator changed')


def fault_plan(description,mode):
    require(mode=='accepted-write','foreign accepted-write fault mode')
    return struct.pack('<8sQQI',b'OGCGF002',*target_line(description),3)


def history(record):
    if record is None:return 0,0,0
    r,ir,w,iw,accepted=(uint(record.get(key),64) for key in COUNTERS)
    require(r==ir==0 and w==iw==accepted,'unattributed or uncommitted accepted-write error')
    return r,w,accepted


def fault_receipt(record,index,previous=None):
    require(phase(index)['mode']=='accepted-write','healthy phase cannot supply a failed receipt')
    before=history(previous);actual=history(record)
    require(actual==(before[0],before[1]+1,before[2]+1) and
        uint(record.get('faultTargetWriteAttempts'),64) in (3,5,7),
        'missing committed first-write error or original surviving attempts')
    return actual


def check_round(record,index,held,previous=None,*,protocol=None):
    contract=sys.modules[__name__] if protocol is None else protocol
    stage=contract.phase(index)
    expected=dict(schema=contract.ROUND_SCHEMA,phase=index,mode=stage['mode'],commandId=uint(held['commandId']),
        resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,independentBytesRequired=True)
    required_flags(record,expected)
    require(set(record)==set(expected)|set(contract.COUNTERS)|{'startCycle','endCycle'},'foreign accepted-write round fields')
    require((index==0)==(previous is None),'accepted-write phase lacks its original predecessor')
    require(contract.history(record)==(contract.history(previous) if stage['mode']=='healthy' else
        contract.fault_receipt(held,index,previous)),
        'accepted-write history changed through retirement or cleanup')
    start,end=(uint(record[key],64) for key in ('startCycle','endCycle'))
    require(end>start,'accepted-write phase lost its execution clock')
    if previous is not None:
        require(previous['phase']==index-1 and start>=previous['endCycle'] and
            record['commandId']==previous['commandId'],'accepted-write DUT clock/command restarted')
    return record


def verify_fault(initial,returned,description,mode,*,target_attempts):
    require(mode=='accepted-write','foreign accepted-write result')
    p,kernarg,_=recipe(initial,description)
    words=struct.unpack('<8I',returned.read(kernarg+OUTPUT_OFFSET,32))
    groups=surviving_groups(words)
    active=[event for group in groups for event in (2*group,2*group+1)]
    require(uint(target_attempts,64)==1+len(active),'accepted target writes differ from returned old words')
    calls=events(p['case']);failed=0 if 0 not in groups else 1
    first=calls[2*failed]
    # The first physical target write precedes all successful RMWs. Its data
    # commits even though its caller receives no old word and reports failure.
    committed=result(first['operation'],p['seed'],first['operand'],first['compare'])
    original=predecessors(p['case'])
    order=[sum(1<<j for j,old in enumerate(active) if original[n] & (1<<old)) for n in active]
    final=returned.read(kernarg+p['target_offset'],4)
    linearization(committed,struct.unpack('<I',final)[0],
        [calls[n] for n in active],[words[n] for n in active],order)
    changes=[(kernarg+p['target_offset'],final)]
    changes.extend((kernarg+OUTPUT_OFFSET+4*n,struct.pack('<I',words[n])) for n in active)
    verify_byte_changes(initial,returned,changes)
    return len(active)
