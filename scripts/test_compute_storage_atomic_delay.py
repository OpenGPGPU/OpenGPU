#!/usr/bin/env python3
"""Reject premature, misbound and fictitious delayed storage acknowledgments."""
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest
from compute_storage_atomic_cases import CASES, parameters
from compute_storage_atomic_delay import SCHEMA, GLOBAL_SCHEMA, plan_bytes, check_report
from compute_global_atomic_cases import CASES as GLOBAL_CASES, parameters as global_parameters
from compute_global_atomic_source import SCHEMA as GLOBAL_SOURCE_SCHEMA

ROOT = Path(__file__).resolve().parents[1]


def description(case=CASES[0]):
    return dict(sourceCase=case, targetOffset=parameters(case)['target_offset'],
        target=dict(allocationPhysical=0x1000, allocationBytes=8192))


def report(case=CASES[0]):
    _, first, end, cycles = struct.unpack('<8sQQI', plan_bytes(description(case)))
    return dict(schema=SCHEMA, commandId=42, targetLineStart=first, targetLineEnd=end,
        requestedHoldCycles=cycles, heldWriteCycles=41, statusBeforeRelease=0, externalDrained=True)


def global_description(case=GLOBAL_CASES[0]):
    return dict(schema=GLOBAL_SOURCE_SCHEMA,sourceCase=case,groups=4,local=16,viewBytes=8192,guard=0xdeadbeef,context=1,
        kernarg=dict(handle=2,allocationPhysical=0x1000,allocationBytes=8192,offset=0,bytes=8192))


def global_report(case=GLOBAL_CASES[0]):
    _,first,end,hold,old_first,old_end,guard=struct.unpack('<8sQQIQQI',plan_bytes(global_description(case)))
    return dict(schema=GLOBAL_SCHEMA,commandId=43,targetLineStart=first,targetLineEnd=end,requestedHoldCycles=hold,
        heldWriteCycles=261,statusBeforeRelease=0,externalDrained=True,oldOutputStart=old_first,
        oldOutputEnd=old_end,oldOutputGuard=guard,oldValuesHeldUntilAck=True)


class StorageAckDelayTests(unittest.TestCase):
    def test_all_original_global_targets_and_old_output_windows_are_exact(self):
        for case in GLOBAL_CASES:
            magic,first,end,hold,old_first,old_end,guard=struct.unpack('<8sQQIQQI',plan_bytes(global_description(case)))
            self.assertEqual((magic,end-first,hold,old_first,old_end,guard),
                             (b'OGCGACK1',64,257,0x1080,0x10a0,0xdeadbeef))
            offset=global_parameters(case)['target_offset']
            self.assertLessEqual(first,0x1000+offset)
            self.assertLess(0x1000+offset+3,end)
            check_report(global_report(case),global_description(case),43)

    def test_global_foreign_owners_early_returns_and_short_hold_are_rejected(self):
        for key,value in (('handle',3),('allocationPhysical',0),('allocationPhysical',0x1001),
                          ('allocationPhysical',0xfffff000),('offset',4),('bytes',8188)):
            d=global_description();d['kernarg'][key]=value
            with self.assertRaises(ValueError):plan_bytes(d)
        for key,value in (('schema',SCHEMA),('oldOutputStart',0x1084),('oldOutputEnd',0x10a4),
                          ('oldOutputGuard',0),('oldValuesHeldUntilAck',False),('requestedHoldCycles',37),
                          ('heldWriteCycles',256),('statusBeforeRelease',2),('commandId',42),('externalDrained',False)):
            r=global_report();r[key]=value
            with self.assertRaises(ValueError):check_report(r,global_description(),43)
        for key in global_report():
            r=global_report();del r[key]
            with self.assertRaises(ValueError):check_report(r,global_description(),43)
    def test_exact_original_word_and_endpoint_lines(self):
        for case in CASES:
            p = parameters(case)
            if p['result_status']:
                with self.assertRaises(ValueError):
                    plan_bytes(description(case))
                continue
            magic, first, end, cycles = struct.unpack('<8sQQI', plan_bytes(description(case)))
            self.assertEqual((magic, end-first, cycles), (b'OGCSACK1', 64, 37))
            self.assertLessEqual(first, 0x1000+p['target_offset'])
            self.assertLess(0x1000+p['target_offset']+3, end)
            check_report(report(case), description(case), 42)
        self.assertEqual(struct.unpack('<8sQQI', plan_bytes(description(CASES[-1])))[1], 0x2fc0)

    def test_wrong_or_unbounded_original_view_is_rejected(self):
        for key, value in (('allocationPhysical', True), ('allocationPhysical', 0),
                ('allocationPhysical', 0x1001), ('allocationPhysical', 0xfffff000),
                ('allocationBytes', 4096)):
            data = description()
            data['target'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                plan_bytes(data)
        data = description()
        data['targetOffset'] = 4
        with self.assertRaises(ValueError):
            plan_bytes(data)

    def test_wrong_receipt_or_premature_completion_never_passes(self):
        mutations = dict(schema='wrong', commandId=43, targetLineStart=0x1040,
            targetLineEnd=0x1080, requestedHoldCycles=36, heldWriteCycles=36,
            statusBeforeRelease=2, externalDrained=False)
        for key, value in mutations.items():
            data = report()
            data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                check_report(data, description(), 42)
        for key in report():
            data = report()
            del data[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                check_report(data, description(), 42)
        for key in ('commandId', 'requestedHoldCycles', 'heldWriteCycles', 'statusBeforeRelease'):
            for value in (True, -1, '41', 1 << 64):
                data = report()
                data[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    check_report(data, description(), 42)
        for extra in ({'foreign': 0}, {'heldWriteCycles': 4097}):
            data = dict(report(), **extra)
            with self.assertRaises(ValueError):
                check_report(data, description(), 42)

    def test_actual_native_gate_and_rejection_paths_with_sanitizers(self):
        with tempfile.TemporaryDirectory(prefix='storage-ack-') as temporary:
            executable = Path(temporary) / 'guard'
            subprocess.run([*shlex.split(os.environ.get('CXX', 'c++')), '-std=c++17', '-O1',
                '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                str(ROOT/'scripts/test_compute_storage_atomic_delay.cpp'), '-o', str(executable)], check=True)
            result = subprocess.run([str(executable)], check=True, capture_output=True, text=True)
            lines = result.stdout.splitlines()
            self.assertEqual(lines[-1], 'PASS storage ACK guard negatives and real AXI gate')
            check_report(json.loads(lines[0]), description(), 42)
            check_report(json.loads(lines[1]),global_description(),43)


if __name__ == '__main__':
    unittest.main()
