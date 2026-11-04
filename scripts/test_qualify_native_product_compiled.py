#!/usr/bin/env python3
"""Qualification/AXI guards and an optional tiny bound-check execution probe."""
import copy
import json
import os
from pathlib import Path
import re
import resource
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
            layer = 'verification/assert/layers-Top-Verification-Assert.sv'
            rtl[layer] = 'bound checks'
            prepared = dict(rtl_sha256=rtl, manifest_sha256='profile')
            build = dict(exit_code=0, executable_sha256=q.sha(exe),
                context=dict(rtl_sha256=rtl, profile_manifest_sha256='profile', driver_sha256={},
                    assertion_layers=q.assertion_layer_hashes(rtl),
                    hierarchy=q.hierarchy_plan(rtl, 'flat')))
            q.write_json(out / 'build.json', build); q.guard_build(out, prepared)
            for layers in [None, {}, {layer: 'old check'}]:
                stale = copy.deepcopy(build)
                if layers is None: del stale['context']['assertion_layers']
                else: stale['context']['assertion_layers'] = layers
                q.write_json(out / 'build.json', stale)
                with self.assertRaises(RuntimeError): q.guard_build(out, prepared)
            q.write_json(out / 'build.json', build)
            with self.assertRaises(RuntimeError): q.guard_build(out, prepared, 'fp32')
            for changed in [dict(prepared, rtl_sha256={'top': 'two'}), dict(prepared, manifest_sha256='other')]:
                with self.assertRaises(RuntimeError): q.guard_build(out, changed)
            exe.write_bytes(b'another model')
            with self.assertRaises(RuntimeError): q.guard_build(out, prepared)

    def test_progress_source_is_bound_to_both_compiled_drivers(self):
        import qualify_native_api_compiled as api
        source = 'scripts/native_execution_progress.hpp'
        self.assertIn(source, q.DRIVER)
        self.assertIn(source, api.DRIVER)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); out = root / 'out'; out.mkdir()
            header = root / source; header.parent.mkdir(); header.write_text('original monitor')
            executable = out / 'native_product_driver'; executable.write_bytes(b'compiled model')
            rtl = {n + '.sv': 'RTL' for n in ['GpuComputeUnit', 'Rgba8BlendFmaEngine']}
            prepared = dict(rtl_sha256=rtl, manifest_sha256='profile')
            q.write_json(out / 'build.json', dict(exit_code=0, executable_sha256=q.sha(executable),
                context=dict(rtl_sha256=rtl, profile_manifest_sha256='profile',
                    driver_sha256={source: q.sha(header)}, assertion_layers={},
                    hierarchy=q.hierarchy_plan(rtl, 'flat'))))
            with patch.object(q, 'ROOT', root):
                q.guard_build(out, prepared, driver_files=[source])
                header.write_text('changed monitor')
                with self.assertRaises(RuntimeError):
                    q.guard_build(out, prepared, driver_files=[source])

    def test_progress_reports_cycles_atomically_without_changing_gates(self):
        compiler = os.environ.get('CXX', 'c++')
        self.assertIsNotNone(shutil.which(compiler))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'progress-test'
            subprocess.run([compiler, '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                str(ROOT / 'scripts/test_native_execution_progress.cpp'), '-o', str(output)], check=True)
            result = subprocess.run([str(output), directory], check=True, capture_output=True, text=True)
            initial, running, complete, next_scene = map(json.loads, result.stdout.splitlines())
            self.assertEqual((initial['state'], initial['scene'], initial['serviceCycles']),
                             ('running', 'first-scene', 0))
            self.assertEqual((running['serviceCycles'], running['gpuCycles'], running['readBusBytes'],
                              running['writeBusBytes'], running['enabledWriteBytes'], running['elapsedMilliseconds']),
                             (500, 510, 64, 32, 17, 15000))
            self.assertEqual((complete['state'], complete['serviceCycles'], complete['elapsedMilliseconds']),
                             ('scene-complete', 600, 16000))
            self.assertEqual((next_scene['sceneIndex'], next_scene['sceneCount'], next_scene['maxServiceCycles']),
                             (2, 6, 0x100000001))
            self.assertNotIn('trafficDrained', complete)
            self.assertNotIn('busyCuCycles', initial)
            self.assertEqual(running['busyCuCycles'], 0x100000002)
            self.assertEqual(running['fragmentPhaseWorkerCycles'], [11, 22, 33, 44, 55, 66])
            self.assertEqual((running['stagingOutstandingCycles'], running['stagingBlockedCycles'],
                              running['fragmentOutputBlockedCycles'], running['payloadSpills']),
                             (77, 88, 99, 101))

    def test_fp32_hierarchy_requires_real_wrappers_and_preserves_format_pruning(self):
        modules = ['GpuComputeUnit', 'Rgba8BlendFmaEngine', 'ClipFloatArithmetic',
            'ClipFloatStreamingAddMul', 'Fp32FragmentPreparation', 'Fp32PrimitiveRasterEngine']
        files = {m + '.sv': 'RTL' for m in modules + ['FloatFMA', 'GpuComputeUnit_1', 'Fp32FragmentPreparation_1']}
        plan = q.hierarchy_plan(files, 'fp32')
        self.assertEqual(set(plan['modules']), set(modules + ['GpuComputeUnit_1', 'Fp32FragmentPreparation_1']))
        self.assertEqual(q.hierarchy_plan(files, 'baseline')['modules'], modules[:2])
        self.assertEqual(q.hierarchy_plan(files, 'flat')['modules'], [])
        bound = dict(files, **{'verification/assert/layers-Top-Verification-Assert.sv': 'bind'})
        self.assertEqual(q.hierarchy_plan(bound, 'flat')['modules'], [])
        for profile in ['baseline', 'fp32']:
            with self.assertRaisesRegex(RuntimeError, 'private state.*flat'):
                q.hierarchy_plan(bound, profile)
        self.assertNotIn('FloatFMA', q.hierarchy_control(plan))
        for missing in modules:
            with self.assertRaises(RuntimeError):
                q.hierarchy_plan({n: h for n, h in files.items() if not n.startswith(missing)}, 'fp32')

    def test_bind_layers_and_parent_include_directories_are_discovered(self):
        with tempfile.TemporaryDirectory() as directory:
            rtl = Path(directory) / 'rtl'; checks = rtl / 'verification/assert'
            checks.mkdir(parents=True)
            names = ['Top.sv', 'verification/assert/Leaf_Verification_Assert.sv',
                     'verification/assert/layers-Top-Verification-Assert.sv',
                     'verification/assert/layers-Leaf-Verification-Assert.sv']
            for name in names: (rtl / name).write_text('// emitted source\n')
            (rtl / 'filelist.f').write_text('# definitions only\nTop.sv\n\n'
                'verification/assert/Leaf_Verification_Assert.sv\nTop.sv\n')
            sources, includes = q.simulation_inputs(rtl)
            self.assertEqual(set(sources), {(rtl / name).resolve() for name in names})
            self.assertEqual(len(sources), len(names))
            self.assertEqual(set(includes), {rtl.resolve(), checks.resolve(), checks.parent.resolve()})
            for name in names[2:]: (rtl / name).unlink()
            with self.assertRaisesRegex(RuntimeError, 'no bind layers'): q.simulation_inputs(rtl)
            for name in names[2:]: (rtl / name).write_text('// emitted source\n')
            for name in ['missing.sv', '../external.sv']:
                (Path(directory) / 'external.sv').write_text('// outside the prepared hash tree\n')
                (rtl / 'filelist.f').write_text(name + '\n')
                with self.assertRaises(RuntimeError): q.simulation_inputs(rtl)

    def test_discovered_checks_observe_private_state_in_both_instances(self):
        verilator = shutil.which('verilator')
        compiler = shutil.which(os.environ.get('CXX', 'c++'))
        if not verilator or not compiler:
            self.skipTest('Verilator and C++ are required for the tiny bind probe')
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory); rtl = out / 'rtl'; checks = rtl / 'verification/assert'
            checks.mkdir(parents=True)
            (rtl / 'Top.sv').write_text('''
module Leaf(input clock, reset, input [7:0] expected, output [7:0] q);
  logic [7:0] hidden;
  assign q=hidden;
  always @(posedge clock) if (reset) hidden <= 0; else hidden <= hidden+1;
endmodule
module Top(input clock, reset, input [7:0] expected_left, expected_right,
           output [7:0] left_q, right_q);
  Leaf left(clock,reset,expected_left,left_q);
  Leaf right(clock,reset,expected_right,right_q);
endmodule
''')
            (checks / 'Leaf_Verification_Assert.sv').write_text('''
module Leaf_Verification_Assert;
  always @(posedge Leaf.clock)
    if (!Leaf.reset && Leaf.hidden != Leaf.expected) $fatal(1,"bound count before NBA");
endmodule
''')
            (checks.parent / 'layers-Leaf-Verification.sv').write_text(
                '`ifndef leaf_base\n`define leaf_base\n`endif\n')
            (checks / 'layers-Leaf-Verification-Assert.sv').write_text(
                '`ifndef leaf_bind\n`define leaf_bind\n'
                '`include "layers-Leaf-Verification.sv"\n'
                'bind Leaf Leaf_Verification_Assert bound_checker ();\n`endif\n')
            (checks / 'layers-Top-Verification-Assert.sv').write_text(
                '`include "layers-Leaf-Verification-Assert.sv"\n')
            (rtl / 'filelist.f').write_text('Top.sv\nverification/assert/Leaf_Verification_Assert.sv\n')
            driver = out / 'driver.cpp'
            driver.write_text('''#include "VTop.h"
#include <string>
int main(int argc,char **argv) {
  VTop d;
  auto tick=[&](){d.clock=0;d.eval();d.clock=1;d.eval();};
  d.reset=1; d.expected_left=99; d.expected_right=99; tick();
  d.reset=0; d.expected_left=0; d.expected_right=0; tick();
  d.expected_left=1; d.expected_right=1;
  const std::string mode=argc>1?argv[1]:"pass";
  if(mode=="left") d.expected_left=44;
  if(mode=="right") d.expected_right=44;
  tick();
  return d.left_q==2 && d.right_q==2 ? 0 : 3;
}
''')
            prepared = dict(rtl_sha256=q.tree_hashes(rtl), manifest_sha256='tiny test profile')
            class TinyRunner(q.Runner):
                def driver_sources(self): return ['driver.cpp']
                def prepared(self):
                    if q.tree_hashes(rtl) != prepared['rtl_sha256']:
                        raise RuntimeError('Tiny RTL changed during build')
                    return prepared
            # Exercise the real builder with a tiny design rather than GPU
            # elaboration. The separate profile test checks GPU family names.
            with patch.object(q, 'ROOT', out), patch.object(q, 'TOP', 'Top'), \
                    patch.object(q, 'hierarchy_plan', return_value={'profile': 'flat', 'modules': []}):
                runner = TinyRunner(out, False)
                self.assertEqual(runner.hierarchy_profile, 'flat')
                try: runner.build(1)
                except RuntimeError:
                    self.fail('\n'.join(p.read_text() for p in out.glob('*.log')))
                record = q.guard_build(out, prepared, driver_files=['driver.cpp'])
                self.assertEqual(record['context']['assertion_layers'],
                                 q.assertion_layer_hashes(prepared['rtl_sha256']))
                self.assertIn('--assert', record['context']['flags'])
                self.assertNotIn('--hierarchical', record['context']['flags'])
            binary = out / 'native_product_driver'
            def no_core(): resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
            for mode in ['pass', 'left', 'right']:
                result = subprocess.run([str(binary), mode], capture_output=True,
                                        text=True, preexec_fn=no_core)
                if mode == 'pass': self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('bound count before NBA', result.stdout + result.stderr)

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

    def test_native_host_step_preserves_settled_axi_snapshot(self):
        compiler = os.environ.get('CXX', 'c++')
        self.assertIsNotNone(shutil.which(compiler), 'C++ compiler required for CPU-only AXI tests')
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'host-step-test'
            subprocess.run([compiler, '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                '-I', str(ROOT / 'scripts/test_stubs/native_host'), '-I', str(ROOT / 'scripts'),
                str(ROOT / 'scripts/test_native_host_simulation.cpp'), '-o', str(output)], check=True)
            result = subprocess.run([str(output)], check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout, 'native host AXI cycle phases: PASS\n')

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
