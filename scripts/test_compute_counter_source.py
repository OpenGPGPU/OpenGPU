#!/usr/bin/env python3
"""Fabricated CPU positive/negative oracle inputs; no GPU qualification."""
import copy
from pathlib import Path
import struct
import tempfile
import unittest
from compute_counter_source import ready, SCHEMA
from compute_owner_oracle import SparseMemory, verify
from owner_resource_contract import SAMPLE_WORDS

ROOT = Path(__file__).resolve().parents[1]


def fixture(source_case="counter-increment"):
    dynamic='dynamic-' in source_case;all_counters='dynamic-all-' in source_case
    index=0 if all_counters or source_case=='counter-scalar' else 4 if dynamic else 7
    offset,count=(8188,1) if source_case=='counter-scalar' else (4080,8)
    groups=(2 if all_counters else 1) if dynamic else 4
    seed=(0 if dynamic else 1) if source_case.endswith('decrement') else 0xfffffffe
    allocations = {0x1000: bytearray(4096), 0x20000: bytearray(b'\x5a'*8192)}
    allocations[0x70000]=bytearray(4096) # Real Mesa also owns an unused graphics context.
    for a in (0x30000, 0x32000, 0x34000, 0x36000):
        allocations[a] = bytearray(struct.pack('<I', 0xdeadbeef)*2048)
    for a in (0x40000, 0x41000, 0x42000): allocations[a] = bytearray(4096)
    tables = {}
    def word(pa, value):
        for first, data in allocations.items():
            if first <= pa <= first+len(data)-4:
                struct.pack_into('<I', data, pa-first, value); return
        raise AssertionError(pa)
    def map_page(va, pa, flags):
        upper = va >> 22
        if upper not in tables:
            table = 0x50000+len(tables)*4096; allocations[table] = bytearray(4096); tables[upper] = table
            word(0x1000+upper*4, (table >> 12)*1024+1)
        word(tables[upper]+((va>>12)&1023)*4, (pa>>12)*1024+flags+704)
    def view(a, binding, offset=0, count=8192):
        return dict(globalBinding=binding, allocationPhysical=a, allocationBytes=8192, offset=offset, bytes=count)
    descriptor_root, counter_va, storage_root = 0xb1400000, 0xb1c00000+offset, 0xb0c00000
    for i in range(count): word(0x20000+offset+4*i, (seed+(i+int(dynamic))*0x10203)&0xffffffff)
    word(0x20000+offset+(7 if dynamic else index)*4, seed)
    d = dict(schema=SCHEMA, sourceCase=source_case, groups=groups, local=4, viewBytes=8192,
        guard=0xdeadbeef, seed=seed, index=index, count=count, counterHandle=4, context=2,
        descriptorPhysical=0x40000, descriptorRoot=descriptor_root, kernargPhysical=0x42000, kernargBytes=64,
        counter=view(0x20000, 0, offset, count*4), storageBindings=[0,1,2,3], outputs=[view(a, i) for i,a in enumerate((0x30000,0x32000,0x34000,0x36000))])
    record = [0x47534201,64,1,2,0,0,0,1,1,1,counter_va,counter_va+count*4,0,counter_va&~63,(counter_va+count*4+63)&~63,0]
    for i,w in enumerate(record): word(0x40000+i*4,w)
    map_page(descriptor_root,0x40000,15)
    map_page(counter_va,0x20000+(offset//4096)*4096,15)
    map_page(counter_va+count*4-1,0x20000+((offset+count*4-1)//4096)*4096,15)
    map_page(storage_root,0x41000,15);map_page(0x80800000,0x42000,15)
    for i,pa in enumerate((0x30000,0x32000,0x34000,0x36000)):
        va=0xcfffc000+i*(128*1024*1024+4096)
        record=[0x47534201,64,1,2,0,0,i,1,1,1,va,va+8192,0,va,va+8192,0]
        for j,w in enumerate(record):word(0x41000+i*64+j*4,w)
        map_page(va,pa,15);map_page(va+4096,pa+4096,15)
    discovery=[(w['address'],w['value']) for w in SAMPLE_WORDS]+[(0,0x47550002),
        (0x60,(1<<6)|(1<<22)|(1<<23)|(1<<25)|(1<<29)|(128<<8)),(0x68,0x160),
        (0x320,1048576),(0x158,0x02020810),(0x494,0x47544d01),(0x43c,0x47534d01),
        (0x3fc,0x47494d01),(0x4bc,0x47414d01),(0x460,0x47554d01)]
    writable=sorted([(0x42000,0x42040),(0x20000+offset,0x20000+offset+count*4)]+[(a,a+8192) for a in (0x30000,0x32000,0x34000,0x36000)])
    registers={0xc8:0,0xd0:0x80800000,0x14c:0x80800001,0x150:0x80800001,0x11c:1,0x440:0}
    registers.update({0xd4:groups,0xd8:1,0xdc:1,0xe0:4,0xe4:1,0xe8:1})
    for base,root,size in ((0x4a0,descriptor_root,64),(0x420,storage_root,256)):
        registers.update({base+4*i:v for i,v in enumerate((257,root,root+size,0,2,0,0))})
    if dynamic:
        allocations[0x44000]=bytearray(b'\x6d'*4096)
        struct.pack_into('<4I',allocations[0x44000],0,index,0x13579bdf,0x2468ace0,0x11223344)
        d.update(indexUniform=dict(globalBinding=35,allocationPhysical=0x44000,allocationBytes=4096,offset=0,bytes=16),
            uniformRoot=0xb1800000,uniformPhysical=0x60000,uniformBytes=8192)
        allocations[0x60000]=bytearray(8192);u=allocations[0x60000];root=0xb1800000
        struct.pack_into('<16I',u,0,0x47555201,64,1,2,0,0,2,1,0,0,root+64,root+1600,0,0,0,0)
        struct.pack_into('<16I',u,64,0x47554201,64,1,2,0,0,0,4,root+4096,root+4112,0,root+4096,root+4160,0,1,0)
        u[4096:4112]=allocations[0x44000][:16]
        map_page(root,0x60000,3);map_page(root+4096,0x61000,3)
        # RO snapshot pages are accessed but not dirty; source data stays an
        # independently guarded original GEM rather than a writable GPU view.
        for va in (root,root+4096):
            pa=tables[va>>22]+((va>>12)&1023)*4
            for first,data in allocations.items():
                if first<=pa<=first+len(data)-4:
                    struct.pack_into('<I',data,pa-first,struct.unpack_from('<I',data,pa-first)[0]&~128)
        registers.update({0x440+4*i:v for i,v in enumerate((257,root,2,0,0,0x80800000,0x80800040,0))})
    if source_case=='counter-coexist':
        image_pas=(0x90000,0x91000,0x92000,0x93000);root=0xb1000000
        allocations[0x80000]=bytearray(4096);map_page(root,0x80000,15)
        d.update(images=[dict(globalBinding=i,allocationPhysical=pa,allocationBytes=4096,offset=0,bytes=512)
                         for i,pa in enumerate(image_pas)],imageBindings=[0,1,2,3],imageRoot=root,imagePhysical=0x80000,imageBytes=1024)
        registers.update({0x3e0+4*i:v for i,v in enumerate((257,root,root+1024,0,2,0,0))})
        for unit,pa in enumerate(image_pas):
            allocations[pa]=bytearray(b'\x69'*4096)
            base=0xc7ffb000 if unit==3 else 0x87ffd000+unit*0x8001000
            image=[0]*64;image[:14]=[0x47495501,256,1,2,0,0,unit,1,1,4,0,1,14,14]
            image[20]=5;image[22]=image[25]=base;image[23]=base+4096
            image[26:30]=[1,1,1,1];image[37:40]=[4,128,512]
            struct.pack_into('<64I',allocations[0x80000],unit*256,*image)
            map_page(base,pa,15);writable.append((pa,pa+512))
        writable.sort()
    def capture(discovery=discovery, writable=writable, registers=registers,prefix=(),suffix=(),inactive=((1,0x80400070),)):
        actions=list(prefix)+[(1,a,v,0) for a,v in registers.items() if a!=0x11c]+[(1,0x11c,1,0)]+list(suffix)
        return (b'OGCMP003'+struct.pack('<QIII',1000000,37,11,len(discovery))+
            b''.join(struct.pack('<II',*r) for r in discovery)+struct.pack('<I',len(writable))+
            b''.join(struct.pack('<QQ',*r) for r in writable)+struct.pack('<I',len(inactive))+
            b''.join(struct.pack('<II',*r) for r in inactive)+struct.pack('<I',len(actions))+
            b''.join(struct.pack('<IIII',*r) for r in actions))
    return allocations,d,capture,discovery,writable,registers,tables


def emit(path, allocations):
    with path.open('wb') as f:
        pages=sum(len(d)//4096 for d in allocations.values())
        f.write(b'OGMEM001'+struct.pack('<II',len(allocations),pages))
        for a,d in sorted(allocations.items()): f.write(struct.pack('<QQII',a,a+len(d),0,0))
        for a,d in sorted(allocations.items()):
            for n in range(0,len(d),4096):f.write(struct.pack('<Q',a+n)+d[n:n+4096])


class Counter(unittest.TestCase):
    def test_original_four_image_coexistence_requires_all_nine_write_owners(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-images-',dir=parent) as t:
            before,after=Path(t)/'before',Path(t)/'after'
            alloc,d,cap,discovery,writable,registers,tables=fixture('counter-coexist');emit(before,alloc)
            with SparseMemory(before) as b:ready(b,d,cap())
            good=copy.deepcopy(alloc);values=[0xffffffff,0xfffffffe]+list(range(13,-1,-1))
            for pa in (0x30000,0x32000,0x34000,0x36000,0x90000,0x91000,0x92000,0x93000):
                struct.pack_into('<16I',good[pa],0,*values)
            struct.pack_into('<I',good[0x20000],4108,14)
            def check(data,description=d):
                emit(after,data)
                with SparseMemory(before) as b,SparseMemory(after) as a:verify(b,a,description)
            check(good)
            with self.assertRaises(ValueError):check(alloc)
            for pa in (0x90000,0x91000,0x92000,0x93000):
                for offset in (0,60,64,511,512,4095):
                    wrong=copy.deepcopy(good);wrong[pa][offset]^=1
                    with self.subTest(pa=pa,offset=offset),self.assertRaises(ValueError):check(wrong)
            for bindings in ([0,0,2,3],[3,2,1,0]):
                wrong=copy.deepcopy(d);wrong['imageBindings']=bindings
                with SparseMemory(before) as b,self.assertRaises(ValueError):ready(b,wrong,cap())
            for key,value in (('offset',4),('bytes',4096),('allocationPhysical',0x30000)):
                wrong=copy.deepcopy(d);wrong['images'][0][key]=value
                with SparseMemory(before) as b,self.assertRaises(ValueError):ready(b,wrong,cap())
            widened=[(a,b+3584 if a==0x93000 else b) for a,b in writable]
            with SparseMemory(before) as b,self.assertRaises(ValueError):ready(b,d,cap(writable=widened))
            for unit,word in ((0,3),(0,11),(1,13),(2,25),(3,38),(3,39),(3,63)):
                wrong=copy.deepcopy(alloc);wrong[0x80000][unit*256+word*4]^=1;emit(before,wrong)
                with self.subTest(unit=unit,word=word),SparseMemory(before) as b,self.assertRaises(ValueError):ready(b,d,cap())

    def test_original_scalar_uses_the_allocation_last_word_and_guards_every_other_byte(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-scalar-',dir=parent) as t:
            before,after=Path(t)/'before',Path(t)/'after'
            alloc,d,cap,*_=fixture('counter-scalar');emit(before,alloc)
            with SparseMemory(before) as b:ready(b,d,cap())
            good=copy.deepcopy(alloc)
            values=[0xffffffff,0xfffffffe]+list(range(13,-1,-1))
            for pa in (0x30000,0x32000,0x34000,0x36000):struct.pack_into('<16I',good[pa],0,*values)
            struct.pack_into('<I',good[0x20000],8188,14)
            def check(data,description=d):
                emit(after,data)
                with SparseMemory(before) as b,SparseMemory(after) as a:verify(b,a,description)
            check(good)
            with self.assertRaises(ValueError):check(alloc)
            wrong=copy.deepcopy(good)
            wrong[0x20000][8188:8192]=alloc[0x20000][8188:8192]
            struct.pack_into('<I',wrong[0x20000],4108,14)
            with self.assertRaises(ValueError):check(wrong)
            for pa,offset in ((0x20000,0),(0x20000,4079),(0x20000,4080),(0x20000,8187),
                              (0x20000,8191),(0x30000,64),(0x36000,8191)):
                wrong=copy.deepcopy(good);wrong[pa][offset]^=1
                with self.subTest(pa=pa,offset=offset),self.assertRaises(ValueError):check(wrong)
            wrong=copy.deepcopy(d);wrong['index']=7
            with self.assertRaises(ValueError):check(good,wrong)

    def test_inactive_graphics_context_requires_its_original_empty_root(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-empty-context-',dir=parent) as t:
            before=Path(t)/'before';alloc,d,cap,*_=fixture();emit(before,alloc)
            with SparseMemory(before) as memory:
                ready(memory,d,cap())
                for inactive in ((),((2,0x80400070),),((1,0x80800070),),((1,0x80400042),)):
                    with self.subTest(inactive=inactive),self.assertRaises(ValueError):ready(memory,d,cap(inactive=inactive))
                with self.assertRaises(ValueError):ready(memory,d,b'OGCMP002'+cap()[8:])
            alloc[0x70000][-1]=1;emit(before,alloc)
            with SparseMemory(before) as memory,self.assertRaises(ValueError):ready(memory,d,cap())

    def test_math_permutation_four_outputs_wrap_and_all_guards(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-oracle-',dir=parent) as t:
            before,after=Path(t)/'before',Path(t)/'after'
            alloc,d,cap,*_=fixture();emit(before,alloc)
            good=copy.deepcopy(alloc)
            values=[0xffffffff,0xfffffffe]+list(range(13,-1,-1))
            for a in (0x30000,0x32000,0x34000,0x36000):struct.pack_into('<16I',good[a],0,*values)
            struct.pack_into('<I',good[0x20000],4108,14)
            def check(data,description=d):
                emit(after,data)
                with SparseMemory(before) as b,SparseMemory(after) as a:verify(b,a,description)
            check(good)
            with SparseMemory(before) as b:ready(b,d,cap())
            for allocation,offset in ((0x20000,0),(0x20000,4079),(0x20000,4080),(0x20000,4108),
                (0x20000,4112),(0x20000,8191),(0x30000,0),(0x32000,4),(0x34000,64),
                (0x36000,8191),(0x40000,40),(0x41000,0),(0x1000,4),(0x50000,0),(0x42000,63)):
                wrong=copy.deepcopy(good);wrong[allocation][offset]^=1
                with self.subTest(allocation=allocation,offset=offset),self.assertRaises(ValueError):check(wrong)
            for values in ([14]+list(range(15)),[0xfffffffe]*16):
                wrong=copy.deepcopy(good)
                for a in (0x30000,0x32000,0x34000,0x36000):struct.pack_into('<16I',wrong[a],0,*values)
                with self.assertRaises(ValueError):check(wrong)
            with self.assertRaises(ValueError):check(alloc)
            # Precomputed producer results must fail before execution.
            emit(before,good)
            with self.assertRaises(ValueError):check(good)

    def test_read_and_essl_predecrement_wrap_not_tgsi_old_values(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-essl-math-',dir=parent) as t:
            before,after=Path(t)/'before',Path(t)/'after'
            for case in ('counter-read','counter-decrement'):
                alloc,d,cap,*_=fixture(case);emit(before,alloc)
                # ESSL atomicCounter reads unchanged; atomicCounterDecrement
                # returns the value AFTER subtracting one, unlike TGSI add(-1).
                values=[0xfffffffe]*16 if case=='counter-read' else [0]+[0xffffffff-i for i in range(15)]
                final=0xfffffffe if case=='counter-read' else 0xfffffff1
                def returned(values=values,final=final):
                    result=copy.deepcopy(alloc)
                    for address in (0x30000,0x32000,0x34000,0x36000):struct.pack_into('<16I',result[address],0,*values)
                    struct.pack_into('<I',result[0x20000],4108,final)
                    return result
                def check(data):
                    emit(after,data)
                    with SparseMemory(before) as b,SparseMemory(after) as a:verify(b,a,d)
                check(returned());check(returned(list(reversed(values))))
                with SparseMemory(before) as b:ready(b,d,cap())
                with self.assertRaises(ValueError):check(alloc)
                wrong_values=[0xffffffff]*16 if case=='counter-read' else [1,0]+[0xffffffff-i for i in range(14)]
                with self.assertRaises(ValueError):check(returned(wrong_values))
                with self.assertRaises(ValueError):check(returned(final=final^1))
                wrong=returned();wrong[0x32000][0]^=1
                with self.assertRaises(ValueError):check(wrong)
                for allocation,offset in ((0x20000,4080),(0x20000,4112),(0x40000,0),(0x30000,64)):
                    wrong=returned();wrong[allocation][offset]^=1
                    with self.subTest(case=case,allocation=allocation,offset=offset),self.assertRaises(ValueError):check(wrong)

    def test_dynamic_nonzero_uniform_and_all_counter_recipes(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-dynamic-',dir=parent) as t:
            before,after=Path(t)/'before',Path(t)/'after'
            for prefix,start,count in (('counter-dynamic-',4,4),('counter-dynamic-all-',0,8)):
                for op in ('increment','read','decrement'):
                    case=prefix+op;alloc,d,cap,*_=fixture(case);emit(before,alloc)
                    with SparseMemory(before) as b:ready(b,d,cap())
                    seed=0 if op=='decrement' else 0xfffffffe
                    original=[seed if i==7 else (seed+(i+1)*0x10203)&0xffffffff for i in range(8)]
                    values=[(original[i]-(op=='decrement'))&0xffffffff for i in range(start,start+count)]
                    good=copy.deepcopy(alloc)
                    for pa in (0x30000,0x32000,0x34000,0x36000):struct.pack_into('<%uI'%count,good[pa],0,*values)
                    for i in range(start,start+count):
                        final=(original[i]+(1 if op=='increment' else -1 if op=='decrement' else 0))&0xffffffff
                        struct.pack_into('<I',good[0x20000],4080+4*i,final)
                    def check(data):
                        emit(after,data)
                        with SparseMemory(before) as b,SparseMemory(after) as a:verify(b,a,d)
                    check(good)
                    with self.assertRaises(ValueError):check(alloc)
                    # A missing nonzero UBO addition, another-lane result or
                    # old rather than new decrement result cannot pass.
                    wrong=copy.deepcopy(good)
                    wrong_values=([original[i] for i in range(start,start+count)] if op=='decrement' else
                                  [(original[i]-(op=='decrement'))&0xffffffff for i in range(count)] if start else
                                  list(reversed(values)))
                    for pa in (0x30000,0x32000,0x34000,0x36000):struct.pack_into('<%uI'%count,wrong[pa],0,*wrong_values)
                    with self.assertRaises(ValueError):check(wrong)
                    for pa,offset in ((0x20000,4108),(0x20000,4112),(0x20000,4079),
                        (0x30000,count*4),(0x36000,0),(0x44000,0),(0x44000,4095),
                        (0x60000,7*4),(0x60000,64+14*4),(0x60000,4096),(0x60000,8191)):
                        wrong=copy.deepcopy(good);wrong[pa][offset]^=1
                        with self.subTest(case=case,pa=pa,offset=offset),self.assertRaises(ValueError):check(wrong)
                    if start:
                        wrong=copy.deepcopy(good)
                        omitted_ubo=[(original[i]-(op=='decrement'))&0xffffffff for i in range(count)]
                        for pa in (0x30000,0x32000,0x34000,0x36000):struct.pack_into('<4I',wrong[pa],0,*omitted_ubo)
                        with self.assertRaises(ValueError):check(wrong)
                    if start==0:
                        # All eight initial values differ: the previous equal
                        # index0/index7 seed could conceal this exact swap.
                        wrong=copy.deepcopy(good);swapped=list(values);swapped[0],swapped[7]=swapped[7],swapped[0]
                        for pa in (0x30000,0x32000,0x34000,0x36000):struct.pack_into('<8I',wrong[pa],0,*swapped)
                        with self.assertRaises(ValueError):check(wrong)
                        wrong=copy.deepcopy(good)
                        first,last=struct.unpack_from('<I',wrong[0x20000],4080)[0],struct.unpack_from('<I',wrong[0x20000],4108)[0]
                        struct.pack_into('<I',wrong[0x20000],4080,last);struct.pack_into('<I',wrong[0x20000],4108,first)
                        with self.assertRaises(ValueError):check(wrong)
                    # These malformed READY snapshots are rejected before a
                    # DUT can receive a command, including original UBO bytes.
                    for pa,offset in ((0x44000,0),(0x60000,7*4),(0x60000,64+14*4),(0x60000,4096),(0x60000,8191)):
                        wrong=copy.deepcopy(alloc);wrong[pa][offset]^=1;emit(before,wrong)
                        with SparseMemory(before) as b,self.assertRaises(ValueError):ready(b,d,cap())
                    emit(before,alloc)
                    wrong=copy.deepcopy(d);wrong['indexUniform']['globalBinding']=34
                    with SparseMemory(before) as b,self.assertRaises(ValueError):ready(b,wrong,cap())

    def test_ready_metadata_factory_writable_descriptor_and_pte_mutants(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-ready-',dir=parent) as t:
            path=Path(t)/'initial';alloc,d,cap,discovery,writable,registers,tables=fixture()
            def check(a=alloc,description=d,data=None):
                emit(path,a)
                with SparseMemory(path) as m:ready(m,description,cap() if data is None else data)
            check()
            for key,value in (('groups',1),('local',128),('index',6),('count',7),('seed',0),
                ('counterHandle',0),('storageBindings',[0,0,2,3]),('storageBindings',[3,2,0,1]),('context',3),('descriptorRoot',0xb1400040),('descriptorPhysical',0x41000),
                ('kernargPhysical',0x42004),('extra',1)):
                wrong=copy.deepcopy(d);wrong[key]=value
                with self.subTest(key=key),self.assertRaises(ValueError):check(description=wrong)
            for key,value in (('offset',4084),('allocationBytes',4096),('allocationPhysical',0x30000)):
                wrong=copy.deepcopy(d);wrong['counter'][key]=value
                with self.assertRaises(ValueError):check(description=wrong)
            wrong=copy.deepcopy(d);wrong['outputs'][1]=wrong['outputs'][0]
            with self.assertRaises(ValueError):check(description=wrong)
            for i in range(16):
                wrong=copy.deepcopy(alloc);wrong[0x40000][i*4]^=1
                with self.subTest(descriptorWord=i),self.assertRaises(ValueError):check(wrong)
            for va in (0xb1400000,0xb1c00ff0,0xb1c0100f,0xcfffc000,0x80800000):
                for bit in range(10):
                    wrong=copy.deepcopy(alloc)
                    offset=((va>>12)&1023)*4
                    word,=struct.unpack_from('<I',wrong[tables[va>>22]],offset)
                    struct.pack_into('<I',wrong[tables[va>>22]],offset,word^(1<<bit))
                    with self.subTest(pte=va,bit=bit),self.assertRaises(ValueError):check(wrong)
            for opcode in (0,14):
                prefix=[(1,0xc8,opcode,0),(1,0x11c,1,0)]
                with self.subTest(extraOpcode=opcode),self.assertRaisesRegex(ValueError,'one final kernel'):
                    check(data=cap(prefix=prefix))
            with self.assertRaisesRegex(ValueError,'one final kernel'):
                check(data=cap(suffix=[(2,0x120,1,1)]))
            with self.assertRaisesRegex(ValueError,'publish one command'):
                check(data=cap(prefix=[(1,0xc8,5,0),(1,0x11c,2,0)]))
            # Negative control: suppress only the new command-shape check.
            # The prior last-register snapshot then accepts a hidden kernel.
            from unittest.mock import patch
            import compute_counter_source as contract
            original_require=contract.require
            def old_shape(ok,message):
                if 'one final kernel' not in message:original_require(ok,message)
            with patch.object(contract,'require',side_effect=old_shape):
                check(data=cap(prefix=[(1,0xc8,0,0),(1,0x11c,1,0)]))
            wrong=list(discovery);wrong[0]=(wrong[0][0],wrong[0][1]^1)
            with self.assertRaises(ValueError):check(data=cap(discovery=wrong))
            with self.assertRaises(ValueError):check(data=b'OGCMP001'+cap()[8:])
            for index in range(6):
                wrong=list(writable);a,b=wrong[index];wrong[index]=(a,b+4)
                with self.subTest(writable=index),self.assertRaises(ValueError):check(data=cap(writable=wrong))
            for a,v in ((0xc8,14),(0xe0,128),(0x4a8,0xb1400080),(0x4b0,3),(0x14c,0x80000001)):
                wrong=dict(registers);wrong[a]=v
                with self.assertRaises(ValueError):check(data=cap(registers=wrong))



class CounterSourceClosure(unittest.TestCase):
    def test_private_source_mutants_invalidate_real_executor_checker(self):
        import shutil
        from unittest.mock import patch
        import prepare_owner_executor as q
        from test_prepare_owner_executor import ExecutorTests
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-source-copy-',dir=parent) as t:
            source=Path(t)/'source';source.mkdir()
            files=set(q.checker_files('compute')) | set(q.driver_files('compute',q.SAMPLE_PROFILE)) | set(q.DEFAULT_DEPENDENCIES)
            for name in files:
                target=source/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,target)
            with patch.object(q,'ROOT',source):
                helper=ExecutorTests();helper.setUp()
                try:
                    path,record=helper.fixture(profile=q.SAMPLE_PROFILE)
                    for name in ('scripts/compute_counter_source.py','scripts/test_gallium_atomic_counter.py',
                        'userspace/tests/cs_glsl_frontend.cpp','userspace/tests/cs_glsl_frontend.h',
                        'driver/opengpu_compute_counter_source.h','driver/opengpu_compute.c',
                        'scripts/compute_owner_oracle.py','scripts/test_gallium_compute_shared.py'):
                        target=source/name;original=target.read_bytes();target.write_bytes(original+b'\n/* CPU stale-input mutant */\n')
                        with self.subTest(name=name),self.assertRaises(RuntimeError):q.verify_qualification(path)
                        target.write_bytes(original)
                    target=source/'userspace/tests/cs_glsl_frontend.h';original=target.read_bytes();target.unlink()
                    with self.assertRaises((RuntimeError,FileNotFoundError)):q.checker_files('compute')
                    target.write_bytes(original)
                    frontend=source/'userspace/tests/compute_host_frontend.c';original=frontend.read_bytes()
                    frontend.write_bytes(original+b'\n#include "counter-missing-dependency.h"\n')
                    with self.assertRaisesRegex(RuntimeError,'missing.*dependency'):q.checker_files('compute')
                    extra=source/'userspace/tests/counter-missing-dependency.h';extra.write_text('/* private new input */\n')
                    self.assertIn(str(extra.relative_to(source)),q.checker_files('compute'))
                    with self.assertRaises(RuntimeError):q.verify_qualification(path)
                    frontend.write_bytes(original);extra.unlink()
                    q.verify_qualification(path)
                finally:helper.doCleanups()


class CounterController(unittest.TestCase):
    def test_ready_checks_before_execution_and_freezes_input_files(self):
        import json
        from compute_owner_controller import ready_inputs,unchanged_inputs
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-controller-',dir=parent) as t:
            root=Path(t);alloc,d,cap,*_=fixture()
            emit(root/'union.memory',alloc);(root/'oracle.json').write_text(json.dumps(d));(root/'record-0.capture').write_bytes(cap())
            for case in (None,'shared'):
                with self.assertRaisesRegex(ValueError,'distinct qualification'):ready_inputs(root,case)
            description,inputs=ready_inputs(root,'counter-increment')
            self.assertEqual(description,d);unchanged_inputs(root,inputs)
            for name in inputs:
                path=root/name;original=path.read_bytes();path.write_bytes(original+b' ')
                with self.subTest(name=name),self.assertRaisesRegex(ValueError,'immutable'):unchanged_inputs(root,inputs)
                path.write_bytes(original)
            path=root/'record-0.capture';original=path.read_bytes();path.unlink();(root/'alias').write_bytes(original);path.symlink_to('alias')
            with self.assertRaisesRegex(ValueError,'aliased'):unchanged_inputs(root,inputs)



class CounterCompilerDependencies(unittest.TestCase):
    def test_actual_mmd_rejects_changed_gpu_atomic_object_private_and_unbound_inputs(self):
        from compute_owner_inputs import dependency_inputs
        from native_sample_compiler_inputs import sha256
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-mmd-',dir=parent) as t:
            root=Path(t);cpu=root/'cpu';source=root/'source';external=root/'mesa'
            for p in (cpu,source,external):p.mkdir()
            gpu=source/'frontend.h';gpu.write_bytes(b'CPU GPU-header fixture')
            atom=external/'state_tracker_st_atom_atomicbuf.c.o';atom.write_bytes(b'CPU original-object fixture')
            private=cpu/'body.inc';private.write_bytes(b'CPU extracted-body fixture')
            dep=cpu/'unit.d';dep.write_text('object.o: '+' '.join(map(str,(gpu,atom,private)))+'\n')
            bound={str(atom):[str(atom),sha256(atom)]};qualified={'frontend.h':sha256(gpu)};generated={str(private):sha256(private)}
            def check():return dependency_inputs(dep,cpu=cpu,source=source,bound=bound,qualified=qualified,generated=generated)
            self.assertEqual(len(check()),3)
            for path in (gpu,atom,private):
                original=path.read_bytes();path.write_bytes(original+b'mutation')
                with self.subTest(path=path.name),self.assertRaises(ValueError):check()
                path.write_bytes(original)
            extra=external/'unbound.h';extra.write_bytes(b'CPU extra dependency')
            dep.write_text('object.o: '+str(extra)+'\n')
            with self.assertRaises(ValueError):check()


if __name__=='__main__':unittest.main()
