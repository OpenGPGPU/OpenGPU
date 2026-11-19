"""Checked same-GEM UC sources, actual cache proof and continuous recovery."""
import struct
from compute_global_atomic_cases import CASES
from compute_cache_alias_cases import parameters
from compute_uc_cache_alias_source import SCHEMA, verify_fault
from compute_cache_alias_observation import checked_uc_observation
from compute_global_recovery import BASE,STEP,require
from compute_global_accepted_recovery import (COUNTERS,history,fault_receipt,
    check_round as accepted_round)
from compute_owner_protocol import required_flags,uint

PHASES=tuple(dict(index=2*n+k,case=case,mode=mode,base=BASE+(2*n+k)*STEP)
    for n,case in enumerate(CASES) for k,mode in enumerate(('accepted-write','healthy')))
ROUND_SCHEMA='opengpu-compute-uc-cache-alias-recovery-round-v1'
PLAN_NAME='uc-cache-alias-recovery.plan'
RUNNER_FLAG='--active-uc-cache-alias-recovery-matrix'
LABEL='UC cache alias recovery'
INACTIVE_CONTEXTS=0
CACHE_ALIAS=True
UC_CACHE_ALIAS=True


def phase(index):
    require(type(index) is int and 0<=index<len(PHASES),'foreign cache alias phase')
    return PHASES[index]


def matrix_plan():
    require(len(CASES)==24 and len(PHASES)==48,'original cache alias source matrix changed')
    return struct.pack('<8sIIQQ',b'OGCUAM01',48,2,BASE,STEP)


def source_launch(index,build,session,profile):
    stage=phase(index);inputs=build/'inputs'
    command=[str(build/'compute_global_host'),str(session/('round-'+str(index))),str(profile),
        str(inputs/(stage['case']+'.bin')),str(inputs/(stage['case']+'.kernarg')),stage['case'],
        '--cached-kernarg','--uc-cache-alias-source','--uc-cache-alias-live']
    if stage['mode']=='accepted-write':command+=['--expect-uc-cache-alias-write-error']
    return command+['--physical-base',str(stage['base'])]


def check_source_launch(command,index,build,session,profile):
    require(command==source_launch(index,build,session,profile),'cache alias source/order/profile/mode/allocator changed')


def target_line(description):
    required_flags(description,dict(schema=SCHEMA))
    p=parameters(description.get('sourceCase'));view=description['kernarg']
    physical=uint(view['allocationPhysical'],64)
    require(physical and not physical&4095,'foreign cache alias physical allocation')
    line=physical+(p['target_offset']&~63)
    require(line+64<=physical+8192 and line+64<=1<<32,'cache alias target escapes original argument pages')
    return line


def target_plan(description,index):
    phase(index)
    return struct.pack('<8sIIQ',b'OGCUL001',index,0,target_line(description))


def fault_plan(description,mode):
    require(mode=='accepted-write','foreign cache alias fault mode')
    line=target_line(description)
    return struct.pack('<8sQQI',b'OGCUF001',line,line+64,3)


def check_round(record,index,held,previous=None):
    import sys
    return accepted_round(record,index,held,previous,protocol=sys.modules[__name__])


def check_cache(record,index,description,held,previous=None,done=None):
    failed=phase(index)['mode']=='accepted-write'
    start,end=(uint(record.get(key),64) for key in ('startCycle','endCycle'))
    require(previous is None or start>=uint(previous['endCycle'],64),'cache observation clock restarted across owners')
    if done is not None:
        require(start==uint(done['startCycle'],64) and end<=uint(done['endCycle'],64),
                'cache observation left its native recovery round')
    return checked_uc_observation(record,command_id=held['commandId'],phase=index,
        target_line=target_line(description),accepted_error=failed,
        target_attempts=held['faultTargetWriteAttempts'] if failed else 8,start_cycle=start,end_cycle=end)
