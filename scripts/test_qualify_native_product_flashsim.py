#!/usr/bin/env python3
"""CPU guards for assertion-enabled FlashSim product model provenance."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash


class FlashSimQualificationTest(unittest.TestCase):
    def test_generate_stage_does_not_compile_or_run(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(sys, 'argv', ['qualification', '--smoke', '--stage', 'generate',
                                           '--flashsim-dir', tmp, '--output', tmp]), \
                patch.object(flash, 'Runner') as runner_type:
            flash.main()
            runner = runner_type.return_value
            runner.generate.assert_called_once_with()
            runner.build.assert_not_called()
            runner.run.assert_not_called()
            runner.verify.assert_not_called()

    def test_reshard_provenance_rejects_changed_model_and_tool(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); out = root / 'out'; out.mkdir()
            source = out / 'flashsim-dut'; source.mkdir()
            derived = root / 'derived'; derived.mkdir()
            tool = root / 'tool'; package = tool / 'flashsim'; package.mkdir(parents=True)
            (package / 'reshard.py').write_text('transform')
            (package / 'emit.py').write_text('emitter')
            for directory, shard_text in [(source, 'original'), (derived, 'derived')]:
                (directory / 'dut.h').write_text('static constexpr uint64_t assertion_count = 3;\n')
                (directory / 'dut_0.cpp').write_text(shard_text)
            generated = flash.generated_sources(source, 1)
            manifest = dict(input_sha256=generated['files'],
                output_sha256=flash.generated_sources(derived, 1)['files'],
                tool_sha256=common.sha(package / 'reshard.py'),
                emitter_sha256=common.sha(package / 'emit.py'))
            common.write_json(derived / 'flashsim-reshard.json', manifest)
            runner = flash.Runner(out, False, tool, shards=1, reshard=derived, reshard_tool=tool)
            with patch.object(flash.subprocess, 'run') as verify:
                provenance = runner.checked_reshard(generated)
                self.assertEqual(provenance['derived']['assertions'], 3)
                self.assertIn('verify', verify.call_args.args[0])
                (derived / 'dut_0.cpp').write_text('changed')
                with self.assertRaisesRegex(RuntimeError, 'do not match'):
                    runner.checked_reshard(generated)
                (derived / 'dut_0.cpp').write_text('derived')
                (package / 'emit.py').write_text('changed')
                with self.assertRaisesRegex(RuntimeError, 'do not match'):
                    runner.checked_reshard(generated)
                (package / 'emit.py').write_text('emitter')
                (source / 'dut_0.cpp').write_text('changed')
                with self.assertRaisesRegex(RuntimeError, 'do not match'):
                    runner.checked_reshard(flash.generated_sources(source, 1))
            with self.assertRaisesRegex(ValueError, 'supplied together'):
                flash.Runner(out, False, tool, reshard=derived)

    def test_circt_serial_setting_is_part_of_generator_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); package = root / 'flashsim'; package.mkdir()
            (package / '__main__.py').write_text('')
            (root / 'pyproject.toml').write_text('')
            tool = root / 'compiler'; tool.write_bytes(b'compiler')
            with patch.object(flash.subprocess, 'check_output', return_value=json.dumps(str(tool))), \
                    patch.object(flash, 'loaded_libraries', return_value={}), \
                    patch.object(flash, 'dynamic_libraries', return_value={}):
                with patch.dict(os.environ, {'FLASHSIM_CIRCT_SERIAL': '0'}):
                    parallel = flash.generator_inputs(root)
                with patch.dict(os.environ, {'FLASHSIM_CIRCT_SERIAL': '1'}):
                    serial = flash.generator_inputs(root)
            self.assertEqual(serial['environment']['FLASHSIM_CIRCT_SERIAL'], '1')
            self.assertNotEqual(parallel, serial)

    def test_circt_pipeline_settings_are_part_of_generator_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); package = root / 'flashsim'; package.mkdir()
            (package / '__main__.py').write_text('')
            (root / 'pyproject.toml').write_text('')
            tool = root / 'compiler'; tool.write_bytes(b'compiler')
            with patch.object(flash.subprocess, 'check_output', return_value=json.dumps(str(tool))), \
                    patch.object(flash, 'loaded_libraries', return_value={}), \
                    patch.object(flash, 'dynamic_libraries', return_value={}):
                with patch.dict(os.environ, {'CIRCT_EXPERIMENTAL_LOCAL_CANONICALIZER': '0'}):
                    original = flash.generator_inputs(root)
                with patch.dict(os.environ, {'CIRCT_EXPERIMENTAL_LOCAL_CANONICALIZER': '1'}):
                    changed = flash.generator_inputs(root)
            self.assertNotEqual(original, changed)

    def test_dynamic_library_overrides_are_fingerprinted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); tool = root / 'bin/circt-verilog'; tool.parent.mkdir()
            tool.write_bytes(b'compiler')
            fallback = root / 'lib'; fallback.mkdir()
            default = fallback / 'libdefault.dylib'; default.write_bytes(b'default')
            override = root / 'override'; override.mkdir()
            first = override / 'first.dylib'; first.write_bytes(b'one')
            second = override / 'second.dylib'; second.write_bytes(b'two')
            alias = override / 'libchoice.dylib'; alias.symlink_to(first.name)
            env = {'DYLD_LIBRARY_PATH': str(override)}
            loaded = {str(default): 'uuid'}
            before = flash.dynamic_libraries(tool, env, loaded)
            alias.unlink(); alias.symlink_to(second.name)
            self.assertNotEqual(before, flash.dynamic_libraries(tool, env, loaded))
            before = flash.dynamic_libraries(tool, env, loaded)
            second.write_bytes(b'changed')
            self.assertNotEqual(before, flash.dynamic_libraries(tool, env, loaded))
            with self.assertRaisesRegex(RuntimeError, 'empty search path'):
                flash.dynamic_libraries(tool, {'LD_LIBRARY_PATH': str(override) + ':'}, loaded)
            with self.assertRaises(RuntimeError):
                flash.dynamic_libraries(tool, {'LD_PRELOAD': str(root / 'missing.so')}, loaded)

    def test_compiler_wrapper_is_not_a_provenance_boundary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); package = root / 'flashsim'; package.mkdir()
            (package / '__main__.py').write_text('')
            (root / 'pyproject.toml').write_text('')
            wrapper = root / 'compiler'; wrapper.write_text('#!/bin/sh\nexit 0\n')
            with patch.object(flash.subprocess, 'check_output', return_value=json.dumps(str(wrapper))):
                with self.assertRaisesRegex(RuntimeError, 'not a wrapper'):
                    flash.generator_inputs(root)

    def test_real_bound_assertion_if_flashsim_is_available(self):
        checkout = os.environ.get('OPENGPU_FLASHSIM_DIR')
        if not checkout or sys.version_info < (3, 10) or not shutil.which('c++'):
            self.skipTest('set OPENGPU_FLASHSIM_DIR and use Python 3.10+ with C++')
        identity = flash.generator_inputs(Path(checkout))
        self.assertTrue(identity['loaded_library_ids'])
        for library in identity['loaded_library_ids']:
            if Path(library).is_file():
                self.assertIn(library, identity['circt_libraries'])
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); rtl = root / 'rtl'; checks = rtl / 'verification/assert'
            checks.mkdir(parents=True)
            (rtl / (common.TOP + '.sv')).write_text('''
module GpuHostSystemAxi(input logic clock,reset,go,output logic [7:0] q);
  always @(posedge clock) if (reset) q <= 0; else if (go) q <= q + 1;
endmodule
''')
            (checks / 'GpuHostSystemAxi_Verification_Assert.sv').write_text('''
module GpuHostSystemAxi_Verification_Assert;
  always @(posedge GpuHostSystemAxi.clock)
    if (!GpuHostSystemAxi.reset && GpuHostSystemAxi.q == 8'd255) $fatal(1, "overflow");
endmodule
''')
            (checks / 'layers-GpuHostSystemAxi-Verification-Assert.sv').write_text(
                'bind GpuHostSystemAxi GpuHostSystemAxi_Verification_Assert verification_assert ();\n')
            (rtl / 'filelist.f').write_text('GpuHostSystemAxi.sv\n'
                'verification/assert/GpuHostSystemAxi_Verification_Assert.sv\n')
            checkout = Path(checkout).resolve(); flash.verify_flashsim_sources(checkout, rtl)
            dut = root / 'dut'; header = dut / 'dut.h'
            compile_probe = ('from pathlib import Path; from flashsim.compile import compile_file; '
                'import sys; compile_file(Path(sys.argv[1]),Path(sys.argv[2]),'
                'frontend="circt",assertions=True,split=2)')
            subprocess.run([sys.executable, '-c', compile_probe,
                            str(rtl / (common.TOP + '.sv')), str(header)], cwd=checkout, check=True)
            self.assertEqual(flash.generated_sources(dut, 2)['assertions'], 1)
            program = dut / 'probe.cpp'
            program.write_text('''#include "dut.h"
#include <cassert>
#include <stdexcept>
int main() {
  GpuHostSystemAxiDut d; d.clock=1; d.reset=1; d.go=1; d.tick(); d.reset=0;
  for (int n=1; n<=255; ++n) { d.tick(); d.eval_q(); assert(d.q==n); }
  try { d.tick(); } catch (const std::exception &) { return 0; }
  return 1;
}
''')
            build = root / 'build'
            subprocess.run([sys.executable, str(flash.ROOT / 'scripts/build_native_flashsim.py'),
                '--dut-dir', str(dut), '--build-dir', str(build), '--driver', str(program),
                '--jobs', '1'], check=True, stdout=subprocess.PIPE, text=True)
            builder = json.loads((build / 'native-flashsim-build.json').read_text())
            generated = flash.generated_sources(dut)['files']
            self.assertEqual(builder['dut_header_sha256'], generated.pop('dut.h'))
            self.assertEqual(builder['dut_sources'], generated)
            subprocess.run([str(build / 'native_product_driver')], check=True)

    def test_requires_split_assertion_model_and_fingerprints_every_shard(self):
        with tempfile.TemporaryDirectory() as tmp:
            dut = Path(tmp)
            with self.assertRaises(RuntimeError):
                flash.generated_sources(dut)
            header = dut / 'dut.h'; header.write_text('static constexpr uint64_t assertion_count = 0;\n')
            shard = dut / 'dut_0.cpp'; shard.write_text('shard')
            with self.assertRaises(RuntimeError):
                flash.generated_sources(dut)
            header.write_text('static constexpr uint64_t assertion_count = 42;\n')
            first = flash.generated_sources(dut)
            self.assertEqual(first['assertions'], 42)
            self.assertEqual(first, flash.generated_sources(dut, 1))
            with self.assertRaisesRegex(RuntimeError, 'shard set'):
                flash.generated_sources(dut, 2)
            (dut / 'dut_2.cpp').write_text('unexpected shard')
            with self.assertRaisesRegex(RuntimeError, 'shard set'):
                flash.generated_sources(dut, 1)
            (dut / 'dut_2.cpp').unlink()
            shard.write_text('changed')
            self.assertNotEqual(first, flash.generated_sources(dut))

    def test_flashsim_and_reference_must_use_the_same_source_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            rtl = Path(tmp) / 'rtl'; rtl.mkdir()
            top = rtl / (common.TOP + '.sv'); top.write_text('module')
            layer = rtl / 'verification/assert/layers-top.sv'; layer.parent.mkdir(parents=True)
            layer.write_text('bind')
            (rtl / 'filelist.f').write_text(top.name + '\n')
            expected = [str(top.resolve()), str(layer.resolve())]
            with patch.object(flash.subprocess, 'check_output', return_value=json.dumps(expected)):
                flash.verify_flashsim_sources(Path(tmp), rtl)
            for actual in [expected[:-1], expected + [expected[0]], expected + ['/tmp/other.sv']]:
                with patch.object(flash.subprocess, 'check_output', return_value=json.dumps(actual)):
                    with self.assertRaisesRegex(RuntimeError, 'source list'):
                        flash.verify_flashsim_sources(Path(tmp), rtl)

    def test_run_rejects_changed_model_or_generator(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp); dut = out / 'flashsim-dut'; dut.mkdir()
            (dut / 'dut.h').write_text('static constexpr uint64_t assertion_count = 3;\n')
            shard = dut / 'dut_0.cpp'; shard.write_text('source')
            generation = dut / 'generation.json'; generation.write_text('generation')
            build_dir = out / 'flashsim-build'; build_dir.mkdir()
            builder = build_dir / 'native-flashsim-build.json'; builder.write_text('builder')
            binary = out / 'native_product_driver'; binary.write_bytes(b'executable')
            layers = {'verification/assert/layers-one.sv': 'check'}
            prepared = dict(rtl_sha256=layers, manifest_sha256='profile')
            runner = flash.Runner(out, False, out, shards=1)
            runner.driver_sources = lambda: []
            generator = {'generator': 'qualified'}
            record = dict(exit_code=0, executable_sha256=common.sha(binary), generation_sha256=common.sha(generation),
                builder_sha256=common.sha(builder), context=dict(backend='flashsim', shards=1,
                rtl_sha256=layers, profile_manifest_sha256='profile', assertion_layers=layers,
                driver_sha256={}, generator=generator, generated=flash.generated_sources(dut)))
            common.write_json(out / 'build.json', record)
            with patch.object(flash, 'generator_inputs', return_value=generator):
                runner.checked_build(prepared)
                self.assertEqual(runner.backend(), 'flashsim')
                record['context']['shard_opt'] = '-O0'
                common.write_json(out / 'build.json', record)
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    runner.checked_build(prepared)
                record['context']['shard_opt'] = '-O2'
                common.write_json(out / 'build.json', record)
                runner.checked_build(prepared)
                record['context']['conservative_alias'] = True
                common.write_json(out / 'build.json', record)
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    runner.checked_build(prepared)
                conservative = flash.Runner(out, False, out, shards=1, conservative_alias=True)
                conservative.driver_sources = lambda: []
                conservative.checked_build(prepared)
                record['context']['conservative_alias'] = False
                common.write_json(out / 'build.json', record)
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    conservative.checked_build(prepared)
                runner.checked_build(prepared)
                old_root = out / 'original-driver'; old_root.mkdir()
                old_driver = old_root / 'driver'; old_driver.write_text('original')
                record['context']['driver_sha256'] = {'driver': common.sha(old_driver)}
                common.write_json(out / 'build.json', record)
                historical = flash.Runner(out, False, out, shards=1, driver_root=old_root)
                historical.driver_sources = lambda: ['driver']
                historical.checked_build(prepared)
                old_driver.write_text('changed')
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    historical.checked_build(prepared)
                with self.assertRaisesRegex(RuntimeError, 'only valid for verifying'):
                    historical.build(1)
                record['context']['driver_sha256'] = {}
                common.write_json(out / 'build.json', record)
                shard.write_text('changed')
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    runner.checked_build(prepared)
                shard.write_text('source'); binary.write_bytes(b'other')
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    runner.checked_build(prepared)
                binary.write_bytes(b'executable'); generation.write_text('tampered generation')
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    runner.checked_build(prepared)
                generation.write_text('generation')
            binary.write_bytes(b'executable')
            with patch.object(flash, 'generator_inputs', return_value={'generator': 'changed'}):
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    runner.checked_build(prepared)

    def test_build_requires_assertions_and_uses_flashsim_builder(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp); layers = {'verification/assert/layers-one.sv': 'check'}
            prepared = dict(rtl_sha256=layers, manifest_sha256='profile')
            runner = flash.Runner(out, False, out, shards=1)
            runner.prepared = lambda: prepared
            runner.driver_sources = lambda: []
            commands = []
            stale = out / 'flashsim-dut'; stale.mkdir()
            (stale / 'hw.mlir').write_text('stale MLIR')
            common.write_json(stale / 'generation.json',
                              {'context': {'generator': 'old'}, 'mlir_sha256': common.sha(stale / 'hw.mlir')})
            cache_expected = iter([False, True, False])
            def execute(stage, command, log, cwd=None):
                commands.append(command)
                if stage == 'flashsim-generate':
                    dut = out / 'flashsim-dut'; dut.mkdir(exist_ok=True)
                    self.assertEqual((dut / 'hw.mlir').exists(), next(cache_expected))
                    (dut / 'dut.h').write_text('static constexpr uint64_t assertion_count = 7;\n')
                    (dut / 'dut_0.cpp').write_text('model')
                    (dut / 'hw.mlir').write_text('fresh MLIR')
                elif stage == 'flashsim-build':
                    build = out / 'flashsim-build'; build.mkdir(exist_ok=True)
                    binary = build / 'native_product_driver'; binary.write_bytes(b'model')
                    generated = flash.generated_sources(out / 'flashsim-dut')['files']
                    common.write_json(build / 'native-flashsim-build.json',
                        {'executable_sha256': common.sha(binary),
                         'dut_header_sha256': generated['dut.h'],
                         'dut_sources': {'dut_0.cpp': generated['dut_0.cpp']}})
                else:
                    self.fail('unexpected build stage')
            runner.execute = execute
            with patch.object(flash, 'generator_inputs', return_value={'generator': 'one'}), \
                    patch.object(flash, 'verify_flashsim_sources'):
                runner.build(2)
                runner.checked_build(prepared)
                runner.build(2)  # A verified generated model skips regeneration.
                (stale / 'dut_0.cpp').write_text('tampered source')
                runner.build(2)  # Changed generated code reuses MLIR but regenerates.
                (stale / 'hw.mlir').write_text('tampered MLIR')
                runner.build(2)  # Changed MLIR forces a new CIRCT import.
            self.assertIn('assertions=True', commands[0][2])
            self.assertIn('frontend="circt"', commands[0][2])
            self.assertEqual(commands[0][-1], '1')
            self.assertTrue(commands[1][1].endswith('build_native_flashsim.py'))
            self.assertTrue(commands[2][1].endswith('build_native_flashsim.py'))
            self.assertNotIn('--conservative-alias', commands[1])
            self.assertIn('assertions=True', commands[3][2])
            self.assertIn('assertions=True', commands[5][2])
            self.assertEqual(len(commands), 7)
            self.assertEqual(json.loads((out / 'build.json').read_text())['context']['generated']['assertions'], 7)

    def test_conservative_alias_build_records_and_checks_the_option(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp); layers = {'verification/assert/layers-one.sv': 'check'}
            prepared = dict(rtl_sha256=layers, manifest_sha256='profile')
            dut = out / 'flashsim-dut'; dut.mkdir()
            (dut / 'dut.h').write_text('static constexpr uint64_t assertion_count = 7;\n')
            (dut / 'dut_0.cpp').write_text('model')
            (dut / 'generation.json').write_text('generation')
            generated = flash.generated_sources(dut, 1)
            generator = {'generator': 'one'}
            runner = flash.Runner(out, False, out, shards=1, conservative_alias=True)
            runner.prepared = lambda: prepared
            runner.driver_sources = lambda: []
            runner.generate = lambda: (prepared, layers, generator, generated)
            commands = []
            expected_alias = [False, True]

            def execute(stage, command, log, cwd=None):
                self.assertEqual(stage, 'flashsim-build')
                commands.append(command)
                build = out / 'flashsim-build'; build.mkdir(exist_ok=True)
                binary = build / 'native_product_driver'; binary.write_bytes(b'model')
                common.write_json(build / 'native-flashsim-build.json',
                    {'executable_sha256': common.sha(binary),
                     'dut_header_sha256': generated['files']['dut.h'],
                     'dut_sources': {'dut_0.cpp': generated['files']['dut_0.cpp']},
                     'conservative_alias': expected_alias.pop(0)})

            runner.execute = execute
            with patch.object(flash, 'generator_inputs', return_value=generator):
                with self.assertRaisesRegex(RuntimeError, 'builder does not match'):
                    runner.build(2)
                runner.build(2)
                runner.checked_build(prepared)
                runner.conservative_alias = False
                with self.assertRaisesRegex(RuntimeError, 'does not match'):
                    runner.checked_build(prepared)
            self.assertTrue(all('--conservative-alias' in command for command in commands))
            record = json.loads((out / 'build.json').read_text())
            self.assertTrue(record['context']['conservative_alias'])

    def test_failed_rebuild_removes_old_success_records(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            runner = flash.Runner(out, False, out)
            runner.prepared = lambda: {'rtl_sha256': {}, 'manifest_sha256': 'profile'}
            (out / 'build.json').write_text('old build')
            (out / 'qualification.json').write_text('old qualification')
            with self.assertRaisesRegex(RuntimeError, 'no assertion bind layers'):
                runner.build(1)
            self.assertFalse((out / 'build.json').exists())
            self.assertFalse((out / 'qualification.json').exists())

    def test_failed_native_compile_resumes_from_verified_generated_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp); layers = {'verification/assert/layers-one.sv': 'check'}
            prepared = dict(rtl_sha256=layers, manifest_sha256='profile')
            runner = flash.Runner(out, False, out, shards=1)
            runner.prepared = lambda: prepared
            runner.driver_sources = lambda: []
            stages = []

            def execute(stage, command, log, cwd=None):
                stages.append(stage)
                dut = out / 'flashsim-dut'
                if stage == 'flashsim-generate':
                    (dut / 'dut.h').write_text('static constexpr uint64_t assertion_count = 1;\n')
                    (dut / 'dut_0.cpp').write_text('model')
                    (dut / 'hw.mlir').write_text('MLIR')
                elif stages.count('flashsim-build') == 1:
                    raise RuntimeError('interrupted C++ build')
                else:
                    build = out / 'flashsim-build'; build.mkdir(exist_ok=True)
                    binary = build / 'native_product_driver'; binary.write_bytes(b'model')
                    generated = flash.generated_sources(dut)['files']
                    common.write_json(build / 'native-flashsim-build.json',
                        {'executable_sha256': common.sha(binary),
                         'dut_header_sha256': generated['dut.h'],
                         'dut_sources': {'dut_0.cpp': generated['dut_0.cpp']}})

            runner.execute = execute
            with patch.object(flash, 'generator_inputs', return_value={'generator': 'one'}), \
                    patch.object(flash, 'verify_flashsim_sources'):
                (out / 'build.json').write_text('stale build')
                (out / 'qualification.json').write_text('stale qualification')
                runner.generate()  # Standalone generation does not compile C++.
                self.assertEqual(stages, ['flashsim-generate'])
                self.assertFalse((out / 'build.json').exists())
                self.assertFalse((out / 'qualification.json').exists())
                with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                    runner.build(1)
                self.assertTrue((out / 'flashsim-dut/generation.json').is_file())
                self.assertFalse((out / 'build.json').exists())
                runner.build(1)
                runner.checked_build(prepared)
            self.assertEqual(stages, ['flashsim-generate', 'flashsim-build', 'flashsim-build'])


if __name__ == '__main__':
    unittest.main()
