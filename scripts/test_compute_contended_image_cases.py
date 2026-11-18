#!/usr/bin/env python3
"""Source/event identity checks only; actual Mesa and DUT gates are separate."""
from pathlib import Path
import tempfile
import unittest
from compute_atomic_order import linearization
from compute_contended_image_cases import CASES, TARGETS, PAIRS, parameters, steps, operations, coordinate, write_sources


class ContendedImageSources(unittest.TestCase):
    def test_complete_target_format_operation_and_order_contract(self):
        self.assertEqual(len(CASES),55)
        seen=set()
        for case in CASES:
            p=parameters(case);events,predecessors=operations(case);count=len(steps(p))
            self.assertEqual((p['groups'],p['local'],p['bytes']),(2,4,4))
            self.assertEqual(len(events),8*count)
            self.assertEqual(len(predecessors),len(events))
            self.assertLessEqual(len(events),16)
            for invocation in range(8):
                self.assertEqual(predecessors[invocation*count],0)
                if count==2:self.assertEqual(predecessors[invocation*count+1],1<<(invocation*count))
            seen.update(event['operation'] for event in events)
            if p['pair']=='and-min':
                self.assertEqual(events[1]['operation'],'signed-min' if p['format']=='r32i' else 'unsigned-min')
            if p['pair']=='max-cas':
                self.assertEqual(events[1]['compare'],events[0]['operand'])
        self.assertEqual(seen,{'exchange','add','xor','or','and','signed-min','unsigned-min',
                              'signed-max','unsigned-max','compare-swap'})

    def test_cas_invocation_winner_and_per_invocation_order_are_observable(self):
        case='image-contended-buffer-r32ui-cas-pair'
        events,predecessors=operations(case)
        old=[6]*16;old[10]=0
        self.assertEqual(len(linearization(0,6,events,old,predecessors)),16)
        changed=list(old);changed[10],changed[12]=changed[12],changed[10]
        with self.assertRaises(ValueError):linearization(0,6,events,changed,predecessors)
        changed=list(old);changed[10],changed[11]=changed[11],changed[10]
        with self.assertRaises(ValueError):linearization(0,6,events,changed,predecessors)

    def test_original_texel_is_shared_and_each_returned_value_has_its_own_output(self):
        with tempfile.TemporaryDirectory(prefix='contended-image-source-') as temporary:
            out=Path(temporary);write_sources(out)
            self.assertEqual(len(list(out.glob('*.comp'))),55)
            for case in CASES:
                p=parameters(case);source=(out/p['source']).read_text()
                self.assertNotIn('g',coordinate(p))
                self.assertIn('int(g)',coordinate(p,True))
                self.assertEqual(source.count('=imageAtomic'),len(steps(p)))
                self.assertIn('imageStore(destination,%s,'%coordinate(p,True),source)
                self.assertIn('coherent uniform',source)
                self.assertNotIn('while(',source)
                self.assertNotIn('if(',source)
        for case in ('image-atomic-buffer-r32ui',None,True):
            with self.assertRaises(ValueError):parameters(case)


if __name__=='__main__':
    unittest.main()
