#!/usr/bin/env python3
"""CPU reset metadata/byte rejection checks; never DUT execution evidence."""
from copy import deepcopy
import struct
import unittest
from compute_image_cases import parameters
from compute_image_source import SCHEMA, source_view, initial_bytes
from compute_image_reset import reset_plan, reset_receipt_bytes, verify_cancelled_memory, verify_cancelled_cleanup
from owner_resource_contract import IMAGE_FLOAT_PROFILE, SAMPLE_PROFILE


class Uploads:
    def __init__(self,data):
        self.data={a:bytearray(b) for a,b in data.items()}
        self.ranges=[(a,a+len(b),0) for a,b in sorted(data.items())]
    def read(self,address,count):
        for first,data in self.data.items():
            if first<=address<=address+count<=first+len(data):
                return bytes(data[address-first:address-first+count])
        raise ValueError('outside CPU upload')
    def word(self,address,value):
        for first,data in self.data.items():
            if first<=address<=first+len(data)-4:
                struct.pack_into('<I',data,address-first,value);return
        raise ValueError('outside CPU upload')


def original():
    p=parameters('image-atomic-buffer-r32f')
    data={0x1000:struct.pack('<I',0xdeadbeef)*2048,0x40000:b'\x52'*4096,0x50000:bytes(4096)}
    images=[]
    for role in range(2):
        binding,view=source_view(p,role,role);physical=0x10000*(role+1)
        data[physical]=b'\xa7'*8192 if role else initial_bytes(p,8192)
        images.append(dict(apiBinding=1 if role else 3,nativeUnit=role,handle=role+1,
            allocationPhysical=physical,allocationBytes=8192,bindingBytes=binding,view=view))
    memory=Uploads(data);memory.word(0x50000,0x4c7)
    return memory,dict(schema=SCHEMA,sourceCase='image-atomic-buffer-r32f',groups=2,local=4,viewBytes=8192,
        guard=0xdeadbeef,context=1,kernargPhysical=0,kernargBytes=0,imagePhysical=0,imageRoot=0,
        storagePhysical=0,storageRoot=0,output=dict(globalBinding=2,allocationPhysical=0x1000,
            allocationBytes=8192,offset=0,bytes=8192),images=images)


def observation(phase,channel):
    drained=phase in ('ack','retired')
    return dict(schema='opengpu-compute-image-reset-'+phase+'-v1',phase=phase,record=0,
        channel=channel,commandId=4,legacyStatus=0,status={'held':33,'busy':40,'ack':1,'retired':1}[phase],
        irq={'held':3,'busy':2,'ack':3,'retired':1}[phase],irqAsserted=phase in ('held','ack'),
        externalDrained=drained,completionAbsent=True,resetCount=1,
        unifiedResetRequests=0 if phase=='held' else 1,resetRequestTraceReplayed=phase!='held',
        resetTailTraceReplayed=drained,resetIrqRetirementReplayed=phase=='retired',
        busySubmitRejected=drained,
        noLateTrafficCycles=32 if drained else 0,physicalReadFaults=0,injectedReadFaults=0,
        physicalWriteFaults=0,heldReadCycles=5 if channel=='read' else 0,
        heldWriteCycles=5 if channel=='write' else 0,readOnlyGuards=True,
        serviceCycles={'held':100,'busy':110,'ack':160,'retired':180}[phase],
        sourceQualificationRequired=True,independentBytesRequired=True,clockQualified=False,fpsQualified=False)


