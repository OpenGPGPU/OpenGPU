#!/usr/bin/env python3
"""CPU rejection tests; synthetic metadata here is never GPU execution evidence."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import qualify_native_layered_product as q
from owner_resource_contract import COMPUTE_STAGE, COMPUTE_WORDS
import test_prepare_native_owner_flashsim as fixtures


class LayeredProductTests(unittest.TestCase):
    def manifest(self, emitted=True):
        manifest = fixtures.OwnerInputTests().manifest()
        manifest.update(version=2, profile=q.PROFILE, computeFeatureFlags=0xfffff,
            resourceDiscoveryWords=104, ownerResourceVersion=2, ownerResourceStage=q.FULL_STAGE,
            nativeGeometryDraw=True, nativeGeometryTransport=True, transformFeedback=True,
            fullResourceQualification=False, requiredDiscovery=copy.deepcopy(q.FULL_WORDS),
            rtlEmitted=emitted)
        return manifest

    def rows(self, product):
        width, height = q.common.frame_size(product, True)
        return [dict(scene=name, profile=q.PROFILE, ownerResourceStage=q.FULL_STAGE,
            computeUnits=2, nativeEngines=4, nativeMaxWidth=2048, nativeMaxHeight=2048,
            resourceDiscoveryWords=104, computeFeatureFlags=0xfffff,
            nativeJobVersion=2, nativeFrameVersion=3, nativeCompletionVersion=2,
            frameWidth=width, frameHeight=height, samples=4 if name == 'msaa-4x' else 1,
            serviceCycles=100, gpuCycles=180, readBusBytes=32, writeBusBytes=64,
            enabledWriteBytes=48, phaseWorkerCycles=[0, 10, 20, 30, 40, 50],
            trafficDrained=True, imageVerificationRequired=True, clockQualified=False,
            fpsQualified=False, cycleScopeVersion=2, serviceCycleScope='post-submit-to-irq',
            frameCycleScope='reset-through-verified-retirement', frameCycles=250,
            gpuCyclesAtServiceBegin=40, gpuCyclesAtIrq=140, submittedCommands=1,
            successfulCompletions=1, retiredCompletions=1,
            completionCountSource='observed-command-retirement', drawCompletionCountAvailable=False,
            primitiveCompletionCountAvailable=False, liveProfilePreflight=True, idleBeforeSubmit=True,
            shaderInputSource='native-layered-product-hand-encoded',
            gnx=[0x474e5801, 0, 0, 0, 0, 0, 7, 8, 9, 0, 0, 0],
            resourceDiscovery=copy.deepcopy(q.FULL_WORDS), profileRegisters=[
                dict(address=0, value=2),
                dict(address=0x60, value=(1<<6)|(1<<22)|(1<<23)|(1<<25)|(1<<29)|(128<<8)),
                dict(address=0x68, value=0x160), dict(address=0x320, value=1048576),
                dict(address=0x158, value=16|(8<<8)|(32<<16)|(2<<24))])
            for name in q.common.SCENES]

    def test_refuses_v1_compute_stage_and_describe_only_before_fixture_work(self):
        full = self.manifest()
        q.check_full_manifest(full)
        for bad in (fixtures.OwnerInputTests().manifest(), dict(full, profile=COMPUTE_STAGE,
                requiredDiscovery=COMPUTE_WORDS, computeFeatureFlags=0x7ffff,
                resourceDiscoveryWords=96, ownerResourceStage='compute'), self.manifest(False)):
            with self.assertRaises(RuntimeError):
                q.check_full_manifest(bad)
        with tempfile.TemporaryDirectory(dir=q.ROOT / '.artifacts', prefix='layered-guard-') as d:
            out = Path(d)
            runner = q.Runner(out, False, out, api_capacity=True, two_cu_vga=True)
            (out / 'owner-manifest.json').write_text(json.dumps(fixtures.OwnerInputTests().manifest()))
            with patch.object(q.owner, 'prepared', return_value={}), patch.object(runner, 'execute') as execute:
                with self.assertRaises(RuntimeError):
                    runner.prepare()
                execute.assert_not_called()
            self.assertFalse((out / 'fixtures').exists())

    def test_requires_all_original_frames_actual_full_discovery_and_unqualified_timing(self):
        for product in (False, True):
            rows = self.rows(product)
            q.validate_rows(rows, product)
            for bad in (None, [{}], rows[:-1], rows[::-1], rows+rows[:1], self.rows(not product)):
                with self.assertRaises(RuntimeError):
                    q.validate_rows(bad, product)
            for key, value in (
                ('profile', q.owner.PROFILE), ('profile', COMPUTE_STAGE),
                ('ownerResourceStage', 'compute'), ('resourceDiscoveryWords', 96),
                ('computeFeatureFlags', 0x7ffff), ('nativeJobVersion', 1),
                ('nativeFrameVersion', 1), ('nativeCompletionVersion', 1),
                ('computeUnits', 4), ('nativeMaxWidth', 640), ('samples', True),
                ('serviceCycles', 0), ('gpuCycles', True), ('enabledWriteBytes', -1),
                ('phaseWorkerCycles', [0]*5), ('phaseWorkerCycles', [False]*6),
                ('trafficDrained', False), ('clockQualified', True), ('fpsQualified', True),
                ('resourceDiscovery', q.owner.EXPECTED_WORDS), ('resourceDiscovery', COMPUTE_WORDS)):
                wrong = copy.deepcopy(rows)
                wrong[0][key] = value
                with self.subTest(product=product, key=key), self.assertRaises(RuntimeError):
                    q.validate_rows(wrong, product)
        rows = self.rows(False)
        for index in range(104):
            for field, value in (('address', True), ('value', True),
                                 ('address', q.FULL_WORDS[index]['address'] ^ 4),
                                 ('value', q.FULL_WORDS[index]['value'] ^ 1)):
                wrong = copy.deepcopy(rows)
                wrong[0]['resourceDiscovery'][index][field] = value
                with self.subTest(index=index, field=field), self.assertRaises(RuntimeError):
                    q.validate_rows(wrong, False)
        for words in (q.FULL_WORDS[:-1], q.FULL_WORDS[::-1], q.FULL_WORDS + q.FULL_WORDS[:1]):
            wrong = copy.deepcopy(rows)
            wrong[0]['resourceDiscovery'] = words
            with self.assertRaises(RuntimeError):
                q.validate_rows(wrong, False)

    def test_requires_coarse_success_all_identities_retirement_and_live_profile(self):
        rows = self.rows(False)
        for key, value in (
            ('cycleScopeVersion', 1), ('frameCycles', True), ('frameCycles', 180),
            ('gpuCyclesAtServiceBegin', 41), ('gpuCyclesAtIrq', 141),
            ('gpuCyclesAtIrq', 2**64), ('serviceCycleScope', 'frame'),
            ('frameCycleScope', 'post-submit-to-irq'), ('submittedCommands', 2),
            ('retiredCompletions', 0), ('successfulCompletions', False),
            ('completionCountSource', 'inferred-draw-count'), ('drawCompletionCountAvailable', True),
            ('primitiveCompletionCountAvailable', True), ('liveProfilePreflight', False),
            ('idleBeforeSubmit', False), ('shaderInputSource', 'mesa'), ('gnx', []),
            ('profileRegisters', [])):
            wrong = copy.deepcopy(rows)
            wrong[0][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                q.validate_rows(wrong, False)
        for index in range(12):
            for bit in range(32):
                wrong = copy.deepcopy(rows)
                wrong[0]['gnx'][index] ^= 1 << bit
                with self.assertRaises(RuntimeError):
                    q.validate_rows(wrong, False)
        for index in range(5):
            for field, value in (('address', 1), ('value', True), ('value', 0), ('value', 2**32)):
                wrong = copy.deepcopy(rows)
                wrong[0]['profileRegisters'][index][field] = value
                with self.assertRaises(RuntimeError):
                    q.validate_rows(wrong, False)

    def test_requires_every_independent_image_and_full_gny2_marker(self):
        with tempfile.TemporaryDirectory(dir=q.ROOT / '.artifacts', prefix='layered-marker-') as d:
            log = Path(d) / 'verify.log'
            for product in (False, True):
                width, height = q.common.frame_size(product, True)
                for tile in (3, 4):
                    lines = [f'NATIVE_LAYERED_PRODUCT_IMAGE scene={name} tile={1 << tile} '
                        f'frame={width}x{height} exactImagesSamplesResolvesGuardsAndGNY2=true'
                        for name in q.common.SCENES] + [q.marker(product, tile)]
                    log.write_text('\n'.join(lines)+'\n')
                    q.verify_marker(log, product, tile)
                    for bad in (lines[:-1], lines[1:], lines[::-1], lines+lines[-1:],
                                [line.replace('AndGNY2=true', '=true') for line in lines]):
                        log.write_text('\n'.join(bad)+'\n')
                        with self.assertRaises(RuntimeError):
                            q.verify_marker(log, product, tile)

    def test_new_attempt_discards_old_success_before_any_preflight_failure(self):
        with tempfile.TemporaryDirectory(dir=q.ROOT / '.artifacts', prefix='layered-failed-') as d:
            out = Path(d)
            runner = q.Runner(out, False, out, api_capacity=True, two_cu_vga=True)
            for name in ('execution.json', 'qualification.json'):
                (out / name).write_text('{"passed":true}')
            with patch.object(runner, 'prepared', side_effect=RuntimeError('source changed')):
                with self.assertRaisesRegex(RuntimeError, 'source changed'):
                    runner.run()
            self.assertFalse((out / 'execution.json').exists())
            self.assertFalse((out / 'qualification.json').exists())

    def test_cli_preserves_build_options_and_declares_extra_header_dependencies(self):
        with tempfile.TemporaryDirectory(dir=q.ROOT / '.artifacts', prefix='layered-cli-') as d:
            out = Path(d)
            argv = ['layered-product', '--product', '--stage', 'build', '--output', d,
                '--flashsim-dir', d, '--tile-log2', '4', '--jobs', '2', '--shards', '32',
                '--precompiled-header', '--conservative-alias', '--shard-opt=-O0']
            with patch.object(sys, 'argv', argv), patch.object(q, 'checked_output', return_value=out), \
                    patch.object(q, 'Runner') as runner:
                q.main()
                runner.assert_called_once_with(out, True, out, 1, 32, reshard=None,
                    reshard_tool=None, precompiled_header=True, shard_opt='-O0',
                    conservative_alias=True, api_capacity=True, two_cu_vga=True, tile_log2=4)
                runner.return_value.build.assert_called_once_with(2)
                runner.return_value.run.assert_not_called()
            runner = q.Runner(out, False, out, api_capacity=True, two_cu_vga=True)
            self.assertEqual(runner.extra_driver_dependencies(), [q.ROOT / name for name in (
                'scripts/native_layered_product_fixture.hpp', 'scripts/native_layered_product_execution.hpp',
                'driver/opengpu_native_layered_abi.h')])


if __name__ == '__main__':
    unittest.main()
