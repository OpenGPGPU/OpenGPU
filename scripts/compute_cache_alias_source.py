"""Independent values and physical-byte guards for source cache warming.

Ordinary observations are read-only events in the bounded history search.
The source kind stays separate from target AMO attempt accounting. Neither
this oracle nor cached PTE admission establishes actual cache residency.
"""
import struct

import compute_cache_alias_cases as source
from compute_atomic_order import linearization, result
from compute_global_atomic_source import _recipe, _ready
from compute_global_recovery import surviving_groups
from compute_owner_oracle import verify_byte_changes
from compute_owner_protocol import uint

SCHEMA='opengpu-compute-cache-alias-oracle-v1'


def require(ok,message):
    if not ok:raise ValueError('cache alias source: '+message)


def recipe(memory,description):
    return _recipe(memory,description,source,SCHEMA)


def ready(memory,description,data,code_words,profile):
    return _ready(memory,description,data,code_words,profile,source,SCHEMA,True)


def history_events(case,groups):
    """Keep each caller's vector read/AMO order; do not invent a CU schedule."""
    original=source.events(case);events=[];predecessors=[]
    for group in groups:
        first=len(events)
        if group>=2:
            events.append(dict(kind='read',group=group,offset=source.WARM_OFFSET+4*group,
                               operation='add',operand=0,compare=0))
        for index in (2*group,2*group+1):
            events.append(dict(kind='atomic',group=group,offset=source.OUTPUT_OFFSET+4*index,
                               **original[index]))
        events.append(dict(kind='read',group=group,offset=source.REFETCH_OFFSET+4*group,
                           operation='add',operand=0,compare=0))
        predecessors.extend(0 if index==first else 1<<(index-1) for index in range(first,len(events)))
    return events,predecessors


def _outputs(initial,returned,description,*,failed=None,target_attempts=None,recipe_function=None):
    p,kernarg,_=(recipe if recipe_function is None else recipe_function)(initial,description)
    def word(offset):return struct.unpack('<I',returned.read(kernarg+offset,4))[0]
    if failed is None:
        groups=list(range(4));value=p['seed']
    else:
        require(failed=='accepted-write','foreign cache alias fault mode')
        groups=surviving_groups(struct.unpack('<8I',returned.read(kernarg+source.OUTPUT_OFFSET,32)))
        require(uint(target_attempts,64)==1+2*len(groups),'target attempts differ from original AMOs')
        caller=0 if 0 not in groups else 1
        event=source.events(p['case'])[2*caller]
        value=result(event['operation'],p['seed'],event['operand'],event['compare'])
    # Both initial callers must have completed an ordinary vector load and
    # observed the ready barrier before the first target AMO can be issued.
    require(word(source.READY_OFFSET)==2,'both resident callers did not meet the bounded barrier')
    require(all(word(source.WARM_OFFSET+4*g)==p['seed'] for g in (0,1)),
            'initial vector observations differ from the original seed')
    events,order=history_events(p['case'],groups)
    observed=[word(event['offset']) for event in events]
    final=word(p['target_offset'])
    linearization(value,final,
        [{key:event[key] for key in ('operation','operand','compare')} for event in events],observed,order)
    changes=[(kernarg+p['target_offset'],struct.pack('<I',final)),
             (kernarg+source.READY_OFFSET,struct.pack('<I',2)),
             (kernarg+source.WARM_OFFSET,struct.pack('<2I',p['seed'],p['seed']))]
    changes.extend((kernarg+event['offset'],struct.pack('<I',actual)) for event,actual in zip(events,observed))
    return changes,sum(event['kind']=='atomic' for event in events)


def returned_outputs(initial,returned,description):
    return _outputs(initial,returned,description)[0]


def verify_fault(initial,returned,description,mode,*,target_attempts):
    require(mode=='accepted-write','foreign cache alias fault mode')
    outputs,count=_outputs(initial,returned,description,failed=mode,target_attempts=target_attempts)
    verify_byte_changes(initial,returned,outputs)
    return count
