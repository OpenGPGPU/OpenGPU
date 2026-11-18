#!/usr/bin/env python3
"""Explicit CPU oracle controls and original mixed-alias capture checks."""
import argparse
from copy import deepcopy
from pathlib import Path
import struct
import unittest
from compute_mixed_atomic_cases import CASES,parameters
from compute_mixed_atomic_source import SCHEMA,recipe,ready
from compute_owner_oracle import SparseMemory,verify
from compute_owner_protocol import strict_json
from compute_owner_controller import ready_inputs
from owner_resource_contract import IMAGE_FLOAT_PROFILE,SAMPLE_PROFILE
from test_compute_contended_image_source import Memory,literal_history as integer_history


def literal_history(p):
    if p['format']!='r32f':return integer_history(p)
    return [0x3f800000,0x3f400000,0x40000000,0x3f400001,0x40000011,0x3f400002,
            0x40000022,0x3f400003,0x40000033,0x3f400004,0x40000044,0x3f400005,
            0x40000055,0x3f400006,0x40000066,0x3f400007],0x40000077


def fixture(case):
    p=parameters(case);target=bytearray(b'\x6b'*8192)
    struct.pack_into('<I',target,p['target_offset'],p['seed'])
    memory=Memory({0x10000:struct.pack('<I',0xdeadbeef)*2048,0x20000:target,
                   0x40000:bytes(4096),0x50000:b'\x6d'*4096})
    d=dict(schema=SCHEMA,sourceCase=case,groups=2,local=4,viewBytes=8192,guard=0xdeadbeef,
        context=1,targetHandle=10,storageBindings=[2,0],kernargPhysical=0x50000,kernargBytes=64,
        imagePhysical=0x60000,imageRoot=0x70000,storagePhysical=0x80000,storageRoot=0x90000,
        codePhysical=0x40000,codeBytes=4,
        target=dict(globalBinding=0,allocationPhysical=0x20000,allocationBytes=8192,offset=p['storage_offset'],bytes=p['storage_bytes']),
        output=dict(globalBinding=2,allocationPhysical=0x10000,allocationBytes=8192,offset=0,bytes=8192),
        image=dict(apiBinding=3,nativeUnit=0,handle=10,allocationPhysical=0x20000,allocationBytes=8192,bindingBytes=8192,
            view=dict(offset=p['image_offset'],rowPitch=p['image_bytes'],slicePitch=0,layerPitch=0,slot=39,flags=3,
                target=5,storageFormat=p['native'],imageFormat=p['native'],level=0,width=p['image_bytes']//4,
                height=1,depth=1,layers=1,layer=0)))
    return memory,d


def cpu_result(memory,d,old,final):
    result=Memory(memory.pages);p=parameters(d['sourceCase'])
    result.write(0x10000,struct.pack('<16I',*old))
    result.write(0x20000+p['target_offset'],struct.pack('<I',final))
    return result


