#!/usr/bin/env python3
"""CPU-only exact 112-word factory manifest guards; no RTL is generated."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_native_owner_flashsim as owner
from owner_resource_contract import (SAMPLE_PROFILE, SAMPLE_STAGE, SAMPLE_WORDS,
    PROFILE, FULL_STAGE, FULL_WORDS)


class SampleOwnerManifestTests(unittest.TestCase):
    def test_float_exchange_factory_preserves_resources_and_rejects_relabelled_words(self):
        from owner_resource_contract import IMAGE_FLOAT_PROFILE, IMAGE_FLOAT_STAGE, IMAGE_FLOAT_WORDS
        valid=self.manifest()
        valid.update(profile=IMAGE_FLOAT_PROFILE, ownerResourceStage=IMAGE_FLOAT_STAGE,
            computeFeatureFlags=0x3fffff, imageFloatExchange=True,
            requiredDiscovery=copy.deepcopy(IMAGE_FLOAT_WORDS))
        owner.check_manifest(valid)
        inline=copy.deepcopy(valid);inline['assertionMode']='inline';owner.check_manifest(inline)
        self.assertEqual([w for w in IMAGE_FLOAT_WORDS if w['address']!=0x368],
                         [w for w in SAMPLE_WORDS if w['address']!=0x368])
        for key,value in valid.items():
            if key=='requiredDiscovery':continue
            bad=copy.deepcopy(valid);bad[key]=not value if type(value) is bool else 0
            with self.subTest(field=key),self.assertRaises(RuntimeError):owner.check_manifest(bad)
            bad=copy.deepcopy(valid);del bad[key]
            with self.subTest(missing=key),self.assertRaises(RuntimeError):owner.check_manifest(bad)
        for index in range(112):
            for field in ('address','value'):
                bad=copy.deepcopy(valid);bad['requiredDiscovery'][index][field]^=1
                with self.subTest(index=index,field=field),self.assertRaises(RuntimeError):owner.check_manifest(bad)
        for words in (SAMPLE_WORDS,FULL_WORDS,IMAGE_FLOAT_WORDS[::-1]):
            bad=copy.deepcopy(valid);bad['requiredDiscovery']=copy.deepcopy(words)
            with self.assertRaises(RuntimeError):owner.check_manifest(bad)
        bad=self.manifest();bad['imageFloatExchange']=True
        with self.assertRaises(RuntimeError):owner.check_manifest(bad)
        for old in ('compute_stage','full_resources','sample_resources'):
            with self.subTest(old=old),self.assertRaisesRegex(RuntimeError,'one exact owner factory'):
                owner.prepare(None,'1G',float_resources=True,**{old:True})

    def test_inline_assertions_require_the_complete_emitted_sample_factory(self):
        valid = self.manifest()
        valid['assertionMode'] = 'inline'
        owner.check_manifest(valid)
        for mode in (False, None, 'off', 'bound', ''):
            bad = copy.deepcopy(valid)
            bad['assertionMode'] = mode
            with self.subTest(mode=mode), self.assertRaises(RuntimeError):
                owner.check_manifest(bad)
        from test_prepare_native_owner_flashsim import OwnerInputTests
        wrong = OwnerInputTests().manifest()
        wrong['assertionMode'] = 'inline'
        with self.assertRaises(RuntimeError):
            owner.check_manifest(wrong)
        valid['rtlEmitted'] = False
        with self.assertRaises(RuntimeError):
            owner.check_manifest(valid, rtl_emitted=False)
        with self.assertRaisesRegex(RuntimeError, 'complete sample factory'):
            owner.prepare(Path('unused'), '1G', inline_assertions=True)

    def test_inline_preparation_rejects_empty_checks_binding_layers_and_changed_rtl(self):
        # CPU-only format/source guard fixture; it cannot qualify a DUT.
        parent = owner.ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='inline-owner-', dir=parent) as directory:
            out = Path(directory)
            rtl = out / 'rtl'
            rtl.mkdir()
            sv = rtl / 'Sample.sv'
            checked = 'module Sample(input clock, okay);\nalways @(posedge clock)\n  assert(okay) else $error("check");\nendmodule\n'
            sv.write_text(checked)
            manifest = self.manifest()
            manifest['assertionMode'] = 'inline'
            manifest_path = out / 'owner-manifest.json'
            manifest_path.write_text(json.dumps(manifest))
            with patch.object(owner, 'source_inputs', return_value={'fixture': 'original'}):
                record = dict(version=1, exit_code=0, source_sha256={'fixture': 'original'},
                    manifest_sha256=owner.common.sha(manifest_path))
                def refresh():
                    record['rtl_sha256'] = owner.common.tree_hashes(rtl)
                    (out / 'preparation.json').write_text(json.dumps(record))
                refresh()
                self.assertEqual(owner.prepared(out), record)
                sv.write_text(checked + '// changed after preparation\n')
                with self.assertRaises(RuntimeError):
                    owner.prepared(out)
                for empty in ('module Sample; endmodule\n',
                              '/*\n assert(okay) else $error("comment only");\n*/\n'):
                    sv.write_text(empty)
                    refresh()
                    with self.assertRaisesRegex(RuntimeError, 'no executable assertion'):
                        owner.prepared(out)
                sv.write_text(checked)
                layers = rtl / 'verification/assert'
                layers.mkdir(parents=True)
                (layers / 'layers-Sample-Verification-Assert.sv').write_text('bind Sample Check c();\n')
                refresh()
                with self.assertRaisesRegex(RuntimeError, 'still contains assertion bind layers'):
                    owner.prepared(out)

    def manifest(self):
        return dict(version=2, ownerResourceVersion=2, ownerResourceStage=SAMPLE_STAGE,
            profile=SAMPLE_PROFILE, computeUnits=2, frameWidth=640, frameHeight=480,
            lanes=16, warps=8, transactionsPerCu=16, axiDataBytes=32, engines=4,
            nativeMaxWidth=2048, nativeMaxHeight=2048, pagedCapacity=65536,
            payloadBytes=524288, resourceDiscoveryWords=112, computeFeatureFlags=0x1fffff,
            targetFps=60, nativeGeometryDraw=True, nativeGeometryTransport=True,
            transformFeedback=True, nativeSampleShading=True, nativeSampleTransport=True,
            nativeFragmentVersions=[6, 7], tessellationFragmentImages=True,
            tessellationSampleShading=True, fullResourceQualification=False,
            rtlEmitted=True, sourceQualificationRequired=True, gpuExecution=False,
            clockQualified=False, fpsQualified=False, requiredDiscovery=copy.deepcopy(SAMPLE_WORDS))

    def test_complete_sample_identity_and_words(self):
        valid = self.manifest()
        owner.check_manifest(valid)
        describe = copy.deepcopy(valid)
        describe['rtlEmitted'] = False
        owner.check_manifest(describe, rtl_emitted=False)
        for key, value in valid.items():
            if key == 'requiredDiscovery':
                continue
            bad = copy.deepcopy(valid)
            bad[key] = not value if type(value) is bool else 0
            with self.subTest(field=key), self.assertRaises(RuntimeError):
                owner.check_manifest(bad)
            bad = copy.deepcopy(valid)
            del bad[key]
            with self.subTest(missing=key), self.assertRaises(RuntimeError):
                owner.check_manifest(bad)
        for index in range(112):
            for key in ('address', 'value'):
                bad = copy.deepcopy(valid)
                bad['requiredDiscovery'][index][key] ^= 1
                with self.subTest(index=index, field=key), self.assertRaises(RuntimeError):
                    owner.check_manifest(bad)
            bad = copy.deepcopy(valid)
            del bad['requiredDiscovery'][index]
            with self.assertRaises(RuntimeError):
                owner.check_manifest(bad)
        for words in (SAMPLE_WORDS + [SAMPLE_WORDS[0]], list(reversed(SAMPLE_WORDS)), FULL_WORDS):
            bad = copy.deepcopy(valid)
            bad['requiredDiscovery'] = copy.deepcopy(words)
            with self.assertRaises(RuntimeError):
                owner.check_manifest(bad)

    def test_frozen_104_factory_cannot_authorize_or_relabel_the_sample_factory(self):
        old = self.manifest()
        old.update(profile=PROFILE, ownerResourceStage=FULL_STAGE, resourceDiscoveryWords=104,
                   computeFeatureFlags=0xfffff, requiredDiscovery=copy.deepcopy(FULL_WORDS))
        for key in ('nativeSampleShading', 'nativeSampleTransport', 'nativeFragmentVersions',
                    'tessellationFragmentImages', 'tessellationSampleShading'):
            del old[key]
        owner.check_manifest(old)
        for key in ('nativeSampleShading', 'nativeSampleTransport', 'nativeFragmentVersions',
                    'tessellationFragmentImages', 'tessellationSampleShading'):
            bad = copy.deepcopy(old)
            bad[key] = self.manifest()[key]
            with self.assertRaises(RuntimeError):
                owner.check_manifest(bad)
        for original, changed in ((old, self.manifest()), (self.manifest(), old)):
            bad = copy.deepcopy(original)
            bad.update(profile=changed['profile'], ownerResourceStage=changed['ownerResourceStage'])
            with self.assertRaises(RuntimeError):
                owner.check_manifest(bad)
        with self.assertRaises(RuntimeError):
            owner.prepare(None, '384m', compute_stage=True, sample_resources=True)
        with self.assertRaises(RuntimeError):
            owner.prepare(None, '384m', full_resources=True, sample_resources=True)


if __name__ == '__main__':
    unittest.main()
