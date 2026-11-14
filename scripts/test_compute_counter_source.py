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


def fixture():
    allocations = {0x1000: bytearray(4096), 0x20000: bytearray(b'\x5a'*8192)}
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
    descriptor_root, counter_va, storage_root = 0xb1400000, 0xb1c00ff0, 0xb0c00000
    for i in range(8): word(0x20000+4080+4*i, (0xfffffffe+i*0x10203)&0xffffffff)
    word(0x20000+4108, 0xfffffffe)
    d = dict(schema=SCHEMA, sourceCase='counter-increment', groups=4, local=4, viewBytes=8192,
        guard=0xdeadbeef, seed=0xfffffffe, index=7, count=8, counterHandle=4, context=2,
        descriptorPhysical=0x40000, descriptorRoot=descriptor_root, kernargPhysical=0x42000, kernargBytes=64,
        counter=view(0x20000, 0, 4080, 32), storageBindings=[0,1,2,3], outputs=[view(a, i) for i,a in enumerate((0x30000,0x32000,0x34000,0x36000))])
    record = [0x47534201,64,1,2,0,0,0,1,1,1,counter_va,counter_va+32,0,counter_va&~63,(counter_va+95)&~63,0]
    for i,w in enumerate(record): word(0x40000+i*4,w)
    map_page(descriptor_root,0x40000,15);map_page(counter_va,0x20000,15);map_page(counter_va+31,0x21000,15)
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
    writable=sorted([(0x42000,0x42040),(0x20ff0,0x21010)]+[(a,a+8192) for a in (0x30000,0x32000,0x34000,0x36000)])
    registers={0xc8:0,0xd0:0x80800000,0x14c:0x80800001,0x150:0x80800001,0x11c:1}
    registers.update({0xd4:4,0xd8:1,0xdc:1,0xe0:4,0xe4:1,0xe8:1})
    for base,root,size in ((0x4a0,descriptor_root,64),(0x420,storage_root,256)):
        registers.update({base+4*i:v for i,v in enumerate((257,root,root+size,0,2,0,0))})
    def capture(discovery=discovery, writable=writable, registers=registers):
        actions=[(1,a,v,0) for a,v in registers.items() if a!=0x11c]+[(1,0x11c,1,0)]
        return (b'OGCMP002'+struct.pack('<QIII',1000000,37,11,len(discovery))+
            b''.join(struct.pack('<II',*r) for r in discovery)+struct.pack('<I',len(writable))+
            b''.join(struct.pack('<QQ',*r) for r in writable)+struct.pack('<I',len(actions))+
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
