#!/usr/bin/env python3
"""CPU metadata rejection only; no fabricated GPU success or rendered output."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import qualify_native_layered_product as old
import qualify_native_sample_layered_product as sample
from owner_resource_contract import SAMPLE_WORDS
import test_prepare_native_sample_owner as sample_guard
import test_qualify_native_layered_product as old_tests


class SampleLayeredQualificationTests(unittest.TestCase):
    def rows(self, product):
        rows = old_tests.LayeredProductTests().rows(product)
        for row in rows:
            row.update(profile=sample.CONTRACT.profile, ownerResourceStage=sample.CONTRACT.stage,
                resourceDiscoveryWords=112, computeFeatureFlags=0x1fffff,
                resourceDiscovery=copy.deepcopy(SAMPLE_WORDS), nativeSampleTransport=True,
                nativeSampleShading=True, sampleBuiltinsQualified=False)
        return rows

    def test_fixed_runners_reject_each_others_factory_before_work(self):
        old_manifest = old_tests.LayeredProductTests().manifest()
        new_manifest = sample_guard.SampleOwnerManifestTests().manifest()
        old.check_full_manifest(old_manifest)
        sample.check_manifest(new_manifest)
        with self.assertRaises(RuntimeError):
            old.check_full_manifest(new_manifest)
        with self.assertRaises(RuntimeError):
            sample.check_manifest(old_manifest)
        for runner_type, wrong in ((sample.Runner, old_manifest), (old.Runner, new_manifest)):
            with tempfile.TemporaryDirectory(dir=old.ROOT / '.artifacts', prefix='sample-profile-') as d:
                out = Path(d)
                runner = runner_type(out, False, out, api_capacity=True, two_cu_vga=True)
                (out / 'owner-manifest.json').write_text(json.dumps(wrong))
                with patch.object(old.owner, 'prepared', return_value={}), patch.object(runner, 'execute') as execute:
                    with self.assertRaises(RuntimeError):
                        runner.prepare()
                    execute.assert_not_called()
                self.assertFalse((out / 'fixtures').exists())
        with self.assertRaises(RuntimeError):
            old.contract_words(None)

    def test_exact_six_scene_rows_and_all_112_observations(self):
        for product in (False, True):
            rows = self.rows(product)
            sample.validate_rows(rows, product)
            with self.assertRaises(RuntimeError):
                old.validate_rows(rows, product)
            with self.assertRaises(RuntimeError):
                sample.validate_rows(old_tests.LayeredProductTests().rows(product), product)
            for invalid in (rows[:-1], rows[::-1], rows + rows[:1], self.rows(not product)):
                with self.assertRaises(RuntimeError):
                    sample.validate_rows(invalid, product)
            for key, value in (('profile', old.PROFILE), ('ownerResourceStage', old.FULL_STAGE),
                    ('resourceDiscoveryWords', 104), ('computeFeatureFlags', 0xfffff),
                    ('nativeSampleTransport', False), ('nativeSampleShading', False),
                    ('sampleBuiltinsQualified', True), ('sampleBuiltinsQualified', 0),
                    ('nativeJobVersion', 1), ('nativeFrameVersion', 1), ('nativeCompletionVersion', 1),
                    ('trafficDrained', False), ('retiredCompletions', 0), ('clockQualified', True)):
                wrong = copy.deepcopy(rows)
                wrong[0][key] = value
                with self.subTest(product=product, key=key), self.assertRaises(RuntimeError):
                    sample.validate_rows(wrong, product)
        rows = self.rows(False)
        for index in range(112):
            for field, value in (('address', True), ('value', True),
                    ('address', SAMPLE_WORDS[index]['address'] ^ 4),
                    ('value', SAMPLE_WORDS[index]['value'] ^ 1)):
                wrong = copy.deepcopy(rows)
                wrong[0]['resourceDiscovery'][index][field] = value
                with self.subTest(index=index, field=field), self.assertRaises(RuntimeError):
                    sample.validate_rows(wrong, False)
        for words in (old.FULL_WORDS, SAMPLE_WORDS[:-1], SAMPLE_WORDS[::-1], SAMPLE_WORDS + SAMPLE_WORDS[:1]):
            wrong = copy.deepcopy(rows)
            wrong[0]['resourceDiscovery'] = words
            with self.assertRaises(RuntimeError):
                sample.validate_rows(wrong, False)

    def test_fixture_scope_is_002_original_abi1_and_not_builtin_qualification(self):
        with tempfile.TemporaryDirectory(dir=old.ROOT / '.artifacts', prefix='sample-manifest-') as d:
            out = Path(d)
            runner = sample.Runner(out, False, out, api_capacity=True, two_cu_vga=True)
            manifest = sample_guard.SampleOwnerManifestTests().manifest()
            manifest.update(rtlEmitted=False, workloadProduct=False, workloadWidth=32,
                workloadHeight=32, workloadTileLog2=3, scenes=old.common.SCENES,
                workloadContainerMagic='OGLAY002', workloadFragmentVersion=1, sampleBuiltinsQualified=False)
            path = out / sample.CONTRACT.fixture_manifest
            path.write_text(json.dumps(manifest))
            runner.fixture_manifest()
            for key, value in (('workloadContainerMagic', 'OGLAY001'), ('workloadFragmentVersion', 6),
                    ('sampleBuiltinsQualified', True), ('resourceDiscoveryWords', 104)):
                wrong = dict(manifest, **{key: value})
                path.write_text(json.dumps(wrong))
                with self.assertRaises(RuntimeError):
                    runner.fixture_manifest()
            self.assertEqual(runner.driver_path().name, 'native_sample_layered_product_driver.cpp')
            self.assertNotIn('scripts/native_layered_product_driver.cpp', runner.driver_sources())
            self.assertIn('scripts/qualify_native_sample_layered_product.py', runner.driver_sources())
            self.assertIn(old.ROOT / 'scripts/native_layered_product_execution.hpp',
                          runner.extra_driver_dependencies())

    def test_fixed_cli_and_preparation_choose_sample_entries_and_dependencies(self):
        with tempfile.TemporaryDirectory(dir=old.ROOT / '.artifacts', prefix='sample-cli-') as d:
            out = Path(d)
            argv = ['sample-product', '--product', '--stage', 'build', '--output', d,
                    '--flashsim-dir', d, '--shard-opt=-O0', '--precompiled-header', '--conservative-alias']
            with patch.object(sys, 'argv', argv), patch.object(old, 'checked_output', return_value=out) as checked, \
                    patch.object(sample, 'Runner') as runner:
                sample.main()
                checked.assert_called_once_with(out, contract=sample.CONTRACT)
                runner.assert_called_once_with(out, True, out, 1, 32, reshard=None,
                    reshard_tool=None, precompiled_header=True, shard_opt='-O0',
                    conservative_alias=True, api_capacity=True, two_cu_vga=True, tile_log2=3)
                runner.return_value.build.assert_called_once_with(1)
            runner = sample.Runner(out, False, out, api_capacity=True, two_cu_vga=True)
            with patch.object(runner, 'full_owner', return_value={'cpu-test': True}), \
                    patch.object(runner, 'execute') as execute, patch.object(runner, 'sbt', side_effect=lambda command, heap: command), \
                    patch.object(old.common, 'verify_prepare_completion') as completion, \
                    patch.object(runner, 'fixture_manifest'), patch.object(runner, 'fixture_inputs', return_value={}), \
                    patch.object(runner, 'prepared'):
                runner.prepare()
                self.assertIn('opengpu.system.PrepareNativeSampleLayeredProductFixtures --smoke', execute.call_args.args[1])
                self.assertIn('NATIVE_SAMPLE_LAYERED_PRODUCT_FIXTURES scenes=6 frame=32x32 words=112', completion.call_args.args[1])
            self.assertTrue((out / sample.CONTRACT.fixture_record).exists())
            self.assertFalse((out / old.FIXTURE_RECORD).exists())

    def test_old_new_and_mixed_verifier_markers_are_separate(self):
        with tempfile.TemporaryDirectory(dir=old.ROOT / '.artifacts', prefix='sample-marker-') as d:
            log = Path(d) / 'verify.log'
            for product in (False, True):
                width, height = old.common.frame_size(product, True)
                lines = [f'NATIVE_SAMPLE_LAYERED_PRODUCT_IMAGE scene={name} tile=16 '
                    f'frame={width}x{height} exactImagesSamplesResolvesGuardsAndGNY2=true'
                    for name in old.common.SCENES] + [sample.marker(product, 4)]
                log.write_text('\n'.join(lines) + '\n')
                sample.verify_marker(log, product, 4)
                with self.assertRaises(RuntimeError):
                    old.verify_marker(log, product, 4)
                for bad in (lines[:-1], lines[1:], lines[::-1], lines + lines[-1:],
                        lines + [old.marker(product, 4)],
                        [line.replace('NATIVE_SAMPLE_LAYERED_PRODUCT', 'NATIVE_LAYERED_PRODUCT') for line in lines]):
                    log.write_text('\n'.join(bad) + '\n')
                    with self.assertRaises(RuntimeError):
                        sample.verify_marker(log, product, 4)


if __name__ == '__main__':
    unittest.main()
