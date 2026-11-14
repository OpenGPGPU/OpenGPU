#!/usr/bin/env python3
"""Actual Linux source snapshots and independent oracle; CPU fixture, no Mesa/GPU."""
import copy
import json
import os
from pathlib import Path
import runpy
import shlex
import struct
import subprocess
import tempfile
import unittest
from owner_resource_contract import SAMPLE_WORDS
from compute_owner_oracle import SparseMemory
from native_sample_graphics_source_oracle import verify, Space, expected_color, descriptor_schemas
ROOT=Path(__file__).resolve().parents[1]

class Overlay:
    def __init__(self, base, patches):self.base,self.patches,self.ranges=base,patches,base.ranges
    def read(self, address, size):
        result=bytearray(self.base.read(address,size))
        for start,data in self.patches:
            lo,hi=max(address,start),min(address+size,start+len(data))
            if lo<hi:result[lo-address:hi-address]=data[lo-start:hi-start]
        return bytes(result)

class SourceProofTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.temp=tempfile.TemporaryDirectory(prefix='graphics-source-',dir=ROOT/'.artifacts');cls.out=Path(cls.temp.name)
        runpy.run_path(str(ROOT/'scripts/test_native_mmio_capture.py'))['write_bodies'](cls.out)
        aux=[(0,0x47550002),(0x60,sum(1<<b for b in (6,22,23,25,29))|128<<8),(0x68,0x160),(0x320,1048576),(0x158,0x02020810)]
        cls.out.joinpath('sample_profile.inc').write_text('static const struct opengpu_native_sample_word profile[117]={'+
            ','.join('{%du,%du}'%(w['address'],w['value']) for w in SAMPLE_WORDS)+','+','.join('{%du,%du}'%p for p in aux)+'};\n')
        flags=['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-function','-Wno-unused-parameter','-D_POSIX_C_SOURCE=200809L']
        if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
        includes=[cls.out,ROOT/'driver',ROOT/'driver/tests',ROOT/'driver/tests/private_snapshot_stubs',ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs',ROOT/'userspace',ROOT/'userspace/tests']
        binary=cls.out/'source-fixture'
        compiler=shlex.split(os.environ.get('CC','cc'));objects=[]
        for relative in ('userspace/tests/graphics_sample_source_test.c','userspace/tests/native_graphics_uniform_source.c',
                         'userspace/opengpu_native_sample_graphics.c','driver/opengpu_native_frame.c'):
            obj=cls.out/(Path(relative).stem+'.o');objects.append(obj)
            subprocess.run([*compiler,*flags,*['-I'+str(p) for p in includes],'-MMD','-MF',str(obj.with_suffix('.d')),
                            '-c',str(ROOT/relative),'-o',str(obj)],check=True)
        subprocess.run([*compiler,*flags,*map(str,objects),'-o',str(binary)],check=True)
        subprocess.run([str(binary),str(cls.out)],check=True,timeout=30)
        cls.descriptions=[json.loads((cls.out/('record-%d.source-plan.json'%i)).read_text()) for i in range(2)]
        cls.programs=[(cls.out/('compiler-%s.words'%stage)).read_bytes() for stage in ('vs','fs')]
        cls.reflection=dict(schema='opengpu-linked-graphics-reflection-v1',stages=[
            dict(stage=0,defaultComponents=1024,bindings=list(range(11,-1,-1))),
            dict(stage=1,defaultComponents=1024,bindings=[24+(i+5)%12 for i in range(12)])])
        for stage,program in zip(('vs','fs'),cls.programs):(cls.out/('native-real-%s.words'%stage)).write_bytes(program)
        (cls.out/'compiler-reflection.json').write_text(json.dumps(cls.reflection))
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def memory(self):return SparseMemory(self.out/'union.memory')
    def test_two_actual_snapshot_exports_all_sources_reflection_and_high_mapping(self):
        with self.memory() as memory:
            colors=[verify(memory,d,self.programs,self.reflection) for d in self.descriptions]
            self.assertEqual(colors,[(0x375808f1,)*4,(0x807663bd,)*4])
            self.assertEqual([len(d['uniforms']) for d in self.descriptions],[26,26])
            self.assertTrue(any(u['bindingIndex']==23 for u in self.descriptions[0]['uniforms']))
        from test_gallium_sample_graphics_uniforms import validate_source_plans
        self.assertEqual(validate_source_plans(self.out),colors)
        self.assertIn('graphics_sample_source_plan.inc',(self.out/'graphics_sample_source_test.d').read_text())
    def test_each_original_and_private_window_interior_both_initial_returned_mutants(self):
        with self.memory() as memory:
            for d in self.descriptions:
                space=Space(memory,d)
                for u in d['uniforms']:
                    for physical in (u['allocationPhysical']+u['offset']+516,space.physical(u['privateVA'],u['bytes'])+516):
                        bad=Overlay(memory,[(physical,bytes([memory.read(physical,1)[0]^1]))])
                        with self.assertRaises(ValueError):verify(bad,d,self.programs,self.reflection,returned=bad)
    def test_gur_reserved_mapping_metadata_rx_and_pte_same_memory_mutants(self):
        d=self.descriptions[0]
        with self.memory() as memory:
            space=Space(memory,d);root=d['uniforms'][0]['rootVA']
            addresses=[space.physical(root+stage*64,64)+word*4 for stage in range(2) for word in range(16)]
            addresses += [space.physical(p['privateVA'],p['bytes'],executable=True)+52 for p in d['programs']]
            addresses += [p['physical']+p['allocationBytes']-1 for p in d['privateSegments'] if p['executable']]
            for physical in addresses:
                bad=Overlay(memory,[(physical,bytes([memory.read(physical,1)[0]^1]))])
                with self.assertRaises(ValueError):verify(bad,d,self.programs,self.reflection,returned=bad)
            for u in d['uniforms']:
                physical=space.physical(u['bindingVA'],64)
                for word in range(16):
                    at=physical+word*4;bad=Overlay(memory,[(at,bytes([memory.read(at,1)[0]^1]))])
                    with self.assertRaises(ValueError):verify(bad,d,self.programs,self.reflection,returned=bad)
            for name in ('privateVA','bindingVA','rootVA','globalBinding','offset','bytes','handle','allocationPhysical'):
                changed=copy.deepcopy(d);changed['uniforms'][0][name]^=4
                with self.assertRaises(ValueError):verify(memory,changed,self.programs,self.reflection)
    def test_actual_descriptor_source_links_identity_abi_and_limits_both_memory_mutants(self):
        fields={
            'job':['job.drawBase','job.drawLimit','job.drawCount','job.drawStride','job.frameBase','job.frameLimit',
                   'job.identity.context','job.identity.pass','job.identity.generation'],
            'frame':['frame.states.base','frame.states.limit','frame.states.version','frame.version',
                     'frame.states.identity.context','frame.states.identity.pass','frame.states.identity.generation'],
            'draw':['draw.state','draw.vertex.geometry.vertex.shaderPc','draw.vertex.geometry.vertex.uniforms.address',
                    'draw.vertex.geometry.vertex.uniforms.version','draw.vertex.geometry.vertex.version',
                    'draw.vertex.geometry.vertex.input.identity.context','draw.vertex.geometry.vertex.input.identity.pass',
                    'draw.vertex.geometry.vertex.input.identity.generation'],
            'state':['fragment.shaderPc','fragment.uniforms.address','fragment.uniforms.version','fragment.version',
                     'fragment.identity.context','fragment.identity.pass','fragment.identity.generation']}
        d=self.descriptions[0]
        with self.memory() as memory:
            space=Space(memory,d)
            for name,schema in descriptor_schemas().items():
                used={f['name']:f for f in schema['fields']}
                words=[0,1,2]+[used[f]['word']+i for f in fields[name] for i in range((used[f]['bits']+31)//32)]
                if name=='state':words+=list(range(3,8))
                for word in words:
                    physical=space.physical(d[name+'VA'],schema['bytes'])+word*4
                    bad=Overlay(memory,[(physical,bytes([memory.read(physical,1)[0]^1]))])
                    with self.assertRaises(ValueError,msg=name+':'+str(word)):
                        verify(bad,d,self.programs,self.reflection,returned=bad)
                changed=copy.deepcopy(d);changed[name+'VA']+=64
                with self.assertRaises(ValueError):verify(memory,changed,self.programs,self.reflection)
            changed=copy.deepcopy(d);entry=changed['pteEntries'][0];entry['leafWord']^=4
            bad=Overlay(memory,[(entry['leafAddress'],struct.pack('<I',entry['leafWord']))])
            with self.assertRaises(ValueError):verify(bad,changed,self.programs,self.reflection,returned=bad)
            for mutation in ('duplicate','missing','extra'):
                changed=copy.deepcopy(d)
                if mutation=='duplicate':changed['pteEntries'].append(changed['pteEntries'][0])
                elif mutation=='missing':changed['pteEntries'].pop()
                else:
                    entry=copy.deepcopy(changed['pteEntries'][0]);entry['va']=0;changed['pteEntries'].append(entry)
                with self.assertRaises(ValueError):verify(memory,changed,self.programs,self.reflection)
    def test_compiler_reflection_external_bytes_and_original_recipe_are_required(self):
        with self.memory() as memory:
            d=self.descriptions[0]
            reflected=copy.deepcopy(self.reflection);reflected['stages'][0]['bindings'][0:2]=reflected['stages'][0]['bindings'][1::-1]
            with self.assertRaises(ValueError):verify(memory,d,self.programs,reflected)
            programs=list(self.programs);programs[0]=programs[0][:-4]+bytes(4)
            with self.assertRaises(ValueError):verify(memory,d,programs,self.reflection)
            for replacement in ('opengpu-native-owner-source-v1','opengpu-native-sample-inputs-v1'):
                changed=copy.deepcopy(d);changed['schema']=replacement
                with self.assertRaises(ValueError):verify(memory,changed,self.programs,self.reflection)
            changed=copy.deepcopy(self.descriptions[1]);changed['record']=0
            with self.assertRaises(ValueError):verify(memory,changed,self.programs,self.reflection)
            changed=copy.deepcopy(d);changed['shaderABI']=6
            with self.assertRaises(ValueError):verify(memory,changed,self.programs,self.reflection)
    def test_abi1_image_formula_and_entire_original_allocation_guards(self):
        with self.memory() as memory:
            for d in self.descriptions:
                image=next(v for v in d['resources'] if (v['role'],v['index'])==(1,0))
                known=(0x375808f1,0x807663bd)[d['record']]
                self.assertEqual(expected_color(d['record']),(known,)*4)
                pixel=struct.pack('<4I',*(known,)*4)*16
                returned=Overlay(memory,[(image['allocationPhysical']+64,pixel)])
                verify(memory,d,self.programs,self.reflection,returned=returned,image=True)
                for offset in (0,63,64,319,320,383,4095):
                    at=image['allocationPhysical']+offset
                    bad=Overlay(returned,[(at,bytes([returned.read(at,1)[0]^1]))])
                    with self.assertRaises(ValueError):verify(memory,d,self.programs,self.reflection,returned=bad,image=True)
                at=image['allocationPhysical']+383
                both=Overlay(memory,[(at,b'\0')])
                with self.assertRaises(ValueError):verify(both,d,self.programs,self.reflection,returned=both,image=True)

if __name__=='__main__':unittest.main()
