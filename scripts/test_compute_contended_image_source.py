#!/usr/bin/env python3
"""CPU oracle controls and actual Mesa capture checks; no DUT qualification."""
import argparse
from copy import deepcopy
from pathlib import Path
import struct
import unittest
from compute_contended_image_cases import CASES, parameters, operations
from compute_image_source import (CONTENDED_SCHEMA, source_view, initial_bytes, texel_address,
    ready, recipe)
from compute_atomic_order import linearization
from compute_owner_oracle import SparseMemory, verify
from compute_owner_protocol import strict_json
from compute_owner_controller import ready_inputs
from owner_resource_contract import IMAGE_FLOAT_PROFILE


class Memory:
    """Explicit CPU-only oracle fixture, never an execution input provider."""
    def __init__(self, pages):
        self.pages={first:bytearray(data) for first,data in pages.items()}
        self.ranges=[(first,first+len(data),0) for first,data in sorted(self.pages.items())]

    def read(self, address, count):
        for first,data in self.pages.items():
            if first<=address and address+count<=first+len(data):
                return bytes(data[address-first:address-first+count])
        raise ValueError('outside CPU fixture allocation')

    def write(self, address, data):
        for first,original in self.pages.items():
            if first<=address and address+len(data)<=first+len(original):
                original[address-first:address-first+len(data)]=data
                return
        raise ValueError('outside CPU fixture allocation')


def literal_history(p):
    """Literal ESSL histories for invocation order 0..7, independent of solver."""
    pair=p['pair']
    if pair=='swap':return [0x3f800000,0x3f400000,0x3f400001,0x3f400002,
                           0x3f400003,0x3f400004,0x3f400005,0x3f400006],0x3f400007
    if pair=='swap-add':
        return [0xfffffff0,0x80000000,0xfffffff0,0x80000011,1,0x80000022,
                0x12,0x80000033,0x23,0x80000044,0x34,0x80000055,
                0x45,0x80000066,0x56,0x80000077],0x67
    if pair=='xor-or':
        return [0xfffffff0,0xaaaa555a,0xaaaa555f,0xfffffff5,0xffffffff,0xaaaa5555,
                0xaaaa555f,0xfffffff5,0xffffffff,0xaaaa5555,0xaaaa555f,0xfffffff5,
                0xffffffff,0xaaaa5555,0xaaaa555f,0xfffffff5],0xffffffff
    if pair=='and-min':
        return ([0x7fffffff,0x7ffffff3] if p['kind']=='sint' else [0xffffffff,0xfffffff3])+[
                0x80000007]+[0x80000003]*13,0x80000003
    if pair=='max-cas':
        maximum=0x7fffff00 if p['kind']=='sint' else 0x80000020
        return [p['seed'],maximum,0x12345670,maximum,0x12345671,maximum,
                0x12345672,maximum,0x12345673,maximum,0x12345674,maximum,
                0x12345675,maximum,0x12345676,maximum],0x12345677
    if pair=='cas-pair':return [0]+[1]*15,1
    raise AssertionError('missing literal history')


def fixture(case):
    p=parameters(case);pages={0x10000:struct.pack('<I',0xdeadbeef)*2048,0x40000:bytes(4096),
                              0x50000:b'\x6d'*4096}
    images=[]
    for role,first in enumerate((0x20000,0x30000)):
        binding,view=source_view(p,role,role)
        size=(binding+4095)//4096*4096
        pages[first]=b'\xa7'*size if role else initial_bytes(p,size)
        images.append(dict(apiBinding=1 if role else 3,nativeUnit=role,handle=10+role,
            allocationPhysical=first,allocationBytes=size,bindingBytes=binding,view=view))
    description=dict(schema=CONTENDED_SCHEMA,sourceCase=case,groups=2,local=4,viewBytes=8192,
        guard=0xdeadbeef,context=1,kernargPhysical=0x50000,kernargBytes=64,
        imagePhysical=0x60000,imageRoot=0x70000,storagePhysical=0x80000,storageRoot=0x90000,
        codePhysical=0x40000,codeBytes=4,images=images,
        output=dict(globalBinding=2,allocationPhysical=0x10000,allocationBytes=8192,offset=0,bytes=8192))
    return Memory(pages),description


