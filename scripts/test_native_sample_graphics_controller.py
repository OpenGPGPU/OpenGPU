#!/usr/bin/env python3
"""CPU-only controller phase/failure tests; mocked oracles are no DUT proof."""
from contextlib import ExitStack
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native_sample_graphics_controller as controller
from test_native_sample_owner import profile

ROOT = Path(__file__).resolve().parents[1]


def receipt(record, retired=False):
    command = 41+record
    value = dict(schema='opengpu-native-sample-graphics-held-v1', phase='held',
        ownerProfile=controller.SAMPLE_PROFILE, record=record, recordCount=2, resetCount=1,
        sourceQualificationRequired=True, heldCompletionDrained=True, readOnlyGuards=True,
        heldIrqAsserted=True, clockQualified=False, fpsQualified=False,
        commandId=command, completion=0x8700|command, appendReadBytes=64,
        heldLegacyStatus=0, heldStatus=2, heldIrq=3,
        gnx=[0x474e5801,0,0x12345678,0x89abcdef,0,0,7,11,13,0,0,0])
    if retired:
        value.update(schema='opengpu-native-sample-graphics-retired-v1', phase='retired',
            externalDrained=True, retiredIrqAsserted=False, resets=1,
            resetsBetweenRecords=False, independentImagesRequired=True,
            retiredStatus=1, retiredIrq=1)
    return value


