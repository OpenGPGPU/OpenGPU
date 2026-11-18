"""Original scalar AMOs with source-level vector cache warming and refetch.

The first two resident callers synchronize after their ordinary vector loads.
No host RAM writes, PTE replacement or cache-state injection primes the aliases.
These programs retain every original AMO operand and endpoint.
"""
import struct
from compute_global_atomic_cases import (CASES, parameters as original_parameters,
    events, initial_kernarg as original_kernarg, _addi, _constant, _bne,
    FUNCT5, FENCE, CEASE, OUTPUT_OFFSET)

READY_OFFSET=192
WARM_OFFSET=256
REFETCH_OFFSET=320
GUARD=0xdeadbeef
POLL_ATTEMPTS=64
VSET_ONE=0xc100f057


def parameters(case):
    return dict(original_parameters(case),warm_offset=WARM_OFFSET,refetch_offset=REFETCH_OFFSET,
        ready_offset=READY_OFFSET,poll_attempts=POLL_ATTEMPTS)


def _store(register,offset):
    if not 0<=offset<=2047 or offset&3:raise ValueError('unaligned cache observation slot')
    return (offset>>5)<<25|register<<20|1<<15|2<<12|(offset&31)<<7|0x23


def _target(offset):
    code=[_addi(11,1,0)]
    while offset:
        step=min(2044,offset);code.append(_addi(11,11,step));offset-=step
    return code


def _vector_read(offset,output):
    # vle32.v v3,(x11), one active element; vmv.x.s x14,v3.
    return [*_target(offset),VSET_ONE,0x0205e187,0x42302757,_store(14,output),FENCE]


def _branch_equal(left,right,displacement):
    # Same bounded forward encoding as the original dispatch, with BEQ funct3.
    return _bne(left,right,displacement)&~(7<<12)


def words(case):
    p=parameters(case);calls=events(case);code=[]
    for group in range(4):
        arm=_vector_read(p['target_offset'],WARM_OFFSET+4*group)
        if group<2:
            arm += [_addi(11,1,READY_OFFSET),*_constant(13,1),
                FUNCT5['add']<<27|13<<20|11<<15|2<<12|12<<7|0x2f,FENCE,
                *_constant(13,0),*_constant(10,2)]
            branches=[]
            for _ in range(POLL_ATTEMPTS):
                arm.append(FUNCT5['add']<<27|13<<20|11<<15|2<<12|12<<7|0x2f)
                branches.append(len(arm));arm.append(0)
            # Failure to meet the bounded barrier produces no AMO results;
            # it cannot pass the independent whole-program byte oracle.
            arm.append(CEASE);target=len(arm)
            for index in branches:arm[index]=_branch_equal(12,10,4*(target-index))
        arm += _target(p['target_offset'])
        for call in range(2):
            index=2*group+call;event=calls[index]
            arm += _constant(13,event['operand'])
            if event['operation']=='compare-swap':arm += _constant(12,event['compare'])
            arm += [FUNCT5[event['operation']]<<27|13<<20|11<<15|2<<12|12<<7|0x2f,
                _store(12,OUTPUT_OFFSET+4*index),FENCE]
        arm += _vector_read(p['target_offset'],REFETCH_OFFSET+4*group)+[CEASE]
        if group<3:code += [_addi(10,0,group),_bne(2,10,4*(len(arm)+1))]
        code += arm
    if len(code)*4>4096:raise ValueError('cache source exceeds original code binding limit')
    return tuple(code)


def bound_words(case):
    code=words(case)
    return code+(0,)*(-len(code)%16)


def initial_kernarg(case):
    data=bytearray(original_kernarg(case))
    struct.pack_into('<I',data,READY_OFFSET,0)
    struct.pack_into('<4I',data,WARM_OFFSET,*([GUARD]*4))
    struct.pack_into('<4I',data,REFETCH_OFFSET,*([GUARD]*4))
    return bytes(data)
