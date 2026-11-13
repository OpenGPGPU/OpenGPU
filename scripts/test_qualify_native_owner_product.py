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
            imageVerificationRequired=True, clockQualified=False, fpsQualified=False)
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