class ControllerTests(unittest.TestCase):
    def setUp(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        temporary=tempfile.TemporaryDirectory(prefix='ordinary112-controller-',dir=parent)
        self.addCleanup(temporary.cleanup);self.directory=Path(temporary.name)
        self.profile=profile()
        self.write('ready.json',controller.READY)
        self.write('profile.json',self.profile)
        (self.directory/'profile.bin').write_bytes(controller.profile_bytes(self.profile))
        for record in range(2):self.write('record-%d.source-plan.json'%record,dict(record=record,context=7))
        self.write('compiler-reflection.json',dict(cpuOnly=True))
        for name in ('union.memory','native-real-vs.words','native-real-fs.words','record-0.capture'):
            (self.directory/name).write_bytes(name.encode())
        self.events=[]

    def write(self,name,value):
        (self.directory/name).write_text(json.dumps(value))

    def exercise(self, failure=None):
        test=self;directory=self.directory
        class Process:
            def poll(self):
                if 'result0' in test.events and ((failure=='early-owner-exit' and self is bridge) or
                        (failure=='early-executor-exit' and self is running)):
                    return 0
                return None
            def wait(self,timeout):
                test.assertTrue((directory/('response.bin' if self is bridge else 'cleanup.authorize.bin')).is_file())
                test.events.append('producer-exit' if self is bridge else 'executor-exit')
                return 1 if failure=='executor-exit' and self is running else 0
        bridge,running=Process(),Process()
        def memory(path):
            from contextlib import nullcontext
            return nullcontext(Path(path))
        def preflight(*args):
            self.events.append('preflight')
            if failure=='preflight':raise ValueError('preflight rejected')
            return 41
        def start(initial,results):
            self.assertEqual(self.events,['preflight'])
            self.events.append('start');results.mkdir()
            return running
        def wait(path,*args):
            name=path.name;self.events.append('wait:'+name)
            if name=='record-1.capture':
                self.assertTrue((directory/'record-0.response.bin').is_file())
                path.write_bytes(b'capture1');return
            if name.endswith('.held.json'):
                record=int(name[7]);value=receipt(record)
                if record:
                    self.assertEqual((directory/'record-1.admit').read_bytes(),controller.admission_bytes(b'capture1'))
                if failure=='foreign-held' and not record:value['gnx'][6]=8
                if failure=='swapped-held' and not record:value=receipt(1)
                self.write(name,value);return
            if name.endswith('.retired.json'):
                record=int(name[7]);self.assertTrue((directory/('record-%d.held.bin'%record)).is_file())
                value=receipt(record,True)
                if failure=='foreign-retired' and not record:value['gnx'][7]=12
                self.write(name,value);return
            if name.endswith('.memory'):
                path.write_bytes(name.encode());return
            if name=='cleanup.json':
                self.assertIn('pair',self.events)
                self.assertTrue((directory/'cleanup.authorize.bin').is_file())
                self.write(name,dict(phase='cleanup',ownerCleanupObserved=True,contextCleanupObserved=True,
                    records=1 if failure=='cleanup' else 2,resetCount=1,ramReplaced=False,
                    imagesPreserved=True,externalDrained=True,sourceQualificationRequired=True,
                    clockQualified=False,fpsQualified=False,batches=1,pteClears=1,shootdowns=1));return
            self.fail('unexpected wait '+name)
        def verify_result(*args):
            record=args[7];self.events.append('result%d'%record)
            self.assertFalse((directory/('record-%d.response.bin'%record)).exists())
            self.assertFalse((directory/'cleanup.authorize.bin').exists())
            if failure=='result%d'%record:raise ValueError('result rejected')
            if failure=='source-mutation' and not record:(directory/'native-real-vs.words').write_bytes(b'changed')
            if failure=='first-result-mutation' and record:(directory/'results/record-0.memory').write_bytes(b'changed')
        def pair(*args):
            self.events.append('pair')
            self.assertIn('result0',self.events);self.assertIn('result1',self.events)
            self.assertFalse((directory/'cleanup.authorize.bin').exists())
            self.assertEqual(args[-1],[b'record-0.capture',b'capture1'])
            if failure=='pair':raise ValueError('pair rejected')
            return (41,42)
        def qualified():
            self.events.append('qualified')
            self.assertIn('executor-exit',self.events)
            self.assertFalse((directory/'response.bin').exists())
            if failure=='qualification':raise ValueError('qualification changed')
            if failure=='late-result-mutation':(directory/'results/record-0.memory').write_bytes(b'changed')
        with ExitStack() as stack:
            for name,value in dict(SparseMemory=memory,preflight=preflight,capture_identity=lambda *args:42,
                    wait_file=wait,verify_result=verify_result,verify_pair=pair).items():
                stack.enter_context(patch.object(controller,name,value))
            return controller.exchange(directory,self.profile,bridge,start,qualified,1)

    def test_ordered_admission_results_pair_cleanup_and_final(self):
        self.assertEqual(self.exercise(),[41,42])
        self.assertEqual(self.events[-3:],['executor-exit','qualified','producer-exit'])
        self.assertTrue((self.directory/'response.bin').is_file())
        raw=(self.directory/'record-0.held.bin').read_bytes()
        self.assertIn(bytes.fromhex('78563412efcdab89'),raw) # raw reader counts survive

    def test_failure_before_oracle_cannot_authorize_execution_or_cleanup(self):
        for failure in ('preflight','foreign-held','swapped-held','foreign-retired','result0','source-mutation',
                'early-owner-exit','early-executor-exit'):
            with self.subTest(failure=failure):
                # Each scenario needs a fresh set of non-overwritable IPC inputs.
                other=ControllerTests();other.setUp()
                try:
                    with other.assertRaises(ValueError):other.exercise(failure)
                    self.assertFalse((other.directory/'record-0.response.bin').exists())
                    self.assertFalse((other.directory/'cleanup.authorize.bin').exists())
                    self.assertFalse((other.directory/'response.bin').exists())
                finally:other.doCleanups()

    def test_second_result_and_pair_failures_block_cleanup(self):
        for failure in ('result1','first-result-mutation','pair'):
            with self.subTest(failure=failure):
                other=ControllerTests();other.setUp()
                try:
                    with other.assertRaises(ValueError):other.exercise(failure)
                    self.assertTrue((other.directory/'record-0.response.bin').is_file())
                    self.assertFalse((other.directory/'cleanup.authorize.bin').exists())
                    self.assertFalse((other.directory/'response.bin').exists())
                finally:other.doCleanups()

    def test_cleanup_executor_and_qualification_failures_block_final(self):
        for failure in ('cleanup','executor-exit','qualification','late-result-mutation'):
            with self.subTest(failure=failure):
                other=ControllerTests();other.setUp()
                try:
                    with other.assertRaises(ValueError):other.exercise(failure)
                    self.assertTrue((other.directory/'cleanup.authorize.bin').is_file())
                    self.assertFalse((other.directory/'response.bin').exists())
                finally:other.doCleanups()

    def test_ready_rejects_premature_capture_aliased_inputs_and_changed_contract(self):
        controller.ready_files(self.directory)
        second=self.directory/'record-1.capture';second.write_bytes(b'early')
        with self.assertRaisesRegex(ValueError,'preceded'):controller.ready_files(self.directory)
        second.unlink()
        target=self.directory/'native-real-fs.words';target.unlink()
        target.symlink_to('native-real-vs.words')
        with self.assertRaisesRegex(ValueError,'aliased'):controller.ready_files(self.directory)
        target.unlink();target.write_bytes(b'fs')
        value=copy.deepcopy(controller.READY);value['recordCount']=True;self.write('ready.json',value)
        with self.assertRaisesRegex(ValueError,'READY'):controller.ready_files(self.directory)

    def test_original_probe_and_receipt_identity_are_strict(self):
        for record in range(2):
            good=receipt(record)
            controller.bind_receipt(good,41+record,dict(context=7),record)
            for field,value in (('commandId',True),('commandId',42-record),
                    ('record',bool(record)),('record',1-record)):
                bad=copy.deepcopy(good);bad[field]=value
                with self.assertRaises(ValueError):controller.bind_receipt(bad,41+record,dict(context=7),record)
            for index in (6,7,8):
                bad=copy.deepcopy(good);bad['gnx'][index]^=1
                with self.assertRaises(ValueError):controller.bind_receipt(bad,41+record,dict(context=7),record)
        (self.directory/'profile.bin').write_bytes(b'old profile')
        with self.assertRaisesRegex(ValueError,'profile files differ'):
            self.exercise()
        self.assertEqual(self.events,[])

    def test_only_one_exact_original_live_producer_success_marker_is_accepted(self):
        path=self.directory/'bridge.log'
        good=controller.PRODUCER_SUCCESS
        for value in ('', 'ACTUAL_SAMPLE_GRAPHICS_CPU_CAPTURE_PASS records=2 GPU=false\n',
                good+' extra\n',good+'\n'+good+'\n'):
            path.write_text(value)
            with self.assertRaisesRegex(ValueError,'LIVE producer'):
                controller.producer_success(self.directory)
        path.write_text('compiler diagnostic\n'+good+'\n')
        controller.producer_success(self.directory)
        original=self.directory/'other.log';path.rename(original);path.symlink_to(original.name)
        with self.assertRaisesRegex(ValueError,'aliased'):
            controller.producer_success(self.directory)

    def test_cleanup_preserves_inputs_on_unknown_exit_and_attempts_all_consumers(self):
        first,second=object(),object()
        with patch.object(controller,'stop_group',side_effect=[controller.CleanupIncomplete('active'),None]) as stop, \
                patch.object(controller,'remove_container') as container:
            with self.assertRaises(controller.CleanupIncomplete):
                controller.cleanup_inputs(self.directory,first,second,'owned')
            self.assertEqual(stop.call_args_list[0].args,(first,))
            self.assertEqual(stop.call_args_list[1].args,(second,))
            container.assert_called_once_with('owned')
        self.assertTrue((self.directory/'union.memory').is_file())
        with patch.object(controller,'stop_group') as stop:
            controller.cleanup_inputs(self.directory,first,second)
            self.assertEqual(stop.call_count,2)
        self.assertFalse(self.directory.exists())


if __name__=='__main__':unittest.main()
