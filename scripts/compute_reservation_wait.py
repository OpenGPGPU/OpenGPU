"""Original external CPU/GEM producer waits; native evidence stays mandatory."""
import struct
import subprocess
from compute_global_atomic_cases import CASES,bound_words
from compute_global_atomic_source import SCHEMA,CACHED_SCHEMA
from compute_owner_protocol import required_flags,uint,strict_json

BLOCKED_SCHEMA='opengpu-compute-reservation-blocked-v1'
RELEASED_SCHEMA='opengpu-compute-reservation-released-v1'
IDLE_SCHEMA='opengpu-compute-reservation-idle-v1'
QUIET_CYCLES=257
BASE,STEP=0x01000000,0x01000000
PHASES=tuple(dict(index=2*n+k,case=case,cached=bool(k),base=BASE+(2*n+k)*STEP)
    for n,case in enumerate(CASES) for k in range(2))
MODE_FLAGS=('storage_matrix','storage_delayed_ack','storage_recovery_matrix','storage_accepted_matrix',
    'image_accepted_matrix','contended_image_matrix','mixed_atomic_matrix','global_atomic_matrix',
    'global_cached_matrix','global_delayed_ack','global_recovery_matrix','global_accepted_matrix',
    'cache_alias_matrix','uc_cache_alias_matrix','expect_image_source_fault','image_source_recovery',
    'image_permission_recovery','image_reset_recovery')


def require(ok,message):
    if not ok:raise ValueError('reservation wait: '+message)


def phase(index):
    require(type(index) is int and 0<=index<48,'foreign source phase')
    return PHASES[index]


def mode(args,case):
    require(case==CASES[0] and not any(getattr(args,key,False) for key in MODE_FLAGS),
            'requires its distinct first-source matrix')


def source_launch(index,build,session,profile):
    p=phase(index);inputs=build/'inputs'
    return [str(build/'compute_global_host'),str(session/('round-'+str(index))),str(profile),
        str(inputs/(p['case']+'.bin')),str(inputs/(p['case']+'.kernarg')),p['case'],
        '--reservation-wait',*(['--cached-kernarg'] if p['cached'] else []),'--physical-base',str(p['base'])]


def check_source_launch(command,index,build,session,profile):
    require(command==source_launch(index,build,session,profile),'original source/mode/order changed')


def checked_source(record,index,*,released=False,previous=None):
    p=phase(index)
    expected=dict(schema=RELEASED_SCHEMA if released else BLOCKED_SCHEMA,sourceCase=p['case'],
        cachedKernarg=p['cached'],producerKind='external-cpu',executionAttemptStatus=-16,
        context=1,handle=2,objects=1,dependencies=1,pendingDependencies=0 if released else 1,
        reservationDependencies=1,producerDependencies=1,reservationUsage=1,queued=1,
        kernelSubmissions=0,blockedAttempts=1,originalIdentity=True,
        producerStatus=1 if released else 0,consumerStatus=0,producerSequence=1,consumerSequence=1,bytes=8192)
    required_flags(record,expected)
    require(set(record)==set(expected)|{'producerContext','physical'},'foreign source fields')
    require(uint(record['producerContext'],64)>0,'missing external producer context')
    physical=uint(record['physical'],64)
    require(not physical&4095 and p['base']<=physical and physical+8192<=p['base']+STEP,
            'original GEM escaped its allocator namespace')
    if released:
        require(previous is not None,'release lacks its blocked source')
        checked_source(previous,index)
        for key in record:
            if key not in ('schema','producerStatus','pendingDependencies'):
                require(record[key]==previous[key],'source identity changed on release: '+key)
    return record


def source_inputs(directory,index):
    """Bind original private code and allocation identity before a DUT exists."""
    from compute_owner_controller import input_digest
    p=phase(index);blocked=checked_source(strict_json(directory/'reservation.blocked.json'),index)
    d=strict_json(directory/'oracle.json')
    required_flags(d,dict(schema=CACHED_SCHEMA if p['cached'] else SCHEMA,sourceCase=p['case'],context=1))
    required_flags(d['kernarg'],dict(handle=2,allocationPhysical=blocked['physical'],allocationBytes=8192,offset=0,bytes=8192))
    require((directory/'code.words').read_text().splitlines()==['%08x'%w for w in bound_words(p['case'])],
            'original program changed while blocked')
    return blocked,{name:input_digest(directory/name) for name in
        ('reservation.blocked.json','oracle.json','code.words')}


