#!/usr/bin/env python3
"""Original atomic partial-commit and continuous-owner negatives; CPU only."""
import argparse
from contextlib import ExitStack, redirect_stdout
import io
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
from compute_storage_atomic_cases import parameters
from compute_storage_recovery import (BASE, STEP, SOURCES, PHASES, COUNTERS, ROUND_SCHEMA,
    matrix_plan, fault_plan, phase, fault_receipt, check_round, check_source_launch, verify_fault)
import test_compute_atomic_order as byte_fixtures
Memory=byte_fixtures.Memory

ROOT=Path(__file__).resolve().parents[1]


def partial(case, group=0):
    _,_,description=byte_fixtures.SourceBytes().fixture()
    p=parameters(case)
    description.update(sourceCase=case,seed=p['seed'],targetOffset=p['target_offset'],
        atomicOffset=p['access_offset'],targetAccess=p['target_access'],resultStatus=p['result_status'])
    initial=bytearray(b'\x6b'*8192);struct.pack_into('<I',initial,p['target_offset'],p['seed'])
    before=Memory({0x1000:bytes(initial),0x5000:b'\xa7'*8192,0x9000:b'K'*4096})
    active=list(range(group*4,group*4+4));order=active[::-1]
    literal={
        'exchange':([10,7,7,7],7),
        'add':([0xfffffff0,0xfffffff5,0xfffffffa,0xffffffff],4),
        'xor':([10,0x8000000d,10,0x8000000d],10),
        'or':([0x80000000,0x80000007,0x80000007,0x80000007],0x80000007),
        'and':([0xffffffff,0x80000007,0x80000007,0x80000007],0x80000007),
        'signed-min':([0x7fffffff,0xfffffff9,0xfffffff9,0xfffffff9],0xfffffff9),
        'signed-max':([0x80000000,7,7,7],7),
        'unsigned-min':([0xffffffff,7,7,7],7),
        'unsigned-max':([0x7fffffff,0x80000007,0x80000007,0x80000007],0x80000007),
        'compare-match':([10,7,7,7],7),
        'compare-miss':([10]*4,10)}
    if case.endswith('endpoint'):
        olds,final=literal['add']
    elif p['operation'] in literal:
        olds,final=literal[p['operation']]
    else:
        final=p['seed'];olds=[]
        for invocation in order:
            olds.append(final)
            if p['operation']=='exchange-lane':final=0x80000000+invocation
            elif p['operation']=='add-lane':final=(final+invocation+1)&0xffffffff
            elif p['operation']=='compare-lane':final=invocation+1 if final==0 else final
            else:raise AssertionError('unqualified CPU reference operation')
    target=bytearray(initial);struct.pack_into('<I',target,p['target_offset'],final)
    output=bytearray(b'\xa7'*8192)
    for invocation,old in zip(order,olds):struct.pack_into('<I',output,4*invocation,old)
    return before,Memory({0x1000:bytes(target),0x5000:bytes(output),0x9000:b'K'*4096}),description


def changed(memory, base, offset, value=None):
    data=dict(memory.data);part=bytearray(data[base])
    if value is None:part[offset]^=1
    else:struct.pack_into('<I',part,offset,value)
    data[base]=bytes(part)
    return Memory(data)


def counters(index):
    r=sum(p['mode']=='read' for p in PHASES[:index+1])
    w=sum(p['mode']=='write' for p in PHASES[:index+1])
    return dict(zip(COUNTERS,(r,r,w,w)))


def held(index):
    return dict(commandId=42,faultTargetWriteAttempts=5 if PHASES[index]['mode']=='write' else 0,**counters(index))


def done(index):
    return dict(schema=ROUND_SCHEMA,phase=index,mode=PHASES[index]['mode'],commandId=42,
        resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,
        independentBytesRequired=True,startCycle=index*10,endCycle=(index+1)*10,**counters(index))


