#!/usr/bin/env python3
"""CPU-only stale-output guards for equivalent FlashSim qualification."""
import json
import fcntl
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash
import qualify_equivalent_flashsim_product as equivalent


def metrics():
    return [dict(scene=name, frameWidth=32, frameHeight=32, trafficDrained=True,
                 serviceCycles=1, gpuCycles=1, imageVerificationRequired=True,
                 clockQualified=False, fpsQualified=False, phaseWorkerCycles=[0]*6)
            for name in common.SCENES]


class EquivalentQualificationTest(unittest.TestCase):
    def test_cli_passes_conservative_alias_to_both_runners(self):
        argv = ['qualify-equivalent', '--smoke', '--prepared-output', str(self.current),
                '--execution-output', str(self.old), '--flashsim-dir', self.temp.name,
                '--conservative-alias']
        with patch.object(sys, 'argv', argv), patch.object(equivalent, 'verify') as verify:
            equivalent.main()
        current, original = verify.call_args.args[:2]
        self.assertTrue(current.conservative_alias)
        self.assertTrue(original.conservative_alias)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.current, self.old = root/'current', root/'old'
        manifest = dict(version=1, product=False, frameWidth=32, frameHeight=32,
                        computeUnits=4, lanes=16, warps=8, axiDataBytes=32,
                        rtlEmitted=True, scenes=common.SCENES)
        for directory, source in ((self.current, 'new'), (self.old, 'old')):
            (directory/'rtl').mkdir(parents=True); (directory/'fixtures').mkdir()
            (directory/'rtl'/'top.sv').write_text('RTL')
            (directory/'fixtures'/'fixture').write_text('scene input')
            common.write_json(directory/'fixture-manifest.json', manifest)
            common.write_json(directory/'preparation.json', dict(exit_code=0,
                source_sha256={'src': source}, rtl_sha256=common.tree_hashes(directory/'rtl'),
                fixture_sha256=common.tree_hashes(directory/'fixtures'),
                manifest_sha256=common.sha(directory/'fixture-manifest.json')))
        self.new = flash.Runner(self.current, False, root, scene_jobs=2)
        self.original = flash.Runner(self.old, False, root, scene_jobs=2)
        (self.old/'build.json').write_text('immutable build')
        (self.old/'native_product_driver').write_text('immutable executable')
        self.run = self.old/'runs'/'completed'; self.run.mkdir(parents=True)
        for name in common.SCENES: (self.run/(name+'.memory')).write_text(name)
        common.write_json(self.old/'execution.json', dict(exit_code=0,
            simulator_backend='flashsim', directory=str(self.run), scenes=metrics(),
            scene_execution=self.original.scene_execution, output_sha256=common.tree_hashes(self.run),
            executable_sha256=common.sha(self.old/'native_product_driver'),
            preparation_sha256=common.sha(self.old/'preparation.json'),
            build_sha256=common.sha(self.old/'build.json')))
        self.patches = [patch.object(common, 'rtl_inputs', return_value={'src':'new'}),
                        patch.object(self.original, 'checked_build', return_value={})]
        for p in self.patches: p.start(); self.addCleanup(p.stop)

    def oracle(self, stage, command, log):
        images = [f'NATIVE_COMPILED_IMAGE id={name} frame=32x32 exactStoredImagesAndGuards=true'
                  for name in common.SCENES]
        final = ('NATIVE_COMPILED_PRODUCT PASS scenes=6 fullExecutionAndStorage=true '
                 'exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false')
        log.write_text('\n'.join([*images, final]))

    def test_verified_equivalence_keeps_original_execution_and_current_source(self):
        original = (self.old/'execution.json').read_bytes()
        with patch.object(self.new, 'execute', side_effect=self.oracle):
            equivalent.verify(self.new, self.original)
        result = json.loads((self.current/'qualification-equivalent.json').read_text())
        self.assertTrue(result['passed'] and result['exact_stored_images_and_guards'])
        self.assertFalse(result['FPS_qualified'])
        self.assertEqual(result['source_sha256'], {'src':'new'})
        self.assertEqual(result['original_preparation_sha256'], common.sha(self.old/'preparation.json'))
        self.assertEqual((self.old/'execution.json').read_bytes(), original)
        self.assertEqual(self.original.checked_build.call_count, 2)
        with self.assertRaisesRegex(RuntimeError, 'already exists'):
            equivalent.verify(self.new, self.original)

    def test_rejects_changed_inputs_and_execution(self):
        paths = [self.old/'rtl'/'top.sv', self.old/'fixtures'/'fixture',
                 self.current/'rtl'/'top.sv', self.old/'runs'/'completed'/
                 (common.SCENES[0]+'.memory'), self.old/'build.json',
                 self.old/'native_product_driver']
        for path in paths:
            with self.subTest(path=path):
                previous = path.read_bytes(); path.write_bytes(previous+b'!')
                with self.assertRaises(RuntimeError): equivalent.guard_equivalence(self.new,self.original)
                path.write_bytes(previous)
        record = json.loads((self.old/'preparation.json').read_text())
        record['fixture_sha256'] = {'fixture':'different'}
        common.write_json(self.old/'preparation.json',record)
        with self.assertRaisesRegex(RuntimeError, 'Preparations differ'):
            equivalent.guard_equivalence(self.new,self.original)

    def test_rejects_change_during_oracle_without_qualification(self):
        def changed(stage, command, log):
            self.oracle(stage,command,log)
            (self.run/(common.SCENES[0]+'.memory')).write_text('changed')
        with patch.object(self.new,'execute',side_effect=changed):
            with self.assertRaisesRegex(RuntimeError, 'outputs changed'):
                equivalent.verify(self.new,self.original)
        self.assertFalse((self.current/'qualification-equivalent.json').exists())

    def test_rejects_incomplete_scenes_and_failed_oracle(self):
        path = self.old/'execution.json'
        original = path.read_text(); record = json.loads(original)
        record['scenes'][0]['trafficDrained'] = False; common.write_json(path, record)
        with self.assertRaisesRegex(RuntimeError, 'Incomplete'):
            equivalent.guard_equivalence(self.new,self.original)
        path.write_text(original)
        with patch.object(self.new,'execute',side_effect=lambda stage, command, log: log.write_text('partial')):
            with self.assertRaisesRegex(RuntimeError, 'verification is missing'):
                equivalent.verify(self.new,self.original)
        self.assertFalse((self.current/'qualification-equivalent.json').exists())

    def test_live_original_lock_cannot_be_requalified(self):
        argv = ['verify', '--smoke', '--prepared-output', str(self.current),
                '--execution-output', str(self.old), '--flashsim-dir', str(self.old),
                '--scene-jobs', '2']
        with (self.old/'.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with patch.object(sys,'argv',argv), self.assertRaises(BlockingIOError):
                equivalent.main()
        self.assertFalse((self.current/'qualification-equivalent.json').exists())


if __name__ == '__main__':
    unittest.main()
