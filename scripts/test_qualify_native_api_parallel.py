#!/usr/bin/env python3
"""Exercise parallel API orchestration with real, bounded child processes."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import qualify_native_api_compiled as api
import qualify_native_product_compiled as common


FAKE_DRIVER = r'''#!/usr/bin/env python3
import json, pathlib, sys, time
mode, fixtures, output, flag, scene = sys.argv[1:]
assert mode in ('--api-extent-product', '--smoke') and flag == '--scene'
fixtures, output = pathlib.Path(fixtures), pathlib.Path(output)
(fixtures / ('started-' + scene)).write_text('started')
if (fixtures / 'overlap').exists():
    deadline = time.monotonic() + 5
    while len(list(fixtures.glob('started-*'))) != 2:
        if time.monotonic() > deadline: sys.exit(88)
        time.sleep(.01)
if scene.endswith('1x') and (fixtures / 'fail-one').exists(): sys.exit(137)
time.sleep(.15 if scene.endswith('4x') else .05)
(output / (scene + '.memory')).write_bytes(scene.encode())
row = dict(scene=scene, frameWidth=640 if mode == '--api-extent-product' else 32,
           frameHeight=480 if mode == '--api-extent-product' else 32, samples=4 if scene.endswith('4x') else 1,
           serviceCycles=100, gpuCycles=180, trafficDrained=True, imageVerificationRequired=True,
           phaseWorkerCycles=[1]*6, clockQualified=False, fpsQualified=False)
if scene.endswith('1x') and (fixtures / 'bad-one').exists(): row['trafficDrained'] = False
print(json.dumps(row), flush=True)
'''


class ParallelApiTests(unittest.TestCase):
    def setup_run(self, root):
        out = Path(root); (out / 'fixtures').mkdir(); (out / 'result').mkdir()
        driver = out / 'driver'; driver.write_text(FAKE_DRIVER); driver.chmod(0o755)
        return api.Runner(out, product=True, scene_jobs=2), driver, out

    def test_both_real_children_overlap_and_preserve_ordered_outputs(self):
        with tempfile.TemporaryDirectory() as root:
            runner, driver, out = self.setup_run(root)
            (out / 'fixtures/overlap').touch()
            rows = runner.run_parallel_frames(driver, out / 'result')
            api.validate_metrics(rows, True)
            self.assertEqual([row['scene'] for row in rows], api.SCENES)
            self.assertEqual(runner.scene_execution, dict(mode=common.FRESH_MODELS, max_workers=2))
            for name in api.SCENES:
                flat = out / 'result' / (name + '.memory')
                local = out / 'result/scenes' / name / (name + '.memory')
                self.assertEqual(flat.read_bytes(), name.encode())
                self.assertTrue(os.path.samefile(flat, local))

    def test_failed_or_invalid_frame_does_not_terminate_other_frame(self):
        for fault in ('fail-one', 'bad-one'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as root:
                runner, driver, out = self.setup_run(root)
                (out / 'fixtures' / fault).touch()
                with self.assertRaisesRegex(RuntimeError, 'native-api-extent-1x'):
                    runner.run_parallel_frames(driver, out / 'result')
                completed = out / 'result/scenes/native-api-extent-4x/native-api-extent-4x.memory'
                self.assertTrue(completed.is_file())
                self.assertEqual(completed.read_bytes(), b'native-api-extent-4x')
                self.assertFalse((out / 'execution.json').exists())

    def test_smoke_frames_keep_their_smaller_profile(self):
        with tempfile.TemporaryDirectory() as root:
            _, driver, out = self.setup_run(root)
            runner = api.Runner(out, product=False, scene_jobs=2)
            rows = runner.run_parallel_frames(driver, out / 'result')
            api.validate_metrics(rows, False)
            with self.assertRaises(RuntimeError):
                api.validate_metrics(rows, True)

    def test_spawn_memory_error_retries_after_other_child_finishes(self):
        for failing_start in (1, 2):
            with self.subTest(failing_start=failing_start), tempfile.TemporaryDirectory() as root:
                runner, driver, out = self.setup_run(root)
                real_popen = subprocess.Popen
                count = 0
                missing = api.SCENES[failing_start - 1]
                other = api.SCENES[2 - failing_start]
                def start(*args, **kwargs):
                    nonlocal count
                    count += 1
                    if count == failing_start:
                        raise OSError(12, 'Cannot allocate memory')
                    if count == 3:
                        self.assertTrue((out / 'result/scenes' / other / (other + '.memory')).is_file())
                    return real_popen(*args, **kwargs)
                with patch.object(api.subprocess, 'Popen', side_effect=start):
                    rows = runner.run_parallel_frames(driver, out / 'result')
                api.validate_metrics(rows, True)
                self.assertEqual(count, 3)
                retries = json.loads((out / 'result/memory-retries.json').read_text())
                self.assertEqual(retries[missing]['outcome'], 'completed')
                self.assertTrue((out / 'result/scenes' / other / (other + '.memory')).is_file())

    def test_repeated_spawn_memory_error_stops_after_one_retry(self):
        with tempfile.TemporaryDirectory() as root:
            runner, driver, out = self.setup_run(root)
            real_popen = subprocess.Popen
            count = 0
            def start(*args, **kwargs):
                nonlocal count
                count += 1
                if count in (2, 3): raise OSError(12, 'Cannot allocate memory')
                return real_popen(*args, **kwargs)
            with patch.object(api.subprocess, 'Popen', side_effect=start):
                with self.assertRaisesRegex(RuntimeError, 'native-api-extent-4x'):
                    runner.run_parallel_frames(driver, out / 'result')
            self.assertEqual(count, 3)
            self.assertTrue((out / 'result/scenes/native-api-extent-1x/native-api-extent-1x.memory').is_file())
            retries = json.loads((out / 'result/memory-retries.json').read_text())
            self.assertEqual(retries[api.SCENES[1]]['outcome'], 'failed')

    def test_python_memory_error_uses_the_same_bounded_fallback(self):
        with tempfile.TemporaryDirectory() as root:
            runner, driver, out = self.setup_run(root)
            real_popen = subprocess.Popen
            count = 0
            def start(*args, **kwargs):
                nonlocal count
                count += 1
                if count == 1: raise MemoryError('process allocation failed')
                return real_popen(*args, **kwargs)
            with patch.object(api.subprocess, 'Popen', side_effect=start):
                rows = runner.run_parallel_frames(driver, out / 'result')
            api.validate_metrics(rows, True)
            self.assertEqual(count, 3)
            retries = json.loads((out / 'result/memory-retries.json').read_text())
            self.assertEqual(retries[api.SCENES[0]]['outcome'], 'completed')

    def test_other_spawn_errors_are_not_retried(self):
        with tempfile.TemporaryDirectory() as root:
            runner, driver, out = self.setup_run(root)
            real_popen = subprocess.Popen
            count = 0
            def start(*args, **kwargs):
                nonlocal count
                count += 1
                if count == 1: raise OSError(13, 'Permission denied')
                return real_popen(*args, **kwargs)
            with patch.object(api.subprocess, 'Popen', side_effect=start):
                with self.assertRaisesRegex(RuntimeError, 'native-api-extent-1x'):
                    runner.run_parallel_frames(driver, out / 'result')
            self.assertEqual(count, 2)
            self.assertTrue((out / 'result/scenes/native-api-extent-4x/native-api-extent-4x.memory').is_file())
            self.assertFalse((out / 'result/memory-retries.json').exists())

    def test_verify_rejects_a_different_execution_mode(self):
        with tempfile.TemporaryDirectory() as root:
            out = Path(root); (out / 'execution.json').write_text(json.dumps(dict(
                scene_execution=dict(mode=common.FRESH_MODELS, max_workers=2))))
            runner = api.Runner(out, product=True, scene_jobs=1)
            with patch.object(runner, 'prepared', return_value={}), patch.object(runner, 'checked_build'):
                with self.assertRaisesRegex(RuntimeError, 'execution mode'):
                    runner.verify()
            with self.assertRaises(ValueError):
                api.Runner(out, product=True, scene_jobs=3)

    def test_parallel_record_requires_an_oracle_and_unchanged_outputs(self):
        with tempfile.TemporaryDirectory() as root:
            runner, driver, out = self.setup_run(root)
            driver.rename(out / 'native_api_driver')
            (out / 'preparation.json').write_text('{}')
            (out / 'build.json').write_text('{}')
            prepared = dict(source_sha256={'rtl':'source'}, rtl_sha256={})
            def oracle(stage, command, log):
                self.assertEqual(stage, 'verify-all-api-stored-images')
                lines = [f'NATIVE_API_COMPILED_IMAGE id={n} frame=640x480 exactStoredImagesAndGuards=true'
                         for n in api.SCENES]
                lines.append('NATIVE_COMPILED_API PASS frames=2 fullExecutionAndStorage=true '
                             'exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false')
                log.write_text('\n'.join(lines))
            with patch.object(runner, 'prepared', return_value=prepared), \
                 patch.object(runner, 'checked_build', return_value={'context': {'driver_sha256': {'driver': 'hash'}}}), \
                 patch.object(runner, 'execute', side_effect=oracle):
                runner.run()
                self.assertFalse((out / 'qualification.json').exists())
                execution = json.loads((out / 'execution.json').read_text())
                self.assertEqual(execution['scene_execution']['mode'], common.FRESH_MODELS)
                runner.verify()
                result = json.loads((out / 'qualification.json').read_text())
                self.assertTrue(result['exact_stored_images_and_guards'])
                self.assertFalse(result['shared_model_reset_exercised'])
                self.assertIn('independent fresh models', result['scope'])
                memory = Path(execution['directory']) / (api.SCENES[0] + '.memory')
                memory.write_bytes(b'corrupted')
                with self.assertRaisesRegex(RuntimeError, 'output bytes changed'):
                    runner.verify()


if __name__ == '__main__':
    unittest.main()
