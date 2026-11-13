#!/usr/bin/env python3
"""Qualification/AXI guards and an optional tiny bound-check execution probe."""
import copy
import argparse
import json
import os
from pathlib import Path
import re
import resource
import shutil
import struct
import subprocess
import sys
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
        readBusBytes=64, writeBusBytes=32, enabledWriteBytes=4,
        imageVerificationRequired=True, clockQualified=False, fpsQualified=False) for name in q.SCENES]


class PreparationTests(unittest.TestCase):
    """Execute real child processes against a previously populated output tree."""

    def seed(self, out):
        for directory, name in [('rtl', 'model.sv'), ('fixtures', 'frame.memory')]:
            (out / directory).mkdir()
            (out / directory / name).write_text('old bytes')
        q.write_json(out / 'fixture-manifest.json', profile())
        q.write_json(out / 'preparation.json', dict(source_sha256={'source': 'old'}))
        (out / 'prepare.log').write_text(self.marker())

    def marker(self, product=False):
        width, height = (640, 480) if product else (32, 32)
        return f'NATIVE_COMPILED_INPUTS scenes=6 frame={width}x{height} rtlEmitted=true'

    def runner(self, out, backend, product=False):
        if backend == 'flashsim':
            import qualify_native_product_flashsim as flash
            return flash.Runner(out, product, out, two_cu_vga=product)
        return q.Runner(out, product, two_cu_vga=product)

    def child(self, runner, code):
        return patch.object(runner, 'sbt', return_value=[sys.executable, '-c', code])

    def manifest_code(self, out, data):
        return (f'from pathlib import Path; import json; out=Path({str(out)!r}); '
                f'(out / "fixture-manifest.json").write_text({json.dumps(data)!r}); ')

    def test_zero_exit_background_failure_cannot_relabel_old_outputs(self):
        code = ('import threading\n'
                'def worker(): raise RuntimeError("generation did not finish")\n'
                't=threading.Thread(target=worker); t.start(); t.join()\n')
        for backend in ['verilator', 'flashsim']:
            with self.subTest(backend=backend), tempfile.TemporaryDirectory() as directory:
                out = Path(directory); self.seed(out); runner = self.runner(out, backend)
                with patch.object(q, 'rtl_inputs', return_value={'source': 'new'}), self.child(runner, code):
                    with self.assertRaisesRegex(RuntimeError, 'preparation is missing'):
                        runner.prepare()
                self.assertEqual(json.loads((out / 'state.json').read_text())['exit_code'], 0)
                self.assertIn('generation did not finish', (out / 'prepare.log').read_text())
                self.assertFalse((out / 'preparation.json').exists())
                self.assertFalse((out / 'fixture-manifest.json').exists())
                self.assertEqual((out / 'rtl/model.sv').read_text(), 'old bytes')
                self.assertEqual((out / 'fixtures/frame.memory').read_text(), 'old bytes')

    def test_completion_requires_exactly_one_matching_rtl_receipt(self):
        correct = self.marker()
        for lines in [[], [correct.replace('scenes=6', 'scenes=5')],
                      [correct.replace('32x32', '640x480')],
                      [correct.replace('true', 'false')], [correct, correct],
                      [correct, 'NATIVE_COMPILED_INPUTS incomplete']]:
            with self.subTest(lines=lines), tempfile.TemporaryDirectory() as directory:
                out = Path(directory); self.seed(out); runner = self.runner(out, 'verilator')
                code = self.manifest_code(out, profile()) + f'print({chr(10).join(lines)!r})'
                with patch.object(q, 'rtl_inputs', return_value={'source': 'new'}), self.child(runner, code):
                    with self.assertRaisesRegex(RuntimeError, 'preparation is missing'):
                        runner.prepare()
                self.assertFalse((out / 'preparation.json').exists())

    def test_receipt_cannot_reuse_an_old_manifest_or_accept_fixtures_only(self):
        for emitted in ['missing', False, 1, 'true']:
            with self.subTest(emitted=emitted), tempfile.TemporaryDirectory() as directory:
                out = Path(directory); self.seed(out); runner = self.runner(out, 'verilator')
                code = '' if emitted == 'missing' else self.manifest_code(out, dict(profile(), rtlEmitted=emitted))
                code += f'print({self.marker()!r})'
                with patch.object(q, 'rtl_inputs', return_value={'source': 'new'}), self.child(runner, code):
                    with self.assertRaises((RuntimeError, FileNotFoundError)):
                        runner.prepare()
                self.assertFalse((out / 'preparation.json').exists())

    def test_fresh_complete_child_outputs_bind_both_backends_and_frame_modes(self):
        for backend in ['verilator', 'flashsim']:
            for product in [False, True]:
                with self.subTest(backend=backend, product=product), tempfile.TemporaryDirectory() as directory:
                    out = Path(directory); self.seed(out); runner = self.runner(out, backend, product)
                    data = profile()
                    if product:
                        data.update(product=True, frameWidth=640, frameHeight=480, computeUnits=2,
                            profile='two-cu-vga60-v1', targetFps=60, apiCapacity=True,
                            screenWidth=640, screenHeight=480, nativeMaxWidth=2048, nativeMaxHeight=2048,
                            pagedCapacity=65536, engines=4, payloadBytes=524288)
                    code = (self.manifest_code(out, data) +
                            '(out / "rtl/model.sv").write_text("new RTL"); '
                            '(out / "fixtures/frame.memory").write_text("new fixtures"); ' +
                            f'print({self.marker(product)!r})')
                    with patch.object(q, 'rtl_inputs', return_value={'source': 'new'}), self.child(runner, code):
                        runner.prepare()
                        record = runner.prepared()
                    self.assertEqual(record['source_sha256'], {'source': 'new'})
                    self.assertEqual(record['rtl_sha256'], {'model.sv': q.sha(out / 'rtl/model.sv')})
                    self.assertEqual(record['fixture_sha256'], {'frame.memory': q.sha(out / 'fixtures/frame.memory')})

    def test_source_change_during_a_successful_child_invalidates_old_preparation(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory); self.seed(out); runner = self.runner(out, 'verilator')
            code = self.manifest_code(out, profile()) + f'print({self.marker()!r})'
            with patch.object(q, 'rtl_inputs', side_effect=[{'source': 'old'}, {'source': 'new'}]), self.child(runner, code):
                with self.assertRaisesRegex(RuntimeError, 'Source changed'):
                    runner.prepare()
            self.assertFalse((out / 'preparation.json').exists())


