#!/usr/bin/env python3
"""CPU controls for committed-error histories; these are not GPU evidence."""
from copy import deepcopy
from pathlib import Path
import struct
import unittest

from compute_atomic_order import result
from compute_global_atomic_cases import CASES, events, parameters, OUTPUT_GUARD
import compute_global_accepted_recovery as contract
from compute_global_recovery import verify_fault as suppressed_fault
from test_compute_global_recovery import partial
import test_compute_global_recovery as recovery_tests


def accepted(case,failed=1,admitted=3,schedule=None):
    before,_,description=partial(case,failed,admitted=admitted)
    after=deepcopy(before);calls=events(case)
    first=calls[2*failed]
    value=result(first['operation'],parameters(case)['seed'],first['operand'],first['compare'])
    active=[n for g in range(admitted) if g!=failed for n in (2*g,2*g+1)]
    for n in active if schedule is None else schedule:
        struct.pack_into('<I',after.data[0x20000],128+4*n,value)
        call=calls[n];value=result(call['operation'],value,call['operand'],call['compare'])
    struct.pack_into('<I',after.data[0x20000],parameters(case)['target_offset'],value)
    return before,after,description


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


class AcceptedHistories(unittest.TestCase):
    def test_all_original_sources_admitted_prefixes_and_ordered_interleavings(self):
        for case in CASES:
            for admitted in (2,3,4):
                for failed in (0,1):
                    groups=[g for g in range(admitted) if g!=failed]
                    schedules=([n for g in groups for n in (2*g,2*g+1)],
                        [n for g in reversed(groups) for n in (2*g,2*g+1)],
                        [2*g for g in groups]+[2*g+1 for g in reversed(groups)])
                    attempts=1+2*len(groups)
                    for schedule in schedules:
                        before,after,d=accepted(case,failed,admitted,schedule)
                        self.assertEqual(contract.verify_fault(before,after,d,'accepted-write',
                            target_attempts=attempts),2*len(groups))
                        with self.assertRaises(ValueError):
                            suppressed_fault(before,after,d,'write',target_attempts=attempts)
                        _,rolled_back,_=partial(case,failed,schedule,admitted)
                        with self.assertRaises(ValueError):
                            contract.verify_fault(before,rolled_back,d,'accepted-write',target_attempts=attempts)
                        for address,offset in ((0x10000,0),(0x20000,127),(0x20000,160),
                                (0x30000,4095),(0x40000,0),(0x80000,1),
                                (0x20000,parameters(case)['target_offset'])):
                            bad=deepcopy(after);bad.data[address][offset]^=1
                            with self.assertRaises(ValueError):
                                contract.verify_fault(before,bad,d,'accepted-write',target_attempts=attempts)
                        for wrong in (0,2,3,4,5,6,7,8,True):
                            if type(wrong) is int and wrong==attempts:continue
                            with self.assertRaises(ValueError):
                                contract.verify_fault(before,after,d,'accepted-write',target_attempts=wrong)

    def test_literal_committed_first_write_before_successful_caller(self):
        literals={
            'swap-add':((0x80000002,0x80000001),0),
            'xor-or':((0xfffff017,0x7ffff010),0x7ffff110),
            'and-min':((0x7ffffff2,0x7ffffff0),0x80000007),
            'max-cas':((8,8),8),
            'unsigned-min-max':((8,7),0x80000007),
            'cas-pair':((2,2),2),
        }
        for case in CASES:
            before,after,d=accepted(case,admitted=2)
            old,final=literals[parameters(case)['pair']]
            self.assertEqual(struct.unpack('<8I',after.read(0x20080,32)),(*old,*(OUTPUT_GUARD,)*6))
            self.assertEqual(struct.unpack('<I',after.read(0x20000+parameters(case)['target_offset'],4))[0],final)
            contract.verify_fault(before,after,d,'accepted-write',target_attempts=3)

    def test_preserves_original_source_order_and_pair_ownership(self):
        before,after,d=accepted(CASES[0],admitted=2,schedule=[1,0])
        with self.assertRaises(ValueError):contract.verify_fault(before,after,d,'accepted-write',target_attempts=3)
        for slot in range(8):
            before,after,d=accepted(CASES[0],admitted=2)
            struct.pack_into('<I',after.data[0x20000],128+4*slot,OUTPUT_GUARD if slot<2 else 0)
            with self.assertRaises(ValueError):contract.verify_fault(before,after,d,'accepted-write',target_attempts=3)

    def test_exact_plan_modes_native_source_and_retained_error_history(self):
        self.assertEqual(struct.unpack('<8sIIQQ',contract.matrix_plan()),(b'OGCGAM01',48,2,contract.BASE,contract.STEP))
        build,session,profile=Path('/build'),Path('/session'),Path('/profile.bin')
        previous=None
        for index,stage in enumerate(contract.PHASES):
            self.assertEqual(stage,dict(index=index,case=CASES[index//2],
                mode='accepted-write' if index%2==0 else 'healthy',base=contract.BASE+index*contract.STEP))
            command=contract.source_launch(index,build,session,profile)
            contract.check_source_launch(command,index,build,session,profile)
            self.assertEqual('--expect-global-fault' in command,index%2==0)
            for slot in range(len(command)):
                bad=list(command);bad[slot]='substituted'
                with self.assertRaises(ValueError):contract.check_source_launch(bad,index,build,session,profile)
            row=done(index)
            self.assertIs(contract.check_round(row,index,held(index),previous),row)
            previous=row
        self.assertEqual(contract.history(previous),(0,24,24))
        _,_,d=accepted(CASES[0])
        self.assertEqual(struct.unpack('<8sQQI',contract.fault_plan(d,'accepted-write')),
                         (b'OGCGF002',0x20000,0x20040,3))
        for mode in ('read','write','healthy'):
            with self.assertRaises(ValueError):contract.fault_plan(d,mode)
        for value in (-1,True,48):
            with self.assertRaises(ValueError):contract.phase(value)
        for change in ({'acceptedWriteFaults':0},{'acceptedWriteFaults':True},{'physicalReadFaults':1},
                {'injectedWriteFaults':0},{'faultTargetWriteAttempts':2}):
            with self.assertRaises(ValueError):contract.fault_receipt({**held(0),**change},0)
        for change in ({'schema':'suppressed'},{'resetCount':2},{'ramReplaced':True},
                {'commandId':43},{'phase':2},{'acceptedWriteFaults':0},{'foreign':True}):
            with self.assertRaises(ValueError):contract.check_round({**done(0),**change},0,held(0))


class AcceptedContinuous(unittest.TestCase):
    def test_one_original_producer_and_dut_all_forty_eight_phases(self):
        recovery_tests.GlobalContinuousOwnerTests.peer(self,False,protocol=contract,records=(held,done),expected_words=288)

    def test_bad_committed_history_does_not_authorize_cleanup_or_next_owner(self):
        recovery_tests.GlobalContinuousOwnerTests.peer(self,True,protocol=contract,records=(held,done),expected_words=288)


if __name__=='__main__':unittest.main()
