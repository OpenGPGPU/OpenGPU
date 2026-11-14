#!/usr/bin/env python3
"""Protected112 blit source/admission/result guards; no generated DUT executes."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import qualify_framebuffer_blit_host as old
import qualify_framebuffer_blit_sample_host as gate


def result():
    return dict(schema=gate.SCHEMA,executorKind=gate.KIND,profile=gate.SAMPLE_PROFILE,
        ownerResourceVersion=2,ownerResourceStage='geometry-compute-sample',resourceDiscoveryWords=112,
        computeFeatureFlags=0x1fffff,full112Profile=True,fullResourceQualification=False,
        finalProductQualification=False,resetCount=1,unifiedResetCount=2,partialCancellationBytesChecked=True,
        originalCallerInputs=True,originalLinuxTrace=False,independentBytes=True,vmAndReadOnlyGuards=True,
        sourceQualificationRequired=True,clockQualified=False,fpsQualified=False,
        cases=[dict(inputIndex=n,coordinateInvalidateCycles=100,computeThroughRetirementCycles=200,
                    publicationInvalidateCycles=100) for n in range(43)],
        lifecycle=dict(sourceFaultReads=1,sourceFaultRetirementCycles=200,healthyAfterFaultCycles=200,
            resets=[dict(heldChannel=c,heldResponseCycles=100,resetThroughRevocationCycles=200,
                         healthyIDReuseCycles=200) for c in ('R','B')]))


class Blit112Tests(unittest.TestCase):
    def test_all_43_observations_and_old_result_contract_are_separate(self):
        gate.check_result(result())
        frozen = {k:v for k,v in result().items() if k not in ('executorKind','profile','ownerResourceVersion',
            'ownerResourceStage','resourceDiscoveryWords','computeFeatureFlags','full112Profile',
            'fullResourceQualification','finalProductQualification')}
        frozen.update(schema='opengpu-framebuffer-blit-host-v2',full96Profile=True)
        old.check_result(frozen)
        with self.assertRaises(RuntimeError):gate.check_result(frozen)
        with self.assertRaises(RuntimeError):old.check_result(result())
        renamed = dict(result(),schema='opengpu-framebuffer-blit-host-v2',full96Profile=True)
        with self.assertRaises(RuntimeError):old.check_result(renamed)

    def test_wrong_factory_scope_and_all_lifecycle_omissions_reject(self):
        for key,value in (('executorKind','native-sample'),('executorKind','native-sample-graphics'),
            ('profile','two-cu-vga60-owner-v1'),('resourceDiscoveryWords',96),('computeFeatureFlags',0x3ffff),
            ('ownerResourceStage','compute'),('ownerResourceVersion',True),('originalLinuxTrace',True),
            ('fullResourceQualification',True),('full112Profile',False),('clockQualified',True),('fpsQualified',True),
            ('full96Profile',True),('extraEvidence',True)):
            bad=result();bad[key]=value
            with self.subTest(key=key),self.assertRaises(RuntimeError):gate.check_result(bad)
        for mode in ('case','reorder','fault','reuse','R','B','partial'):
            bad=result()
            if mode=='case':bad['cases'].pop()
            elif mode=='reorder':bad['cases'].reverse()
            elif mode=='fault':bad['lifecycle']['sourceFaultReads']=0
            elif mode=='reuse':bad['lifecycle']['healthyAfterFaultCycles']=0
            elif mode=='partial':bad['partialCancellationBytesChecked']=False
            else:bad['lifecycle']['resets'][('R','B').index(mode)]['healthyIDReuseCycles']=0
            with self.subTest(mode=mode),self.assertRaises(RuntimeError):gate.check_result(bad)

    def test_missing_or_other_qualification_fails_before_inputs_or_execution(self):
        with tempfile.TemporaryDirectory(dir=gate.ROOT/'.artifacts',prefix='blit112-admit-') as temp:
            args=argparse.Namespace(executor=Path(temp)/'executor',executor_qualification=Path(temp)/'qualification.json')
            with patch.object(gate.owner,'verify_qualification') as verify:
                with self.assertRaises(RuntimeError):gate.qualified(args)
                verify.assert_not_called()
            args.executor.write_bytes(b'CPU fixture; not executed');args.executor.chmod(0o755)
            args.executor_qualification.write_text('{}')
            for record in (dict(kind='native-sample',profile=gate.SAMPLE_PROFILE,source_root=str(gate.ROOT)),
                           dict(kind=gate.KIND,profile='two-cu-vga60-owner-v1',source_root=str(gate.ROOT)),
                           dict(kind=gate.KIND,profile=gate.SAMPLE_PROFILE,source_root='/another-checkout')):
                with patch.object(gate.owner,'verify_qualification',return_value=record) as verify:
                    with self.assertRaises(RuntimeError):gate.qualified(args)
                    self.assertEqual(verify.call_args.kwargs['kind'],gate.KIND)

    def test_bounded_failure_stops_real_child_group_before_temp_cleanup(self):
        with tempfile.TemporaryDirectory(dir=gate.ROOT/'.artifacts',prefix='blit112-exit-') as temp:
            out=Path(temp);pid_file=out/'pid'
            command=[sys.executable,'-B','-c',
                "import os,time,sys;from pathlib import Path;Path(sys.argv[1]).write_text(str(os.getpid()));time.sleep(30)",
                str(pid_file)]
            with self.assertRaises(subprocess.TimeoutExpired):gate.run_child(command,out,1)
            pid=int(pid_file.read_text())
            with self.assertRaises(ProcessLookupError):os.kill(pid,0)

    def test_real_112_and_legacy_translation_units_typecheck(self):
        for extra in ([],['--sample-owner']):
            subprocess.run([sys.executable,'-B',str(gate.ROOT/'scripts/check_framebuffer_blit_host_source.py'),
                '--owner-root',str(gate.ROOT),*extra],check=True)


if __name__ == '__main__':unittest.main()
