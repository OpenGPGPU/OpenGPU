"""Original contended image AMOs after a committed write returns an error."""
import struct
import sys
from compute_atomic_order import linearization, result
from compute_contended_image_cases import CASES, parameters, operations
from compute_global_accepted_recovery import COUNTERS, history, check_round as accepted_round
from compute_image_source import recipe, source_view, texel_address
from compute_owner_oracle import verify_byte_changes
from compute_owner_protocol import uint
from compute_storage_recovery import BASE, STEP, require

SOURCES=CASES
PHASES=tuple(dict(index=2*n+k,case=case,mode=mode,base=BASE+(2*n+k)*STEP)
    for n,case in enumerate(SOURCES) for k,mode in enumerate(('accepted-write','healthy')))
ROUND_SCHEMA='opengpu-compute-image-accepted-recovery-round-v1'
PLAN_NAME='image-accepted-recovery.plan'
RUNNER_FLAG='--active-image-accepted-recovery-matrix'
LABEL='image accepted-write recovery'
INACTIVE_CONTEXTS=1


def phase(index):
    require(type(index) is int and 0<=index<len(PHASES),'unknown image accepted-write phase')
    return PHASES[index]


def matrix_plan():
    require(len(SOURCES)==55 and len(PHASES)==110,'original image accepted-write matrix changed')
    return struct.pack('<8sIIQQ',b'OGCIAM01',110,2,BASE,STEP)


def source_launch(index,build,session,profile):
    stage=phase(index)
    command=[str(build/'compute_host_frontend'),str(profile),str(session/('round-'+str(index))),
        str(build/'glsl'/parameters(stage['case'])['source']),stage['case']]
    if stage['mode']=='accepted-write':command+=['--expect-image-write-fault']
    return command+['--physical-base',str(stage['base'])]


def check_source_launch(command,index,build,session,profile):
    require(command==source_launch(index,build,session,profile),
        'image accepted-write source/order/profile/session/fault/allocator changed')


def fault_plan(description,mode):
    require(mode=='accepted-write','foreign image accepted-write fault mode')
    p=parameters(description.get('sourceCase'));image=description['images'][0]
    first,size=uint(image['allocationPhysical']),uint(image['allocationBytes'])
    binding,view=source_view(p,0,uint(image['nativeUnit']))
    require(image['view']==view and image['bindingBytes']==binding and image['apiBinding']==3 and
        first>0 and first%4096==0 and size==(binding+4095)//4096*4096 and first+size<=1<<32,
        'image fault lost its original atomic view/allocation')
    address=texel_address(image,p,0,0);line=address&~63
    require(first<=line and line+64<=first+size,'image fault target escapes its original allocation')
    return struct.pack('<8sQQI',b'OGCIF002',line,line+64,3)


def healthy_old_words(description):
    return len(operations(description['sourceCase'])[0])


def target_attempts(case):
    return 1+len(operations(case)[0])//2


def fault_receipt(record,index,previous=None):
    stage=phase(index)
    require(stage['mode']=='accepted-write','healthy image phase cannot supply a failed receipt')
    before=history(previous);actual=history(record)
    require(actual==(before[0],before[1]+1,before[2]+1) and
        uint(record.get('faultTargetWriteAttempts'),64)==target_attempts(stage['case']),
        'missing committed first-write error or exact surviving image attempts')
    return actual


def check_round(record,index,held,previous=None):
    return accepted_round(record,index,held,previous,protocol=sys.modules[__name__])


def verify_fault(initial,returned,description,mode,*,target_attempts):
    p,output,images=recipe(initial,description)
    require(p['case'] in SOURCES and mode=='accepted-write','foreign image accepted-write recipe')
    calls,predecessors=operations(p['case']);per_invocation=len(calls)//8
    require(uint(target_attempts,64)==1+4*per_invocation,
        'image accepted-write has an incorrect number of target attempts')
    words=struct.unpack('<%dI'%len(calls),returned.read(output,4*len(calls)))
    active=[]
    for invocation in range(8):
        slots=words[invocation*per_invocation:(invocation+1)*per_invocation]
        require(all(word!=0xdeadbeef for word in slots) or all(word==0xdeadbeef for word in slots),
            'image accepted-write returned only part of an invocation')
        if slots[0]!=0xdeadbeef:active.append(invocation)
    require(active in ([0,1,2,3],[4,5,6,7]),
        'image accepted-write must retain exactly one successful workgroup')
    events=[invocation*per_invocation+step for invocation in active for step in range(per_invocation)]
    order=[sum(1<<j for j,old in enumerate(events) if predecessors[n]&(1<<old)) for n in events]
    target=texel_address(images[0],p,0,0);final=returned.read(target,4)
    valid=False
    for failed in (n for n in range(8) if n not in active):
        # The image unit owns one atomic request until its response. A failed
        # first AMO stops this vector instruction before any other lane commits.
        first=calls[failed*per_invocation]
        committed=result(first['operation'],p['seed'],first['operand'],first['compare'])
        try:
            linearization(committed,struct.unpack('<I',final)[0],
                [calls[n] for n in events],[words[n] for n in events],order)
        except ValueError:
            continue
        valid=True
        break
    require(valid,'image old words/final texel do not follow the failed group\'s committed first AMO')
    changes=[(target,final)]
    changes.extend((output+4*n,struct.pack('<I',words[n])) for n in events)
    for invocation in active:
        last=words[(invocation+1)*per_invocation-1]
        changes.append((texel_address(images[1],p,1,invocation),struct.pack('<I',last)))
    verify_byte_changes(initial,returned,changes)
    return len(events)
