#!/usr/bin/env python3
"""CPU source/protocol controls; no real cache execution is claimed."""
import argparse
from contextlib import redirect_stdout
from copy import deepcopy
import io
import json
import os
from pathlib import Path
import resource
import struct
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock,patch

import compute_cache_alias_recovery as contract
from compute_cache_alias_observation import SCHEMA as OBSERVATION_SCHEMA
from compute_cache_alias_cases import CASES,parameters
from test_compute_cache_alias_source import returned
from test_compute_global_accepted_recovery import held as accepted_held,done as accepted_done
from test_compute_global_recovery import GlobalContinuousOwnerTests

ROOT=Path(__file__).resolve().parents[1]


def native_source_controls(build,profile_wire,profile,request):
    """Exercise original C admission and option guards, without DUT receipts."""
    from compute_global_host_inputs import Inputs
    from test_compute_global_atomic_source import check_capture
    guard=Inputs(request,ROOT)
    commands=json.loads((build/'commands.json').read_text())
    guard.prepare(build,[(c['label'],c['argv']) for c in commands[:-1]],commands[-1]['argv'])
    guard.before_link();guard.built(commands,build/'compute_global_host')
    resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    session=build/'native-live-source-controls';session.mkdir()
    rejections=0
    for index in (0,47):
        local=session/('round-'+str(index));local.mkdir()
        command=contract.source_launch(index,build,session,profile_wire)
        with (local/'source.log').open('w') as log:
            child=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,
                env=dict(os.environ,OPENGPU_COMPUTE_HOST_TIMEOUT='30'))
            try:
                deadline=time.monotonic()+20
                while not (local/'READY').is_file():
                    assert child.poll() is None,'original live C source exited before READY: '+str(local)
                    assert time.monotonic()<deadline,'original live C source did not reach READY'
                    time.sleep(.01)
                assert child.poll() is None,'original source did not wait for actual DUT receipts'
                assert (local/'READY').read_text()=='original RV32/Linux active capture\n'
                rejections+=check_capture(local,profile,cached=True,cache_alias=True)
                for name in ('record-0.held.response','record-0.retire','record-0.retired.response',
                        'cleanup_wait','BEGIN_CLEANUP','cleanup.json','FINAL_RESPONSE'):
                    assert not (local/name).exists(),'original C owner advanced without DUT receipts: '+name
                guard.unchanged(full=True)
            finally:
                if child.poll() is None:child.terminate()
                try:child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill();child.wait(timeout=10)
    invalid=(
        ('--cached-kernarg','--cache-alias-source'),
        ('--cache-alias-live',),
        ('--cache-alias-source','--cache-alias-live'),
        ('--cached-kernarg','--cache-alias-source','--cache-alias-live','--capture-only'),
        ('--cached-kernarg','--cache-alias-source','--cache-alias-live','--expect-global-fault','write'),
        ('--cached-kernarg','--expect-cache-alias-write-error'),
        ('--cached-kernarg','--cache-alias-source','--cache-alias-live','--cache-alias-live'),
        ('--cached-kernarg','--cache-alias-source','--capture-only','--expect-cache-alias-write-error'),
    )
    for index,options in enumerate(invalid):
        local=session/('rejected-'+str(index));local.mkdir()
        command=contract.source_launch(0,build,session,profile_wire)[:6]
        command[1]=str(local)
        result=subprocess.run([*command,*options],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=10)
        assert result.returncode and b'Assertion' in result.stderr,(options,result.stderr)
        assert not (local/'READY').exists(),'mixed original C options reached admission'
    guard.unchanged(full=True)
    assert rejections==80,rejections
    print('original C cache-live source controls: 2 READY/receipt-wait captures, '
          '80 source negatives, 8 option rejections PASS; no DUT execution',flush=True)


def held(index):return accepted_held(index)


def done(index):return dict(accepted_done(index),schema=contract.ROUND_SCHEMA)


