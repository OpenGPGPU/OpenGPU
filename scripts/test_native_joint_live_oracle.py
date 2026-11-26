#!/usr/bin/env python3
"""Exercise the joint replay checks without obtaining a device receipt."""
from pathlib import Path
import struct
import unittest

from build_native_joint_live_producer import producer_sources
from native_joint_live_oracle import (
    FULL_PATCH_ENDPOINTS, FULL_PATCH_MUTATION, PATCH_ENDPOINTS, RECORDS, SOURCE_CANCEL_RECORD, TESS_BLOCK_WORDS,
    TESS_DEFAULT_WORDS, TESS_UNIFORMS, TESS_UNIFORM_RECOVERY, UNIFORM_RECOVERY_PER_DOMAIN,
    UniformRead, VGA_QUAD, check_tess_uniforms, expected_image, tess_default_word,
    tess_ubo_windows, tess_ubo_word, verify_image,
)
from qualify_native_joint_live import check_source_mutation, cleanup_checked, receipt_words, reset_receipt, retired_receipt


class ImagePage:
    def __init__(self, size=4096):
        self.ranges = [(0x1000,0x1000+size,0xa5)]
        self.data = bytearray([0xa5])*size

    def read(self, address, size):
        offset = address - 0x1000
        return bytes(self.data[offset:offset+size])


