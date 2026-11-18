"""Derived original word operations through checked UC storage aliases.

Ordinary vector loads use the original cached kernarg mapping. Target and
barrier AMOs use the retained GSB1 view of the same GEM, one active lane per
original sixteen-lane workgroup. No literal UC pointer is admitted.
"""
from compute_global_atomic_cases import (CASES, events, _addi, _constant, _bne,
    FENCE, CEASE, OUTPUT_OFFSET)
from compute_cache_alias_cases import (parameters, initial_kernarg, _vector_read,
    _store, _target, _branch_equal, VSET_ONE, READY_OFFSET, WARM_OFFSET, REFETCH_OFFSET,
    POLL_ATTEMPTS, GUARD)

OPERATIONS=('exchange','add','xor','or','and','signed-min','signed-max',
            'unsigned-min','unsigned-max','compare-swap')


def _splat(vector,value):
    return [*_constant(13,value),23<<26|1<<25|13<<15|4<<12|vector<<7|0x57]


def _storage(function,form,result,left,right):
    return function<<26|1<<25|right<<20|left<<15|form<<12|result<<7|0x2b


def _extract(vector,scalar):
    return 16<<26|1<<25|vector<<20|2<<12|scalar<<7|0x57


def _request(operation):
    index=OPERATIONS.index(operation)
    # v2 is checked binding zero; v6 is the descriptor-relative byte offset.
    return _storage(25 if index<8 else 26,index%8,7,2,6)


def _parameters(offset,operand,compare=0):
    return [*_splat(4,operand),*_splat(5,compare),*_splat(6,offset),
            _storage(24,0,0,4,5)]


def words(case):
    p=parameters(case);calls=events(case)
    code=[VSET_ONE,*_splat(2,0)]
    # Share the bounded poll across callers, retaining all 64 attempts while
    # staying inside the production 512-word shader binding. Only the first
    # two callers warm/increment; every caller waits before target operations.
    skips=[]
    for group in (2,3):
        code.append(_addi(10,0,group));skips.append(len(code));code.append(0)
    code += [*_target(p['target_offset']),VSET_ONE,0x0205e187,_extract(3,14),
             _addi(10,0,0),_bne(2,10,12),_store(14,WARM_OFFSET),
             _branch_equal(0,0,8),_store(14,WARM_OFFSET+4),FENCE,
             *_parameters(READY_OFFSET,1),_request('add'),FENCE]
    poll=len(code)
    for index in skips:code[index]=_branch_equal(2,10,4*(poll-index))
    code += [*_parameters(READY_OFFSET,0),*_constant(10,2)]
    branches=[]
    for _ in range(POLL_ATTEMPTS):
        code += [_request('add'),_extract(7,12)]
        branches.append(len(code));code.append(0)
    code.append(CEASE);target=len(code)
    for index in branches:code[index]=_branch_equal(12,10,4*(target-index))
    for group in range(4):
        arm=_vector_read(p['target_offset'],WARM_OFFSET+4*group) if group>=2 else []
        for call in range(2):
            index=2*group+call;event=calls[index]
            arm += [*_parameters(p['target_offset'],event['operand'],event['compare']),
                    _request(event['operation']),_extract(7,12),
                    _store(12,OUTPUT_OFFSET+4*index),FENCE]
        arm += _vector_read(p['target_offset'],REFETCH_OFFSET+4*group)+[CEASE]
        if group<3:code += [_addi(10,0,group),_bne(2,10,4*(len(arm)+1))]
        code += arm
    if len(code)>512:raise ValueError('UC cache source exceeds the original 512-word code binding')
    return tuple(code)


def bound_words(case):
    code=words(case)
    return code+(0,)*(-len(code)%16)