class MixedOracle(unittest.TestCase):
    def test_all_original_case_histories_and_untouched_upload_rejection(self):
        for case in CASES:
            with self.subTest(case=case):
                memory,d=fixture(case);old,final=literal_history(parameters(case))
                verify(memory,cpu_result(memory,d,old,final),d)
                with self.assertRaises(ValueError):verify(memory,memory,d)

    def test_every_old_word_final_and_all_allocation_guards_are_checked(self):
        for case in CASES:
            memory,d=fixture(case);p=parameters(case);old,final=literal_history(p)
            result=cpu_result(memory,d,old,final)
            addresses=[0x10000+4*n for n in range(16)]+[0x20000+p['target_offset'],
                0x10040,0x20040 if p['target_offset']==0 else 0x20000,0x40000,0x50000]
            for address in addresses:
                changed=Memory(result.pages);changed.write(address,bytes([changed.read(address,1)[0]^1]))
                with self.subTest(case=case,address=address),self.assertRaises(ValueError):verify(memory,changed,d)

    def test_original_alias_identity_views_and_schema_are_required(self):
        for case in CASES:
            memory,d=fixture(case)
            for field,value in (('schema','opengpu-compute-image-oracle-v1'),('targetHandle',True),
                                ('storageBindings',[0,0]),('codeBytes',True),('codePhysical',0)):
                changed=deepcopy(d);changed[field]=value
                with self.subTest(case=case,field=field),self.assertRaises(ValueError):recipe(memory,changed)
            for field in ('offset','rowPitch','flags','imageFormat'):
                changed=deepcopy(d);changed['image']['view'][field]+=4
                with self.subTest(case=case,field=field),self.assertRaises(ValueError):recipe(memory,changed)
            changed=deepcopy(d);changed['image']['allocationPhysical']+=4096
            with self.assertRaises(ValueError):recipe(memory,changed)
            changed=deepcopy(d);changed['target']['offset']+=256
            with self.assertRaises(ValueError):recipe(memory,changed)

    def test_cross_client_program_order_cannot_be_replaced_by_an_unordered_history(self):
        for order in ('storage-first','image-first'):
            memory,d=fixture('mixed-atomic-r32ui-cas-pair-'+order+'-endpoint')
            with self.assertRaisesRegex(ValueError,'no legal mixed atomic serialization'):
                verify(memory,cpu_result(memory,d,[0,0]+[1]*14,1),d)


class Overlay:
    def __init__(self,original,address,data):
        self.original,self.address,self.data=original,address,data;self.ranges=original.ranges
    def read(self,address,count):
        result=bytearray(self.original.read(address,count))
        low,high=max(address,self.address),min(address+count,self.address+len(self.data))
        if low<high:result[low-address:high-address]=self.data[low-self.address:high-self.address]
        return bytes(result)


def check_capture(directory,profile):
    d=strict_json(directory/'oracle.json');case=d['sourceCase'];assert case in CASES
    data=(directory/'record-0.capture').read_bytes();code=(directory/'code.words').read_text().splitlines()
    def reject(function):
        try:function()
        except ValueError:return
        raise AssertionError('original mixed atomic negative accepted: '+case)
    with SparseMemory(directory/'union.memory') as memory:
        ready(memory,d,data,code,profile)
        ready_inputs(directory,case,owner_profile=IMAGE_FLOAT_PROFILE,profile=profile)
        reject(lambda:ready_inputs(directory,case,owner_profile=SAMPLE_PROFILE,profile=profile))
        reject(lambda:verify(memory,memory,d))
        reject(lambda:ready(memory,d,data,[],profile))
        changed=list(code);changed[0]='%08x'%(int(changed[0],16)^1)
        reject(lambda:ready(memory,d,data,changed,profile))
        changed=deepcopy(d);changed['image']['handle']+=1
        reject(lambda:ready(memory,changed,data,code,profile))
        changed=deepcopy(d);changed['image']['allocationPhysical']+=4096
        reject(lambda:ready(memory,changed,data,code,profile))
        changed=deepcopy(d);changed['codePhysical']+=4
        reject(lambda:ready(memory,changed,data,code,profile))
        first=d['storagePhysical']+44;word=struct.unpack('<I',memory.read(first,4))[0]
        reject(lambda:ready(Overlay(memory,first,struct.pack('<I',word+4)),d,data,code,profile))
        reject(lambda:ready(Overlay(memory,d['imagePhysical']+256+28,struct.pack('<I',1)),d,data,code,profile))
        altered=bytearray(data);position=8+20+122*8;count,=struct.unpack_from('<I',data,position);assert count
        end,=struct.unpack_from('<Q',data,position+12);struct.pack_into('<Q',altered,position+12,end+4)
        reject(lambda:ready(memory,d,bytes(altered),code,profile))
    print(case+': original shared GEM, both private aliases/code/guards and rejection checks PASS (CPU only)')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--captures',type=Path)
    parser.add_argument('--profile',type=Path);args,remaining=parser.parse_known_args()
    if args.captures:
        assert args.profile and not remaining;profile=strict_json(args.profile)
        for case in CASES:check_capture(args.captures/case,profile)
    else:unittest.main(argv=[__file__,*remaining])
