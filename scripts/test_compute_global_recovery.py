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
    fault_receipt, check_round, surviving_groups, verify_fault)
from test_compute_global_atomic_source import fixture, HISTORIES
from test_compute_atomic_order import Memory


def partial(case, failed=1, schedule=None, admitted=3):
    before,_,description=fixture(case)
    before=Memory({**before.data,0x80000:bytearray(b'previous cleaned RAM'*128)})
    after=deepcopy(before)
    calls=events(case)
    groups=[n for n in range(admitted) if n!=failed]
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
                faultTargetWriteAttempts=5 if phase(index)['mode']=='write' else 0)


def done(index):
    h=held(index)
    return dict(schema=ROUND_SCHEMA,phase=index,mode=phase(index)['mode'],commandId=42,
        resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,
        independentBytesRequired=True,startCycle=1000*index,endCycle=1000*index+100,
        **{key:h[key] for key in COUNTERS})


class GlobalRecovery(unittest.TestCase):
    def test_raw_capture_has_exactly_zero_inactive_contexts_without_weakening_image_layout(self):
        from compute_image_source import captured_page_tables
        pages={a:bytearray(4096) for a in (0x1000,0x2000,0x3000,0x4000,0x5000)}
        struct.pack_into('<I',pages[0x1000],0,7)
        struct.pack_into('<I',pages[0x2000],0,(3<<10)|1)
        struct.pack_into('<I',pages[0x3000],16,(4<<10)|0xc7)
        memory=Memory(pages)
        actions=[(1,address,satp,0) for satp in (0x80000001,0x80400002) for address in (0x14c,0x150)]
        def capture(inactive):
            return (b'OGCMP003'+struct.pack('<QIII',100000,8,17,122)+bytes(122*8)+
                struct.pack('<II',0,len(inactive))+b''.join(struct.pack('<II',*c) for c in inactive)+
                struct.pack('<I',len(actions))+b''.join(struct.pack('<IIII',*a) for a in actions))
        raw=capture([])
        image=capture([(2,0x80800005)])
        self.assertEqual(captured_page_tables(memory,raw,inactive_contexts=0),({0x1000,0x2000,0x3000},{0x3010}))
        self.assertEqual(captured_page_tables(memory,image),({0x1000,0x2000,0x3000},{0x3010}))
        with self.assertRaises(ValueError):captured_page_tables(memory,raw)
        with self.assertRaises(ValueError):captured_page_tables(memory,image,inactive_contexts=0)
        for count in (True,-1,2):
            with self.assertRaises(ValueError):captured_page_tables(memory,raw,inactive_contexts=count)

    def test_all_sources_failed_callers_legal_interleavings_and_whole_ram(self):
        for case in CASES:
            for admitted,failed in ((a,f) for a in (2,3,4) for f in (0,1)):
                groups=[n for n in range(admitted) if n!=failed]
                attempts=1+2*len(groups)
                schedules=([event for group in groups for event in (2*group,2*group+1)],
                           [event for group in reversed(groups) for event in (2*group,2*group+1)],
                           [2*group for group in groups]+[2*group+1 for group in reversed(groups)])
                for schedule in schedules:
                    before,after,d=partial(case,failed,schedule,admitted)
                    self.assertEqual(verify_fault(before,after,d,'write',target_attempts=attempts),2*len(groups))
                    self.assertEqual(verify_fault(before,before,d,'read',target_attempts=0),0)
                    with self.assertRaises(ValueError):verify_fault(before,after,d,'read',target_attempts=0)
                    for wrong in (0,1,2,3,4,5,6,7,8,True):
                        if type(wrong) is int and wrong==attempts:continue
                        with self.assertRaises(ValueError):
                            verify_fault(before,after,d,'write',target_attempts=wrong)
                    for address,offset in ((0x20000,parameters(case)['target_offset']),
                            (0x10000,0),(0x30000,4095),(0x40000,0),(0x80000,1),
                            (0x20000,127),(0x20000,160)):
                        bad=deepcopy(after);bad.data[address][offset]^=1
                        with self.subTest(case=case,failed=failed,address=address,offset=offset),self.assertRaises(ValueError):
                            verify_fault(before,bad,d,'write',target_attempts=attempts)
                    for index in range(8):
                        bad=deepcopy(after)
                        struct.pack_into('<I',bad.data[0x20000],128+4*index,
                            0 if index//2 not in groups else OUTPUT_GUARD)
                        with self.assertRaises(ValueError):verify_fault(before,bad,d,'write',target_attempts=attempts)

    def test_rejects_nonprefix_survivors_and_a_later_first_failed_caller(self):
        for active in ([],[2],[3],[0,1],[0,3],[1,3],[2,3],[0,1,2],[0,1,3],[0,1,2,3]):
            words=[0 if group in active else OUTPUT_GUARD for group in range(4) for _ in range(2)]
            with self.subTest(active=active),self.assertRaises(ValueError):surviving_groups(words)

    def test_partial_serial_fixture_matches_existing_independent_literal_histories(self):
        for case in CASES:
            _,after,_=partial(case,admitted=2)
            words,final=HISTORIES[parameters(case)['pair']]
            self.assertEqual(struct.unpack('<8I',after.read(0x20080,32)),(*words[:2],*(OUTPUT_GUARD,)*6))
            self.assertEqual(struct.unpack('<I',after.read(0x20000+parameters(case)['target_offset'],4))[0],words[2])

    def test_unordered_successful_calls_do_not_replace_original_caller_order(self):
        case=CASES[0]
        before,after,d=partial(case,1,[1,0,4,5])
        active=(0,1,4,5)
        observed=[struct.unpack('<I',after.read(0x20080+4*n,4))[0] for n in active]
        final=struct.unpack('<I',after.read(0x20000,4))[0]
        linearization(parameters(case)['seed'],final,[events(case)[n] for n in active],observed)
        with self.assertRaises(ValueError):verify_fault(before,after,d,'write',target_attempts=5)

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
        for attempts in (0,1,2,4,6,8,True):
            h=held(2);h['faultTargetWriteAttempts']=attempts
            with self.assertRaises(ValueError):fault_receipt(h,2,done(1))
        for attempts in (3,5,7):
            self.assertEqual(fault_receipt({**held(2),'faultTargetWriteAttempts':attempts},2,done(1)),(4,1))
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

    def peer(self, bad_write, *, protocol=None, records=None, expected_words=480, source_fixture=None):
        from compute_global_recovery_controller import run_recovery_matrix
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        phases=PHASES if protocol is None else protocol.PHASES
        make_held,make_done=(held,done) if records is None else records
        source_fixture=partial if source_fixture is None else source_fixture
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
            return make_done(index) if path.name=='executor.done' else make_held(index)
        def ready(path,case,**_kwargs):
            index=int(path.name[6:]);_,_,description=source_fixture(case)
            view=(description['images'][0] if 'images' in description else
                description['kernarg' if 'kernarg' in description else 'target'])
            view['allocationPhysical']=phases[index]['base']+4096
            return description,{}
        class MemoryPeer:
            def __init__(self,path):
                n=int(path.parent.name[6:]) if path.parent.name.startswith('round-') else 0
                base=phases[n]['base'];self.ranges=[(base,base+65536,0)]
            def __enter__(self):return self
            def __exit__(self,*_args):pass
        def fault_bytes(_before,_after,_description,mode,*,target_attempts):
            events.append((-1,'fault-bytes-'+mode))
            if bad_write and mode!='read':raise ValueError('deliberately invalid partial old word')
            expected=(protocol.target_attempts(_description['sourceCase'])
                if protocol is not None and hasattr(protocol,'target_attempts') else 5)
            self.assertEqual(target_attempts,0 if mode=='read' else expected)
            return 0 if mode=='read' else expected-1
        qualified=Mock();tables=Mock(return_value='actual-cpu-peer-tables')
        with tempfile.TemporaryDirectory(prefix='global-recovery-peer-') as temporary:
            directory=Path(temporary)
            patches=(
                patch('compute_global_recovery_controller.subprocess.Popen',side_effect=start),
                patch('compute_global_recovery_controller.strict_json',side_effect=record),
                patch('compute_global_recovery_controller.SparseMemory',MemoryPeer),
                patch('compute_global_recovery_controller.captured_page_tables',new=tables),
                patch('compute_global_recovery_controller.union_original_memory',return_value=b'old RAM plus disjoint originals'),
                patch('compute_global_recovery_controller.verify'),
                (patch('compute_global_recovery_controller.verify_fault',side_effect=fault_bytes) if protocol is None
                    else patch.object(protocol,'verify_fault',side_effect=fault_bytes)),
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
            stdout=io.StringIO()
            with ExitStack() as stack,redirect_stdout(stdout):
                for active in patches:stack.enter_context(active)
                if bad_write:
                    with self.assertRaisesRegex(ValueError,'invalid partial old word'):
                        run_recovery_matrix(argparse.Namespace(timeout=60),['original-producer','{ipc}'],
                            directory,Path('native-executor'),{},qualified,IMAGE_FLOAT_PROFILE,protocol=protocol)
                else:
                    run_recovery_matrix(argparse.Namespace(timeout=60),['original-producer','{ipc}'],
                        directory,Path('native-executor'),{},qualified,IMAGE_FLOAT_PROFILE,protocol=protocol)
        self.assertEqual(len(processes),2)
        contexts=0 if protocol is None else protocol.INACTIVE_CONTEXTS
        self.assertTrue(all(call.kwargs=={'inactive_contexts':contexts} for call in tables.call_args_list))
        self.assertEqual(commands[1][1],'--active-global-recovery-matrix' if protocol is None else protocol.RUNNER_FLAG)
        cleanups=[index for index,name in events if name=='BEGIN_CLEANUP']
        if bad_write:
            first_bad=next(s['index'] for s in phases if s['mode'] in ('write','accepted-write'))
            self.assertEqual(cleanups,list(range(first_bad)))
            self.assertNotIn((first_bad,'record-0.retired.response'),events)
            self.assertNotIn((first_bad+1,'BEGIN_EXECUTION'),events)
            self.assertTrue(all(p.terminated for p in processes))
            qualified.assert_not_called()
        else:
            self.assertIn('%d old words'%expected_words,stdout.getvalue())
            self.assertEqual(cleanups,list(range(len(phases))))
            self.assertTrue(all(p.returncode==0 and not p.terminated for p in processes))
            for mode in {s['mode'] for s in phases if s['mode']!='healthy'}:
                self.assertEqual(sum(name=='fault-bytes-'+mode for _,name in events),
                                 sum(s['mode']==mode for s in phases))
            qualified.assert_called_once()


if __name__=='__main__':
    unittest.main()