def plan(record,index):
    checked_source(record,index)
    return struct.pack('<8sIIIIQQQQQQ',b'OGCRWP01',index,int(phase(index)['cached']),QUIET_CYCLES,0,
        record['physical'],record['producerContext'],record['producerSequence'],record['consumerSequence'],
        record['context'],record['bytes'])


def checked_idle(record,blocked,index):
    checked_source(blocked,index)
    expected=dict(schema=IDLE_SCHEMA,phase=index,cachedKernarg=phase(index)['cached'],producerKind='external-cpu',
        physical=blocked['physical'],producerContext=blocked['producerContext'],producerSequence=1,
        consumerSequence=1,context=1,bytes=8192,resetCount=1,ramUploadedBeforeRelease=False,
        readyBefore=True,readyAfter=True,irqAsserted=False,externalRequests=0)
    required_flags(record,expected)
    require(set(record)==set(expected)|{'startCycle','endCycle','quietCycles'},'foreign native idle fields')
    start,end,quiet=(uint(record[key],64) for key in ('startCycle','endCycle','quietCycles'))
    require(end>start and end-start==quiet and quiet>=QUIET_CYCLES,'native idle interval is short/restarted')
    return record


def execution_authorization(index):
    phase(index)
    return struct.pack('<8sI',b'OGCRGO01',index)


def checked_done(record,idle,index):
    required_flags(record,dict(schema='opengpu-compute-reservation-done-v1',phase=phase(index)['index'],
        resetCount=1,readyAfterCleanup=True,irqAsserted=False,externalDrained=True,vectorSatp=0,
        instructionSatp=0,physicalReadFaults=0,physicalWriteFaults=0,startCycle=idle['startCycle']))
    require(set(record)=={'schema','phase','resetCount','readyAfterCleanup','irqAsserted','externalDrained',
        'vectorSatp','instructionSatp','physicalReadFaults','physicalWriteFaults','startCycle','endCycle'},
        'foreign cleanup proof fields')
    require(uint(record['endCycle'],64)>uint(idle['endCycle'],64),'DUT clock restarted after producer release')
    return record


def begin_owner(args,local,index,executor,output,bridge,env,remaining):
    from compute_owner_controller import atomic,wait_file,input_digest,unchanged_inputs,stop
    for name in ('reservation.blocked.json','oracle.json','code.words'):
        wait_file(local/name,bridge,None,remaining())
    require(not any((local/name).exists() for name in ('READY','union.memory','record-0.capture','reservation.released.json')),
            'consumer advanced while the original producer is pending')
    blocked,frozen=source_inputs(local,index)
    atomic(local/'reservation.plan',plan(blocked,index));frozen['reservation.plan']=input_digest(local/'reservation.plan')
    running=None
    try:
        running=subprocess.Popen([executor,'--active-reservation-wait',local/'results',local],
            env=env,stdout=output,stderr=subprocess.STDOUT)
        wait_file(local/'reservation.idle.json',bridge,running,remaining())
        checked_idle(strict_json(local/'reservation.idle.json'),blocked,index)
        frozen['reservation.idle.json']=input_digest(local/'reservation.idle.json')
        unchanged_inputs(local,frozen)
        require(not any((local/name).exists() for name in ('READY','union.memory','record-0.capture','reservation.released.json')),
                'producer completed before native idle authorization')
        atomic(local/'RELEASE_RESERVATION',struct.pack('<8sI',b'OGCRRL01',1))
        frozen['RELEASE_RESERVATION']=input_digest(local/'RELEASE_RESERVATION')
        wait_file(local/'READY',bridge,running,remaining())
        wait_file(local/'reservation.released.json',bridge,running,remaining())
        checked_source(strict_json(local/'reservation.released.json'),index,released=True,previous=blocked)
        frozen['reservation.released.json']=input_digest(local/'reservation.released.json')
        unchanged_inputs(local,frozen)
        return running,frozen
    except BaseException:
        stop(running)
        raise