class StorageRecoveryTests(unittest.TestCase):
    def test_all_operations_allow_only_the_precise_healthy_workgroup(self):
        self.assertEqual(len(SOURCES),15)
        for case in SOURCES:
            for group in (0,1):
                with self.subTest(case=case,group=group):
                    before,after,description=partial(case,group)
                    verify_fault(before,after,description,'write')
                    verify_fault(before,before,description,'read')
                    for base,offset in ((0x1000,description['targetOffset']), (0x1000,4 if not description['targetOffset'] else 0),
                            (0x5000,group*16),(0x5000,(1-group)*16),(0x5000,32),(0x9000,4095)):
                        with self.assertRaises(ValueError):
                            verify_fault(before,changed(after,base,offset),description,'write')
                    with self.assertRaises(ValueError):
                        verify_fault(before,after,description,'read')

    def test_cas_winner_identity_and_mixed_failed_lanes_are_rejected(self):
        before,after,description=partial('storage-atomic-compare-lane')
        output=bytearray(after.data[0x5000]);words=list(struct.unpack('<8I',output[:32]))
        words[0],words[3]=words[3],words[0];output[:32]=struct.pack('<8I',*words)
        values=dict(after.data);values[0x5000]=bytes(output)
        with self.assertRaises(ValueError):verify_fault(before,Memory(values),description,'write')
        with self.assertRaises(ValueError):verify_fault(before,changed(after,0x5000,16,0),description,'write')

    def test_exact_matrix_and_original_source_arguments(self):
        self.assertEqual(struct.unpack('<8sIIQQ',matrix_plan()),(b'OGCSRM01',60,4,BASE,STEP))
        build=Path('/original/build');session=Path('/original/session');profile=session/'profile.bin'
        for index,stage in enumerate(PHASES):
            self.assertEqual(stage['index'],index)
            self.assertEqual(stage['mode'],('read','healthy','write','healthy')[index%4])
            self.assertEqual(stage['base'],BASE+index*STEP)
            command=[str(build/'compute_host_frontend'),str(profile),str(session/('round-'+str(index))),
                str(build/'glsl'/parameters(stage['case'])['source']),stage['case']]
            if stage['mode']!='healthy':command+=['--expect-storage-fault',stage['mode']]
            command+=['--physical-base',str(stage['base'])]
            check_source_launch(command,index,build,session,profile)
            for slot in range(len(command)):
                bad=list(command);bad[slot]='replaced'
                with self.subTest(index=index,slot=slot),self.assertRaises(ValueError):
                    check_source_launch(bad,index,build,session,profile)
        for index in (-1,True,60):
            with self.assertRaises(ValueError):phase(index)
        _,_,description=partial('storage-atomic-endpoint')
        self.assertEqual(struct.unpack('<8sQQI',fault_plan(description,'write')),(b'OGCSF001',0x2fc0,0x3000,2))

    def test_error_history_identity_and_clock_survive_every_phase(self):
        previous=None
        for index in range(60):
            record=done(index)
            self.assertIs(check_round(record,index,held(index),previous),record)
            previous=record
        self.assertEqual(tuple(previous[key] for key in COUNTERS),(15,15,15,15))
        mutations=dict(schema='wrong',phase=1,mode='healthy',commandId=43,resetCount=2,ramReplaced=True,
            externalDrained=False,sourceQualificationRequired=False,independentBytesRequired=False,
            endCycle=0,physicalReadFaults=0,injectedReadFaults=0,physicalWriteFaults=1,injectedWriteFaults=1)
        for key,value in mutations.items():
            record=done(0);record[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):check_round(record,0,held(0))
        for key in done(0):
            record=done(0);del record[key]
            with self.subTest(missing=key),self.assertRaises(ValueError):check_round(record,0,held(0))
        for key,value in (('startCycle',True),('endCycle',-1),('foreign',0)):
            record=done(0);record[key]=value
            with self.assertRaises(ValueError):check_round(record,0,held(0))
        for patch in ({'physicalWriteFaults':2,'injectedWriteFaults':2},
                      {'physicalReadFaults':2,'injectedReadFaults':2},
                      {'injectedWriteFaults':0},{'faultTargetWriteAttempts':4},{'faultTargetWriteAttempts':6}):
            record=dict(held(2),**patch)
            with self.assertRaises(ValueError):fault_receipt(record,2,done(1))
        with self.assertRaises(ValueError):check_round(done(3),3,held(3),done(0))

    def test_real_axi_write_fault_and_existing_response_cases_with_sanitizers(self):
        with tempfile.TemporaryDirectory(prefix='storage-recovery-axi-') as directory:
            binary=Path(directory)/'axi'
            subprocess.run([*shlex.split(os.environ.get('CXX','c++')),'-std=c++17','-O1',
                '-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-fno-omit-frame-pointer',
                str(ROOT/'scripts/test_native_frame_axi.cpp'),'-o',str(binary)],check=True)
            result=subprocess.run([str(binary),directory],check=True,capture_output=True,text=True)
            self.assertEqual(result.stdout,'PASS native frame AXI cases=25\n')


