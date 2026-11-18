#!/usr/bin/env python3
"""CPU-only partial histories, byte guards and continuous global protocol controls."""
import argparse
from contextlib import ExitStack, redirect_stdout
from copy import deepcopy
import io
import tempfile
from unittest.mock import Mock, patch
from pathlib import Path
import struct
import unittest

from compute_atomic_order import result, linearization
from compute_global_atomic_cases import CASES, events, parameters, OUTPUT_GUARD
from compute_global_recovery import (BASE, STEP, PHASES, COUNTERS, ROUND_SCHEMA,
    phase, matrix_plan, fault_plan, source_launch, check_source_launch,
    fault_receipt, check_round, verify_fault)
from test_compute_global_atomic_source import fixture, HISTORIES
from test_compute_atomic_order import Memory


def partial(case, failed=3, schedule=None):
    before,_,description=fixture(case)
    before=Memory({**before.data,0x80000:bytearray(b'previous cleaned RAM'*128)})
    after=deepcopy(before)
    calls=events(case)
    groups=[n for n in range(4) if n!=failed]
    order=([event for group in groups for event in (2*group,2*group+1)]
           if schedule is None else schedule)
    # Fabricated CPU fixture, not GPU execution or evidence. The arithmetic
    # helper has separate literal ten-operation tests; this tests partial
    # ownership, projected caller order and complete unchanged-byte checks.
    value=parameters(case)['seed']
    for index in order:
        struct.pack_into('<I',after.data[0x20000],128+4*index,value)
        call=calls[index]
        value=result(call['operation'],value,call['operand'],call['compare'])
    struct.pack_into('<I',after.data[0x20000],parameters(case)['target_offset'],value)
    return before,after,description


def held(index):
    r=4*sum(s['mode']=='read' for s in PHASES[:index+1])
    w=sum(s['mode']=='write' for s in PHASES[:index+1])
    return dict(commandId=42,**dict(zip(COUNTERS,(r,r,w,w))),
                faultTargetWriteAttempts=7 if phase(index)['mode']=='write' else 0)


def done(index):
    h=held(index)
    return dict(schema=ROUND_SCHEMA,phase=index,mode=phase(index)['mode'],commandId=42,
        resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,
        independentBytesRequired=True,startCycle=1000*index,endCycle=1000*index+100,
        **{key:h[key] for key in COUNTERS})


