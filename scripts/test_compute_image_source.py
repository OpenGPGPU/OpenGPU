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
from compute_image_cases import IMAGE_CASES, IMAGE_BOUNDARY_CASES, parameters
from compute_image_source import (loaded_words, ready as validate_ready, texel_address, atomic_values,
    boundary_coordinates,boundary_address,boundary_exchange_value,source_view,initial_bytes,returned_outputs,SCHEMA,
    source_read_fault_plan,source_read_fault_proof,verify_source_read_fault)
from compute_owner_oracle import SparseMemory, verify
from compute_owner_protocol import strict_json
from compute_owner_controller import ready_inputs
from owner_resource_contract import SAMPLE_PROFILE, SAMPLE_FACTORY_CONTRACTS


class Formats(unittest.TestCase):
    def test_source_fault_plan_and_all_byte_failure_guard_keep_original_uploads(self):
        # Only original CPU uploads are represented, with no simulated shader.
        class Uploads:
            def __init__(self,data):
                self.data=data;self.ranges=[(a,a+len(b),0) for a,b in sorted(data.items())]
            def read(self,address,count):
                for first,data in self.data.items():
                    if first<=address<=address+count<=first+len(data):return data[address-first:address-first+count]
                raise ValueError('outside original upload')
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        p=parameters('image-atomic-buffer-r32f')
        data={0x1000:struct.pack('<I',0xdeadbeef)*2048,0x40000:b'\x52'*4096}
        images=[]
        for role in range(2):
            binding,view=source_view(p,role,role);size=(binding+4095)//4096*4096
            physical=0x10000*(role+1)
            data[physical]=b'\xa7'*size if role else initial_bytes(p,size)
            images.append(dict(apiBinding=1 if role else 3,nativeUnit=role,handle=role+1,
                allocationPhysical=physical,allocationBytes=size,bindingBytes=binding,view=view))
        description=dict(schema=SCHEMA,sourceCase='image-atomic-buffer-r32f',groups=2,local=4,viewBytes=8192,
            guard=0xdeadbeef,context=1,kernargPhysical=0,kernargBytes=0,imagePhysical=0,imageRoot=0,
            storagePhysical=0,storageRoot=0,output=dict(globalBinding=2,allocationPhysical=0x1000,
                allocationBytes=8192,offset=0,bytes=8192),images=images)
        uploads=Uploads(data)
        self.assertEqual(source_read_fault_plan(uploads,description,owner_profile=IMAGE_FLOAT_PROFILE),
                         struct.pack('<8sQQ',b'OGCFS001',0x10000,0x12000))
        verify_source_read_fault(uploads,uploads,description)
        # Healthy execution must still reject exactly these untouched uploads.
        with self.assertRaises(ValueError):verify(uploads,uploads,description)
        with self.assertRaises(ValueError):source_read_fault_plan(uploads,description,owner_profile=SAMPLE_PROFILE)
        wrong=deepcopy(description);wrong['sourceCase']='image-atomic-buffer-r32ui'
        with self.assertRaises(ValueError):source_read_fault_plan(uploads,wrong,owner_profile=IMAGE_FLOAT_PROFILE)
        for address in (0x1000,0x1fff,0x10000,0x10030,0x11fff,0x20000,0x21fff,0x40000,0x40fff):
            class Corrupted:
                ranges=uploads.ranges
                def read(self,first,count):
                    value=bytearray(uploads.read(first,count))
                    if first<=address<first+count:value[address-first]^=1
                    return bytes(value)
            with self.subTest(address=address),self.assertRaises(ValueError):
                verify_source_read_fault(uploads,Corrupted(),description)

    def test_source_fault_proof_rejects_absent_unplanned_or_changed_errors(self):
        valid=dict(physicalReadFaults=2,injectedReadFaults=2,physicalWriteFaults=0)
        self.assertEqual(source_read_fault_proof(valid),(2,2))
        self.assertEqual(source_read_fault_proof(valid,valid),(2,2))
        for field,value in [('physicalReadFaults',0),('injectedReadFaults',0),
                ('physicalReadFaults',3),('physicalWriteFaults',1),
                ('physicalReadFaults',True),('injectedReadFaults',-1)]:
            wrong=dict(valid);wrong[field]=value
            with self.subTest(field=field,value=value),self.assertRaises(ValueError):source_read_fault_proof(wrong)
        wrong=dict(valid);del wrong['injectedReadFaults']
        with self.assertRaises(ValueError):source_read_fault_proof(wrong)
        with self.assertRaises(ValueError):source_read_fault_proof(
            dict(physicalReadFaults=3,injectedReadFaults=3,physicalWriteFaults=0),valid)

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
                     'image-atomic-array-rgba8ui','image-atomic-2d-rgba32f',
                     'image-bounds-array-rgba8ui','image-atomic-bounds-2d-r32i'):
            with self.subTest(name=name),self.assertRaises(ValueError):parameters(name)

    def test_atomic_old_values_wrap_signed_order_and_cas_paths(self):
        common=[0x13579bdf,0x80000000,0xfffffff0,0xaaaa555a,0xaaaa555f,0xaaaa5553]
        unsigned=atomic_values(parameters('image-atomic-2d-r32ui'),0x13579bdf,0)
        signed=atomic_values(parameters('image-atomic-2d-r32i'),0x13579bdf,0)
        self.assertEqual(unsigned,(common+[0x7fffff00,0x80000020,0x12345670,0xdeadb000],0xdeadb000))
        self.assertEqual(signed,(common+[0xaaaa5553,0xaaaa5553,0x12345670,0xdeadb000],0xdeadb000))
        self.assertEqual(atomic_values(parameters('image-atomic-buffer-r32ui'),7,1),
            ([7,0x80000011,1,0x5555aaab,0x5555aaaf,0x5555aaa3,0x5555aaa3,0x80000020,0x12345671,0xdeadb001],0xdeadb001))

    def test_float_exchange_old_encodings_signed_zero_infinity_and_final_finite_word(self):
        for target in ('buffer','2d','array','cube','3d'):
            p=parameters('image-atomic-'+target+'-r32f')
            self.assertEqual(atomic_values(p,0x3f123456,0),
                ([0x3f123456,0,0x3f400000,0xbf800000,0x7f800000],0x40000000))
            self.assertEqual(atomic_values(p,0xbf654321,1),
                ([0xbf654321,0x80000000,0x3f400001,0xbf800001,0xff800000],0x40100000))

    def test_boundaries_keep_only_first_and_last_texels_and_never_wrap_addresses(self):
        expected={'buffer':(1,(12,0,0)),'2d':(3,(12,4,0)),
            'array':(7,(12,4,2)),'cube':(7,(12,12,5)),'3d':(7,(12,4,1))}
        for target,(last_lane,last) in expected.items():
            p=parameters('image-bounds-'+target+'-r32ui')
            images=[dict(allocationPhysical=0x10000*(role+1),view=source_view(p,role,role)[1])
                    for role in range(2)]
            for image in images:
                valid=[(lane,access,coordinate) for lane in range(8)
                    for access,coordinate in enumerate(boundary_coordinates(p,lane))
                    if boundary_address(image,p,coordinate) is not None]
                self.assertEqual(valid,[(0,0,(0,0,0)),(last_lane,1,last)],target)
                for coordinate in ((-1,0,0),(13,0,0),(0x7fffffff,0,0),(-0x80000000,0,0)):
                    self.assertIsNone(boundary_address(image,p,coordinate))
                first=boundary_address(image,p,(0,0,0))
                self.assertEqual(first,image['allocationPhysical']+image['view']['offset'])
            self.assertEqual(images[0]['view']['depth'],images[1]['view']['depth'])

    def test_boundary_exchange_operands_are_exact_raw_words(self):
        for target,last_lane in (('buffer',1),('2d',3),('array',7),('cube',7),('3d',7)):
            for fmt,base in (('r32ui',0x87650000),('r32f',0x3f400000)):
                p=parameters('image-atomic-bounds-'+target+'-'+fmt)
                self.assertEqual(boundary_exchange_value(p,0,0),base)
                self.assertEqual(boundary_exchange_value(p,last_lane,1),base+17*last_lane+1)
                self.assertEqual(p['mode'],'atomic-bounds')

    def test_boundary_oracle_has_four_zero_channels_and_only_two_valid_texel_writes(self):
        # Only initial GEM bytes are modeled. No returned GPU memory is made.
        class InitialMemory:
            def __init__(self,data):
                self.data=data;self.ranges=[(a,a+len(b),0) for a,b in sorted(data.items())]
            def read(self,address,count):
                for first,data in self.data.items():
                    if first<=address<=address+count<=first+len(data):return data[address-first:address-first+count]
                raise ValueError('read outside initial allocation')
        endpoints={'buffer':(1,(12,0,0)),'2d':(3,(12,4,0)),
            'array':(7,(12,4,2)),'cube':(7,(12,12,5)),'3d':(7,(12,4,1))}
        for case in IMAGE_BOUNDARY_CASES:
            p=parameters(case);atomic=p['mode']=='atomic-bounds'
            data={0x1000:struct.pack('<I',0xdeadbeef)*2048};images=[]
            for role in range(2):
                binding,view=source_view(p,role,role);size=(binding+4095)//4096*4096
                physical=0x10000*(role+1)
                data[physical]=b'\xa7'*size if role else initial_bytes(p,size)
                images.append(dict(apiBinding=1 if role else 3,nativeUnit=role,handle=role+1,
                    allocationPhysical=physical,allocationBytes=size,bindingBytes=binding,view=view))
            description=dict(schema=SCHEMA,sourceCase=case,groups=2,local=4,viewBytes=8192,
                guard=0xdeadbeef,context=1,kernargPhysical=0,kernargBytes=0,imagePhysical=0,imageRoot=0,
                storagePhysical=0,storageRoot=0,output=dict(globalBinding=2,allocationPhysical=0x1000,
                    allocationBytes=8192,offset=0,bytes=8192),images=images)
            memory=InitialMemory(data);last_lane,(x,y,z)=endpoints[p['shader']]
            points=[]
            for image in images:
                v=image['view'];first=image['allocationPhysical']+v['offset']
                points.append((first,first+x*4+y*v['rowPitch']+z*(v['slicePitch'] or v['layerPitch'])))
            stride=4 if atomic else 16;observed=bytearray(16*stride);expected=[]
            for index,(lane,access) in enumerate(((0,0),(last_lane,1))):
                raw=memory.read(points[0][index],4)
                value=raw if atomic else loaded_words(p,raw)
                first=(lane*2+access)*stride;observed[first:first+stride]=value
                if atomic:
                    word=(0x3f400000 if p['format']=='r32f' else 0x87650000)+17*lane+access
                    stored=word.to_bytes(4,'little')
                    expected.extend(((points[0][index],stored),(points[1][index],stored)))
                else:expected.append((points[1][index],raw))
            self.assertEqual(returned_outputs(memory,None,description),[(0x1000,bytes(observed)),*expected],case)


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
        if p['mode'].endswith('bounds'):
            addresses=[]
            for lane in range(8):
                for coordinate in boundary_coordinates(p,lane):
                    address=boundary_address(source_image,p,coordinate)
                    if address is not None:addresses.append(address)
            assert len(addresses)==len(set(addresses))==2,'boundary recipe aliases valid texels'
        else:addresses=[texel_address(source_image,p,0,lane) for lane in range(8)]
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
