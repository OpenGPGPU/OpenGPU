#!/usr/bin/env python3
"""CPU image-format checks and rejection checks over actual Mesa captures.

No returned shader memory is synthesized. Untouched uploads must fail the result
oracle. --captures consumes fresh CPU captures, never a DUT qualification.
"""
import argparse
from copy import deepcopy
from pathlib import Path
import struct
import unittest
from compute_image_cases import IMAGE_CASES, parameters
from compute_image_source import loaded_words, ready as validate_ready, texel_address, atomic_values
from compute_owner_oracle import SparseMemory, verify
from compute_owner_protocol import strict_json
from compute_owner_controller import ready_inputs
from owner_resource_contract import SAMPLE_PROFILE, SAMPLE_FACTORY_CONTRACTS


class Formats(unittest.TestCase):
    def test_scalar_defaults_and_signed_extension(self):
        self.assertEqual(struct.unpack('<4I',loaded_words(parameters('image-2d-r32ui'),b'\x89\x67\x45\x83')),
            (0x83456789,0,0,1))
        self.assertEqual(struct.unpack('<4I',loaded_words(parameters('image-2d-r32f'),struct.pack('<f',0.75))),
            (0x3f400000,0,0,0x3f800000))
        self.assertEqual(struct.unpack('<4I',loaded_words(parameters('image-array-rgba8i'),bytes((0,127,128,255)))),
            (0,127,0xffffff80,0xffffffff))

    def test_half_and_normalized_endpoints(self):
        self.assertEqual(loaded_words(parameters('image-3d-rgba16f'),struct.pack('<4e',0.5,0.75,1,-1)),
            struct.pack('<4f',0.5,0.75,1,-1))
        self.assertEqual(loaded_words(parameters('image-cube-rgba8'),bytes((0,255,255,0))),
            struct.pack('<4f',0,1,1,0))
        self.assertEqual(loaded_words(parameters('image-cube-rgba8_snorm'),bytes((0,127,129,128))),
            struct.pack('<4f',0,1,-1,-1))

    def test_unknown_source_is_rejected(self):
        for name in ('image-2d-rgba8ui-extra','image-plane-buffer-r32ui','image-single-cube-r32ui','image-rectangle-r32ui',
                     'image-atomic-array-rgba8ui','image-atomic-2d-r32f'):
            with self.subTest(name=name),self.assertRaises(ValueError):parameters(name)

    def test_atomic_old_values_wrap_signed_order_and_cas_paths(self):
        common=[0x13579bdf,0x80000000,0xfffffff0,0xaaaa555a,0xaaaa555f,0xaaaa5553]
        unsigned=atomic_values(parameters('image-atomic-2d-r32ui'),0x13579bdf,0)
        signed=atomic_values(parameters('image-atomic-2d-r32i'),0x13579bdf,0)
        self.assertEqual(unsigned,(common+[0x7fffff00,0x80000020,0x12345670,0xdeadb000],0xdeadb000))
        self.assertEqual(signed,(common+[0xaaaa5553,0xaaaa5553,0x12345670,0xdeadb000],0xdeadb000))
        self.assertEqual(atomic_values(parameters('image-atomic-buffer-r32ui'),7,1),
            ([7,0x80000011,1,0x5555aaab,0x5555aaaf,0x5555aaa3,0x5555aaa3,0x80000020,0x12345671,0xdeadb001],0xdeadb001))


class ChangedMemory:
    def __init__(self, original, first, data):
        self.original,self.first,self.data=original,first,data
        self.ranges=original.ranges

    def read(self, first, count):
        result=bytearray(self.original.read(first,count))
        begin,end=max(first,self.first),min(first+count,self.first+len(self.data))
        if begin<end:result[begin-first:end-first]=self.data[begin-self.first:end-self.first]
        return bytes(result)


def rejected(function, message):
    try:
        function()
    except ValueError:
        return
    raise AssertionError('image negative control accepted: '+message)