class JointOracle(unittest.TestCase):
    row = {'record':0,'domain':0,'pass':11,'generation':41,
           'allocation':0x1000,'allocationBytes':4096,'imageOffset':64,'imageBytes':256}

    def test_coverage_and_original_color(self):
        color, mutation = struct.pack('<4I',6,17,47,53), struct.pack('<4I',3,24,62,20)
        zero = bytes(16)
        for domain, covered in enumerate(({4,5,6},set(range(16))-{11,14,15},set(range(16)))):
            self.assertEqual(expected_image(domain,False),
                             b''.join(color if i in covered else zero for i in range(16)))
            self.assertEqual(expected_image(domain,True),
                             b''.join(mutation if i in covered else zero for i in range(16)))

    def test_writable_image_allocation_guards(self):
        initial, observed = ImagePage(), ImagePage()
        observed.data[64:320] = expected_image(0,False)
        interval = [(0x1000,0x2000)]
        verify_image(initial,observed,self.row,interval)
        observed.data[16] ^= 1
        with self.assertRaisesRegex(ValueError,'guard'):
            verify_image(initial,observed,self.row,interval)
        observed.data[16] ^= 1
        observed.data[64] ^= 1
        with self.assertRaisesRegex(ValueError,'pixel'):
            verify_image(initial,observed,self.row,interval)

    def test_vga_quad_checks_the_last_pixel_and_its_guard(self):
        image_bytes = 640*480*16
        size = (image_bytes+64+4095)&~4095
        base = dict(self.row,domain=2,allocationBytes=size,imageBytes=image_bytes)
        initial, observed = ImagePage(size), ImagePage(size)
        interval = [(0x1000,0x1000+size)]
        end = 64+image_bytes
        self.assertEqual(VGA_QUAD.tiles,1200)
        for record, color in enumerate(((6,17,47,53),(3,24,62,20))):
            row = base | {'record':record,'pass':11+record,'generation':41+record}
            observed.data[64:end] = struct.pack('<4I',*color)*(640*480)
            verify_image(initial,observed,row,interval,fixture=VGA_QUAD)
        observed.data[end-1] ^= 1
        with self.assertRaisesRegex(ValueError,'pixel 639,479'):
            verify_image(initial,observed,row,interval,fixture=VGA_QUAD)
        observed.data[end-1] ^= 1
        observed.data[end] ^= 1
        with self.assertRaisesRegex(ValueError,'guard'):
            verify_image(initial,observed,row,interval,fixture=VGA_QUAD)

    def test_patch_endpoint_variants_require_the_last_vertex_and_patch_words(self):
        initial, observed = ImagePage(), ImagePage()
        interval = [(0x1000,0x2000)]
        for fixture, record, third in ((PATCH_ENDPOINTS,5,956),(FULL_PATCH_ENDPOINTS,1,0x3f800136),
                                       (FULL_PATCH_MUTATION,1,0x65da5b6c)):
            row = dict(self.row,record=record,domain=2)
            observed.data[64:320] = struct.pack('<4I',0x43000000,0x3f800154,third,0x7f800000)*16
            verify_image(initial,observed,row,interval,fixture=fixture)
            # Substitute the value for vertex 0 at the last column for vertex 31.
            observed.data[68:72] = struct.pack('<I',0x3f800135)
            with self.assertRaisesRegex(ValueError,'pixel 0,0'):
                verify_image(initial,observed,row,interval,fixture=fixture)

    def test_full_patch_mutation_changes_only_the_original_tes_result(self):
        root = Path(__file__).resolve().parents[1]/'userspace/tests/tess_joint_live_essl'
        original = (root/'patch-full-quad.tese').read_text()
        mutated = (root/'patch-full-quad-mutation.tese').read_text()
        self.assertNotEqual(original,mutated)
        self.assertNotIn('0x5a5a5a5au',original)
        self.assertIn('^ indices.w ^ 0x5a5a5a5au;',mutated)
        initial, observed = ImagePage(), ImagePage()
        interval = [(0x1000,0x2000)]
        row = dict(self.row,record=0,domain=2)
        observed.data[64:320] = expected_image(2,False,fixture=FULL_PATCH_MUTATION,record=0)
        verify_image(initial,observed,row,interval,fixture=FULL_PATCH_MUTATION)
        row = dict(self.row,record=1,domain=2)
        observed.data[64:320] = expected_image(2,True,fixture=FULL_PATCH_MUTATION,record=1)
        verify_image(initial,observed,row,interval,fixture=FULL_PATCH_MUTATION)
        observed.data[72:76] = struct.pack('<I',0x3f800136)
        with self.assertRaisesRegex(ValueError,'pixel 0,0'):
            verify_image(initial,observed,row,interval,fixture=FULL_PATCH_MUTATION)

    def test_source_mutation_requires_the_same_vs_and_a_different_tes(self):
        baseline = {}
        check_source_mutation(0,(b'vertex',b'tes-original'),baseline)
        check_source_mutation(1,(b'vertex',b'tes-mutated'),baseline)
        with self.assertRaisesRegex(ValueError,'TES executable'):
            check_source_mutation(1,(b'vertex',b'tes-original'),baseline)
        with self.assertRaisesRegex(ValueError,'VS executable'):
            check_source_mutation(1,(b'changed-vertex',b'tes-mutated'),baseline)

    def test_tess_uniform_variants_require_all_four_stage_contributions(self):
        initial, observed = ImagePage(), ImagePage()
        interval = [(0x1000,0x2000)]
        old_color = (0x644043f9,0x3d2002,0x645043d0,0x5f006d)
        new_color = (0x644053f9,0x0a4002,0x645053d0,0x0fc033)
        for record, color in ((4,old_color),(5,new_color)):
            row = dict(self.row,record=record,domain=2)
            observed.data[64:320] = struct.pack('<4I',*color)*16
            verify_image(initial,observed,row,interval,fixture=TESS_UNIFORMS)
        for channel,value in enumerate(old_color):
            offset = 64+4*channel
            observed.data[offset:offset+4] = struct.pack('<I',value)
            with self.assertRaisesRegex(ValueError,'pixel 0,0'):
                verify_image(initial,observed,row,interval,fixture=TESS_UNIFORMS)
            observed.data[offset:offset+4] = struct.pack('<I',new_color[channel])

    def test_retirement_cannot_change_held_identity_or_byte_count(self):
        held = dict(phase='held',record=0,commandId=5,completion=0x18705,
                    gnz2=[0x474e5a02,0,0,7,11,41,1,1,2,0,2,0,256,0,0,0],
                    heldIrq=3,heldIrqAsserted=True,heldLegacyStatus=0,heldStatus=0x22,
                    physicalWriteFaults=0,metadataReadBytes=384,heldCompletionDrained=True,readOnlyGuards=True,
                    sourceQualificationRequired=True)
        payload = receipt_words(held,'held',5,7,self.row)
        retired = dict(held,phase='retired',ownerReplay=True,retiredStatus=1,retiredIrq=1,
                       retiredIrqAsserted=False,externalDrained=True,resets=1,resetsBetweenRecords=False,
                       independentImagesRequired=True,clockQualified=False,fpsQualified=False,
                       serviceCycles=5000,writeBusBytes=256)
        self.assertEqual(retired_receipt(retired,payload,5,7,self.row),
                         b'OGJR0001'+payload+struct.pack('<4I',1,1,0,1))
        with self.assertRaisesRegex(ValueError,'changed held'):
            retired_receipt(dict(retired,metadataReadBytes=448),payload,5,7,self.row)
        with self.assertRaisesRegex(ValueError,'generation|GNZ2'):
            receipt_words(dict(held,gnz2=held['gnz2'][:5]+[42]+held['gnz2'][6:]),'held',5,7,self.row)
        with self.assertRaisesRegex(ValueError,'resetsBetweenRecords'):
            retired_receipt(dict(retired,resetsBetweenRecords=True),payload,5,7,self.row)

    def test_failed_first_store_preserves_image_and_reports_exact_address(self):
        row = {**self.row, 'record':6, 'domain':1, 'pass':17, 'generation':47}
        initial, observed = ImagePage(), ImagePage()
        intervals = [(0x1000,0x2000)]
        verify_image(initial, observed, row, intervals)
        observed.data[64] = 0
        with self.assertRaisesRegex(ValueError, 'pixel'):
            verify_image(initial, observed, row, intervals)

        address = 0x2040
        held = dict(phase='held', record=6, commandId=5, completion=0x11f05,
                    gnz2=[0x474e5a02,51,address,7,17,47,1,1,2,0,2,0,0,0,0,0],
                    heldIrq=3, heldIrqAsserted=True, heldLegacyStatus=0, heldStatus=0x22,
                    physicalWriteFaults=1, metadataReadBytes=384, heldCompletionDrained=True,
                    readOnlyGuards=True, sourceQualificationRequired=True)
        self.assertTrue(receipt_words(held, 'held', 5, 7, row, address))
        with self.assertRaisesRegex(ValueError, 'physicalWriteFaults'):
            receipt_words(dict(held, physicalWriteFaults=0), 'held', 5, 7, row, address)
        with self.assertRaisesRegex(ValueError, 'GNZ2'):
            receipt_words(held, 'held', 5, 7, row, address + 64)
        held['gnz2'][12] = 64
        with self.assertRaisesRegex(ValueError, 'GNZ2'):
            receipt_words(held, 'held', 5, 7, row, address)

    def test_complete_owner_cleanup_is_required(self):
        cleanup = dict(phase='cleanup',ownerCleanupObserved=True,contextCleanupObserved=True,records=RECORDS,
                       resetCount=1,ramReplaced=False,imagesPreserved=True,externalDrained=True,
                       sourceQualificationRequired=True,clockQualified=False,fpsQualified=False,
                       batches=2,pteClears=1,shootdowns=1)
        cleanup_checked(cleanup)
        with self.assertRaisesRegex(ValueError,'records'):
            cleanup_checked(dict(cleanup,records=RECORDS-1))
        with self.assertRaisesRegex(ValueError,'pteClears'):
            cleanup_checked(dict(cleanup,pteClears=0))

    def test_uniform_read_fault_requires_exact_physical_line_and_no_publication(self):
        row = self.row | {'record':2,'pass':13,'generation':43}
        line = 0x5000
        detail = [0x474e5a02,41,0,7,13,43,1,0,0,0,0,0,0,0,0,0]
        held = dict(phase='held',record=2,commandId=5,completion=0x10f05,gnz2=detail,
                    heldIrq=3,heldIrqAsserted=True,heldLegacyStatus=0,heldStatus=0x22,
                    physicalWriteFaults=0,physicalReadFaults=1,physicalReadFaultLine=line,
                    metadataReadBytes=384,heldCompletionDrained=True,
                    readOnlyGuards=True,sourceQualificationRequired=True)
        self.assertTrue(receipt_words(held,'held',5,7,row,read_fault_line=line))
        with self.assertRaisesRegex(ValueError,'selected uniform line'):
            receipt_words(held,'held',5,7,row,read_fault_line=line+64)
        for counter in (7,8,10,12):
            wrong = detail.copy()
            wrong[counter] = 1
            with self.assertRaisesRegex(ValueError,'GNZ2'):
                receipt_words(dict(held,gnz2=wrong),'held',5,7,row,read_fault_line=line)
        initial, observed = ImagePage(), ImagePage()
        allowed = [(0x1000,0x2000)]
        verify_image(initial,observed,row,allowed,fixture=TESS_UNIFORM_RECOVERY)
        observed.data[64] ^= 1
        with self.assertRaisesRegex(ValueError,'pixel'):
            verify_image(initial,observed,row,allowed,fixture=TESS_UNIFORM_RECOVERY)

    def test_cancel_requires_the_selected_axi_read_to_survive_until_reset_drain(self):
        record, command, index_line, satp = SOURCE_CANCEL_RECORD,17,0x5000,0x80400001
        held = dict(phase='held',record=record,commandId=command,legacyStatus=0,status=0x21,irq=1,
            irqAsserted=False,externalDrained=False,physicalIndexLine=index_line,satp=satp,
            heldReadCycles=5,resetHeldReadCycles=0,serviceCycles=100)
        ack = dict(held,phase='ack',status=1,irq=3,irqAsserted=True,externalDrained=True,
                   heldReadCycles=45,resetHeldReadCycles=35,serviceCycles=190)
        retired = dict(ack,phase='retired',irq=1,irqAsserted=False,serviceCycles=200)

        def receipt(value, phase, previous=None):
            return reset_receipt(value,phase,command,record,index_line,satp,previous)

        self.assertEqual(receipt(held,'held'), b'OGJCH001'+struct.pack('<7I',record,command,0,0x21,1,0,0))
        self.assertEqual(receipt(ack,'ack',held),b'OGJCA001'+struct.pack('<7I',record,command,0,1,3,1,1))
        self.assertEqual(receipt(retired,'retired',ack),b'OGJCV001'+struct.pack('<7I',record,command,0,1,1,0,1))
        with self.assertRaisesRegex(ValueError,'physicalIndexLine'):
            receipt(dict(held,physicalIndexLine=index_line+64),'held')
        with self.assertRaisesRegex(ValueError,'before the reset drain'):
            receipt(dict(ack,resetHeldReadCycles=31),'ack',held)
        with self.assertRaisesRegex(ValueError,'satp'):
            receipt(dict(ack,satp=satp+1),'ack',held)
        with self.assertRaisesRegex(ValueError,'changed the drain'):
            receipt(dict(retired,heldReadCycles=46),'retired',ack)

        row = dict(self.row,record=record,domain=1)
        initial, observed = ImagePage(), ImagePage()
        allowed = [(0x1000,0x2000)]
        verify_image(initial,observed,row,allowed)
        observed.data[64] ^= 1
        with self.assertRaisesRegex(ValueError,'pixel'):
            verify_image(initial,observed,row,allowed)

    def test_uniform_recovery_matrix_covers_each_stage_view_and_fault_mode(self):
        fixture = TESS_UNIFORM_RECOVERY
        expected = {
            2: (4,71,True), 4: (5,1,True), 6: (4,None,True), 8: (5,None,True),
            10: (4,71,False), 12: (5,1,False), 14: (4,None,False), 16: (5,None,False),
        }
        self.assertEqual(UNIFORM_RECOVERY_PER_DOMAIN,18)
        self.assertEqual(len(fixture.cases),54)
        header = (Path(__file__).resolve().parents[1]/'scripts/native_joint_live_bridge.h').read_text()
        self.assertIn('JOINT_LIVE_RECOVERY_PER_DOMAIN = 18',header)
        self.assertIn('JOINT_LIVE_UNIFORM_FAULT_FIRST = 2',header)
        self.assertIn('JOINT_LIVE_UNIFORM_CANCEL_FIRST = 10',header)
        for domain in range(3):
            for position, wanted in expected.items():
                record = domain*UNIFORM_RECOVERY_PER_DOMAIN+position
                read = fixture.uniform_read(record)
                self.assertEqual((read.stage,read.binding,read.fault),wanted)
                self.assertFalse(fixture.cases[record][1])
                self.assertEqual(fixture.cancels_read(record),not read.fault)
                self.assertIsNone(fixture.uniform_read(record+1))
                self.assertFalse(fixture.cancels_read(record+1))
                initial, observed = ImagePage(), ImagePage()
                row = dict(self.row,record=record)
                verify_image(initial,observed,row,[(0x1000,0x2000)],fixture=fixture)
                observed.data[64] ^= 1
                with self.assertRaisesRegex(ValueError,'pixel'):
                    verify_image(initial,observed,row,[(0x1000,0x2000)],fixture=fixture)
                initial, observed = ImagePage(), ImagePage()
                row = dict(self.row,record=record+1)
                observed.data[64:320] = expected_image(domain,True,fixture=fixture,record=record+1)
                verify_image(initial,observed,row,[(0x1000,0x2000)],fixture=fixture)

    def test_uniform_read_lines_select_the_private_ubo_and_default_endpoints(self):
        identity, record = (7,13,43), 2
        windows = tess_ubo_windows(record)
        table = 0x10080
        table_end = table+24*64
        sequence = [0]*512
        sequence[499] = sequence[504] = 3
        sequence[500] = 0x10000
        sequence[505] = sequence[500]+64
        sequence[501:504] = sequence[506:509] = (0,0,0)
        values = {}
        defaults = []
        for index, owned in enumerate(windows):
            stage = 4+index
            slots = tuple(range(index*12,index*12+12))
            mapping = tuple(sum(slot<<(5*position) for position,slot in enumerate(slots[part*6:part*6+6]))
                            for part in range(2))
            default_descriptor = table_end+64*index
            default_base = 0x30000+index*0x1000
            defaults.append(default_base)
            values[default_descriptor] = (0x47554203,64,3,*identity,31,1<<stage,
                default_base,default_base+TESS_DEFAULT_WORDS*4,0,default_base,
                default_base+TESS_DEFAULT_WORDS*4,0,1,0)
            values[default_base] = tuple(tess_default_word(stage,record,word)
                                         for word in range(TESS_DEFAULT_WORDS))
            values[sequence[500+5*index]] = (0x47555203,64,3,*identity,stage,0xfff,
                *mapping,table,table_end,0,default_descriptor,1,0)
            for slot,window in zip(slots,owned):
                base = 0x40000+window*0x4000
                values[table+64*slot] = (0x47554203,64,3,*identity,slot,1<<stage,
                    base,base+TESS_BLOCK_WORDS*4,0,base,base+TESS_BLOCK_WORDS*4,0,1,0)
                values[base] = tuple(tess_ubo_word(window,record,word)
                                     for word in range(TESS_BLOCK_WORDS))

        sequence = tuple(sequence)

        def words(address,count):
            return values[address][:count]

        self.assertEqual(check_tess_uniforms(words,sequence,identity,record,UniformRead(4,71,True)),
                         0x40000+71*0x4000+TESS_BLOCK_WORDS*4-64)
        self.assertEqual(check_tess_uniforms(words,sequence,identity,record,UniformRead(5,1,True)),
                         0x40000+1*0x4000+TESS_BLOCK_WORDS*4-64)
        self.assertEqual(check_tess_uniforms(words,sequence,identity,record,UniformRead(4,None,False)),
                         defaults[0]+TESS_DEFAULT_WORDS*4-64)
        self.assertEqual(check_tess_uniforms(words,sequence,identity,record,UniformRead(5,None,False)),
                         defaults[1]+TESS_DEFAULT_WORDS*4-64)

    def test_uniform_cancel_requires_the_selected_line_for_each_stage_view(self):
        fixture = TESS_UNIFORM_RECOVERY
        command, line, satp = 17, 0x6000, 0x80400001
        for domain in range(3):
            for position, stage, binding in ((10,4,71),(12,5,1),(14,4,None),(16,5,None)):
                record = domain*UNIFORM_RECOVERY_PER_DOMAIN+position
                read = fixture.uniform_read(record)
                self.assertEqual((read.stage,read.binding,read.fault),(stage,binding,False))
                self.assertTrue(fixture.cancels_read(record))
                self.assertIsNone(fixture.uniform_read(record+1))
                held = dict(phase='held',record=record,commandId=command,legacyStatus=0,status=0x21,
                            irq=1,irqAsserted=False,externalDrained=False,physicalReadLine=line,satp=satp,
                            heldReadCycles=5,resetHeldReadCycles=0,serviceCycles=100)
                self.assertEqual(reset_receipt(held,'held',command,record,line,satp,uniform=True),
                                 b'OGJCH001'+struct.pack('<7I',record,command,0,0x21,1,0,0))
                with self.assertRaisesRegex(ValueError,'physicalReadLine'):
                    reset_receipt(held,'held',command,record,line+64,satp,uniform=True)

    def test_qualification_binds_the_complete_producer_and_oracle_sources(self):
        root = Path(__file__).resolve().parents[1]
        sources = {path.relative_to(root).as_posix() for path in producer_sources()}
        for name in ('scripts/build_native_joint_live_producer.py','scripts/native_joint_live_kernel.c',
                     'scripts/native_joint_live_bridge.h','scripts/native_joint_live_oracle.py',
                     'scripts/qualify_native_joint_live.py','scripts/test_native_joint.py',
                     'userspace/tests/native_joint_live_flow.c','userspace/tests/cs_callback_flow.c'):
            self.assertIn(name,sources)


if __name__ == '__main__':
    unittest.main()
