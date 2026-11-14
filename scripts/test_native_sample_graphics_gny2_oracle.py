#!/usr/bin/env python3
"""Data-only expected-result tests; handfilled GNY2 is not GPU evidence."""
import copy
import json
import struct
import unittest

import native_sample_graphics_gny2_oracle as oracle


class OrdinaryGny2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(oracle.CATALOG.read_text())['completion']
        cls.fields = {f['name']: f for f in cls.catalog['fields']}

    def fixture(self, command=1, context=17):
        return struct.pack('<4096I', *oracle._expected_words(command, context))

    def get(self, data, name):
        f = self.fields[name]
        return sum(struct.unpack_from('<I', data, (f['word']+i)*4)[0] << (32*i)
                   for i in range((f['bits']+31)//32))

    def test_independent_exact_clip_polygon_and_full_p16_record_width_census(self):
        polygon = oracle.clipped_polygon()
        self.assertEqual(set(polygon), {(-1,-1,0,1),(-1,1,0,1),(1,-1,0,1),(1,1,0,1)})
        self.assertEqual(len(polygon), 4)
        fan = [(polygon[0], polygon[i], polygon[i+1]) for i in range(1,len(polygon)-1)]
        self.assertEqual(len(fan), 2)
        self.assertTrue(all((b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]) == 4 for a,b,c in fan))
        layout, lines = oracle.record_layout()
        self.assertEqual(len(layout), 332)
        self.assertEqual(layout[-1], ('raster.polygonOffsetUnits',356,32))
        self.assertEqual(lines, (357+15)//16)
        self.assertEqual(len([name for name,_,_ in layout if '.varyings[' in name]), 3*16*4)
        self.assertTrue(all(word//16 == (word+(bits+31)//32-1)//16 for _,word,bits in layout))

    def test_nonzero_counts_are_the_triangle_two_record_one_tile_recipe(self):
        data = self.fixture()
        oracle.verify(data, command_id=1, context=17)
        for name in ['frame.admittedParts','frame.receivedParts','frame.images.producer.accepted',
                     'frame.images.producer.stored','frame.images.producer.references',
                     'frame.images.directory.committedCount','frame.images.images.scanned',
                     'frame.images.images.selected','frame.images.images.accepted',
                     'frame.images.images.issued','frame.images.images.completed']:
            self.assertEqual(self.get(data,name),2,name)
        for name in ['frame.images.producer.recordWriteLines','frame.images.images.recordReadLines']:
            self.assertEqual(self.get(data,name),2*23,name)
        for name in ['issuedDraws','completedDraws','frame.draws','frame.images.images.tiles',
                     'frame.images.images.pageReadLines','frame.images.directory.allocatedPages',
                     'frame.images.directory.tilesOpened','frame.images.directory.directoryMisses']:
            self.assertEqual(self.get(data,name),1,name)
        for name, expected in [('descriptorReadBytes',(8+32+8)*64),('drawReadBytes',2*32*64),
                               ('frame.images.directory.directoryReadBytes',64),
                               ('frame.images.directory.directoryWriteBytes',2*64),
                               ('frame.images.images.storedBytes',4*4*16)]:
            self.assertEqual(self.get(data,name),expected,name)
        # A result carrying the point recipe's single part, one record, or
        # four-color-plus-depth byte count is rejected under this contract.
        for name,value in [('frame.admittedParts',1),('frame.images.images.recordReadLines',23),
                           ('frame.images.images.storedBytes',1088)]:
            wrong=bytearray(data);struct.pack_into('<I',wrong,self.fields[name]['word']*4,value)
            with self.assertRaises(ValueError):oracle.verify(bytes(wrong),command_id=1,context=17)

    def test_all_4096_words_low_high_bits_both_owner_identities_reserved_and_padding(self):
        mutants = 0
        for command in (1,2):
            data = self.fixture(command)
            oracle.verify(data,command_id=command,context=17)
            for word in range(4096):
                value,=struct.unpack_from('<I',data,word*4)
                for bit in (0,31):
                    wrong=bytearray(data);struct.pack_into('<I',wrong,word*4,value^(1<<bit))
                    with self.assertRaises(ValueError,msg='word=%d bit=%d'%(word,bit)):
                        oracle.verify(bytes(wrong),command_id=command,context=17)
                    mutants += 1
        self.assertEqual(mutants,16384)

    def test_all_628_fields_including_inactive_snapshots_and_wide_counter_high_padding(self):
        data=self.fixture()
        counts=dict.fromkeys(oracle.ZERO_SNAPSHOTS,0)
        for name,field in self.fields.items():
            for prefix in counts:
                if name.startswith(prefix):
                    counts[prefix]+=1;self.assertEqual(self.get(data,name),0,name)
            # Exercise all words of >32-bit fields, including the upper 145b
            # counters; unspecified high bits must remain zero too.
            for offset in range((field['bits']+31)//32):
                word=field['word']+offset
                wrong=bytearray(data);value,=struct.unpack_from('<I',data,word*4)
                struct.pack_into('<I',wrong,word*4,value^0x80000000)
                with self.assertRaises(ValueError):oracle.verify(bytes(wrong),command_id=1,context=17)
        self.assertEqual(counts,{'frame.sourceFault.':108,'frame.images.producer.restart.':336,
                                 'frame.images.images.fault.':111})

    def test_catalog_has_no_unknown_missing_overlapping_or_out_of_bounds_fields(self):
        self.assertEqual(len(oracle.classify_layout(self.catalog)),628)
        for mutation in ('unknown','duplicate','missing','overlap','outside','crossline','negative','huge','snapshot','recordWidth'):
            changed=copy.deepcopy(self.catalog)
            if mutation=='unknown':changed['fields'][0]['name']='unknown.new.counter'
            elif mutation=='duplicate':changed['fields'][0]['name']=changed['fields'][1]['name']
            elif mutation=='missing':changed['fields'].pop()
            elif mutation=='overlap':changed['fields'][0]['word']=changed['fields'][1]['word']
            elif mutation=='outside':changed['fields'][0]['word']=4096
            elif mutation=='crossline':changed['fields'][0].update(word=15,bits=64)
            elif mutation=='negative':changed['fields'][0]['bits']=-1
            elif mutation=='huge':changed['fields'][0]['bits']=1<<30
            elif mutation=='snapshot':
                field=next(f for f in changed['fields'] if f['name'].startswith('frame.sourceFault.'))
                field['name']='frame.images.producer.restart.extra'
            else:
                field=next(f for f in changed['fields'] if f['name'].startswith('frame.images.producer.restart.') and
                           f['name'].endswith('.localX.exponent'))
                field['bits']=13
            with self.assertRaises(ValueError,msg=mutation):oracle.classify_layout(changed)

    def test_original_command_context_and_exact_eof_are_required(self):
        data=self.fixture()
        for command,context in [(2,17),(1,18),(0,17),(256,17),(True,17),(1,0),(1,1<<32),(1,True)]:
            with self.assertRaises(ValueError):oracle.verify(data,command_id=command,context=context)
        for wrong in (data[:-1],data+b'\0',bytearray(data),bytes(16384)):
            with self.assertRaises(ValueError):oracle.verify(wrong,command_id=1,context=17)
        for command,context in [(255,1),(1,0xffffffff)]:
            oracle.verify(self.fixture(command,context),command_id=command,context=context)


if __name__=='__main__':
    unittest.main()
