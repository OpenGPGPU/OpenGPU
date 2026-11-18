#!/usr/bin/env python3
"""CPU controls for committed image errors; these are not GPU evidence."""
from pathlib import Path
from itertools import permutations
from functools import lru_cache
from copy import deepcopy
import struct
import unittest

from compute_atomic_order import result, linearization
from compute_contended_image_cases import parameters, operations
from compute_image_source import texel_address
import compute_image_accepted_recovery as contract
from test_compute_contended_image_source import fixture, Memory
import test_compute_global_recovery as recovery_tests


@lru_cache(maxsize=55)
def original_fixture(case):
    return fixture(case)


def accepted(case,group=0,failed=None,schedule=None):
    original,description=original_fixture(case);description=deepcopy(description)
    before=Memory({**original.pages,0xa0000:b'previous cleaned RAM'*128})
    after=Memory(before.pages);p=parameters(case);calls,_=operations(case);count=len(calls)//8
    active=list(range(4*group,4*group+4));failed=4*(1-group) if failed is None else failed
    first=calls[failed*count]
    value=result(first['operation'],p['seed'],first['operand'],first['compare'])
    events=[n*count+k for n in active for k in range(count)] if schedule is None else schedule
    words={}
    for event in events:
        words[event]=value;call=calls[event]
        value=result(call['operation'],value,call['operand'],call['compare'])
        after.write(0x10000+4*event,struct.pack('<I',words[event]))
    after.write(texel_address(description['images'][0],p,0,0),struct.pack('<I',value))
    for n in active:
        after.write(texel_address(description['images'][1],p,1,n),struct.pack('<I',words[(n+1)*count-1]))
    return before,after,description


def held(index):
    count=(index+2)//2
    return dict(commandId=42,physicalReadFaults=0,injectedReadFaults=0,
        physicalWriteFaults=count,injectedWriteFaults=count,acceptedWriteFaults=count,
        faultTargetWriteAttempts=contract.target_attempts(contract.phase(index)['case']) if index%2==0 else 0)


def done(index):
    h=held(index)
    return dict(schema=contract.ROUND_SCHEMA,phase=index,mode=contract.phase(index)['mode'],commandId=42,
        resetCount=1,ramReplaced=False,externalDrained=True,sourceQualificationRequired=True,
        independentBytesRequired=True,startCycle=1000*index,endCycle=1000*index+100,
        **{key:h[key] for key in contract.COUNTERS})