class GuardTests(unittest.TestCase):
    def test_real_cpp_parser_rejects_mismatched_vga_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory); source = out / 'parse.cpp'; exe = out / 'parse'
            source.write_text('#include "native_frame_fixture.hpp"\n'
                'int main(int argc,char** argv){if(argc!=2)return 2;try{'
                'auto f=native_frame::Fixture::read(argv[1]);return f.two_cu_vga()?0:3;'
                '}catch(const std::exception&){return 1;}}\n')
            subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++17', '-O1',
                '-Wall', '-Wextra', '-Werror', '-I', str(ROOT / 'scripts'),
                str(source), '-o', str(exe)], check=True, capture_output=True)
            compute = [0x47435201,16,15,2,16,8,128,128,0xffffffff,16384,16,8,
                       0x10000000,32,131072,4]
            native = [0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,
                      0x08000800,0xe4908000,0x8813d902]
            resources = [(0x360 + 4 * i, v) for i, v in enumerate(compute)]
            resources += [(0x160 + 4 * i, v) for i, v in enumerate(native)]
            def check(values, width=640, height=480):
                commands = out / 'scene.commands'
                def pairs(items):
                    return struct.pack('<I', len(items)) + b''.join(struct.pack('<II', *v) for v in items)
                commands.write_bytes(b'OGNAT001' + struct.pack('<IIIIQ', width, height, 1, 1,
                    10000000 if width == 32 else 1000000000) + pairs(values) +
                    pairs([(0,0)] * 8) + struct.pack('<I', 13) +
                    struct.pack('<III', 0,0,0) * 13 + pairs([(0,0)] * 2))
                return subprocess.run([str(exe), str(commands)], capture_output=True).returncode
            self.assertEqual(check(resources), 0)
            self.assertEqual(check(resources, 32, 32), 0)
            for index, pair in [(3, (0x36c, 4)), (4, (0x370, 8)),
                    (21, (0x174, 0x01e00280)), (22, (0x178, 2 << 27)),
                    (23, (0x17c, 4)), (16, (0x164, 0x474e4303)),
                    (20, (0x170, 0xc1100800))]:
                changed = list(resources); changed[index] = pair
                with self.subTest(index=index): self.assertEqual(check(changed), 1)
            self.assertEqual(check(resources[:16]), 1)
            integrated = list(resources)
            integrated[2] = (0x368,0x7fff)
            blocks = [
                (0x340,[0x47434901,8,0x7f,14,12,4,0xffffffff,128]),
                (0x240,[0x47515601,8,0x0b0a0908,0x47514f01,64,8,0x01001001,31]),
                (0x260,[0x47464301,8,0x1003010c,0x47544601,512,0x04400408,69,255]),
                (0x280,[0x47515001,8,0x0010040d,0x47504f01,64,0x00204008,0x8c88,63]),
                (0x3a0,[0x47554301,16,1,0x7f,3,12,24,16384]),
                (0x3c0,[1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31])]
            for base, words in blocks:
                integrated.extend((base+4*n,v) for n,v in enumerate(words))
            self.assertEqual(check(integrated),0)
            self.assertEqual(check(integrated,32,32),0)
            for index, (address,value) in enumerate(integrated):
                for changed_word in [(address,value^1),(address^4,value)]:
                    changed = list(integrated); changed[index] = changed_word
                    with self.subTest(word=index,changed=changed_word):
                        self.assertEqual(check(changed),1)
            for changed in [integrated[:-1],integrated+[integrated[-1]],
                            integrated[:24],resources+integrated[24:]]:
                self.assertEqual(check(changed),1)

    def test_integrated_profile_rejects_legacy_host_and_partial_discovery(self):
        candidate = dict(profile(), profile='two-cu-vga60-v2', targetFps=60,
            computeUnits=2, product=True, frameWidth=640, frameHeight=480,
            apiCapacity=True, screenWidth=640, screenHeight=480,
            nativeMaxWidth=2048, nativeMaxHeight=2048, pagedCapacity=65536,
            engines=4, payloadBytes=524288, integratedHost=True,
            transactionsPerCu=16,resourceDiscoveryWords=72,computeFeatureFlags=0x7fff,
            nativeGeometryDraw=False,nativeAdvancedBlend=False,nativePatchShaderImages=False)
        q.check_profile(candidate,True,two_cu_vga=True,alignment_v2=True)
        with self.assertRaises(RuntimeError):
            q.check_profile(candidate,True,two_cu_vga=True)
        for key,value in [('profile','two-cu-vga60-v1'),('computeUnits',4),
                ('integratedHost',False),('transactionsPerCu',4),('resourceDiscoveryWords',24),
                ('computeFeatureFlags',15),('nativeGeometryDraw',True),('nativeAdvancedBlend',True),
                ('nativePatchShaderImages',True),('nativePatchShaderImages',0),
                ('nativeAdvancedBlend',0),('nativeGeometryDraw',None)]:
            with self.subTest(key=key),self.assertRaises(RuntimeError):
                q.check_profile(dict(candidate,**{key:value}),True,two_cu_vga=True,alignment_v2=True)
        rows = [dict(r,frameWidth=640,frameHeight=480,computeUnits=2,nativeEngines=4,
            nativeMaxWidth=2048,nativeMaxHeight=2048,profile='two-cu-vga60-v2',
            resourceDiscoveryWords=72,computeFeatureFlags=0x7fff) for r in metrics()]
        q.validate_metrics(rows,True,True,True)
        for key,value in [('profile','two-cu-vga60-v1'),('resourceDiscoveryWords',24),('computeFeatureFlags',15)]:
            changed=copy.deepcopy(rows);changed[0][key]=value
            with self.subTest(key=key),self.assertRaises(RuntimeError):
                q.validate_metrics(changed,True,True,True)

    def test_two_cu_vga_requires_complete_capacity_and_rejects_legacy_results(self):
        candidate = dict(profile(), profile='two-cu-vga60-v1', targetFps=60,
            computeUnits=2, product=True, frameWidth=640, frameHeight=480,
            apiCapacity=True, screenWidth=640, screenHeight=480,
            nativeMaxWidth=2048, nativeMaxHeight=2048, pagedCapacity=65536,
            engines=4, payloadBytes=524288)
        q.check_profile(candidate, True, two_cu_vga=True)
        with self.assertRaises(RuntimeError):
            q.check_profile(candidate, True, api_capacity=True)
        for field, value in [('computeUnits', 4), ('frameWidth', 1280),
                ('frameHeight', 720), ('screenWidth', 32), ('targetFps', 30),
                ('nativeMaxWidth', 640), ('pagedCapacity', 16384), ('apiCapacity', False),
                ('profile', 'old-two-cu'), ('engines', 2)]:
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                q.check_profile(dict(candidate, **{field: value}), True, two_cu_vga=True)
        q.check_profile(dict(candidate, product=False, frameWidth=32, frameHeight=32),
                        False, two_cu_vga=True)
        rows = [dict(r, frameWidth=640, frameHeight=480, computeUnits=2,
                     nativeEngines=4, nativeMaxWidth=2048, nativeMaxHeight=2048,
                     profile='two-cu-vga60-v1') for r in metrics()]
        q.validate_metrics(rows, True, True)
        for field, value in [('computeUnits', 4), ('nativeEngines', 2),
                             ('profile', None), ('nativeMaxHeight', 480)]:
            changed = copy.deepcopy(rows); changed[2][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                q.validate_metrics(changed, True, True)
        with self.assertRaises(RuntimeError):
            q.validate_metrics(rows, True)

    def test_two_cu_vga_uses_explicit_capacity_flags_without_720p_opt_in(self):
        import qualify_native_product_flashsim as flash
        for runner in [q.Runner(Path('.'), True, two_cu_vga=True),
                flash.Runner(Path('.'), True, Path('.'), two_cu_vga=True)]:
            command = runner.sbt('example --product', '6G')
            self.assertIn('-Dopengpu.productTwoCuVga=1', command)
            self.assertIn('-Dopengpu.productApiCapacity=1', command)
            self.assertIn('-Dopengpu.apiExtentQualification=1', command)
            self.assertIn('-Dopengpu.productQualification=0', command)
        with self.assertRaisesRegex(ValueError, 'boolean'):
            q.Runner(Path('.'), True, two_cu_vga='1')

    def test_integrated_selector_is_explicit_and_propagates_to_both_backends(self):
        import qualify_native_product_flashsim as flash
        for runner in [q.Runner(Path('.'),True,alignment_v2=True),
                       flash.Runner(Path('.'),True,Path('.'),alignment_v2=True)]:
            self.assertTrue(runner.alignment_v2 and runner.two_cu_vga and runner.api_capacity)
            command=runner.sbt('example --product','3G')
            for flag in ['-Dopengpu.productAlignmentV2=1','-Dopengpu.productTwoCuVga=1',
                         '-Dopengpu.productApiCapacity=1','-Dopengpu.apiExtentQualification=1',
                         '-Dopengpu.productQualification=0']:
                self.assertIn(flag,command)
        self.assertFalse(q.Runner(Path('.'),True,two_cu_vga=True).alignment_v2)
        parser=argparse.ArgumentParser();q.add_profile_arguments(parser)
        selected=parser.parse_args(['--alignment-v2'])
        self.assertTrue(selected.alignment_v2)
        self.assertFalse(selected.two_cu_vga)
        with patch('sys.stderr'),self.assertRaises(SystemExit):
            parser.parse_args(['--alignment-v2','--two-cu-vga'])
        with self.assertRaisesRegex(ValueError,'boolean'):
            q.Runner(Path('.'),True,alignment_v2='1')

    def test_two_cu_image_gate_requires_explicit_profile_and_all_vga_images(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'verify.log'
            final = 'NATIVE_COMPILED_PRODUCT PASS scenes=6 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false'
            marker = 'NATIVE_COMPILED_PROFILE profile=two-cu-vga60-v1 computeUnits=2 engines=4 targetFps=60 clockQualified=false fpsQualified=false'
            images = [f'NATIVE_COMPILED_IMAGE id={n} frame=640x480 exactStoredImagesAndGuards=true' for n in q.SCENES]
            log.write_text('\n'.join([*images, final, marker]))
            q.verify_marker(log, True, True)
            for lines in [[*images, final], [*images[:-1], final, marker],
                          [*images, final, marker.replace('computeUnits=2', 'computeUnits=4')]]:
                log.write_text('\n'.join(lines))
                with self.assertRaises(RuntimeError): q.verify_marker(log, True, True)

    def test_integrated_image_gate_requires_v2_marker_and_every_scene(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'verify.log'
            final = 'NATIVE_COMPILED_PRODUCT PASS scenes=6 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false'
            marker = 'NATIVE_COMPILED_PROFILE profile=two-cu-vga60-v2 computeUnits=2 engines=4 targetFps=60 clockQualified=false fpsQualified=false'
            images = [f'NATIVE_COMPILED_IMAGE id={n} frame=640x480 exactStoredImagesAndGuards=true' for n in q.SCENES]
            log.write_text('\n'.join([*images,final,marker]))
            q.verify_marker(log,True,True,True)
            for lines in [[*images,final,marker.replace('-v2','-v1')],
                          [*images[:-1],final,marker],[*images,marker]]:
                log.write_text('\n'.join(lines))
                with self.assertRaises(RuntimeError): q.verify_marker(log,True,True,True)

    def test_combined_api_capacity_requires_exact_manifest_and_flag(self):
        combined = dict(profile(), apiCapacity=True, screenWidth=640, screenHeight=480,
                        nativeMaxWidth=2048, nativeMaxHeight=2048, pagedCapacity=65536,
                        engines=4, payloadBytes=524288)
        q.check_profile(combined, False, True)
        with self.assertRaisesRegex(RuntimeError, 'API-capacity'):
            q.check_profile(combined, False)
        with self.assertRaisesRegex(RuntimeError, 'API-capacity'):
            q.check_profile(profile(), False, True)
        for field, value in [('nativeMaxWidth', 1280), ('nativeMaxHeight', 720),
                             ('pagedCapacity', 16384), ('engines', 2),
                             ('payloadBytes', 65536), ('screenWidth', 32)]:
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, 'combined'):
                q.check_profile(dict(combined, **{field: value}), False, True)
        product = dict(combined, product=True, frameWidth=1280, frameHeight=720,
                       screenWidth=1280, screenHeight=720)
        q.check_profile(product, True, True)
        with self.assertRaisesRegex(RuntimeError, 'combined'):
            q.check_profile(dict(product, screenWidth=640), True, True)

    def test_combined_api_capacity_is_part_of_sbt_command(self):
        regular = q.Runner(Path('.'), False)
        combined = q.Runner(Path('.'), False, api_capacity=True)
        self.assertNotIn('-Dopengpu.productApiCapacity=1', regular.sbt('example', '3G'))
        command = combined.sbt('example', '3G')
        self.assertIn('-Dopengpu.productApiCapacity=1', command)
        self.assertIn('-Dopengpu.apiExtentQualification=1', command)
        with self.assertRaisesRegex(ValueError, 'boolean'):
            q.Runner(Path('.'), False, api_capacity='1')

    def test_qualification_java_heap_override_is_validated(self):
        runner = q.Runner(Path('.'), True)
        with patch.dict(os.environ, {}, clear=True):
            self.assertIn('-J-Xmx24G', runner.sbt('example', q.PRODUCT_JAVA_HEAP))
            self.assertIn('-J-Xmx3G', runner.sbt('example', '3G'))
        with patch.dict(os.environ, {'OPENGPU_QUALIFY_JAVA_HEAP': '32G'}):
            self.assertIn('-J-Xmx32G', runner.sbt('example', q.PRODUCT_JAVA_HEAP))
        for value in ('', '0G', '-1G', '16G -Xss4G', 'large'):
            with self.subTest(value=value), patch.dict(os.environ, {'OPENGPU_QUALIFY_JAVA_HEAP': value}):
                with self.assertRaisesRegex(ValueError, 'positive size'):
                    runner.sbt('example', q.PRODUCT_JAVA_HEAP)

    def test_reference_rejects_mixed_startup_modes_and_requires_exact_counters(self):
        shared = dict(mode=q.SHARED_MODEL, max_workers=1)
        fresh = dict(mode=q.FRESH_MODELS, max_workers=3)
        reference = dict(scenes=[dict(row, id=row['scene']) for row in metrics()])
        q.compare_reference(metrics(), reference, shared)
        with self.assertRaisesRegex(RuntimeError, 'execution modes differ'):
            q.compare_reference(metrics(), reference, fresh)
        reference['scene_execution'] = fresh
        q.compare_reference(metrics(), reference, fresh)
        with self.assertRaisesRegex(RuntimeError, 'execution modes differ'):
            q.compare_reference(metrics(), reference, shared)
        reference['scenes'][4]['serviceCycles'] -= 1
        with self.assertRaisesRegex(RuntimeError, 'counters differ: small-triangles'):
            q.compare_reference(metrics(), reference, fresh)
        reference['scenes'] = reference['scenes'][:-1]
        with self.assertRaisesRegex(RuntimeError, 'all six'):
            q.compare_reference(metrics(), reference, fresh)

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
            self.assertEqual(data['scene_execution'], dict(mode=q.SHARED_MODEL, max_workers=1))
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


class ParallelSceneTests(unittest.TestCase):
    def prepare(self, out, options=None):
        fixtures = out / 'fixtures'; fixtures.mkdir()
        (fixtures / 'test-config.json').write_text(json.dumps(dict(rows=metrics(), options=options or {})))
        for name in ['preparation.json', 'build.json']:
            (out / name).write_text('source-bound test placeholder')
        executable = out / 'native_product_driver'
        executable.write_text('#!' + sys.executable + '\n' + '''
import fcntl, json, os, signal, sys, time
from pathlib import Path
fixtures, target = map(Path, sys.argv[1:3])
assert sys.argv[3] == '--scene'
name = sys.argv[4]
config = json.loads((fixtures / 'test-config.json').read_text())
options = config['options'].get(name, {})
def event(kind):
    with (fixtures / 'events.jsonl').open('a') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        stream.write(json.dumps(dict(scene=name, pid=os.getpid(), event=kind)) + '\\n')
        stream.flush()
def stop(sig, frame):
    event('terminated')
    raise SystemExit(128 + sig)
signal.signal(signal.SIGTERM, stop)
event('start')
time.sleep(options.get('delay', 0.15))
if options.get('fail'):
    event('end')
    sys.exit('deliberate test child failure')
row = next(row for row in config['rows'] if row['scene'] == name)
if options.get('wrong_scene'): row['scene'] = 'unexpected-scene'
if not options.get('missing_memory'):
    (target / (name + '.memory')).write_bytes(('stored bytes for ' + name).encode())
if not options.get('missing_metrics'): print(json.dumps(row), flush=True)
event('end')
''')
        executable.chmod(0o755)
        return executable

    def run_batch(self, out, jobs):
        runner = q.Runner(out, False, scene_jobs=jobs)
        with patch.object(q, 'guard_prepared', return_value={}), patch.object(q, 'guard_build', return_value={}):
            runner.run()
        return runner, json.loads((out / 'execution.json').read_text())

    def test_bounded_children_aggregate_all_scenes_in_order_without_copying_frames(self):
        for jobs in [2, 3]:
            with self.subTest(jobs=jobs), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                self.prepare(out, {q.SCENES[0]: dict(delay=0.5)})
                runner, record = self.run_batch(out, jobs)
                self.assertEqual(record['scenes'], metrics())
                self.assertEqual(record['scene_execution'], dict(mode=q.FRESH_MODELS, max_workers=jobs))
                target = Path(record['directory'])
                self.assertEqual(record['output_sha256'], q.tree_hashes(target))
                for name in q.SCENES:
                    memory = target / (name + '.memory')
                    self.assertEqual(memory.read_bytes(), ('stored bytes for ' + name).encode())
                    self.assertEqual(memory.stat().st_ino, (target / 'scenes' / name / memory.name).stat().st_ino)
                events = [json.loads(line) for line in (out / 'fixtures/events.jsonl').read_text().splitlines()]
                active, peak = set(), 0
                for event in events:
                    if event['event'] == 'start': active.add(event['scene'])
                    else: active.remove(event['scene'])
                    peak = max(peak, len(active))
                self.assertFalse(active)
                self.assertGreaterEqual(peak, 2)
                self.assertLessEqual(peak, jobs)
                self.assertNotEqual([e['scene'] for e in events if e['event'] == 'end'], q.SCENES)
                self.assertFalse((out / 'qualification.json').exists())
                with patch.object(q, 'guard_prepared', return_value={}), patch.object(q, 'guard_build', return_value={}):
                    with self.assertRaisesRegex(RuntimeError, 'recorded --scene-jobs'):
                        q.Runner(out, False).verify(None)
                    # The full original independent image marker is still required.
                    with patch.object(runner, 'execute', side_effect=lambda stage, command, log, **kw: log.write_text('partial image check\n')):
                        with self.assertRaisesRegex(RuntimeError, 'independent stored-image'):
                            runner.verify(None)
                self.assertFalse((out / 'qualification.json').exists())

    def test_actual_child_failure_stops_owned_children_and_preserves_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            self.prepare(out, {q.SCENES[0]: dict(delay=0.2, fail=True), q.SCENES[1]: dict(delay=90)})
            with self.assertRaisesRegex(RuntimeError, 'desktop-composition failed with exit'):
                self.run_batch(out, 2)
            self.assertFalse((out / 'execution.json').exists())
            self.assertFalse((out / 'qualification.json').exists())
            events = [json.loads(line) for line in (out / 'fixtures/events.jsonl').read_text().splitlines()]
            self.assertEqual({e['scene'] for e in events if e['event'] == 'start'}, set(q.SCENES[:2]))
            self.assertIn('terminated', [e['event'] for e in events if e['scene'] == q.SCENES[1]])
            for pid in {e['pid'] for e in events}:
                with self.assertRaises(ProcessLookupError): os.kill(pid, 0)
            logs = list((out / 'runs').rglob('execution.log'))
            self.assertTrue(any('deliberate test child failure' in p.read_text() for p in logs))

    def test_qualification_keeps_execution_mode_and_rejects_changed_outputs_or_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory); self.prepare(out)
            runner, record = self.run_batch(out, 3)
            prepared = dict(source_sha256={}, rtl_sha256={})
            reference = out / 'reference.json'
            reference.write_text(json.dumps(dict(scene_execution=record['scene_execution'],
                scenes=[dict(row, id=row['scene']) for row in metrics()])))
            def oracle(stage, command, log, **kwargs):
                # Exercise the report gate without building or simulating GPU RTL.
                images = [f'NATIVE_COMPILED_IMAGE id={n} frame=32x32 exactStoredImagesAndGuards=true' for n in q.SCENES]
                final = 'NATIVE_COMPILED_PRODUCT PASS scenes=6 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false'
                log.write_text('\n'.join([*images, final]))
            with patch.object(q, 'guard_prepared', return_value=prepared), patch.object(q, 'guard_build', return_value={}):
                with patch.object(runner, 'execute', side_effect=oracle):
                    runner.verify(reference)
                qualification = out / 'qualification.json'
                self.assertEqual(json.loads(qualification.read_text())['scene_execution'], record['scene_execution'])
                self.assertEqual(json.loads(qualification.read_text())['matched_reference_sha256'], q.sha(reference))
                qualification.unlink()
                def changed_reference(*args, **kwargs):
                    oracle(*args, **kwargs)
                    reference.write_text(reference.read_text() + '\n')
                with patch.object(runner, 'execute', side_effect=changed_reference):
                    with self.assertRaisesRegex(RuntimeError, 'reference changed'):
                        runner.verify(reference)
                self.assertFalse(qualification.exists())
                (Path(record['directory']) / (q.SCENES[0] + '.memory')).write_bytes(b'changed stored bytes')
                with patch.object(runner, 'execute') as execute:
                    with self.assertRaisesRegex(RuntimeError, 'output bytes changed'):
                        runner.verify(None)
                    execute.assert_not_called()

    def test_missing_metrics_memory_or_wrong_scene_cannot_publish_an_execution(self):
        for error in ['missing_metrics', 'missing_memory', 'wrong_scene']:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as directory:
                out = Path(directory); self.prepare(out, {q.SCENES[0]: {error: True}})
                with self.assertRaises((RuntimeError, FileNotFoundError)):
                    self.run_batch(out, 2)
                self.assertFalse((out / 'execution.json').exists())
                self.assertFalse((out / 'qualification.json').exists())

    def test_scene_worker_limit_is_bounded(self):
        for jobs in [0, -1, 7]:
            with self.assertRaises(ValueError): q.Runner(Path('.'), False, scene_jobs=jobs)


if __name__ == '__main__':
    unittest.main()
