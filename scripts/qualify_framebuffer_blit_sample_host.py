#!/usr/bin/env python3
"""43 original caller blits on an exactly qualified protected112 executor.

No model generation, old96 relabeling, original Linux trace or CPU shader peer.
Build this distinct kind with prepare_owner_executor.py before check/run.
"""
import argparse
from contextlib import ExitStack
import fcntl
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile

from framebuffer_blit_fixture import checked_inputs, digest, NAMES
from native_sample_owner_protocol import strict_json
from owner_process_lifetime import CleanupIncomplete, stop_group
from owner_resource_contract import SAMPLE_PROFILE
import prepare_owner_executor as owner
from qualify_framebuffer_blit_host import check_observations

ROOT = Path(__file__).resolve().parents[1]
KIND = 'framebuffer-blit-sample'
SCHEMA = 'opengpu-framebuffer-blit-sample-host-v1'


def require(ok, text):
    if not ok: raise RuntimeError('protected112 blit: '+text)


def check_result(result):
    expected = dict(schema=SCHEMA,executorKind=KIND,profile=SAMPLE_PROFILE,
        ownerResourceVersion=2,ownerResourceStage='geometry-compute-sample',
        resourceDiscoveryWords=112,computeFeatureFlags=0x1fffff,full112Profile=True,
        fullResourceQualification=False,finalProductQualification=False,
        resetCount=1,unifiedResetCount=2,partialCancellationBytesChecked=True,
        originalCallerInputs=True,originalLinuxTrace=False,independentBytes=True,
        vmAndReadOnlyGuards=True,sourceQualificationRequired=True,clockQualified=False,fpsQualified=False)
    require(type(result) is dict and set(result) == {*expected,'cases','lifecycle'} and all(
        type(result.get(k)) is type(v) and result[k] == v for k,v in expected.items()),
        'wrong exact factory/kind/result scope; frozen96 and graphics receipts are separate')
    check_observations(result)


def qualified(args):
    require(args.executor.is_file() and not args.executor.is_symlink() and os.access(args.executor,os.X_OK),
            'original qualified blit executable required')
    record = owner.verify_qualification(args.executor_qualification,executor=args.executor,kind=KIND)
    require(record.get('profile') == SAMPLE_PROFILE and record.get('kind') == KIND and
            record.get('source_root') == str(ROOT), 'exact source-selected 112 blit factory required')
    return digest(args.executor_qualification),digest(args.executor)


def run_child(command, temporary, timeout):
    process = None
    try:
        process = subprocess.Popen(command,cwd=ROOT,start_new_session=True,
            env=dict(os.environ,TMPDIR=str(temporary),PYTHONDONTWRITEBYTECODE='1'))
        require(process.wait(timeout=timeout) == 0,'actual blit executor failed')
    finally:
        stop_group(process)  # Bounded leader/group exit proof, even after wait returned.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage',choices=('check','run'),required=True)
    parser.add_argument('--executor',type=Path,required=True)
    parser.add_argument('--executor-qualification',type=Path,required=True)
    parser.add_argument('--inputs',type=Path,required=True)
    parser.add_argument('--input-manifest-sha256',required=True)
    parser.add_argument('--timeout',type=int,default=3600)
    args = parser.parse_args()
    require(0 < args.timeout <= 86400,'execution timeout must be 1..86400 seconds')
    for name in ('executor','executor_qualification','inputs'):setattr(args,name,getattr(args,name).absolute())
    identity = qualified(args)  # Reject another kind/profile before input admission or execution.
    record = strict_json(args.executor_qualification)
    directory = Path(record['owner_output']).resolve(strict=True)
    checked_inputs(args.inputs,ROOT,args.input_manifest_sha256)
    common = Path(subprocess.check_output(['git','rev-parse','--path-format=absolute','--git-common-dir'],
                                         cwd=ROOT,text=True).strip()).parent
    require(args.inputs.resolve(strict=True).is_relative_to(common/'.artifacts'), 'original inputs must be GPU-internal')
    temporary = None;preserve = False
    def terminate(_signal,_frame):raise KeyboardInterrupt('protected112 blit interrupted')
    previous = signal.signal(signal.SIGTERM,terminate)
    try:
        with ExitStack() as locks:
            for path in owner.build_lock_paths(directory,KIND):
                require(not path.is_symlink(),'aliased owner/executor lock')
                lock = locks.enter_context(path.open('a'));fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            require(qualified(args) == identity,'qualified blit source/model/binary changed while locking')
            if args.stage == 'run':
                temporary = Path(tempfile.mkdtemp(prefix='blit-sample-',dir=ROOT/'.artifacts'))
                result = temporary/'result.json'
                run_child([str(args.executor),str(result),*(str(args.inputs/(name+'.blit')) for name in NAMES)],temporary,args.timeout)
                require(result.is_file() and not result.is_symlink(),'actual blit result missing/aliased')
                check_result(strict_json(result))
            require(qualified(args) == identity,'blit source/tool/model/build/binary changed during execution')
            checked_inputs(args.inputs,ROOT,args.input_manifest_sha256)
    except CleanupIncomplete:
        preserve = True
        print('Preserved inputs for a possibly active blit executor: '+str(temporary),file=sys.stderr)
        raise
    finally:
        try:
            if temporary is not None and not preserve:
                import shutil
                shutil.rmtree(temporary)
        finally:signal.signal(signal.SIGTERM,previous)
    print('BLIT_SAMPLE_HOST_'+args.stage.upper()+'_PASS cases=43 profile='+SAMPLE_PROFILE+
          ' originalLinuxTrace=false finalProductQualification=false clockQualified=false fpsQualified=false'+
          (' gpuExecution=false' if args.stage == 'check' else ''))


if __name__ == '__main__':main()
