#!/usr/bin/env python3
"""CPU-only stale/incomplete owner product qualification rejection checks."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import qualify_native_owner_product as q


class OwnerProductTests(unittest.TestCase):
    def rows(self, product):
        width, height = q.common.frame_size(product, True)
        return [dict(scene=name, profile=q.owner.PROFILE, computeUnits=2, nativeEngines=4,
            nativeMaxWidth=2048, nativeMaxHeight=2048, resourceDiscoveryWords=96,
            computeFeatureFlags=0x3ffff, frameWidth=width, frameHeight=height,
            samples=4 if name=='msaa-4x' else 1, serviceCycles=100, gpuCycles=180,
            readBusBytes=32, writeBusBytes=64, enabledWriteBytes=48,
            phaseWorkerCycles=[0,10,20,30,40,50], trafficDrained=True,
            imageVerificationRequired=True, clockQualified=False, fpsQualified=False,
            cycleScopeVersion=2,serviceCycleScope='post-submit-to-irq',
            frameCycleScope='reset-through-verified-retirement',frameCycles=250,
            gpuCyclesAtServiceBegin=40,gpuCyclesAtIrq=140,
            submittedCommands=1,successfulCompletions=1,retiredCompletions=1,
            completionCountSource='observed-command-retirement',drawCompletionCountAvailable=False,
            primitiveCompletionCountAvailable=False,
            liveProfilePreflight=True,idleBeforeSubmit=True,shaderInputSource='native-product-hand-encoded',
            gnx=[0x474e5801,0,0,0,0,0,7,8,9,0,0,0],profileRegisters=[
                {'address':0,'value':2},
                {'address':0x60,'value':(1<<6)|(1<<22)|(1<<23)|(1<<25)|(1<<29)|(128<<8)},
                {'address':0x68,'value':0x160},{'address':0x320,'value':1048576},
                {'address':0x158,'value':16|(8<<8)|(32<<16)|(2<<24)}])
            for name in q.common.SCENES]

    def test_rejects_frozen_profiles_partial_frames_missing_traffic_and_early_fps(self):
        for product in (False, True):
            rows=self.rows(product); q.validate_rows(rows,product)
            for bad in (rows[:-1],rows[::-1],rows+rows[:1],self.rows(not product)):
                with self.assertRaises(RuntimeError): q.validate_rows(bad,product)
            for key,value in (('profile','two-cu-vga60-v2'),('resourceDiscoveryWords',72),
                ('computeFeatureFlags',0x7fff),('computeUnits',4),('nativeMaxWidth',640),
                ('serviceCycles',0),('gpuCycles',True),('enabledWriteBytes',-1),
                ('phaseWorkerCycles',[0]*5),('phaseWorkerCycles',[True]*6),
                ('samples',True),('trafficDrained',False),('clockQualified',True),('fpsQualified',True)):
                bad=copy.deepcopy(rows); bad[0][key]=value
                with self.subTest(product=product,key=key), self.assertRaises(RuntimeError):
                    q.validate_rows(bad,product)

    def test_rejects_partial_live_profile_fault_counts_and_cycle_intervals(self):
        for product in (False,True):
            rows=self.rows(product)
            for key,value in [('cycleScopeVersion',1),('cycleScopeVersion',True),
                ('serviceCycleScope','frame'),('frameCycleScope','post-submit-to-irq'),
                ('frameCycles',True),('frameCycles',180),('gpuCyclesAtIrq',141),
                ('gpuCyclesAtServiceBegin',41),('gpuCyclesAtIrq',2**64),
                ('submittedCommands',2),('successfulCompletions',False),('retiredCompletions',0),
                ('drawCompletionCountAvailable',True),('primitiveCompletionCountAvailable',True),
                ('completionCountSource','inferred-draw-count'),
                ('liveProfilePreflight',False),('idleBeforeSubmit',False),
                ('shaderInputSource','mesa'),('gnx',[]),('profileRegisters',[])]:
                bad=copy.deepcopy(rows);bad[0][key]=value
                with self.subTest(product=product,key=key),self.assertRaises(RuntimeError):
                    q.validate_rows(bad,product)
            for word in range(12):
                for bit in range(32):
                    bad=copy.deepcopy(rows);bad[0]['gnx'][word]^=1<<bit
                    with self.assertRaises(RuntimeError):q.validate_rows(bad,product)
            for index in range(5):
                for field,value in [('address',1),('value',0),('value',True),('value',2**32)]:
                    bad=copy.deepcopy(rows);bad[0]['profileRegisters'][index][field]=value
                    with self.assertRaises(RuntimeError):q.validate_rows(bad,product)
            for shader in (0,16|(8<<8)|(1<<16)|(2<<24),16|(8<<8)|(32<<16)|(1<<24)):
                bad=copy.deepcopy(rows);bad[0]['profileRegisters'][4]['value']=shader
                with self.assertRaises(RuntimeError):q.validate_rows(bad,product)

    def test_requires_every_independent_image_and_exact_profile_extent_and_tiling(self):
        parent=q.ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='owner-product-markers-',dir=parent) as d:
            path=Path(d)/'verify.log'
            for product in (False, True):
                width,height=q.common.frame_size(product,True)
                for log2 in (3,4):
                    lines=[f'NATIVE_OWNER_IMAGE scene={name} tile={1<<log2} frame={width}x{height} '
                           'exactImagesAndReadOnlyGuards=true' for name in q.common.SCENES]
                    lines+=[q.marker(product,log2)]
                    path.write_text('\n'.join(lines)+'\n');q.verify_marker(path,product,log2)
                    for bad in (lines[:-1],lines[1:],lines[::-1],lines+[lines[-1]]):
                        path.write_text('\n'.join(bad)+'\n')
                        with self.assertRaises(RuntimeError):q.verify_marker(path,product,log2)
                    path.write_text('\n'.join(lines)+'\n')
                    with self.assertRaises(RuntimeError):q.verify_marker(path,not product,log2)
                    with self.assertRaises(RuntimeError):q.verify_marker(path,product,7-log2)

    def test_shared_owner_requires_the_exact_prepared_source_and_original_lock(self):
        parent=q.sequence.temporary_root()
        with tempfile.TemporaryDirectory(prefix='owner-product-shared-',dir=parent) as d:
            root=Path(d);sibling=root/'sibling-worktree/.artifacts/owner';sibling.mkdir(parents=True)
            with patch.object(q.owner,'prepared',return_value={'CPU fixture':'not execution'}) as checked:
                self.assertEqual(q.sequence.checked_owner_output(sibling),sibling)
                checked.assert_called_once_with(sibling)
            with patch.object(q.owner,'prepared',side_effect=RuntimeError('different complete source/RTL/assertions')):
                with self.assertRaisesRegex(RuntimeError,'different complete source'):
                    q.sequence.checked_owner_output(sibling)
            with patch.object(q.owner,'prepared') as checked:
                for path in (root/'missing',root.parent.parent):
                    with self.assertRaisesRegex(RuntimeError,'inside this GPU repository'):
                        q.sequence.checked_owner_output(path)
                checked.assert_not_called()
            (sibling/'.lock').symlink_to(root/'unrelated-lock')
            with patch.object(q.owner,'prepared'):
                with self.assertRaisesRegex(RuntimeError,'lock must not be aliased'):
                    q.sequence.checked_owner_output(sibling)

    def test_failed_new_attempt_invalidates_old_success_before_preparation(self):
        parent=q.ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='owner-product-failed-',dir=parent) as d:
            out=Path(d)
            for kind,records in ((q.Runner,('execution.json','qualification.json')),
                                 (q.sequence.OwnerRunner,('sequence-execution.json','sequence-qualification.json'))):
                runner=kind(out,False,q.ROOT,api_capacity=True,two_cu_vga=True)
                for name in records:(out/name).write_text('{"passed":true}')
                with patch.object(runner,'prepared',side_effect=RuntimeError('stale source')):
                    with self.assertRaisesRegex(RuntimeError,'stale source'):runner.run()
                self.assertTrue(all(not (out/name).exists() for name in records))


if __name__=='__main__':unittest.main()
