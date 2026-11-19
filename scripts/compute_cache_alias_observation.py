"""Strict read-only cache evidence; values remain the source oracle's job."""
from compute_owner_protocol import required_flags,uint

SCHEMA='opengpu-compute-cache-alias-observation-v1'
UC_SCHEMA='opengpu-compute-uc-cache-alias-observation-v1'
UC_CYCLES=('preAtomicL2InvalidatedCycle','firstAuthoritativeReadRequestCycle','firstAuthoritativeReadResponseCycle')
UC_COUNTS=('uncachedReadBeforeWrites','nonAllocatingWriteResponses','nonAllocatingRetiredWrites')
CYCLES=('warmCycle','invalidateCycle','invalidateAckCycle','firstTargetWriteCycle',
        'writeErrorCycle','l2InvalidatedCycle','refetchRequestCycle','refetchResponseCycle','refillCycle')


def checked_observation(record,*,command_id,phase,target_line,accepted_error,target_attempts,start_cycle,end_cycle):
    def require(ok,message):
        if not ok:raise ValueError('cache alias observation: '+message)
    require(type(accepted_error) is bool,'error mode must be explicit')
    target_line=uint(target_line,64);target_attempts=uint(target_attempts,64)
    start_cycle,end_cycle=uint(start_cycle,64),uint(end_cycle,64)
    require(0<target_line<=0xffffffc0 and not target_line&63,'foreign physical target line')
    require(target_attempts in (3,5,7) if accepted_error else target_attempts==8,
            'foreign original target AMO count')
    expected=dict(schema=SCHEMA,commandId=uint(command_id),phase=uint(phase),targetLine=target_line,
        startCycle=start_cycle,endCycle=end_cycle,
        acceptedWriteError=accepted_error,warmL1Mask=3,warmL2=True,invalidateMask=3,invalidateAckMask=3,
        targetWrites=target_attempts,targetWriteResponses=target_attempts,targetWriteErrors=int(accepted_error),
        refetchedL2=True)
    required_flags(record,expected)
    require(set(record)==set(expected)|set(CYCLES)|{'refetchedL1Mask'},'foreign or missing observation fields')
    mask=uint(record['refetchedL1Mask'])
    require(mask in (1,2,3) if accepted_error else mask==3,'ordinary reads did not refill the actual required L1s')
    warm,inv,ack,write,error,invalid,read,response,refill=(uint(record[key],64) for key in CYCLES)
    require(start_cycle<warm<=inv<ack<write<refill<end_cycle,'cache warming/invalidation/refill clock restarted or unordered')
    if accepted_error:
        require(write<error<invalid<=read<response<refill,
                'write error lacks actual L2 invalidation, lower refetch and cache refill')
    else:require(error==invalid==read==response==0,'healthy observation contains a failed-write history')
    return record


def checked_uc_observation(record,**expected):
    """UC RMWs drop the warmed L2 line before snooping L1, then bypass it.

    Cached observations cannot substitute for the per-write lower-read and
    nonallocation witnesses. The shared history/byte gates remain unchanged.
    """
    required_flags(record,dict(schema=UC_SCHEMA))
    common={key:value for key,value in record.items() if key not in (*UC_CYCLES,*UC_COUNTS)}
    checked_observation(dict(common,schema=SCHEMA),**expected)
    if set(record)!=set(common)|set(UC_CYCLES)|set(UC_COUNTS):
        raise ValueError('UC cache alias observation: missing bypass fields')
    invalid,read,response=(uint(record[key],64) for key in UC_CYCLES)
    if not record['warmCycle']<invalid<=record['invalidateCycle'] or not (
            record['invalidateAckCycle']<read<response<record['firstTargetWriteCycle']):
        raise ValueError('UC cache alias observation: early invalidation or authoritative read unordered')
    for key in UC_COUNTS:
        if uint(record[key],64)!=expected['target_attempts']:
            raise ValueError('UC cache alias observation: missing per-write bypass witness: '+key)
    return record
