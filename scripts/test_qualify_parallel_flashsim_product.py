#!/usr/bin/env python3
"""Exercise independent-scene provenance and the stored-image gate."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash
import qualify_parallel_flashsim_product as parallel
import record_parallel_flashsim_product as recorder


def metric(name):
    return dict(scene=name, frameWidth=32, frameHeight=32, trafficDrained=True,
                serviceCycles=3, gpuCycles=4, imageVerificationRequired=True,
                clockQualified=False, fpsQualified=False, phaseWorkerCycles=[0] * 6)


class ParallelQualificationTest(unittest.TestCase):
    def test_cli_passes_conservative_alias_to_both_runners(self):
        argv = ['qualify-parallel', '--smoke', '--prepared-output', str(self.current),
                '--original-output', str(self.old), '--witness', str(self.witness_path),
                '--extra-record', str(self.extra_path), '--output', str(self.extra / 'qualified'),
                '--flashsim-dir', self.temp.name, '--conservative-alias', '--api-capacity',
                '--original-driver-checkout', str(self.old)]
        with patch.object(sys, 'argv', argv), patch.object(parallel, 'verify') as verify:
            parallel.main()
        current, original = verify.call_args.args[:2]
        self.assertTrue(current.conservative_alias)
        self.assertTrue(original.conservative_alias)
        self.assertTrue(current.api_capacity)
        self.assertTrue(original.api_capacity)
        self.assertEqual(current.driver_root, flash.ROOT)
        self.assertEqual(original.driver_root, self.old.resolve())

    def test_rejects_different_api_capacity_profiles(self):
        self.new.api_capacity = True
        with self.assertRaisesRegex(RuntimeError, 'profiles differ'):
            parallel.guard_model(self.new, self.original)

    def test_recorder_cli_passes_original_build_options(self):
        argv = ['record-parallel', 'extra', '--smoke', '--prepared-output', str(self.current),
                '--original-output', str(self.old), '--output', str(self.extra / 'new'),
                '--scene', common.SCENES[0], '--flashsim-dir', self.temp.name,
                '--conservative-alias', '--api-capacity', '--original-driver-checkout', str(self.old)]
        with patch.object(sys, 'argv', argv), patch.object(recorder, 'run_extra') as run:
            recorder.main()
        current, original = run.call_args.args[:2]
        self.assertTrue(current.conservative_alias and original.conservative_alias)
        self.assertTrue(current.api_capacity and original.api_capacity)
        self.assertEqual(current.driver_root, flash.ROOT)
        self.assertEqual(original.driver_root, self.old.resolve())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name).resolve()
        self.current, self.old, self.extra = root / 'current', root / 'old', root / 'extra'
        manifest = dict(version=1, product=False, frameWidth=32, frameHeight=32,
                        computeUnits=4, lanes=16, warps=8, axiDataBytes=32,
                        rtlEmitted=True, scenes=common.SCENES)
        for directory, source in ((self.current, 'new'), (self.old, 'old')):
            (directory / 'rtl').mkdir(parents=True); (directory / 'fixtures').mkdir()
            (directory / 'rtl' / 'top.sv').write_text('RTL')
            (directory / 'fixtures' / 'fixture').write_text('input')
            common.write_json(directory / 'fixture-manifest.json', manifest)
            common.write_json(directory / 'preparation.json', dict(
                exit_code=0, source_sha256={'src': source},
                rtl_sha256=common.tree_hashes(directory / 'rtl'),
                fixture_sha256=common.tree_hashes(directory / 'fixtures'),
                manifest_sha256=common.sha(directory / 'fixture-manifest.json')))
        self.new = flash.Runner(self.current, False, root, scene_jobs=2)
        self.original = flash.Runner(self.old, False, root, scene_jobs=2)
        (self.old / 'build.json').write_text('build')
        (self.old / 'native_product_driver').write_text('executable')
        self.build = {'context': {'model': 'pinned'}}
        for p in (patch.object(common, 'rtl_inputs', return_value={'src': 'new'}),
                  patch.object(self.original, 'checked_build', return_value=self.build)):
            p.start(); self.addCleanup(p.stop)
        self.run = self.old / 'runs' / 'one'; self.run.mkdir(parents=True)
        self.extra.mkdir(); self.witness_path = self.extra / 'first-two-witness.json'
        self.extra_path = self.extra / 'extra-execution.json'
        identity = dict(preparation_sha256=common.sha(self.old / 'preparation.json'),
                        build_sha256=common.sha(self.old / 'build.json'),
                        executable_sha256=common.sha(self.old / 'native_product_driver'))
        first, later = {}, {}
        for i, name in enumerate(common.SCENES):
            target = (self.run if i < 2 else self.extra) / 'scenes' / name
            target.mkdir(parents=True); row = metric(name)
            (target / 'execution.log').write_text(json.dumps(row) + '\n')
            (target / (name + '.memory')).write_text(name)
            common.write_json(target / 'progress.json', dict(state='scene-complete', scene=name,
                              serviceCycles=row['serviceCycles'], gpuCycles=row['gpuCycles']))
            item = dict(metrics=row, files=common.tree_hashes(target))
            if i < 2:
                item['directory'] = str(target); first[name] = item
            else:
                item.update(exit_code=0, argv=[str(self.old / 'native_product_driver'),
                            str(self.old / 'fixtures'), str(target), '--scene', name])
                later[name] = item
        state = dict(stage='execute-six-scenes-parallel', product=False,
                     scene_execution=self.original.scene_execution, completed=list(first),
                     directory=str(self.run), children=[])
        common.write_json(self.witness_path, dict(status='pass', original=str(self.old),
            run_directory=str(self.run), controller_state=state,
            controller_state_sha256=hashlib.sha256((json.dumps(state, indent=2) + '\n').encode()).hexdigest(),
            scenes=first, **identity))
        prep = json.loads((self.old / 'preparation.json').read_text())
        model = dict(source_sha256=prep['source_sha256'], rtl_sha256=prep['rtl_sha256'],
                     fixture_sha256=prep['fixture_sha256'], build_context=self.build['context'], **identity)
        common.write_json(self.extra_path, dict(status='pass', original=str(self.old), model=model, scenes=later))

    def oracle(self, stage, command, log):
        images = [f'NATIVE_COMPILED_IMAGE id={name} frame=32x32 exactStoredImagesAndGuards=true'
                  for name in common.SCENES]
        final = ('NATIVE_COMPILED_PRODUCT PASS scenes=6 fullExecutionAndStorage=true '
                 'exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false')
        log.write_text('\n'.join([*images, final]))

    def guard(self):
        return parallel.guard_batches(self.new, self.original, self.witness_path, self.extra_path)

    def test_combines_completed_fresh_model_scenes_and_checks_images(self):
        output = Path(self.temp.name) / 'qualified'
        with (self.old / '.lock').open('w') as lock, patch.object(self.new, 'execute', side_effect=self.oracle):
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            parallel.verify(self.new, self.original, self.witness_path, self.extra_path, output)
        result = json.loads((output / 'qualification-parallel.json').read_text())
        self.assertTrue(result['passed'] and result['exact_stored_images_and_guards'])
        self.assertFalse(result['FPS_qualified'])
        self.assertEqual(result['source_sha256'], {'src': 'new'})
        self.assertEqual([s['scene'] for s in result['scenes']], common.SCENES)
        self.assertEqual(result['scene_execution']['mode'], 'independently-orchestrated-fresh-models')
        self.assertEqual(result['scene_execution']['original_scenes'], common.SCENES[:2])
        self.assertFalse((self.old / 'execution.json').exists())

    def test_rejects_changed_model_outputs_and_controller_record(self):
        paths = [self.old / 'rtl' / 'top.sv', self.old / 'fixtures' / 'fixture',
                 self.old / 'build.json', self.old / 'native_product_driver',
                 self.run / 'scenes' / common.SCENES[0] / (common.SCENES[0] + '.memory'),
                 self.extra / 'scenes' / common.SCENES[2] / 'execution.log']
        for path in paths:
            with self.subTest(path=path):
                old = path.read_bytes(); path.write_bytes(old + b'!')
                with self.assertRaises(RuntimeError): self.guard()
                path.write_bytes(old)
        for path, mutate in [
                (self.witness_path, lambda d: d['controller_state']['completed'].clear()),
                (self.extra_path, lambda d: d['scenes'][common.SCENES[2]]['argv'].append('extra')),
                (self.extra_path, lambda d: d['model']['source_sha256'].update({'src': 'changed'}))]:
            with self.subTest(record=path):
                original = path.read_text(); data = json.loads(original); mutate(data); common.write_json(path, data)
                with self.assertRaises(RuntimeError): self.guard()
                path.write_text(original)
        original = self.witness_path.read_text(); data = json.loads(original)
        data['controller_state']['children'] = [{'scene': common.SCENES[0]}]
        encoded = json.dumps(data['controller_state'], indent=2) + '\n'
        data['controller_state_sha256'] = hashlib.sha256(encoded.encode()).hexdigest()
        common.write_json(self.witness_path, data)
        with self.assertRaisesRegex(RuntimeError, 'completed scenes'):
            self.guard()
        self.witness_path.write_text(original)

    def test_rejects_partial_or_changed_oracle_inputs(self):
        memory = self.run / 'scenes' / common.SCENES[0] / (common.SCENES[0] + '.memory')
        original = memory.read_bytes()
        def changed(stage, command, log):
            self.oracle(stage, command, log)
            memory.write_text('changed')
        output = Path(self.temp.name) / 'changed'
        with patch.object(self.new, 'execute', side_effect=changed), self.assertRaises(RuntimeError):
            parallel.verify(self.new, self.original, self.witness_path, self.extra_path, output)
        self.assertFalse((output / 'qualification-parallel.json').exists())
        memory.write_bytes(original)
        output = Path(self.temp.name) / 'partial'
        with patch.object(self.new, 'execute', side_effect=lambda stage, command, log: log.write_text('partial')):
            with self.assertRaisesRegex(RuntimeError, 'verification is missing'):
                parallel.verify(self.new, self.original, self.witness_path, self.extra_path, output)
        self.assertFalse((output / 'qualification-parallel.json').exists())

    def test_refuses_to_write_into_input_directories(self):
        with self.assertRaisesRegex(RuntimeError, 'separate'):
            parallel.verify(self.new, self.original, self.witness_path, self.extra_path, self.old / 'new')

    def test_records_only_controller_completed_original_scenes(self):
        witness = json.loads(self.witness_path.read_text())
        common.write_json(self.old / 'state.json', witness['controller_state'])
        output = self.extra / 'recorded-witness.json'
        recorder.witness_original(self.new, self.original, self.run, output, common.SCENES[:2])
        result = parallel.guard_batches(self.new, self.original, output, self.extra_path)
        self.assertEqual([row['scene'] for row in result['scenes']], common.SCENES)
        state = witness['controller_state']; state['completed'].pop()
        common.write_json(self.old / 'state.json', state)
        with self.assertRaisesRegex(RuntimeError, 'not completed'):
            recorder.witness_original(self.new, self.original, self.run, self.extra / 'rejected.json', common.SCENES[:2])

    def test_runs_real_separate_processes_and_records_immutable_outputs(self):
        executable = self.old / 'native_product_driver'
        executable.write_text('''#!/usr/bin/env python3
import json, pathlib, sys
name = sys.argv[4]; target = pathlib.Path(sys.argv[2])
row = dict(scene=name, frameWidth=32, frameHeight=32, trafficDrained=True,
           serviceCycles=3, gpuCycles=4, imageVerificationRequired=True,
           clockQualified=False, fpsQualified=False, phaseWorkerCycles=[0]*6)
(target / (name + ".memory")).write_text(name)
(target / "progress.json").write_text(json.dumps(dict(state="scene-complete", scene=name, serviceCycles=3, gpuCycles=4)))
print(json.dumps(row))
''')
        executable.chmod(0o755)
        output = Path(self.temp.name) / 'fresh-extra'
        recorder.run_extra(self.new, self.original, output, common.SCENES[2:], jobs=2)
        witness = json.loads(self.witness_path.read_text())
        witness['executable_sha256'] = common.sha(executable); common.write_json(self.witness_path, witness)
        result = parallel.guard_batches(self.new, self.original, self.witness_path, output / 'extra-execution.json')
        self.assertEqual([row['scene'] for row in result['scenes']], common.SCENES)
        self.assertEqual(json.loads((output / 'state.json').read_text())['stage'], 'complete')

    def test_failed_scene_stops_only_its_batch_and_keeps_no_success_record(self):
        executable = self.old / 'native_product_driver'
        executable.write_text('''#!/usr/bin/env python3
import os, pathlib, sys, time
target = pathlib.Path(sys.argv[2]); name = sys.argv[4]
(target / "pid").write_text(str(os.getpid()))
if name == "opaque-overdraw":
    deadline = time.monotonic() + 5
    while not (target.parent / "transparent-layers" / "pid").exists() and time.monotonic() < deadline:
        time.sleep(0.01)
    sys.exit(2)
time.sleep(30)
''')
        executable.chmod(0o755)
        output = Path(self.temp.name) / 'failed-extra'; started = time.monotonic()
        with self.assertRaisesRegex(RuntimeError, 'failed with exit'):
            recorder.run_extra(self.new, self.original, output, common.SCENES[2:4], jobs=2)
        self.assertLess(time.monotonic() - started, 10)
        self.assertFalse((output / 'extra-execution.json').exists())
        state = json.loads((output / 'state.json').read_text())
        self.assertEqual(state['stage'], 'stopped-for-audit')
        other_pid = int((output / 'scenes' / 'transparent-layers' / 'pid').read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(other_pid, 0)


if __name__ == '__main__':
    unittest.main()