class GlobalRecovery(unittest.TestCase):
    def test_all_sources_failed_callers_legal_interleavings_and_whole_ram(self):
        for case in CASES:
            for failed in range(4):
                groups=[n for n in range(4) if n!=failed]
                schedules=([event for group in groups for event in (2*group,2*group+1)],
                           [event for group in reversed(groups) for event in (2*group,2*group+1)],
                           [2*group for group in groups]+[2*group+1 for group in reversed(groups)])
                for schedule in schedules:
                    before,after,d=partial(case,failed,schedule)
                    verify_fault(before,after,d,'write')
                    verify_fault(before,before,d,'read')
                    with self.assertRaises(ValueError):verify_fault(before,after,d,'read')
                    for address,offset in ((0x20000,parameters(case)['target_offset']),
                            (0x10000,0),(0x30000,4095),(0x40000,0),(0x80000,1),
                            (0x20000,127),(0x20000,160)):
                        bad=deepcopy(after);bad.data[address][offset]^=1
                        with self.subTest(case=case,failed=failed,address=address,offset=offset),self.assertRaises(ValueError):
                            verify_fault(before,bad,d,'write')
                    for index in range(8):
                        bad=deepcopy(after)
                        struct.pack_into('<I',bad.data[0x20000],128+4*index,
                            0 if index//2==failed else OUTPUT_GUARD)
                        with self.assertRaises(ValueError):verify_fault(before,bad,d,'write')

    def test_partial_serial_fixture_matches_existing_independent_literal_histories(self):
        for case in CASES:
            _,after,_=partial(case)
            words,final=HISTORIES[parameters(case)['pair']]
            self.assertEqual(struct.unpack('<8I',after.read(0x20080,32)),(*words[:6],OUTPUT_GUARD,OUTPUT_GUARD))
            self.assertEqual(struct.unpack('<I',after.read(0x20000+parameters(case)['target_offset'],4))[0],words[6])

    def test_unordered_successful_calls_do_not_replace_original_caller_order(self):
        case=CASES[0]
        before,after,d=partial(case,3,[1,0,2,3,4,5])
        observed=list(struct.unpack('<6I',after.read(0x20080,24)))
        final=struct.unpack('<I',after.read(0x20000,4))[0]
        linearization(parameters(case)['seed'],final,events(case)[:6],observed)
        with self.assertRaises(ValueError):verify_fault(before,after,d,'write')

    def test_exact_matrix_original_source_flags_and_physical_ranges(self):
        self.assertEqual(struct.unpack('<8sIIQQ',matrix_plan()),(b'OGCGRM01',96,4,BASE,STEP))
        build,session,profile=Path('/source/build'),Path('/source/session'),Path('/source/profile.bin')
        for index,stage in enumerate(PHASES):
            self.assertEqual(stage,dict(index=index,case=CASES[index//4],
                mode=('read','healthy','write','healthy')[index%4],base=BASE+index*STEP))
            command=source_launch(index,build,session,profile)
            check_source_launch(command,index,build,session,profile)
            for slot in range(len(command)):
                changed=list(command);changed[slot]='substituted'
                with self.assertRaises(ValueError):check_source_launch(changed,index,build,session,profile)
            _,_,d=partial(stage['case'])
            start=(0x20000+parameters(stage['case'])['target_offset'])&~63
            self.assertEqual(struct.unpack('<8sQQI',fault_plan(d,'read')),(b'OGCGF001',start,start+64,1))
        for value in (-1,True,96):
            with self.assertRaises(ValueError):phase(value)

    def test_continuous_history_and_exact_global_write_attempts(self):
        previous=None
        for index in range(96):
            row=done(index)
            self.assertIs(check_round(row,index,held(index),previous),row)
            previous=row
        self.assertEqual(tuple(previous[key] for key in COUNTERS),(96,96,24,24))
        mutations=dict(schema='storage',phase=1,mode='write',commandId=43,resetCount=2,
            ramReplaced=True,externalDrained=False,sourceQualificationRequired=False,
            independentBytesRequired=False,endCycle=0,physicalReadFaults=0,
            injectedReadFaults=0,physicalWriteFaults=1,injectedWriteFaults=1)
        for key,value in mutations.items():
            row=done(0);row[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):check_round(row,0,held(0))
        for attempts in (0,1,5,6,8,True):
            h=held(2);h['faultTargetWriteAttempts']=attempts
            with self.assertRaises(ValueError):fault_receipt(h,2,done(1))
        h=held(0);h['faultTargetWriteAttempts']=1
        with self.assertRaises(ValueError):fault_receipt(h,0)
        for changes in ({'physicalReadFaults':3},{'injectedWriteFaults':1}):
            with self.assertRaises(ValueError):fault_receipt({**held(0),**changes},0)
        row=done(1);row['startCycle']=0
        with self.assertRaises(ValueError):check_round(row,1,held(1),done(0))
        row=done(0);row['foreign']=True
        with self.assertRaises(ValueError):check_round(row,0,held(0))


class GlobalContinuousOwnerTests(unittest.TestCase):
    def test_one_live_producer_and_dut_retain_all_ninety_six_original_phases(self):
        self.peer(False)

    def test_bad_partial_bytes_never_authorize_fault_cleanup_or_next_phase(self):
        self.peer(True)

    def peer(self, bad_write):
        from compute_global_recovery_controller import run_recovery_matrix
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
            index=int(path.name[6:]);_,_,description=partial(case)
            description['kernarg']['allocationPhysical']=PHASES[index]['base']+4096
            return description,{}
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
        with tempfile.TemporaryDirectory(prefix='global-recovery-peer-') as temporary:
            directory=Path(temporary)
            patches=(
                patch('compute_global_recovery_controller.subprocess.Popen',side_effect=start),
                patch('compute_global_recovery_controller.strict_json',side_effect=record),
                patch('compute_global_recovery_controller.SparseMemory',MemoryPeer),
                patch('compute_global_recovery_controller.captured_page_tables',return_value='actual-cpu-peer-tables'),
                patch('compute_global_recovery_controller.union_original_memory',return_value=b'old RAM plus disjoint originals'),
                patch('compute_global_recovery_controller.verify'),
                patch('compute_global_recovery_controller.verify_fault',side_effect=fault_bytes),
                patch('compute_global_recovery_controller.verify_cleaned_memory'),
                patch('compute_global_recovery_controller.held_bytes',return_value=b'held'),
                patch('compute_global_recovery_controller.failed_held_bytes',return_value=b'failed-held'),
                patch('compute_global_recovery_controller.retired_bytes',return_value=b'retired'),
                patch('compute_global_recovery_controller.failed_retired_bytes',return_value=b'failed-retired'),
                patch('compute_global_recovery_controller.checked_cleanup'),
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
        self.assertEqual(commands[1][1],'--active-global-recovery-matrix')
        cleanups=[index for index,name in events if name=='BEGIN_CLEANUP']
        if bad_write:
            self.assertEqual(cleanups,[0,1])
            self.assertNotIn((2,'record-0.retired.response'),events)
            self.assertNotIn((3,'BEGIN_EXECUTION'),events)
            self.assertTrue(all(p.terminated for p in processes))
            qualified.assert_not_called()
        else:
            self.assertEqual(cleanups,list(range(96)))
            self.assertTrue(all(p.returncode==0 and not p.terminated for p in processes))
            self.assertEqual(sum(name=='fault-bytes-read' for _,name in events),24)
            self.assertEqual(sum(name=='fault-bytes-write' for _,name in events),24)
            qualified.assert_called_once()


if __name__=='__main__':
    unittest.main()
