#!/usr/bin/env python3
"""CPU-only exact 112-word factory manifest guards; no RTL is generated."""
import copy
import unittest

import prepare_native_owner_flashsim as owner
from owner_resource_contract import (SAMPLE_PROFILE, SAMPLE_STAGE, SAMPLE_WORDS,
    PROFILE, FULL_STAGE, FULL_WORDS)


class SampleOwnerManifestTests(unittest.TestCase):
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
