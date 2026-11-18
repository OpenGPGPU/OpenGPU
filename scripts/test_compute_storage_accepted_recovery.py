#!/usr/bin/env python3
"""CPU controls for original SSBO committed-error histories, not GPU evidence."""
from pathlib import Path
from itertools import permutations
import struct
import unittest

from compute_atomic_order import result
from compute_storage_atomic_cases import parameters, invocations
import compute_storage_accepted_recovery as contract
from compute_storage_recovery import verify_fault as suppressed_fault
from test_compute_storage_recovery import partial, changed, Memory
import test_compute_global_recovery as recovery_tests


def accepted(case,group=0,failed=None,schedule=None):
    original,_,description=partial(case,group)
    before=Memory({**original.data,0xe000:b'previous cleaned RAM'*128})
    active=list(range(4*group,4*group+4))
    failed=(4 if group==0 else 0) if failed is None else failed
    calls=invocations(case);p=parameters(case);first=calls[failed]
    value=result(first['operation'],p['seed'],first['operand'],first['compare'])
    target=bytearray(before.data[0x1000]);output=bytearray(before.data[0x5000])
    for n in active if schedule is None else schedule:
        struct.pack_into('<I',output,4*n,value)
        call=calls[n];value=result(call['operation'],value,call['operand'],call['compare'])
    struct.pack_into('<I',target,p['target_offset'],value)
    return before,Memory({**before.data,0x1000:bytes(target),0x5000:bytes(output)}),description


def held(index):
    count=(index+2)//2
    return dict(commandId=42,physicalReadFaults=0,injectedReadFaults=0,
        physicalWriteFaults=count,injectedWriteFaults=count,acceptedWriteFaults=count,
        faultTargetWriteAttempts=5 if index%2==0 else 0)


def done(index):
    h=held(index)
    return dict(schema=contract.ROUND_SCHEMA,phase=index,mode=contract.phase(index)['mode'],commandId=42,
        resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,
        independentBytesRequired=True,startCycle=1000*index,endCycle=1000*index+100,
        **{key:h[key] for key in contract.COUNTERS})


