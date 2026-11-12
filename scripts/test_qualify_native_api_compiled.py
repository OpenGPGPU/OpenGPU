#!/usr/bin/env python3
"""CPU guards and binary interchange only; never render an API-size frame in CI."""
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import qualify_native_api_compiled as api
import qualify_native_product_compiled as product


def profile():
    return dict(version=1, product=True, apiExtent=True, frameWidth=640, frameHeight=480,
        screenWidth=640, screenHeight=480, computeUnits=4, lanes=16, warps=8, engines=4,
        axiDataBytes=32, pagedCapacity=65536, payloadBytes=524288,
        realSramModels=True, rtlEmitted=True, scenes=api.SCENES)


def metrics():
    return [dict(scene=name, frameWidth=640, frameHeight=480, samples=samples,
        serviceCycles=100, gpuCycles=180, trafficDrained=True,
        imageVerificationRequired=True, phaseWorkerCycles=[1]*6,
        clockQualified=False, fpsQualified=False) for name, samples in zip(api.SCENES, [1, 4])]


class GuardTests(unittest.TestCase):
    def test_api_preparation_uses_the_product_heap_only_for_product(self):
        for is_product, expected in [(True, '-J-Xmx24G'), (False, '-J-Xmx6G')]:
            with self.subTest(product=is_product), tempfile.TemporaryDirectory() as directory:
                runner = api.Runner(Path(directory), is_product)
                with patch.dict('os.environ', {}, clear=True), \
                     patch.object(product, 'rtl_inputs', return_value={}), \
                     patch.object(runner, 'execute', side_effect=RuntimeError('captured')) as execute:
                    with self.assertRaisesRegex(RuntimeError, 'captured'):
                        runner.prepare()
                self.assertIn(expected, execute.call_args.args[1])

    def test_profile_retains_all_execution_storage_and_directory_capacities(self):
        api.check_profile(profile())
        for key, value in [('product', False), ('apiExtent', False), ('frameWidth', 2048), ('frameWidth', 1280),
                ('frameHeight', 720), ('screenWidth', 2048), ('computeUnits', 2),
                ('lanes', 4), ('warps', 2), ('engines', 2), ('axiDataBytes', 16),
                ('pagedCapacity', 16384), ('payloadBytes', 16384), ('realSramModels', False),
                ('rtlEmitted', False), ('scenes', api.SCENES[:1])]:
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                api.check_profile(dict(profile(), **{key: value}))
        for mode in [True, False]:
            with self.assertRaises(RuntimeError): product.check_profile(profile(), mode)

    def test_both_frames_and_samples_must_complete_without_clock_claim(self):
        api.validate_metrics(metrics())
        for rows in [metrics()[:1], list(reversed(metrics())), [metrics()[0]]*2]:
            with self.assertRaises(RuntimeError): api.validate_metrics(rows)
        for key, value in [('samples', 1), ('trafficDrained', False), ('serviceCycles', 0),
                ('gpuCycles', 0), ('frameWidth', 32), ('clockQualified', True),
                ('fpsQualified', True), ('imageVerificationRequired', False), ('phaseWorkerCycles', [])]:
            rows=metrics(); rows[1][key]=value
            with self.subTest(key=key), self.assertRaises(RuntimeError): api.validate_metrics(rows)
        with self.assertRaises(RuntimeError): product.validate_metrics(metrics(), True)

    def test_both_full_image_oracles_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            log=Path(directory)/'verify.log'
            lines=[f'NATIVE_API_COMPILED_IMAGE id={n} frame=640x480 exactStoredImagesAndGuards=true' for n in api.SCENES]
            final='NATIVE_COMPILED_API PASS frames=2 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false'
            log.write_text('\n'.join(lines+[final])); api.verify_marker(log)
            for bad in [lines, lines[:1]+[final], [lines[0]]*2+[final], list(reversed(lines))+[final]]:
                log.write_text('\n'.join(bad))
                with self.assertRaises(RuntimeError): api.verify_marker(log)

    def test_single_frame_diagnostics_cannot_qualify_the_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'verify.log'
            for is_product, extent in [(True, '640x480'), (False, '32x32')]:
                lines = [f'ONE_NATIVE_API_SCENE PASS id={name} frame={extent} samples={samples} '
                    'exactStoredImagesAndGuards=true fullSuiteQualified=false'
                    for name, samples in zip(api.SCENES, [1, 4])]
                final = ('NATIVE_COMPILED_API PASS frames=2 fullExecutionAndStorage=true '
                    'exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false')
                for partial in [lines[:1], lines, lines + [final]]:
                    with self.subTest(product=is_product, records=len(partial)):
                        log.write_text('\n'.join(partial))
                        with self.assertRaises(RuntimeError):
                            api.verify_marker(log, is_product)

    def test_preparation_binds_source_fixtures_rtl_and_manifest(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(product, 'rtl_inputs', return_value={'source':'one'}):
            out=Path(directory); (out/'rtl').mkdir(); (out/'fixtures').mkdir()
            (out/'rtl/model.sv').write_text('one'); (out/'fixtures/frame.memory').write_bytes(b'one')
            product.write_json(out/'fixture-manifest.json', profile())
            product.write_json(out/'preparation.json', dict(exit_code=0, source_sha256={'source':'one'},
                manifest_sha256=product.sha(out/'fixture-manifest.json'),
                rtl_sha256=product.tree_hashes(out/'rtl'), fixture_sha256=product.tree_hashes(out/'fixtures')))
            api.guard_prepared(out)
            with patch.object(product, 'rtl_inputs', return_value={'source':'two'}):
                with self.assertRaises(RuntimeError): api.guard_prepared(out)
            for file in [out/'rtl/model.sv', out/'fixtures/frame.memory', out/'fixture-manifest.json']:
                original=file.read_bytes(); file.write_bytes(original+b' ')
                with self.assertRaises(RuntimeError): api.guard_prepared(out)
                file.write_bytes(original)

    def test_api_flags_and_driver_identity_are_explicit(self):
        runner=api.Runner(Path('/unused'))
        command=runner.sbt('main', '1G')
        for flag in ['fullHostQualification', 'productQualification', 'apiExtentQualification']:
            self.assertIn('-Dopengpu.'+flag+'=1', command)
        self.assertEqual(runner.executable_name, 'native_api_driver')
        self.assertEqual(runner.driver_sources()[0], 'scripts/native_api_driver.cpp')
        self.assertIn('scripts/native_host_simulation.hpp', runner.driver_sources())
        self.assertNotIn('-Dopengpu.apiExtentQualification=1', product.Runner(Path('/unused'), True).sbt('main','1G'))
        smoke=api.Runner(Path('/unused'), product=False)
        self.assertIn('-Dopengpu.productQualification=0', smoke.sbt('main','1G'))
        self.assertEqual(smoke.mode(), '--smoke')

    def test_original_driver_checkout_is_hash_checked_and_verify_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); out = root/'output'; old = root/'original'; out.mkdir(); old.mkdir()
            for name in api.DRIVER:
                path = old/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('original '+name)
            exe = out/'native_api_driver'; exe.write_bytes(b'model')
            files = {'GpuComputeUnit.sv':'cu', 'Rgba8BlendFmaEngine.sv':'blend',
                     'verification/assert/layers-GpuHostSystemAxi-Verification-Assert.sv':'assertions'}
            prepared = dict(rtl_sha256=files, manifest_sha256='fixture-manifest')
            context = dict(rtl_sha256=files, profile_manifest_sha256='fixture-manifest',
                driver_sha256={name:product.sha(old/name) for name in api.DRIVER},
                assertion_layers=product.assertion_layer_hashes(files),
                hierarchy=product.hierarchy_plan(files,'flat'))
            product.write_json(out/'build.json', dict(exit_code=0, executable_sha256=product.sha(exe), context=context))
            runner = api.Runner(out, product=True, driver_root=old)
            self.assertEqual(runner.checked_build(prepared)['context'], context)
            for action in (lambda:runner.prepare(), lambda:runner.build(1), lambda:runner.run()):
                with self.assertRaisesRegex(RuntimeError, 'only for verification'):
                    action()
            (old/api.DRIVER[0]).write_text('changed driver')
            with self.assertRaisesRegex(RuntimeError, 'does not match'):
                runner.checked_build(prepared)
            with self.assertRaisesRegex(RuntimeError, 'does not match'):
                product.guard_build(out, prepared, 'flat', api.DRIVER, 'native_api_driver')
            rejected = subprocess.run([sys.executable, str(api.ROOT/'scripts/qualify_native_api_compiled.py'),
                '--smoke', '--output', str(out), '--stage', 'run', '--original-driver-checkout', str(old)],
                capture_output=True, text=True)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('requires --stage verify', rejected.stderr)

    def test_smoke_evidence_cannot_be_promoted_to_api_extent_product(self):
        small=dict(profile(), product=False, frameWidth=32, frameHeight=32)
        api.check_profile(small, False)
        with self.assertRaises(RuntimeError): api.check_profile(small, True)
        with self.assertRaises(RuntimeError): api.check_profile(profile(), False)
        rows=metrics()
        for row in rows: row.update(frameWidth=32, frameHeight=32)
        api.validate_metrics(rows, False)
        with self.assertRaises(RuntimeError): api.validate_metrics(rows, True)
        with tempfile.TemporaryDirectory() as directory:
            log=Path(directory)/'verify.log'
            lines=[f'NATIVE_API_COMPILED_IMAGE id={n} frame=32x32 exactStoredImagesAndGuards=true' for n in api.SCENES]
            final='NATIVE_COMPILED_API PASS frames=2 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false'
            log.write_text('\n'.join(lines+[final]))
            api.verify_marker(log, False)
            with self.assertRaises(RuntimeError): api.verify_marker(log, True)

    def test_actual_cpp_binary_parser_keeps_api_and_six_scene_formats_disjoint(self):
        compiler=shutil.which('c++'); self.assertIsNotNone(compiler)
        with tempfile.TemporaryDirectory() as directory:
            out=Path(directory); source=out/'parse.cpp'; executable=out/'parse'
            source.write_text('#include "native_api_frame_fixture.hpp"\nint main(int argc,char**argv){'
                'if(argc!=3)return 2;try{if(std::string(argv[1])=="api")native_frame::ApiFixture::read(argv[2],true);'
                'else if(std::string(argv[1])=="smoke")native_frame::ApiFixture::read(argv[2],false);'
                'else native_frame::Fixture::read(argv[2]);return 0;}catch(const std::exception&){return 1;}}\n')
            subprocess.run([compiler, '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I', str(api.ROOT/'scripts'),
                str(source), '-o', str(executable)], check=True)
            def fixture(magic=b'OGAPI001', width=640, height=480, samples=1, identifier=1,
                        cycles=1000000000, resources=24, checks=13, trailing=b'', resource_words=None):
                commands=lambda count:struct.pack('<I',count)+bytes(count*8)
                resource_data = commands(resources) if resource_words is None else (
                    struct.pack('<I',len(resource_words)) + b''.join(struct.pack('<II',a,v) for a,v in resource_words))
                return magic+struct.pack('<IIIIQ',width,height,samples,identifier,cycles)+resource_data+commands(8)+struct.pack('<I',checks)+bytes(checks*12)+commands(2)+trailing
            path=out/'frame.commands'
            def accepted(data, mode):
                path.write_bytes(data)
                return subprocess.run([str(executable),mode,str(path)],capture_output=True).returncode==0
            self.assertTrue(accepted(fixture(), 'api'))
            self.assertTrue(accepted(fixture(samples=4,identifier=2), 'api'))
            small=fixture(width=32,height=32,cycles=10000000)
            self.assertTrue(accepted(small, 'smoke'))
            self.assertFalse(accepted(small, 'api'))
            self.assertFalse(accepted(fixture(), 'smoke'))
            self.assertFalse(accepted(fixture(), 'product'))
            legacy=fixture(magic=b'OGNAT001',width=32,height=32,cycles=10000000,resources=16)
            self.assertTrue(accepted(legacy, 'product')); self.assertFalse(accepted(legacy, 'api'))
            native=[(0x160+4*i, 0) for i in range(8)]
            native[0]=(0x160,0x474e4303); native[1]=(0x164,8); native[5]=(0x174,0x08000800)
            combined=[(0,0)]*16+native
            self.assertTrue(accepted(fixture(magic=b'OGNAT001',width=32,height=32,
                cycles=10000000,resource_words=combined), 'product'))
            for index,value in [(16,(0x160,0x474e4302)),(17,(0x164,7)),
                                (21,(0x174,0x02d00500)),(23,(0x180,0))]:
                changed=combined.copy(); changed[index]=value
                with self.subTest(resource=index):
                    self.assertFalse(accepted(fixture(magic=b'OGNAT001',width=32,height=32,
                        cycles=10000000,resource_words=changed), 'product'))
            for change in [dict(width=2048,height=2048), dict(width=1280), dict(height=720), dict(samples=4), dict(identifier=3),
                    dict(cycles=10000000), dict(resources=16), dict(checks=12), dict(trailing=b'x')]:
                with self.subTest(change=change): self.assertFalse(accepted(fixture(**change), 'api'))


if __name__ == '__main__':
    unittest.main()