def observation(index):
    stage=contract.phase(index);failed=stage['mode']=='accepted-write';start=index*1000
    return dict(schema=OBSERVATION_SCHEMA,commandId=42,phase=index,
        targetLine=stage['base']+4096+(parameters(stage['case'])['target_offset']&~63),
        startCycle=start,endCycle=start+90,acceptedWriteError=failed,warmL1Mask=3,warmL2=True,
        invalidateMask=3,invalidateAckMask=3,targetWrites=5 if failed else 8,
        targetWriteResponses=5 if failed else 8,targetWriteErrors=int(failed),
        refetchedL1Mask=2 if failed else 3,refetchedL2=True,
        warmCycle=start+1,invalidateCycle=start+2,invalidateAckCycle=start+3,
        firstTargetWriteCycle=start+4,writeErrorCycle=start+5 if failed else 0,
        l2InvalidatedCycle=start+6 if failed else 0,refetchRequestCycle=start+7 if failed else 0,
        refetchResponseCycle=start+8 if failed else 0,refillCycle=start+9)


class CacheAliasRecovery(unittest.TestCase):
    def test_all_original_phases_exact_source_commands_target_and_fault_plans(self):
        self.assertEqual(struct.unpack('<8sIIQQ',contract.matrix_plan()),(b'OGCCAM01',48,2,contract.BASE,contract.STEP))
        previous=None
        for index,stage in enumerate(contract.PHASES):
            self.assertEqual(stage,dict(index=index,case=CASES[index//2],mode='healthy' if index%2 else 'accepted-write',
                base=contract.BASE+index*contract.STEP))
            command=contract.source_launch(index,Path('/build'),Path('/session'),Path('/profile.bin'))
            contract.check_source_launch(command,index,Path('/build'),Path('/session'),Path('/profile.bin'))
            self.assertEqual(command[6:9],['--cached-kernarg','--cache-alias-source','--cache-alias-live'])
            self.assertEqual('--expect-cache-alias-write-error' in command,index%2==0)
            for slot in range(len(command)):
                changed=list(command);changed[slot]='substituted'
                with self.assertRaises(ValueError):contract.check_source_launch(changed,index,Path('/build'),Path('/session'),Path('/profile.bin'))
            _,_,description=returned(stage['case']);description['kernarg']['allocationPhysical']=stage['base']+4096
            target=observation(index)['targetLine']
            self.assertEqual(struct.unpack('<8sIIQ',contract.target_plan(description,index)),(b'OGCCL001',index,0,target))
            if not index%2:
                self.assertEqual(struct.unpack('<8sQQI',contract.fault_plan(description,'accepted-write')),
                    (b'OGCCF001',target,target+64,3))
            proof=observation(index)
            self.assertIs(contract.check_cache(proof,index,description,held(index),previous,done(index)),proof)
            contract.check_round(done(index),index,held(index),previous);previous=done(index)
        for index in (-1,True,48):
            with self.assertRaises(ValueError):contract.phase(index)
        with self.assertRaises(ValueError):contract.fault_plan(description,'write')

    def test_observation_bound_to_original_round_target_and_surviving_attempts(self):
        _,_,description=returned(CASES[0]);description['kernarg']['allocationPhysical']=contract.BASE+4096
        for change in ({'targetLine':contract.BASE+8192},{'invalidateAckMask':1},{'refetchResponseCycle':0},
                {'commandId':43},{'phase':1},{'targetWrites':3},{'endCycle':4}):
            with self.assertRaises(ValueError):contract.check_cache(dict(observation(0),**change),0,description,held(0))
        with self.assertRaises(ValueError):contract.check_cache(observation(0),0,description,held(0),None,dict(done(0),startCycle=1))
        with self.assertRaises(ValueError):contract.check_cache(observation(0),0,description,held(0),None,dict(done(0),endCycle=80))

    def test_all_forty_eight_cpu_peer_phases_require_cache_proof_before_retired_response_and_cleanup(self):
        GlobalContinuousOwnerTests.peer(self,False,protocol=contract,records=(held,done),expected_words=288,
            source_fixture=returned,observation_fixture=observation)

    def test_bad_cache_proof_stops_cleanup_and_the_next_original_owner(self):
        for bad_phase in (0,1):
            def wrong(index):return dict(observation(index),invalidateAckMask=1) if index==bad_phase else observation(index)
            GlobalContinuousOwnerTests.peer(self,False,protocol=contract,records=(held,done),expected_words=288,
                source_fixture=returned,observation_fixture=wrong,expected_error='invalid invalidateAckMask',bad_phase=bad_phase)

    def test_bad_returned_bytes_still_prevent_cleanup_even_with_cache_observation(self):
        GlobalContinuousOwnerTests.peer(self,True,protocol=contract,records=(held,done),source_fixture=returned,
            observation_fixture=observation)

    def test_original_producer_forwards_all_forty_eight_distinct_live_commands(self):
        import compute_global_host_producer as producer
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='cache-live-argv-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);profile=out/'profile.bin';profile.write_bytes(b'OGCP0003CPU fixture')
            def build(path,**_kwargs):path.mkdir();return path/'compute_global_host'
            argv=['producer','--build',str(out/'build'),'--profile-wire',str(profile),'--case',CASES[0],
                '--session',str(out/'session'),'--sanitize','--bound-inputs',str(out/'request'),
                '--cached-kernarg','--cache-alias-source','--cache-alias-matrix']
            with patch.object(sys,'argv',argv),patch.object(producer,'build',side_effect=build),\
                    patch('compute_global_host_inputs.Inputs',return_value=Mock()),\
                    patch.object(producer.subprocess,'run',return_value=SimpleNamespace(returncode=0)) as run,\
                    redirect_stdout(io.StringIO()):producer.main()
            self.assertEqual(len(run.call_args_list),48)
            for index,call in enumerate(run.call_args_list):
                contract.check_source_launch(call.args[0],index,out/'build',out/'session',out/'build/inputs/profile.bin')
            for missing in ('--cached-kernarg','--cache-alias-source','--cache-alias-matrix'):
                with patch.object(sys,'argv',[value for value in argv if value!=missing]),\
                        patch('sys.stderr',io.StringIO()),self.assertRaises(SystemExit):producer.main()

    def test_outer_source_command_requires_the_exact_distinct_mode_and_complete_backend(self):
        import compute_global_atomic_cases as original
        import qualify_compute_owner as qualifier
        args=argparse.Namespace(timeout=14400,global_inputs=ROOT/'.artifacts/request.json',cache_alias_matrix=True)
        def command(case=original.CASES[0]):
            return qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=None,image='sha256:'+'c'*64,
                name='original-caller',case=case)
        actual=command()
        self.assertEqual(actual[-3:],['--cached-kernarg','--cache-alias-source','--cache-alias-matrix'])
        self.assertNotIn('--global-atomic-matrix',actual);self.assertNotIn('--global-accepted-matrix',actual)
        with self.assertRaises(ValueError):command(original.CASES[1])
        for option in ('global_atomic_matrix','global_recovery_matrix','global_accepted_matrix','global_cached_matrix',
                'storage_matrix','storage_accepted_matrix','image_accepted_matrix','global_delayed_ack','mixed_atomic_matrix'):
            setattr(args,option,True)
            with self.assertRaises(ValueError):command()
            setattr(args,option,False)
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='cache-backend-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);exe=out/'executor';exe.write_bytes(b'explicit CPU fixture')
            request=out/'qualification.json'
            gate=argparse.Namespace(executor=exe,executor_qualification=request,executor_checker=out/'checker',
                executor_checker_python=sys.executable,case='global-atomics-cache-alias')
            import json
            from owner_resource_contract import IMAGE_FLOAT_PROFILE,SAMPLE_PROFILE
            for profile,backend in ((SAMPLE_PROFILE,'verilator'),(IMAGE_FLOAT_PROFILE,'flashsim'),(IMAGE_FLOAT_PROFILE,'verilator')):
                request.write_text(json.dumps(dict(schema='opengpu-owner-executor-v1',kind='compute',profile=profile,backend=backend)))
                with patch.object(qualifier.subprocess,'run'):
                    if profile==IMAGE_FLOAT_PROFILE and backend=='verilator':qualifier.checked_inputs(gate)
                    else:
                        with self.assertRaises(ValueError):qualifier.checked_inputs(gate)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-source-build',type=Path)
    parser.add_argument('--profile-wire',type=Path)
    parser.add_argument('--profile-json',type=Path)
    parser.add_argument('--bound-inputs',type=Path)
    args=parser.parse_args()
    if args.native_source_build is None:
        if any((args.profile_wire,args.profile_json,args.bound_inputs)):
            parser.error('profile and input options require --native-source-build')
        unittest.main(argv=[sys.argv[0]])
    else:
        if not all((args.profile_wire,args.profile_json,args.bound_inputs)):
            parser.error('--native-source-build requires both profile files and --bound-inputs')
        native_source_controls(args.native_source_build.resolve(strict=True),args.profile_wire.resolve(strict=True),
            json.loads(args.profile_json.read_text()),args.bound_inputs.resolve(strict=True))