class StorageAcceptedHistories(unittest.TestCase):
    def test_all_sources_failed_invocations_and_every_successful_group_permutation(self):
        checked=0
        for case in contract.SOURCES:
            for group in (0,1):
                active=list(range(4*group,4*group+4))
                for failed in range(4*(1-group),4*(1-group)+4):
                    for order in permutations(active):
                        before,after,d=accepted(case,group,failed,order)
                        self.assertEqual(contract.verify_fault(before,after,d,'accepted-write',target_attempts=5),4)
                        checked+=1
        self.assertEqual(checked,2880)

    def test_literal_first_committed_write_and_all_other_physical_bytes(self):
        literals={
            'exchange':([7]*4,7),
            'add':([0xfffffff5,0xfffffffa,0xffffffff,4],9),
            'xor':([0x8000000d,10,0x8000000d,10],0x8000000d),
            'or':([0x80000007]*4,0x80000007),
            'and':([0x80000007]*4,0x80000007),
            'signed-min':([0xfffffff9]*4,0xfffffff9),
            'signed-max':([7]*4,7),
            'unsigned-min':([7]*4,7),
            'unsigned-max':([0x80000007]*4,0x80000007),
            'compare-match':([7]*4,7),
            'compare-miss':([10]*4,10),
            'exchange-lane':([0x80000004,0x80000000,0x80000001,0x80000002],0x80000003),
            'add-lane':([0xfffffff5,0xfffffff6,0xfffffff8,0xfffffffb],0xffffffff),
            'compare-lane':([5]*4,5),
        }
        for case in contract.SOURCES:
            before,after,d=accepted(case)
            old,final=literals[parameters(case)['operation']]
            self.assertEqual(struct.unpack('<8I',after.read(0x5000,32)),(*old,*(0xa7a7a7a7,)*4))
            self.assertEqual(struct.unpack('<I',after.read(0x1000+parameters(case)['target_offset'],4))[0],final)
            for base,offset in ((0x1000,parameters(case)['target_offset']),
                    (0x1000,4 if not parameters(case)['target_offset'] else 0),(0x5000,32),(0x9000,4095),(0xe000,1)):
                with self.subTest(case=case,base=base,offset=offset),self.assertRaises(ValueError):
                    contract.verify_fault(before,changed(after,base,offset),d,'accepted-write',target_attempts=5)
            for slot in range(8):
                value=0xa7a7a7a7 if slot<4 else 0
                with self.assertRaises(ValueError):
                    contract.verify_fault(before,changed(after,0x5000,4*slot,value),d,'accepted-write',target_attempts=5)
            for attempts in (0,1,3,4,6,8,True):
                with self.assertRaises(ValueError):
                    contract.verify_fault(before,after,d,'accepted-write',target_attempts=attempts)

    def test_rejects_rollback_when_data_changes_and_wrong_failed_group_identity(self):
        for case in contract.SOURCES:
            for group in (0,1):
                before,after,d=accepted(case,group)
                _,rollback,_=partial(case,group)
                rollback=Memory({**rollback.data,0xe000:before.data[0xe000]})
                if parameters(case)['operation']=='compare-miss':
                    # A missed CAS writes the existing value. Its physical
                    # acceptance is distinguished by exact native counters.
                    self.assertEqual(contract.verify_fault(before,rollback,d,'accepted-write',target_attempts=5),4)
                else:
                    with self.assertRaises(ValueError):
                        contract.verify_fault(before,rollback,d,'accepted-write',target_attempts=5)
                    with self.assertRaises(ValueError):suppressed_fault(before,after,d,'write')
        for operation in ('exchange-lane','add-lane','compare-lane'):
            case='storage-atomic-'+operation
            for group in (0,1):
                for wrong in range(4*group,4*group+4):
                    before,after,d=accepted(case,group,failed=wrong)
                    with self.assertRaises(ValueError):
                        contract.verify_fault(before,after,d,'accepted-write',target_attempts=5)
        before,after,d=accepted('storage-atomic-exchange-lane')
        output=bytearray(after.data[0x5000]);a,b=struct.unpack('<2I',output[:8])
        struct.pack_into('<2I',output,0,b,a)
        with self.assertRaises(ValueError):
            contract.verify_fault(before,Memory({**after.data,0x5000:bytes(output)}),d,'accepted-write',target_attempts=5)

    def test_exact_original_matrix_native_arguments_and_cumulative_history(self):
        self.assertEqual(struct.unpack('<8sIIQQ',contract.matrix_plan()),(b'OGCSAM01',30,2,contract.BASE,contract.STEP))
        build,session,profile=Path('/build'),Path('/session'),Path('/profile.bin');previous=None
        for index,stage in enumerate(contract.PHASES):
            self.assertEqual(stage,dict(index=index,case=contract.SOURCES[index//2],
                mode='accepted-write' if index%2==0 else 'healthy',base=contract.BASE+index*contract.STEP))
            command=contract.source_launch(index,build,session,profile)
            contract.check_source_launch(command,index,build,session,profile)
            self.assertEqual('--expect-storage-fault' in command,index%2==0)
            for slot in range(len(command)):
                bad=list(command);bad[slot]='substituted'
                with self.assertRaises(ValueError):contract.check_source_launch(bad,index,build,session,profile)
            row=done(index);self.assertIs(contract.check_round(row,index,held(index),previous),row);previous=row
        self.assertEqual(contract.history(previous),(0,15,15))
        _,_,d=accepted('storage-atomic-endpoint')
        self.assertEqual(struct.unpack('<8sQQI',contract.fault_plan(d,'accepted-write')),
            (b'OGCSF002',0x2fc0,0x3000,3))
        for mode in ('read','write','healthy'):
            with self.assertRaises(ValueError):contract.fault_plan(d,mode)
        for value in (-1,True,30):
            with self.assertRaises(ValueError):contract.phase(value)
        for change in ({'acceptedWriteFaults':0},{'acceptedWriteFaults':True},{'physicalReadFaults':1},
                {'injectedWriteFaults':0},{'faultTargetWriteAttempts':3},{'faultTargetWriteAttempts':7}):
            with self.assertRaises(ValueError):contract.fault_receipt({**held(0),**change},0)
        for change in ({'schema':'global'},{'resetCount':2},{'ramReplaced':True},{'commandId':43},
                {'phase':2},{'acceptedWriteFaults':0},{'foreign':True}):
            with self.assertRaises(ValueError):contract.check_round({**done(0),**change},0,held(0))


class StorageAcceptedContinuous(unittest.TestCase):
    def test_all_thirty_original_live_phases_share_one_producer_and_dut(self):
        recovery_tests.GlobalContinuousOwnerTests.peer(self,False,protocol=contract,
            records=(held,done),expected_words=180,source_fixture=partial)

    def test_bad_committed_history_never_authorizes_cleanup_or_next_owner(self):
        recovery_tests.GlobalContinuousOwnerTests.peer(self,True,protocol=contract,
            records=(held,done),expected_words=180,source_fixture=partial)


if __name__=='__main__':unittest.main()