class ImageAcceptedHistories(unittest.TestCase):
    def test_all_sources_failed_lanes_and_successful_workgroup_permutations(self):
        checked=0
        for case in contract.SOURCES:
            count=len(operations(case)[0])//8
            for group in (0,1):
                for failed in range(4*(1-group),4*(1-group)+4):
                    for order in permutations(range(4*group,4*group+4)):
                        schedule=[n*count+k for n in order for k in range(count)]
                        before,after,d=accepted(case,group,failed,schedule)
                        self.assertEqual(contract.verify_fault(before,after,d,'accepted-write',
                            target_attempts=1+4*count),4*count)
                        checked+=1
        self.assertEqual(checked,10560)

    def test_literal_committed_prefix_destination_and_every_physical_guard(self):
        for case in contract.SOURCES:
            before,after,d=accepted(case);p=parameters(case);pair=p['pair']
            if pair=='swap-add':old=[0x80000044,0x80000000,0xfffffff0,0x80000011,1,0x80000022,0x12,0x80000033];final=0x23
            elif pair=='xor-or':old=[0xaaaa555a,0xfffffff0,0xffffffff,0xaaaa5555,0xaaaa555f,0xfffffff5,0xffffffff,0xaaaa5555];final=0xaaaa555f
            elif pair=='and-min':
                first=0x7ffffff3 if p['kind']=='sint' else 0xfffffff3
                old=[first,first,0x80000007]+[0x80000003]*5;final=0x80000003
            elif pair=='max-cas':
                maximum=0x7fffff00 if p['kind']=='sint' else 0x80000020
                old=[maximum,maximum,0x12345670,maximum,0x12345671,maximum,0x12345672,maximum];final=0x12345673
            elif pair=='cas-pair':old=[5]*8;final=5
            else:old=[0x3f400004,0x3f400000,0x3f400001,0x3f400002];final=0x3f400003
            self.assertEqual(struct.unpack('<%dI'%len(old),after.read(0x10000,4*len(old))),tuple(old))
            target=texel_address(d['images'][0],p,0,0)
            self.assertEqual(struct.unpack('<I',after.read(target,4))[0],final)
            count=len(old)//4
            for n in range(4):
                self.assertEqual(struct.unpack('<I',after.read(texel_address(d['images'][1],p,1,n),4))[0],old[(n+1)*count-1])
            addresses=[0x10000+4*n for n in range(8*count)]+[target]+[
                texel_address(d['images'][1],p,1,n) for n in range(8)]+[
                0x10000+32*count,0x20000,0x30000,0x40000,0x50000,0xa0001]
            for address in addresses:
                bad=Memory(after.pages);bad.write(address,bytes([bad.read(address,1)[0]^1]))
                with self.subTest(case=case,address=address),self.assertRaises(ValueError):
                    contract.verify_fault(before,bad,d,'accepted-write',target_attempts=1+4*count)
            for attempts in (0,1,4,6,8,10,True):
                with self.assertRaises(ValueError):contract.verify_fault(before,after,d,'accepted-write',target_attempts=attempts)

    def test_program_order_and_failed_group_identity_are_required(self):
        case='image-contended-buffer-r32ui-swap-add'
        before,after,d=accepted(case,schedule=[1,0,3,2,5,4,7,6])
        calls,_=operations(case);words=struct.unpack('<8I',after.read(0x10000,32))
        final=struct.unpack('<I',after.read(texel_address(d['images'][0],parameters(case),0,0),4))[0]
        linearization(0x80000044,final,calls[:8],words)
        with self.assertRaises(ValueError):contract.verify_fault(before,after,d,'accepted-write',target_attempts=9)
        for case in (contract.SOURCES[0],contract.SOURCES[4],contract.SOURCES[-1]):
            for group in (0,1):
                for wrong in range(4*group,4*group+4):
                    before,after,d=accepted(case,group,wrong)
                    with self.assertRaises(ValueError):contract.verify_fault(before,after,d,'accepted-write',
                        target_attempts=contract.target_attempts(case))

    def test_exact_all_source_launches_fault_lines_and_continuous_history(self):
        self.assertEqual(struct.unpack('<8sIIQQ',contract.matrix_plan()),(b'OGCIAM01',110,2,contract.BASE,contract.STEP))
        previous=None
        for index,stage in enumerate(contract.PHASES):
            command=contract.source_launch(index,Path('/build'),Path('/session'),Path('/profile'))
            contract.check_source_launch(command,index,Path('/build'),Path('/session'),Path('/profile'))
            self.assertEqual('--expect-image-write-fault' in command,index%2==0)
            for slot in range(len(command)):
                bad=list(command);bad[slot]='substituted'
                with self.assertRaises(ValueError):contract.check_source_launch(bad,index,Path('/build'),Path('/session'),Path('/profile'))
            row=done(index);contract.check_round(row,index,held(index),previous);previous=row
            for bad in (0,1,3,5 if index<100 else 9,True):
                if index%2==0:
                    with self.assertRaises(ValueError):contract.fault_receipt({**held(index),'faultTargetWriteAttempts':bad},index,done(index-1) if index else None)
        self.assertEqual(contract.history(previous),(0,55,55))
        for case in contract.SOURCES:
            _,_,d=accepted(case);p=parameters(case)
            target=texel_address(d['images'][0],p,0,0)&~63
            self.assertEqual(struct.unpack('<8sQQI',contract.fault_plan(d,'accepted-write')),(b'OGCIF002',target,target+64,3))
        for change in ({'acceptedWriteFaults':0},{'acceptedWriteFaults':True},{'physicalReadFaults':1},{'injectedWriteFaults':0}):
            with self.assertRaises(ValueError):contract.fault_receipt({**held(0),**change},0)
        for change in ({'schema':'storage'},{'resetCount':2},{'ramReplaced':True},{'commandId':43},{'phase':2},{'foreign':True}):
            with self.assertRaises(ValueError):contract.check_round({**done(0),**change},0,held(0))


class ImageAcceptedContinuous(unittest.TestCase):
    def test_all_original_phases_keep_one_producer_and_dut(self):
        recovery_tests.GlobalContinuousOwnerTests.peer(self,False,protocol=contract,
            records=(held,done),expected_words=1260,source_fixture=accepted)

    def test_bad_committed_history_prevents_cleanup_and_next_source(self):
        recovery_tests.GlobalContinuousOwnerTests.peer(self,True,protocol=contract,
            records=(held,done),expected_words=1260,source_fixture=accepted)


if __name__=='__main__':unittest.main()
