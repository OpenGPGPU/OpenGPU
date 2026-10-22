#!/usr/bin/env python3
"""Protect resource attribution when fragment workers differ from physical CUs."""
import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT=Path(__file__).with_name('analyze_shared_graphics_trace.py')

class TraceLayoutTest(unittest.TestCase):
    def analyze(self,cus,workers,phases,*,legacy=False,historical=False,banks=None,complete=True,duration=5):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);prefix=f'cu-{cus}'
            stride=4672 if historical else 320
            count=2*workers if banks is None else banks
            config=dict(cus=cus,fragment_workers=workers,axi_data_bytes=32,side=2,alu_loops=8,
                lanes=4,warps=2,bank_count=count,bank_stride=stride,kernarg=0x10000,
                color=0x30000,depth=0x40000,commands=0x2000,program=0x4000,descriptor=0x1000,
                pixel_checks_passed=complete,shader_words=['30500073'])
            if legacy:config.pop('fragment_workers')
            if not historical:
                (root/f'{prefix}-metadata.json').write_text(json.dumps(config))
                (root/f'{prefix}-pixels.csv').write_text('x,y,color_hex,expected_color_hex,depth,expected_depth\n0,0,0,0,0,0\n')
            with (root/f'{prefix}-cycles.csv').open('w') as stream:
                writer=csv.writer(stream);writer.writerow(['start_cycle','duration','events_hex'])
                for i,values in enumerate(phases):
                    bits=sum(n<<(8*j) for j,n in enumerate(values))
                    writer.writerow([i*duration,duration,f'{bits:x}'])
            (root/f'{prefix}-memory.csv').write_text(
                f'cycle,operation,address_hex\n0,write,{0x10000+(count-1)*stride:x}\n')
            return subprocess.run([sys.executable,str(SCRIPT),str(root)],capture_output=True,text=True)

    def test_one_cu_four_workers_attributes_all_scratch_banks(self):
        result=self.analyze(1,4,[(1,1,0,2,0,0)])
        self.assertEqual(result.returncode,0,result.stderr)
        row=json.loads(result.stdout)[0]
        self.assertEqual((row['cus'],row['fragment_workers']),(1,4))
        self.assertEqual(row['phase_worker_cycles'],[5,5,0,10,0,0])
        self.assertEqual(row['lower_memory_transactions'],{'staging_or_shader_data_write':1})

    def test_old_metadata_and_historical_traces_keep_cu_sized_workers(self):
        for flags in ({'legacy':True},{'historical':True}):
            with self.subTest(flags=flags):
                result=self.analyze(2,2,[(1,0,0,1,0,0)],**flags)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual(json.loads(result.stdout)[0]['fragment_workers'],2)

    def test_each_interval_must_account_for_the_declared_workers(self):
        # These intervals average four workers. Aggregate totals alone would
        # accept them, but neither interval represents a legal four-worker state.
        result=self.analyze(1,4,[(0,0,0,5,0,0),(0,0,0,3,0,0)])
        self.assertNotEqual(result.returncode,0)
        self.assertIn('Each fragment worker',result.stderr)

    def test_bank_layout_cannot_silently_follow_the_cu_count(self):
        result=self.analyze(1,4,[(0,0,0,4,0,0)],banks=2)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('Scratch bank count',result.stderr)

    def test_unfinished_pixel_checks_cannot_be_reported_as_complete(self):
        result=self.analyze(1,4,[(0,0,0,4,0,0)],complete=False)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('Incomplete pixel qualification',result.stderr)

    def test_nonpositive_intervals_are_rejected(self):
        for duration in (0,-1):
            with self.subTest(duration=duration):
                result=self.analyze(1,4,[(0,0,0,4,0,0)],duration=duration)
                self.assertNotEqual(result.returncode,0)
                self.assertIn('positive duration',result.stderr)

    def test_empty_cycle_evidence_is_rejected(self):
        result=self.analyze(1,4,[])
        self.assertNotEqual(result.returncode,0)
        self.assertIn('Empty cycle trace',result.stderr)

if __name__=='__main__':unittest.main()
