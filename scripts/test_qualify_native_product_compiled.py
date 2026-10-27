#!/usr/bin/env python3
"""CPU-only qualification guards and external AXI agent tests; no RTL build."""
import copy
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import qualify_native_product_compiled as q

ROOT = Path(__file__).resolve().parents[1]


def profile():
    return dict(version=1, product=False, frameWidth=32, frameHeight=32,
        scenes=q.SCENES, rtlEmitted=True, computeUnits=4, lanes=16, warps=8, axiDataBytes=32)


def metrics():
    return [dict(scene=name, frameWidth=32, frameHeight=32, serviceCycles=100,
        gpuCycles=183, trafficDrained=True, phaseWorkerCycles=[1] * 6,
        imageVerificationRequired=True, clockQualified=False, fpsQualified=False) for name in q.SCENES]


class GuardTests(unittest.TestCase):
    def test_profile_requires_full_execution_and_matching_explicit_mode(self):
        q.check_profile(profile(), False)
        for field, value in [('computeUnits', 2), ('warps', 2), ('lanes', 4),
                ('axiDataBytes', 16), ('rtlEmitted', False), ('scenes', q.SCENES[:-1])]:
            data = dict(profile(), **{field: value})
            with self.assertRaises(RuntimeError): q.check_profile(data, False)
        with self.assertRaises(RuntimeError): q.check_profile(profile(), True)

    def test_missing_duplicate_reordered_or_undrained_frames_fail(self):
        q.validate_metrics(metrics(), False)
        for values in [metrics()[:-1], list(reversed(metrics())), [metrics()[0]] * 6]:
            with self.assertRaises(RuntimeError): q.validate_metrics(values, False)
        for field, value in [('trafficDrained', False), ('serviceCycles', 0),
                ('clockQualified', True), ('imageVerificationRequired', False)]:
            values = metrics(); values[3][field] = value
            with self.assertRaises(RuntimeError): q.validate_metrics(values, False)
        with self.assertRaises(RuntimeError): q.validate_metrics(metrics(), True)

    def test_image_gate_requires_each_named_scene_and_final_oracle_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / 'verify.log'
            final = 'NATIVE_COMPILED_PRODUCT PASS scenes=6 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false'
            images = [f'NATIVE_COMPILED_IMAGE id={n} frame=32x32 exactStoredImagesAndGuards=true' for n in q.SCENES]
            p.write_text('\n'.join([*images, final])); q.verify_marker(p, False)
            for lines in [images, [*([images[0]] * 6), final], [*images[:-1], final]]:
                p.write_text('\n'.join(lines))
                with self.assertRaises(RuntimeError): q.verify_marker(p, False)

    def test_prepared_source_model_and_fixture_bytes_are_all_bound(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(q, 'rtl_inputs', return_value={'source': 'one'}):
            out = Path(directory); (out / 'rtl').mkdir(); (out / 'fixtures').mkdir()
            (out / 'rtl' / 'model.sv').write_text('model one')
            (out / 'fixtures' / 'frame.memory').write_bytes(b'one')
            q.write_json(out / 'fixture-manifest.json', profile())
            record = dict(exit_code=0, source_sha256={'source': 'one'},
                manifest_sha256=q.sha(out / 'fixture-manifest.json'),
                rtl_sha256=q.tree_hashes(out / 'rtl'), fixture_sha256=q.tree_hashes(out / 'fixtures'))
            q.write_json(out / 'preparation.json', record); q.guard_prepared(out, False)
            with patch.object(q, 'rtl_inputs', return_value={'source': 'two'}):
                with self.assertRaises(RuntimeError): q.guard_prepared(out, False)
            (out / 'fixtures' / 'frame.memory').write_bytes(b'two')
            with self.assertRaises(RuntimeError): q.guard_prepared(out, False)
            (out / 'fixtures' / 'frame.memory').write_bytes(b'one')
            (out / 'rtl' / 'model.sv').write_text('model two')
            with self.assertRaises(RuntimeError): q.guard_prepared(out, False)

    def test_executable_cannot_be_replayed_against_new_rtl_or_driver(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(q, 'DRIVER', []):
            out = Path(directory); exe = out / 'native_product_driver'; exe.write_bytes(b'model')
            rtl = {name + '.sv': 'one' for name in ['GpuComputeUnit', 'Rgba8BlendFmaEngine',
                'ClipFloatArithmetic', 'ClipFloatStreamingAddMul', 'Fp32FragmentPreparation', 'Fp32PrimitiveRasterEngine']}
            prepared = dict(rtl_sha256=rtl, manifest_sha256='profile')
            build = dict(exit_code=0, executable_sha256=q.sha(exe),
                context=dict(rtl_sha256=rtl, profile_manifest_sha256='profile', driver_sha256={},
                    hierarchy=q.hierarchy_plan(rtl, 'baseline')))
            q.write_json(out / 'build.json', build); q.guard_build(out, prepared)
            with self.assertRaises(RuntimeError): q.guard_build(out, prepared, 'fp32')
            for changed in [dict(prepared, rtl_sha256={'top': 'two'}), dict(prepared, manifest_sha256='other')]:
                with self.assertRaises(RuntimeError): q.guard_build(out, changed)
            exe.write_bytes(b'another model')
            with self.assertRaises(RuntimeError): q.guard_build(out, prepared)

    def test_fp32_hierarchy_requires_real_wrappers_and_preserves_format_pruning(self):
        modules = ['GpuComputeUnit', 'Rgba8BlendFmaEngine', 'ClipFloatArithmetic',
            'ClipFloatStreamingAddMul', 'Fp32FragmentPreparation', 'Fp32PrimitiveRasterEngine']
        files = {m + '.sv': 'RTL' for m in modules + ['FloatFMA', 'GpuComputeUnit_1', 'Fp32FragmentPreparation_1']}
        plan = q.hierarchy_plan(files, 'fp32')
        self.assertEqual(set(plan['modules']), set(modules + ['GpuComputeUnit_1', 'Fp32FragmentPreparation_1']))
        self.assertEqual(q.hierarchy_plan(files, 'baseline')['modules'], modules[:2])
        self.assertNotIn('FloatFMA', q.hierarchy_control(plan))
        for missing in modules:
            with self.assertRaises(RuntimeError):
                q.hierarchy_plan({n: h for n, h in files.items() if not n.startswith(missing)}, 'fp32')

    def test_execution_alone_never_writes_a_qualification(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            for name in ['preparation.json', 'build.json', 'native_product_driver']:
                (out / name).write_text('source-bound placeholder')
            class FakeRunner(q.Runner):
                def execute(self, stage, command, log, cwd=ROOT):
                    self.asserted_stage = stage
                    log.write_text('\n'.join(json.dumps(r) for r in metrics()))
            runner = FakeRunner(out, False)
            with patch.object(q, 'guard_prepared', return_value={}), patch.object(q, 'guard_build', return_value={}):
                runner.run()
            data = json.loads((out / 'execution.json').read_text())
            self.assertEqual(data['preparation_sha256'], q.sha(out / 'preparation.json'))
            self.assertEqual(data['build_sha256'], q.sha(out / 'build.json'))
            self.assertFalse((out / 'qualification.json').exists())

    def test_actual_external_memory_and_axi_protocol_cases(self):
        compiler = os.environ.get('CXX', 'c++')
        self.assertIsNotNone(shutil.which(compiler), 'C++ compiler required for CPU-only AXI tests')
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'axi-test'
            subprocess.run([compiler, '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                str(ROOT / 'scripts/test_native_frame_axi.cpp'), '-o', str(output)], check=True)
            result = subprocess.run([str(output), directory], check=True, capture_output=True, text=True)
            found = re.fullmatch(r'PASS native frame AXI cases=(\d+)\n', result.stdout)
            self.assertIsNotNone(found)
            self.assertGreaterEqual(int(found[1]), 11)


if __name__ == '__main__':
    unittest.main()
