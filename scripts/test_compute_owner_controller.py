#!/usr/bin/env python3
"""CPU-only active-owner liveness and response-file refusal boundaries."""
from pathlib import Path
from copy import deepcopy
import tempfile
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from compute_owner_controller import atomic, wait_file, lock_owner, recovery_commands, checked_recovery_round, exchange_reset_receipts
from compute_owner_protocol import RECOVERY_CASE, RECOVERY_BASES, RECOVERY_COMMAND_SCHEMA

ROOT = Path(__file__).resolve().parents[1]


class Process:
    def __init__(self, code):
        self.code = code

    def poll(self):
        return self.code


class Controller(unittest.TestCase):
    def test_reset_recovery_keeps_channel_hold_history_and_same_live_command(self):
        from test_compute_image_reset import observation
        for channel in ('read','write'):
            commands=[['producer','{ipc}','--case',RECOVERY_CASE,'--physical-base',str(base)]+
                (['--expect-image-reset',channel] if i==0 else []) for i,base in enumerate(RECOVERY_BASES)]
            value=dict(schema=RECOVERY_COMMAND_SCHEMA,rounds=commands)
            self.assertEqual(recovery_commands(value,reset=channel),commands)
            with self.assertRaises(ValueError):recovery_commands(value)
            for flag in ('--expect-image-source-fault','--capture-only','--expect-image-permission-fault','--expect-image-reset'):
                for index in range(2):
                    wrong=deepcopy(value);wrong['rounds'][index].append(flag)
                    with self.subTest(flag=flag,index=index),self.assertRaises(ValueError):recovery_commands(wrong,reset=channel)
            held=observation('retired',channel)
            first=dict(schema='opengpu-compute-image-reset-recovery-round-v1',round=0,commandId=4,
                resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,
                independentBytesRequired=True,physicalReadFaults=0,injectedReadFaults=0,
                physicalWriteFaults=0,startCycle=0,endCycle=200,channel=channel,unifiedResetRequests=1,
                heldReadCycles=held['heldReadCycles'],heldWriteCycles=held['heldWriteCycles'])
            checked_recovery_round(first,0,held,reset=channel)
            second=dict(first,round=1,startCycle=200,endCycle=400)
            checked_recovery_round(second,1,dict(commandId=4),first,reset=channel)
            for key,new in (('commandId',5),('resetCount',2),('unifiedResetRequests',0),('channel','other'),
                    ('heldReadCycles',6),('heldWriteCycles',6),('physicalReadFaults',1),
                    ('injectedReadFaults',1),('physicalWriteFaults',1),('ramReplaced',True),('startCycle',199)):
                wrong=dict(second);wrong[key]=new
                with self.subTest(key=key),self.assertRaises(ValueError):
                    checked_recovery_round(wrong,1,dict(commandId=4),first,reset=channel)

    def test_reset_ack_requires_independent_cancel_bytes_before_original_irq_and_cleanup(self):
        from test_compute_image_reset import observation
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        for fail in (False,True):
            with tempfile.TemporaryDirectory(prefix='reset-controller-',dir=parent) as temporary:
                local=Path(temporary)
                for phase in ('held','busy','ack','retired'):
                    (local/('record-0.reset.'+phase+'.json')).write_text(json.dumps(observation(phase,'write')))
                with patch('compute_owner_controller.SparseMemory'),\
                     patch('compute_image_reset.verify_cancelled_memory',side_effect=ValueError('bad partial bytes') if fail else None,
                           return_value=dict(changedSourceWords=1)) as verify:
                    invoke=lambda:exchange_reset_receipts(SimpleNamespace(timeout=1),local,Process(None),Process(None),
                        local/'results',local/'union.memory',{},[],'write')
                    if fail:
                        with self.assertRaisesRegex(ValueError,'bad partial bytes'):invoke()
                    else:self.assertEqual(invoke()['phase'],'retired')
                    verify.assert_called_once()
                self.assertTrue((local/'record-0.reset.held.response').exists())
                self.assertTrue((local/'record-0.reset.busy.response').exists())
                self.assertEqual((local/'record-0.reset.ack.response').exists(),not fail)
                self.assertEqual((local/'record-0.reset.retired.response').exists(),not fail)
                self.assertFalse((local/'BEGIN_CLEANUP').exists())

    def test_permission_recovery_binds_denied_access_and_zero_physical_errors(self):
        for permission in ('read','write'):
            commands=[['producer','{ipc}','--case',RECOVERY_CASE,'--physical-base',str(base)]+
                (['--expect-image-permission-fault',permission] if i==0 else []) for i,base in enumerate(RECOVERY_BASES)]
            value=dict(schema=RECOVERY_COMMAND_SCHEMA,rounds=commands)
            self.assertEqual(recovery_commands(value,permission=permission),commands)
            with self.assertRaises(ValueError):recovery_commands(value)
            other='write' if permission=='read' else 'read'
            with self.assertRaises(ValueError):recovery_commands(value,permission=other)
            for index in range(2):
                bad=deepcopy(value);bad['rounds'][index].append('--expect-image-source-fault')
                with self.assertRaises(ValueError):recovery_commands(bad,permission=permission)
            bad=deepcopy(value);bad['rounds'][1]+=commands[0][-2:]
            with self.assertRaises(ValueError):recovery_commands(bad,permission=permission)
            first=dict(schema='opengpu-compute-image-recovery-round-v1',round=0,commandId=4,
                resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,
                independentBytesRequired=True,physicalReadFaults=0,injectedReadFaults=0,
                physicalWriteFaults=0,startCycle=0,endCycle=100)
            checked_recovery_round(first,0,dict(commandId=4,physicalReadFaults=0,injectedReadFaults=0,
                physicalWriteFaults=0),permission=permission)
            second=dict(first,round=1,startCycle=100,endCycle=200)
            checked_recovery_round(second,1,dict(commandId=4),first,permission=permission)
            with self.assertRaises(ValueError):checked_recovery_round(second,1,dict(commandId=4),first)
            for field in ('physicalReadFaults','injectedReadFaults','physicalWriteFaults'):
                bad=dict(second);bad[field]=1
                with self.assertRaises(ValueError):checked_recovery_round(bad,1,dict(commandId=4),first,permission=permission)

    def test_recovery_requires_two_live_original_sources_and_disjoint_namespaces(self):
        commands=[['producer','{ipc}','--case',RECOVERY_CASE,'--physical-base',str(base)]+
                  (['--expect-image-source-fault'] if i==0 else []) for i,base in enumerate(RECOVERY_BASES)]
        value=dict(schema=RECOVERY_COMMAND_SCHEMA,rounds=commands)
        self.assertEqual(recovery_commands(value),commands)
        mutations=[]
        for index in range(2):
            bad=deepcopy(value);bad['rounds'][index].append('--capture-only');mutations.append(bad)
            bad=deepcopy(value);bad['rounds'][index][5]=str(RECOVERY_BASES[1-index]);mutations.append(bad)
            bad=deepcopy(value);bad['rounds'][index][3]='image-atomic-2d-r32f';mutations.append(bad)
        bad=deepcopy(value);bad['rounds'][0].remove('--expect-image-source-fault');mutations.append(bad)
        bad=deepcopy(value);bad['rounds'][1].append('--expect-image-source-fault');mutations.append(bad)
        bad=deepcopy(value);bad['rounds']=bad['rounds'][:1];mutations.append(bad)
        bad=deepcopy(value);bad['extra']=True;mutations.append(bad)
        for bad in mutations:
            with self.subTest(bad=bad),self.assertRaises(ValueError):recovery_commands(bad)

    def test_recovery_retains_fault_history_clock_and_same_command_identity(self):
        held=dict(commandId=17,physicalReadFaults=2,injectedReadFaults=2,physicalWriteFaults=0)
        first=dict(schema='opengpu-compute-image-recovery-round-v1',round=0,commandId=17,
            resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,
            independentBytesRequired=True,physicalReadFaults=2,injectedReadFaults=2,
            physicalWriteFaults=0,startCycle=100,endCycle=200)
        checked_recovery_round(first,0,held)
        second=dict(first,round=1,startCycle=200,endCycle=400)
        checked_recovery_round(second,1,dict(commandId=17),first)
        with self.assertRaises(ValueError):checked_recovery_round(second,1,dict(commandId=17))
        with self.assertRaises(ValueError):checked_recovery_round(first,0,held,first)
        for field,value in [('commandId',18),('resetCount',2),('ramReplaced',True),
                ('externalDrained',False),('physicalReadFaults',0),('injectedReadFaults',0),
                ('physicalWriteFaults',1),('startCycle',199),('endCycle',200),
                ('sourceQualificationRequired',False),('round',True)]:
            bad=dict(second);bad[field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):checked_recovery_round(bad,1,dict(commandId=17),first)
        bad=dict(second,physicalReadFaults=3,injectedReadFaults=3)
        with self.assertRaises(ValueError):checked_recovery_round(bad,1,dict(commandId=17),first)

    def test_ready_does_not_override_a_dead_owner_and_responses_do_not_overwrite(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-controller-', dir=parent) as temporary:
            root = Path(temporary)
            ready = root / 'READY'; ready.write_bytes(b'CPU capture only')
            with self.assertRaisesRegex(ValueError, 'producer exited'):
                wait_file(ready, Process(0), None, 1)
            missing = root / 'held.json'
            with self.assertRaisesRegex(ValueError, 'executor exited'):
                wait_file(missing, Process(None), Process(0), 1)
            ready.unlink(); ready.symlink_to(missing.name)
            missing.write_bytes(b'held')
            with self.assertRaisesRegex(ValueError, 'invalid IPC'):
                wait_file(ready, Process(None), Process(None), 1)
            response = root / 'BEGIN_CLEANUP'
            atomic(response, b'first authorized response')
            with self.assertRaisesRegex(ValueError, 'pre-existing response'):
                atomic(response, b'changed response')
            self.assertEqual(response.read_bytes(), b'first authorized response')

    def test_uses_the_product_build_lock_until_the_session_finishes(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-owner-lock-', dir=parent) as temporary:
            owner = Path(temporary)
            path = owner / 'executors/compute/qualification.json'
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(dict(schema='opengpu-owner-executor-v1', kind='compute', owner_output=str(owner))))
            first = lock_owner(path)
            try:
                with self.assertRaises(BlockingIOError):
                    lock_owner(path)
                self.assertTrue((owner / '.lock').is_file())
            finally:
                first.close()
            with lock_owner(path):
                pass


if __name__ == '__main__':
    unittest.main()