class ContinuousOwnerTests(unittest.TestCase):
    def test_one_live_producer_and_dut_retain_all_sixty_original_phases(self):
        self.peer(False)

    def test_bad_partial_bytes_never_authorize_fault_cleanup_or_next_phase(self):
        self.peer(True)

    def peer(self, bad_write):
        from compute_storage_recovery_controller import run_recovery_matrix
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        class Process:
            def __init__(self):self.returncode=None;self.terminated=False
            def poll(self):return self.returncode
            def wait(self,timeout=None):self.returncode=0;return 0
            def terminate(self):self.terminated=True;self.returncode=-15
            def kill(self):self.terminated=True;self.returncode=-9
        processes=[];commands=[];events=[]
        def start(command,**_kwargs):
            commands.append(command);p=Process();processes.append(p);return p
        def atomic(path,data):
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
            if path.parent.name.startswith('round-'):
                events.append((int(path.parent.name[6:]),path.name))
        def wait(path,*_args):
            if path.name=='record-0.capture':atomic(path,b'explicit CPU peer capture')
        def record(path):
            if path.name=='profile.json':return {'actual-profile-cpu-peer':True}
            index=int(path.parent.name[6:])
            return done(index) if path.name=='executor.done' else held(index)
        def ready(path,case,**_kwargs):
            p=parameters(case);index=int(path.name[6:])
            return dict(sourceCase=case,targetOffset=p['target_offset'],
                target=dict(allocationPhysical=PHASES[index]['base'],allocationBytes=8192)),{}
        class MemoryPeer:
            def __init__(self,path):
                n=int(path.parent.name[6:]) if path.parent.name.startswith('round-') else 0
                base=PHASES[n]['base'];self.ranges=[(base,base+65536,0)]
            def __enter__(self):return self
            def __exit__(self,*_args):pass
        def fault_bytes(_before,_after,_description,mode):
            events.append((-1,'fault-bytes-'+mode))
            if bad_write and mode=='write':raise ValueError('deliberately invalid partial old word')
        qualified=Mock()
        with tempfile.TemporaryDirectory(prefix='storage-recovery-peer-') as temporary:
            directory=Path(temporary)
            patches=(
                patch('compute_storage_recovery_controller.subprocess.Popen',side_effect=start),
                patch('compute_storage_recovery_controller.strict_json',side_effect=record),
                patch('compute_storage_recovery_controller.SparseMemory',MemoryPeer),
                patch('compute_storage_recovery_controller.captured_page_tables',return_value='actual-cpu-peer-tables'),
                patch('compute_storage_recovery_controller.union_original_memory',return_value=b'old RAM plus disjoint originals'),
                patch('compute_storage_recovery_controller.verify'),
                patch('compute_storage_recovery_controller.verify_fault',side_effect=fault_bytes),
                patch('compute_storage_recovery_controller.verify_cleaned_memory'),
                patch('compute_storage_recovery_controller.held_bytes',return_value=b'held'),
                patch('compute_storage_recovery_controller.failed_held_bytes',return_value=b'failed-held'),
                patch('compute_storage_recovery_controller.retired_bytes',return_value=b'retired'),
                patch('compute_storage_recovery_controller.failed_retired_bytes',return_value=b'failed-retired'),
                patch('compute_storage_recovery_controller.checked_cleanup'),
                patch('compute_owner_controller.atomic',side_effect=atomic),
                patch('compute_owner_controller.wait_file',side_effect=wait),
                patch('compute_owner_controller.ready_inputs',side_effect=ready),
                patch('compute_owner_controller.unchanged_inputs'),
                patch('compute_owner_controller.input_digest',side_effect=lambda path:str(path)))
            with ExitStack() as stack,redirect_stdout(io.StringIO()):
                for active in patches:stack.enter_context(active)
                if bad_write:
                    with self.assertRaisesRegex(ValueError,'invalid partial old word'):
                        run_recovery_matrix(argparse.Namespace(timeout=60),['original-producer','{ipc}'],
                            directory,Path('native-executor'),{},qualified,IMAGE_FLOAT_PROFILE)
                else:
                    run_recovery_matrix(argparse.Namespace(timeout=60),['original-producer','{ipc}'],
                        directory,Path('native-executor'),{},qualified,IMAGE_FLOAT_PROFILE)
        self.assertEqual(len(processes),2)
        self.assertEqual(commands[1][1],'--active-storage-recovery-matrix')
        cleanups=[index for index,name in events if name=='BEGIN_CLEANUP']
        if bad_write:
            self.assertEqual(cleanups,[0,1])
            self.assertNotIn((2,'record-0.retired.response'),events)
            self.assertNotIn((3,'BEGIN_EXECUTION'),events)
            self.assertTrue(all(p.terminated for p in processes))
            qualified.assert_not_called()
        else:
            self.assertEqual(cleanups,list(range(60)))
            self.assertTrue(all(p.returncode==0 and not p.terminated for p in processes))
            self.assertEqual(sum(name=='fault-bytes-read' for _,name in events),15)
            self.assertEqual(sum(name=='fault-bytes-write' for _,name in events),15)
            qualified.assert_called_once()


if __name__=='__main__':
    unittest.main()
