#!/usr/bin/env python3
"""Independent canonical recipe guards, using actual Linux CPU captures.

Synthetic CEASE FS packets and synthetic healthy returns are explicitly CPU
unit inputs. No Mesa, shader DUT, genuine completion or cleanup success runs.
"""
import json
import os
from pathlib import Path
import shutil
import struct
import tempfile
import unittest

import native_sample_owner_oracle as oracle
import native_sample_owner_recipe as recipe
from native_sample_owner_protocol import profile_bytes
from test_native_sample_owner import profile
from test_native_sample_submit_bridge import build_bridge
import test_native_sample_owner_oracle as byte_tests
from test_native_sample_gny2_oracle import fixture as completion_fixture

ROOT = Path(__file__).resolve().parents[1]


class Overlay:
    """One physical word changed in an otherwise original memory view."""
    def __init__(self, memory, address, raw):
        self.memory, self.address, self.raw = memory, address, raw
        self.ranges = memory.ranges

    def read(self, address, count):
        data = bytearray(self.memory.read(address, count))
        first, last = max(address, self.address), min(address+count, self.address+len(self.raw))
        if first < last:
            data[first-address:last-address] = self.raw[first-self.address:last-self.address]
        return bytes(data)


class CanonicalRecipeTests(unittest.TestCase):
    fixture = byte_tests.SampleByteOracleTests.fixture
    write_physical = byte_tests.SampleByteOracleTests.write_physical

    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.tmp = tempfile.TemporaryDirectory(prefix='sample-recipe-', dir=ROOT/'.artifacts')
        cls.out = Path(cls.tmp.name)
        cls.binary = build_bridge(cls.out, os.environ.get('OPENGPU_TEST_SANITIZERS') == '1', live=True)
        cls.observed = profile()
        cls.wire = cls.out/'profile.bin';cls.wire.write_bytes(profile_bytes(cls.observed))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def space(self, memory, description, captured):
        _, registers, _ = oracle.capture(captured, self.observed)
        return oracle.AddressSpace(memory, description, registers[0x14c])

    def test_independent_native_vertex_words(self):
        words = struct.unpack('<420I', recipe.vertex_program())
        self.assertEqual(words[:3], (0x00241393, 0x007083b3, 0x01007557))
        self.assertEqual(words[-1], 0x30500073)
        # Exactly four position exports plus sixteen slots * four components.
        self.assertEqual(sum((w&127)==0x27 for w in words), 68)
        self.assertEqual(sum((w&127)==7 for w in words), 4)

    def test_complete_records_and_every_word_rejects_mutation(self):
        for samples in (1,2,4):
            directory, description, packet, captured = self.fixture('mask-depth-keep', samples)
            try:
                meta, code = oracle.program(packet, 'mask-depth-keep')
                with oracle.SparseMemory(directory/'union.memory') as memory:
                    space = self.space(memory,description,captured)
                    recipe.verify(space,description,meta,code)
                    expected = recipe.records(space,description,meta,code)
                    for name,(va,record) in expected.items():
                        pa=space.physical(va,len(record.words)*4)
                        for index,value in enumerate(record.words):
                            for bit in (1,1<<31):
                                changed=Overlay(memory,pa+4*index,struct.pack('<I',value^bit))
                                with self.assertRaisesRegex(ValueError,name+' word '+str(index)+r' differs',msg=(samples,name,index,bit)):
                                    recipe.verify(self.space(changed,description,captured),description,meta,code)
                    pa=space.physical(description['vertexPC'],420*4,executable=True)
                    for index,value in enumerate(struct.unpack('<420I',recipe.vertex_program())):
                        changed=Overlay(memory,pa+index*4,struct.pack('<I',value^1))
                        with self.assertRaisesRegex(ValueError,'VS RX differs',msg=(samples,'VS',index)):
                            recipe.verify(self.space(changed,description,captured),description,meta,code)
            finally:
                shutil.rmtree(directory)

    def test_original_plan_identity_is_not_self_authorizing(self):
        import copy
        directory,description,packet,captured=self.fixture('sample',1)
        try:
            meta,code=oracle.program(packet,'sample')
            with oracle.SparseMemory(directory/'union.memory') as memory:
                for key in ('frameResources','vertexScratch'):
                    bad=copy.deepcopy(description);bad.pop(key)
                    with self.assertRaises(ValueError):recipe.verify(self.space(memory,bad,captured),bad,meta,code)
                for key,value in [('handle',0),('offset',64),('viewBytes',64),('viewVA',0),('allocationPhysical',0),('allocationBytes',8192)]:
                    bad=copy.deepcopy(description);bad['frameResources'][0][key]=value
                    with self.assertRaises(ValueError,msg=key):recipe.verify(self.space(memory,bad,captured),bad,meta,code)
                bad=copy.deepcopy(description);bad['frameResources'][-1]=bad['frameResources'][0]
                with self.assertRaises(ValueError):recipe.verify(self.space(memory,bad,captured),bad,meta,code)
        finally:shutil.rmtree(directory)

    def test_confirmed_same_initial_and_healthy_return_mutants(self):
        directory,description,packet,captured=self.fixture('sample',1)
        try:
            source=directory/'union.memory';returned=directory/'healthy.memory'
            changes=[]
            for image in description['images']:
                raw = struct.pack('<4f',0,.5,.5,1)*16 if image['slot']==0 else struct.pack('<f',.25)*(image['viewBytes']//4)
                changes.append((image['allocationPhysical']+image['offset'],raw))
            completion=description['completionOriginal'];command,_,_=oracle.capture(captured,self.observed)
            changes.append((completion['allocationPhysical']+completion['offset'],completion_fixture(command,1)))
            self.write_physical(source,returned,changes)
            meta,code=oracle.program(packet,'sample')
            with oracle.SparseMemory(source) as before,oracle.SparseMemory(returned) as after:
                recipe.verify(self.space(before,description,captured),description,meta,code)
                oracle.verify(before,after,description,packet,captured,self.observed)
                space=self.space(before,description,captured)
                mutations=[(space.physical(description['stateVA'],512),0),
                    (space.physical(description['frameVA'],2048)+74*4,0),
                    (space.physical(description['vertexPC'],420*4,executable=True),0x30500073)]
                for address,value in mutations:
                    bad_before,bad_after=Overlay(before,address,struct.pack('<I',value)),Overlay(after,address,struct.pack('<I',value))
                    # Run the canonical precheck before both public oracle
                    # modes. Same corruption in both memories cannot earn an
                    # immutable-equality exemption from the recipe contract.
                    for returned_view in (None,bad_after):
                        with self.assertRaises(ValueError):
                            recipe.verify(self.space(bad_before,description,captured),description,meta,code)
                            oracle.verify(bad_before,returned_view,description,packet,captured,self.observed)
        finally:shutil.rmtree(directory)


if __name__=='__main__':
    unittest.main()