def cpu_result(before, description, old, final):
    p=parameters(description['sourceCase']);after=Memory(before.pages)
    count=len(old)//8
    after.write(0x10000,struct.pack('<%dI'%len(old),*old))
    after.write(texel_address(description['images'][0],p,0,0),struct.pack('<I',final))
    for invocation in range(8):
        after.write(texel_address(description['images'][1],p,1,invocation),
                    struct.pack('<I',old[invocation*count+count-1]))
    return after


class OracleControls(unittest.TestCase):
    def test_all_fifty_five_literal_histories_and_untouched_upload_rejection(self):
        for case in CASES:
            with self.subTest(case=case):
                before,description=fixture(case)
                old,final=literal_history(parameters(case))
                verify(before,cpu_result(before,description,old,final),description)
                with self.assertRaises(ValueError):verify(before,before,description)

    def test_old_words_destinations_final_and_every_allocation_guard_are_checked(self):
        for case in CASES:
            before,description=fixture(case);p=parameters(case)
            old,final=literal_history(p)
            after=cpu_result(before,description,old,final)
            addresses=[0x10000+4*n for n in range(len(old))]+[
                texel_address(description['images'][0],p,0,0)]+[
                texel_address(description['images'][1],p,1,n) for n in range(8)]+[
                0x10000+4*len(old),0x20000,0x30000,0x40000,0x50000]
            for address in addresses:
                with self.subTest(case=case,address=address):
                    changed=Memory(after.pages);changed.write(address,bytes([changed.read(address,1)[0]^1]))
                    with self.assertRaises(ValueError):verify(before,changed,description)

    def test_program_order_is_required_even_when_unordered_history_is_legal(self):
        case='image-contended-buffer-r32ui-cas-pair'
        before,description=fixture(case)
        old=[0,0]+[1]*14
        events,predecessors=operations(case)
        self.assertEqual(len(linearization(0,1,events,old)),16)
        with self.assertRaises(ValueError):linearization(0,1,events,old,predecessors)
        with self.assertRaises(ValueError):verify(before,cpu_result(before,description,old,1),description)

    def test_original_schema_view_seed_and_code_metadata_cannot_be_replaced(self):
        before,description=fixture(CASES[0])
        for key,value in (('schema','opengpu-compute-image-oracle-v1'),('sourceCase','image-atomic-buffer-r32ui'),
                          ('groups',1),('codePhysical',0),('codeBytes',True),('codeBytes',0)):
            changed=deepcopy(description);changed[key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):recipe(before,changed)
        changed=deepcopy(description);changed['images'][0]['view']['offset']+=4
        with self.assertRaises(ValueError):recipe(before,changed)
        changed=Memory(before.pages)
        address=texel_address(description['images'][0],parameters(CASES[0]),0,0)
        changed.write(address,bytes(4))
        with self.assertRaises(ValueError):recipe(changed,description)


def check_capture(directory):
    description=strict_json(directory/'oracle.json')
    case=description['sourceCase'];assert case in CASES
    data=(directory/'record-0.capture').read_bytes()
    code=(directory/'code.words').read_text().splitlines()
    def reject(function):
        try:function()
        except ValueError:return
        raise AssertionError('actual contended image capture negative accepted')
    with SparseMemory(directory/'union.memory') as memory:
        ready(memory,description,data,owner_profile=IMAGE_FLOAT_PROFILE,code_words=code)
        ready_inputs(directory,case,owner_profile=IMAGE_FLOAT_PROFILE)
        reject(lambda:verify(memory,memory,description))
        reject(lambda:ready(memory,description,data,owner_profile=IMAGE_FLOAT_PROFILE))
        changed=list(code);changed[0]='%08x'%(int(changed[0],16)^1)
        reject(lambda:ready(memory,description,data,owner_profile=IMAGE_FLOAT_PROFILE,code_words=changed))
        changed=deepcopy(description);changed['codePhysical']+=4
        reject(lambda:ready(memory,changed,data,owner_profile=IMAGE_FLOAT_PROFILE,code_words=code))
        changed=deepcopy(description);changed['schema']='opengpu-compute-image-oracle-v1'
        reject(lambda:ready(memory,changed,data,owner_profile=IMAGE_FLOAT_PROFILE,code_words=code))
    print(case+': actual Mesa capture/code/view binding and untouched-result negatives PASS (CPU only)')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--captures',type=Path)
    args,remaining=parser.parse_known_args()
    if args.captures:
        assert not remaining
        for case in CASES:check_capture(args.captures/case)
    else:unittest.main(argv=[__file__,*remaining])
