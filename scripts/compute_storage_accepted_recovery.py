"""Original SSBO invocations after a committed first write returns an error."""
import struct
import sys
from compute_atomic_order import linearization, result
from compute_global_accepted_recovery import COUNTERS, history, check_round as accepted_round
from compute_owner_oracle import verify_byte_changes
from compute_owner_protocol import uint
from compute_storage_atomic_cases import parameters, invocations
from compute_storage_atomic_delay import target_line
from compute_storage_atomic_source import recipe
from compute_storage_recovery import BASE, STEP, SOURCES, require

PHASES=tuple(dict(index=2*n+k,case=case,mode=mode,base=BASE+(2*n+k)*STEP)
    for n,case in enumerate(SOURCES) for k,mode in enumerate(('accepted-write','healthy')))
ROUND_SCHEMA='opengpu-compute-storage-accepted-recovery-round-v1'
PLAN_NAME='storage-accepted-recovery.plan'
RUNNER_FLAG='--active-storage-accepted-recovery-matrix'
LABEL='storage accepted-write recovery'
INACTIVE_CONTEXTS=1


def phase(index):
    require(type(index) is int and 0<=index<len(PHASES),'unknown storage accepted-write phase')
    return PHASES[index]


def matrix_plan():
    require(len(SOURCES)==15 and len(PHASES)==30,'original storage accepted-write matrix changed')
    return struct.pack('<8sIIQQ',b'OGCSAM01',30,2,BASE,STEP)


def source_launch(index,build,session,profile):
    stage=phase(index)
    command=[str(build/'compute_host_frontend'),str(profile),str(session/('round-'+str(index))),
        str(build/'glsl'/parameters(stage['case'])['source']),stage['case']]
    if stage['mode']=='accepted-write':command+=['--expect-storage-fault','write']
    return command+['--physical-base',str(stage['base'])]


def check_source_launch(command,index,build,session,profile):
    require(command==source_launch(index,build,session,profile),
        'storage accepted-write source/order/profile/session/fault/allocator changed')


def fault_plan(description,mode):
    require(mode=='accepted-write','foreign storage accepted-write fault mode')
    return struct.pack('<8sQQI',b'OGCSF002',*target_line(description),3)


def fault_receipt(record,index,previous=None):
    require(phase(index)['mode']=='accepted-write','healthy storage phase cannot supply a failed receipt')
    before=history(previous);actual=history(record)
    require(actual==(before[0],before[1]+1,before[2]+1) and
        uint(record.get('faultTargetWriteAttempts'),64)==5,
        'missing committed first-write error or exact surviving storage attempts')
    return actual


def check_round(record,index,held,previous=None):
    return accepted_round(record,index,held,previous,protocol=sys.modules[__name__])


def verify_fault(initial,returned,description,mode,*,target_attempts):
    p,target,output=recipe(initial,description)
    require(not p['result_status'] and mode=='accepted-write','foreign storage accepted-write recipe')
    require(uint(target_attempts,64)==5,'storage accepted-write requires exactly five target attempts')
    words=struct.unpack('<8I',returned.read(output,32))
    active=[n for n,word in enumerate(words) if word!=0xa7a7a7a7]
    require(active in ([0,1,2,3],[4,5,6,7]),
        'storage accepted-write must retain exactly one successful workgroup')
    calls=invocations(p['case']);final=returned.read(target+p['target_offset'],4)
    # Invocation order inside the failed group is not an API guarantee. One
    # of its four source AMOs committed first; the other three never committed.
    valid=False
    for failed in (n for n in range(8) if n not in active):
        first=calls[failed]
        committed=result(first['operation'],p['seed'],first['operand'],first['compare'])
        try:
            linearization(committed,struct.unpack('<I',final)[0],
                [calls[n] for n in active],[words[n] for n in active])
        except ValueError:
            continue
        valid=True
        break
    require(valid,'storage old words/final target do not follow a committed AMO of the failed group')
    changes=[(target+p['target_offset'],final)]
    changes.extend((output+4*n,struct.pack('<I',words[n])) for n in active)
    verify_byte_changes(initial,returned,changes)
    return len(active)
