#!/usr/bin/env python3
"""Source/event checks only; actual alias capture/compiler/DUT gates are pending."""
from pathlib import Path
import tempfile
import unittest
from compute_atomic_order import linearization
from compute_mixed_atomic_cases import CASES,VIEWS,ORDERS,parameters,steps,operations,write_sources


class MixedSources(unittest.TestCase):
    def test_all_operations_both_domains_and_original_same_gem_view_algebra(self):
        self.assertEqual(len(CASES),66)
        seen={'image':set(),'storage':set()}
        for case in CASES:
            p=parameters(case);events,predecessors,domains=operations(case)
            self.assertEqual((p['groups'],p['local'],p['allocation_bytes']),(2,4,8192))
            self.assertEqual(len(events),16)
            self.assertEqual(p['target_offset'],p['image_offset']+4*p['image_coordinate'])
            self.assertEqual(p['target_offset']%4,0)
            for domain,index in (('storage',p['storage_index']),('image',p['image_coordinate'])):
                first,size=p[domain+'_offset'],p[domain+'_bytes']
                self.assertLessEqual(first+size,8192)
                self.assertLessEqual(4*index+4,size)
            if p['layout']=='endpoint':self.assertEqual(p['target_offset'],8188)
            if p['layout']=='shifted':self.assertNotEqual(p['storage_offset'],p['image_offset'])
            for invocation in range(8):
                self.assertEqual(predecessors[2*invocation:2*invocation+2],(0,1<<(2*invocation)))
                self.assertEqual(domains[2*invocation:2*invocation+2],
                    ('storage','image') if p['order']==ORDERS[0] else ('image','storage'))
            for event,domain in zip(events,domains):seen[domain].add(event['operation'])
        expected={'exchange','add','xor','or','and','signed-min','unsigned-min',
                  'signed-max','unsigned-max','compare-swap'}
        self.assertEqual(seen,dict(image=expected,storage=expected))

    def test_cas_winner_identity_and_cross_resource_program_order_remain_observable(self):
        for order in ORDERS:
            case='mixed-atomic-r32ui-cas-pair-'+order+'-shifted'
            events,predecessors,domains=operations(case)
            old=[7]*16;old[12]=0
            self.assertEqual(len(linearization(0,7,events,old,predecessors)),16)
            changed=list(old);changed[12],changed[14]=changed[14],changed[12]
            with self.assertRaises(ValueError):linearization(0,7,events,changed,predecessors)
            unordered=[0,0]+[1]*14
            self.assertEqual(len(linearization(0,1,events,unordered)),16)
            with self.assertRaises(ValueError):linearization(0,1,events,unordered,predecessors)

    def test_each_original_shader_uses_both_alias_clients_and_two_unique_old_value_slots(self):
        with tempfile.TemporaryDirectory(prefix='mixed-atomic-sources-') as temporary:
            out=Path(temporary);write_sources(out)
            self.assertEqual(len(list(out.glob('*.comp'))),66)
            for case in CASES:
                p=parameters(case);text=(out/p['source']).read_text()
                self.assertEqual(text.count('=imageAtomic'),1)
                self.assertEqual(text.count('=atomic'),1)
                self.assertIn('target.data[%du]'%p['storage_index'],text)
                self.assertIn('(original,%d,'%p['image_coordinate'],text)
                self.assertIn('observed.data[o+0u]=',text)
                self.assertIn('observed.data[o+1u]=',text)
                self.assertIn('binding=0',text);self.assertIn('binding=3',text)
                self.assertEqual(text.count('memoryBarrierBuffer();memoryBarrierImage();'),2)
                if p['format']=='r32f':
                    self.assertIn('coherent buffer Target {uint data[];}',text)
                    self.assertIn('uintBitsToFloat(',text);self.assertIn('floatBitsToUint(',text)
                for control in ('if(','for(','while('):self.assertNotIn(control,text)
        for case in ('image-contended-buffer-r32ui-cas-pair',None,True):
            with self.assertRaises(ValueError):parameters(case)

    def test_finite_float_image_and_uint_storage_exchanges_share_exact_old_bit_patterns(self):
        old=[0x3f800000,0x3f400000,0x40000000,0x3f400001,0x40000011,0x3f400002,
             0x40000022,0x3f400003,0x40000033,0x3f400004,0x40000044,0x3f400005,
             0x40000055,0x3f400006,0x40000066,0x3f400007]
        for order in ORDERS:
            for layout in VIEWS:
                case='mixed-atomic-r32f-swap-swap-'+order+'-'+layout
                events,predecessors,domains=operations(case)
                self.assertEqual(len(linearization(0x3f800000,0x40000077,events,old,predecessors)),16)
                changed=list(old);changed[2],changed[4]=changed[4],changed[2]
                with self.assertRaises(ValueError):linearization(0x3f800000,0x40000077,events,changed,predecessors)


if __name__=='__main__':unittest.main()
