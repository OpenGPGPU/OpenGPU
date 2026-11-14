#!/usr/bin/env python3
"""CPU-only stale/falsely labeled owner-DUT input rejection checks."""
import copy,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import prepare_native_owner_flashsim as owner

class OwnerInputTests(unittest.TestCase):
    def test_full_geometry_factory_requires_its_new_packets_words_and_scope(self):
        from owner_resource_contract import PROFILE, FULL_STAGE, FULL_WORDS, COMPUTE_STAGE, COMPUTE_WORDS
        full=self.manifest()
        full.update(version=2, profile=PROFILE, computeFeatureFlags=0xfffff,
            resourceDiscoveryWords=104, ownerResourceVersion=2, ownerResourceStage=FULL_STAGE,
            nativeGeometryDraw=True, nativeGeometryTransport=True, transformFeedback=True,
            fullResourceQualification=False, requiredDiscovery=copy.deepcopy(FULL_WORDS))
        owner.check_manifest(full)
        # Every identity/quantity/scope field and exact MMIO word is mandatory.
        for key,value in full.items():
            if key=='requiredDiscovery':continue
            bad=copy.deepcopy(full);bad[key]=not value if type(value) is bool else 0
            with self.subTest(key=key),self.assertRaises(RuntimeError):owner.check_manifest(bad)
            bad=copy.deepcopy(full);del bad[key]
            with self.subTest(missing=key),self.assertRaises(RuntimeError):owner.check_manifest(bad)
        for index in range(104):
            for key in ('address','value'):
                bad=copy.deepcopy(full);bad['requiredDiscovery'][index][key]^=1
                with self.subTest(index=index,key=key),self.assertRaises(RuntimeError):owner.check_manifest(bad)
            bad=copy.deepcopy(full);del bad['requiredDiscovery'][index]
            with self.assertRaises(RuntimeError):owner.check_manifest(bad)
        for profile,words in ((owner.PROFILE,owner.EXPECTED_WORDS),(COMPUTE_STAGE,COMPUTE_WORDS)):
            bad=copy.deepcopy(full);bad.update(profile=profile,requiredDiscovery=copy.deepcopy(words))
            with self.assertRaises(RuntimeError):owner.check_manifest(bad)
            bad=self.manifest();bad.update(profile=PROFILE,requiredDiscovery=copy.deepcopy(FULL_WORDS))
            with self.assertRaises(RuntimeError):owner.check_manifest(bad)
        # The existing native and compute executors have no GNJ2 execution
        # contract. A new manifest must not silently widen either binary.
        import prepare_owner_executor as executor
        for kind in executor.KINDS:
            with self.assertRaises(RuntimeError):executor.profile_contract(PROFILE,kind)

    def manifest(self):
        return dict(version=1,profile=owner.PROFILE,computeUnits=2,frameWidth=640,frameHeight=480,
            lanes=16,warps=8,transactionsPerCu=16,axiDataBytes=32,engines=4,
            nativeMaxWidth=2048,nativeMaxHeight=2048,pagedCapacity=65536,payloadBytes=524288,
            resourceDiscoveryWords=96,computeFeatureFlags=0x3ffff,targetFps=60,rtlEmitted=True,
            sourceQualificationRequired=True,gpuExecution=False,clockQualified=False,fpsQualified=False,
            requiredDiscovery=copy.deepcopy(owner.EXPECTED_WORDS))
    def test_shared_atomic_factory_cannot_relabel_the_frozen_owner(self):
        from owner_resource_contract import COMPUTE_STAGE, COMPUTE_WORDS
        stage=self.manifest()
        stage.update(version=2,profile=COMPUTE_STAGE,computeFeatureFlags=0x7ffff,
            ownerResourceVersion=2,ownerResourceStage='compute',fullResourceQualification=False,
            requiredDiscovery=copy.deepcopy(COMPUTE_WORDS))
        owner.check_manifest(stage)
        for key,bad in [('profile',owner.PROFILE),('version',1),('ownerResourceVersion',True),
                        ('ownerResourceStage','final'),('fullResourceQualification',True),
                        ('requiredDiscovery',owner.EXPECTED_WORDS),('computeFeatureFlags',0x3ffff)]:
            wrong=copy.deepcopy(stage);wrong[key]=bad
            with self.subTest(key=key),self.assertRaises(RuntimeError):owner.check_manifest(wrong)
        wrong=self.manifest();wrong['requiredDiscovery']=copy.deepcopy(COMPUTE_WORDS)
        with self.assertRaises(RuntimeError):owner.check_manifest(wrong)
        for index in range(len(COMPUTE_WORDS)):
            wrong=copy.deepcopy(stage);wrong['requiredDiscovery'][index]['value']^=1
            with self.assertRaises(RuntimeError):owner.check_manifest(wrong)

    def test_rejects_every_missing_changed_or_duplicate_wire_word(self):
        owner.check_manifest(self.manifest())
        for malformed in (None,[],True):
            with self.assertRaises(RuntimeError):owner.check_manifest(malformed)
        for index in range(96):
            for field in ('address','value'):
                bad=self.manifest();bad['requiredDiscovery'][index][field]^=1
                with self.subTest(index=index,field=field),self.assertRaises(RuntimeError):owner.check_manifest(bad)
            bad=self.manifest();del bad['requiredDiscovery'][index]
            with self.assertRaises(RuntimeError):owner.check_manifest(bad)
        bad=self.manifest();bad['requiredDiscovery'][1]=bad['requiredDiscovery'][0]
        with self.assertRaises(RuntimeError):owner.check_manifest(bad)
    def test_rejects_other_capacities_and_premature_execution_labels(self):
        for key,value in self.manifest().items():
            if key=='requiredDiscovery':continue
            bad=self.manifest();bad[key]=not value if type(value) is bool else 0
            with self.subTest(key=key),self.assertRaises(RuntimeError):owner.check_manifest(bad)
        for key in ('version','computeUnits','frameWidth','targetFps'):
            bad=self.manifest();bad[key]=True
            with self.assertRaises(RuntimeError):owner.check_manifest(bad)
        bad=self.manifest();bad['requiredDiscovery'][0]['value']=True
        with self.assertRaises(RuntimeError):owner.check_manifest(bad)
    def test_rejects_changed_sources_manifest_rtl_and_missing_assertions(self):
        parent=owner.ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='owner-guard-',dir=parent) as d:
            out=Path(d);rtl=out/'rtl';layers=rtl/'verification/assert';layers.mkdir(parents=True)
            sv=rtl/'GpuHostSystemAxi.sv';sv.write_text('module GpuHostSystemAxi; endmodule\n')
            layer=layers/'layers-test.sv';layer.write_text('// CPU test binding placeholder; no model executes.\n')
            manifest=out/'owner-manifest.json';manifest.write_text(json.dumps(self.manifest()))
            with patch.object(owner,'source_inputs',return_value={'unit-source':'original'}) as sources:
                record=dict(version=1,exit_code=0,source_sha256=sources.return_value,
                    manifest_sha256=owner.common.sha(manifest),rtl_sha256=owner.common.tree_hashes(rtl))
                metadata=out/'preparation.json';metadata.write_text(json.dumps(record))
                self.assertEqual(owner.prepared(out),record)
                sources.return_value={'unit-source':'changed'}
                with self.assertRaises(RuntimeError):owner.prepared(out)
                sources.return_value={'unit-source':'original'}
                original=manifest.read_bytes();manifest.write_bytes(original+b' ')
                with self.assertRaises(RuntimeError):owner.prepared(out)
                manifest.write_bytes(original);sv.write_text('changed RTL')
                with self.assertRaises(RuntimeError):owner.prepared(out)
                sv.write_text('module GpuHostSystemAxi; endmodule\n');layer.unlink()
                record['rtl_sha256']=owner.common.tree_hashes(rtl);metadata.write_text(json.dumps(record))
                with self.assertRaises(RuntimeError):owner.prepared(out)
if __name__=='__main__':unittest.main()