class ResetContracts(unittest.TestCase):
    def test_busy_ready_reports_queue_capacity_and_never_authorizes_drain(self):
        held=observation('held','write')
        for status in (40,41):
            busy=observation('busy','write');busy['status']=status
            self.assertEqual(reset_receipt_bytes(busy,'busy','write',held)[16:20],struct.pack('<I',status))
        for status in (1,8,9,32,33,42,43,44,45,56,57,104,105):
            busy=observation('busy','write');busy['status']=status
            with self.subTest(status=status),self.assertRaises(ValueError):reset_receipt_bytes(busy,'busy','write',held)
        busy=observation('busy','write');busy['externalDrained']=True
        with self.assertRaises(ValueError):reset_receipt_bytes(busy,'busy','write',held)

    def test_plan_is_exact_original_source_allocation_and_channel(self):
        memory,description=original()
        for channel,value in (('read',1),('write',2)):
            self.assertEqual(reset_plan(memory,description,channel,owner_profile=IMAGE_FLOAT_PROFILE),
                             struct.pack('<8sIQQ',b'OGCRP001',value,0x10000,0x12000))
        for channel,profile in (('read',SAMPLE_PROFILE),('bad',IMAGE_FLOAT_PROFILE)):
            with self.assertRaises(ValueError):reset_plan(memory,description,channel,owner_profile=profile)
        for changed in ('case','permission','address'):
            bad=deepcopy(description)
            if changed=='case':bad['sourceCase']='image-atomic-2d-r32f'
            if changed=='permission':bad['images'][0]['view']['flags']=1
            if changed=='address':bad['images'][0]['allocationPhysical']+=64
            with self.assertRaises(ValueError):reset_plan(memory,bad,'read',owner_profile=IMAGE_FLOAT_PROFILE)

    def test_reset_phases_are_distinct_raw_observations_with_continuous_history(self):
        for channel in ('read','write'):
            previous=None
            for phase,magic in (('held',b'OGCRH001'),('busy',b'OGCRB001'),('ack',b'OGCRA001'),('retired',b'OGCRD001')):
                value=observation(phase,channel)
                self.assertEqual(reset_receipt_bytes(value,phase,channel,previous),magic+struct.pack('<6I',
                    4,0,value['status'],value['irq'],int(value['irqAsserted']),int(value['externalDrained'])))
                for key in value:
                    wrong=deepcopy(value)
                    if type(wrong[key]) is bool:wrong[key]=not wrong[key]
                    elif type(wrong[key]) is int:wrong[key]=-1
                    else:wrong[key]='wrong'
                    with self.subTest(channel=channel,phase=phase,key=key),self.assertRaises(ValueError):
                        reset_receipt_bytes(wrong,phase,channel,previous)
                for key in ('completion','processedBytes','gnx','heldStatus','retiredStatus'):
                    wrong=dict(value);wrong[key]=0
                    with self.assertRaises(ValueError):reset_receipt_bytes(wrong,phase,channel,previous)
                if previous:
                    for key,value_change in (('commandId',5),('serviceCycles',0),
                            ('heldReadCycles' if channel=='read' else 'heldWriteCycles',4)):
                        wrong=dict(value);wrong[key]=value_change
                        with self.assertRaises(ValueError):reset_receipt_bytes(wrong,phase,channel,previous)
                    with self.assertRaises(ValueError):reset_receipt_bytes(value,phase,channel)
                previous=value

    def test_read_hold_preserves_every_byte_and_write_hold_requires_committed_data(self):
        memory,description=original()
        verify_cancelled_memory(memory,memory,description,'read')
        with self.assertRaises(ValueError):verify_cancelled_memory(memory,memory,description,'write')
        for address in (0x1000,0x10034,0x2003c,0x40000,0x50000):
            changed=deepcopy(memory);changed.word(address,0)
            with self.subTest(address=address),self.assertRaises(ValueError):
                verify_cancelled_memory(memory,changed,description,'read')

    def test_write_hold_accepts_only_whole_original_exchange_and_output_words(self):
        memory,description=original()
        for value in (0,0x3f400000,0xbf800000,0x7f800000,0x40000000):
            partial=deepcopy(memory);partial.word(0x10034,value)
            partial.word(0x1004,0);partial.word(0x2003c,0x40000000)
            verify_cancelled_memory(memory,partial,description,'write')
        partial=deepcopy(memory);partial.word(0x10034,0);partial.word(0x10038,0x80000000)
        verify_cancelled_memory(memory,partial,description,'write')
        for address,value in ((0x10034,0x80000000),(0x10038,0),(0x1000,0),(0x2003c,0),
                (0x10030,0),(0x10054,0),(0x10064,0),(0x10067,0),(0x10a0,0),
                (0x20030,0),(0x20038,0),(0x2005c,0),(0x40000,0),(0x50000,0)):
            wrong=deepcopy(partial);wrong.word(address,value)
            with self.subTest(address=address,value=value),self.assertRaises(ValueError):
                verify_cancelled_memory(memory,wrong,description,'write')

    def test_cancelled_cleanup_only_advances_valid_words_and_revokes_original_ptes(self):
        memory,description=original();tables=({0x50000},{0x50000})
        for channel in ('read','write'):
            cancelled=deepcopy(memory)
            if channel=='write':cancelled.word(0x10034,0)
            cleaned=deepcopy(cancelled);cleaned.word(0x50000,0)
            if channel=='write':cleaned.word(0x10034,0x3f400000);cleaned.word(0x1004,0)
            verify_cancelled_cleanup(memory,cancelled,cleaned,description,channel,tables)
            with self.assertRaises(ValueError):verify_cancelled_cleanup(memory,cancelled,cancelled,description,channel,tables)
            for address,value in ((0x50004,0x4c7),(0x40000,0),(0x50000,0x4c6),(0x2005c,0)):
                wrong=deepcopy(cleaned);wrong.word(address,value)
                with self.assertRaises(ValueError):verify_cancelled_cleanup(memory,cancelled,wrong,description,channel,tables)
        cancelled=deepcopy(memory);cancelled.word(0x10034,0x3f400000);cancelled.word(0x10038,0x80000000)
        backwards=deepcopy(cancelled);backwards.word(0x10034,0);backwards.word(0x50000,0)
        with self.assertRaises(ValueError):verify_cancelled_cleanup(memory,cancelled,backwards,description,'write',tables)


if __name__=='__main__':unittest.main()