def check_capture(root,owner_profile=SAMPLE_PROFILE):
    def ready(memory,description,data):
        return validate_ready(memory,description,data,owner_profile=owner_profile)
    description=strict_json(root/'oracle.json')
    data=(root/'record-0.capture').read_bytes()
    count=0
    with SparseMemory(root/'union.memory') as memory:
        ready(memory,description,data)
        for other_profile in SAMPLE_FACTORY_CONTRACTS:
            if other_profile!=owner_profile:
                rejected(lambda:validate_ready(memory,description,data,owner_profile=other_profile),
                         'different qualified owner factory');count+=1
        pos=8+20+122*8
        n,=struct.unpack_from('<I',data,pos);pos+=4+16*n
        contexts,=struct.unpack_from('<I',data,pos);pos+=4
        assert contexts==1
        _,inactive_satp=struct.unpack_from('<II',data,pos);pos+=8
        actions,=struct.unpack_from('<I',data,pos);pos+=4
        writes={};write_offsets={}
        for action in range(actions):
            offset=pos+16*action
            kind,address,value,_=struct.unpack_from('<IIII',data,offset)
            if kind==1:writes[address]=value;write_offsets[address]=offset+8
        satp=writes[0x14c]
        p=parameters(description['sourceCase']);source_image=description['images'][0]
        addresses=[texel_address(source_image,p,0,lane) for lane in range(8)]
        values=b''.join(loaded_words(p,memory.read(address,p['bytes'])) for address in addresses)
        if p['shader']!='buffer':
            view=source_image['view']
            for shift in (view['rowPitch'],view['slicePitch'] or view['layerPitch']):
                if shift:
                    wrong=b''.join(loaded_words(p,memory.read(address-shift,p['bytes'])) for address in addresses)
                    assert wrong!=values, 'source oracle cannot distinguish another row/plane'
        recorded,inputs=ready_inputs(root,description['sourceCase'],owner_profile=owner_profile)
        assert recorded==description and set(inputs)=={'union.memory','record-0.capture','oracle.json'}
        rejected(lambda:ready_inputs(root,None,owner_profile=owner_profile),'image contract without a distinct qualified case');count+=1
        other=IMAGE_CASES[0] if description['sourceCase']!=IMAGE_CASES[0] else IMAGE_CASES[1]
        rejected(lambda:ready_inputs(root,other,owner_profile=owner_profile),'different qualified image case');count+=1
        rejected(lambda:verify(memory,memory,description),'untouched upload as GPU result');count+=1
        for role in range(2):
            view=description['images'][role]['view']
            for field in view:
                changed=deepcopy(description);changed['images'][role]['view'][field]=view[field]^1
                rejected(lambda:ready(memory,changed,data),'original '+field);count+=1
            changed=deepcopy(description);changed['images'][role]['view']['flags']=True
            rejected(lambda:ready(memory,changed,data),'boolean source flags');count+=1
            changed=deepcopy(description);changed['images'][role]['bindingBytes']+=1
            rejected(lambda:ready(memory,changed,data),'widened original binding');count+=1
            changed=deepcopy(description);changed['images'][role]['apiBinding']^=1
            rejected(lambda:ready(memory,changed,data),'original API image binding');count+=1
            changed=deepcopy(description);changed['images'][role]['nativeUnit']=2
            rejected(lambda:ready(memory,changed,data),'unbound native image unit');count+=1
            first=description['images'][role]['allocationPhysical']
            byte=bytes((memory.read(first,1)[0]^1,))
            rejected(lambda:ready(ChangedMemory(memory,first,byte),description,data),'original upload/padding');count+=1
            unit=description['images'][role]['nativeUnit'];descriptor=description['imagePhysical']+unit*256
            for word in (10,11,12,13,14,15,18,19,20,22,23,25,26,30,32,34,37,39):
                original=struct.unpack('<I',memory.read(descriptor+4*word,4))[0]
                overlay=ChangedMemory(memory,descriptor+4*word,struct.pack('<I',original^1))
                rejected(lambda:ready(overlay,description,data),'GIU1 word '+str(word));count+=1
            base=0x87ffd000+unit*(128*1024*1024+4096)
            root_pte=struct.unpack('<I',memory.read(((satp&0x3fffff)<<12)+((base>>22)&1023)*4,4))[0]
            leaf=(root_pte>>10)*4096+((base>>12)&1023)*4
            pte=struct.unpack('<I',memory.read(leaf,4))[0]
            for bit in (4,1<<10):
                overlay=ChangedMemory(memory,leaf,struct.pack('<I',pte^bit))
                rejected(lambda:ready(overlay,description,data),'original image PTE permission/backing');count+=1
        inactive_root=(inactive_satp&0x3fffff)<<12
        rejected(lambda:ready(ChangedMemory(memory,inactive_root,b'\x01'),description,data),
            'inactive context root gained authority');count+=1
        changed_data=bytearray(data);struct.pack_into('<I',changed_data,write_offsets[0x3e0],writes[0x3e0]^1)
        rejected(lambda:ready(memory,description,bytes(changed_data)),'image command enable/context');count+=1
        # Alter only the authority list, keeping original metadata/MMIO/bytes.
        start=8+20+122*8
        n,=struct.unpack_from('<I',data,start)
        ranges=[struct.unpack_from('<QQ',data,start+4+i*16) for i in range(n)]
        suffix=data[start+4+n*16:]
        def capture(intervals):
            return data[:start]+struct.pack('<I',len(intervals))+b''.join(struct.pack('<QQ',*r) for r in intervals)+suffix
        for index,(first,end) in enumerate(ranges):
            changed=list(ranges);changed[index]=(first,end+1)
            rejected(lambda:ready(memory,description,capture(changed)),'widened writable interval');count+=1
        source=description['images'][0]['allocationPhysical']
        rejected(lambda:ready(memory,description,capture(sorted([*ranges,(source,source+4)]))),
            'image bytes outside original writable view');count+=1
        changed=deepcopy(description);changed['sourceCase']='image-2d-not-a-format'
        rejected(lambda:ready(memory,changed,data),'unbound source identity');count+=1
    return count


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--captures',type=Path,help='one actual CPU capture or complete image matrix directory')
    parser.add_argument('--owner-profile',choices=tuple(SAMPLE_FACTORY_CONTRACTS),default=SAMPLE_PROFILE)
    args=parser.parse_args()
    if args.captures is None:
        unittest.main(argv=[__file__])
        return
    root=args.captures
    cases=[root] if (root/'oracle.json').is_file() else [root/case for case in IMAGE_CASES]
    negatives=sum(check_capture(case,args.owner_profile) for case in cases)
    print(f'Original typed-image CPU READY: {len(cases)} captures, {negatives} rejection checks PASS; no GPU results')


if __name__=='__main__':
    main()
