#!/usr/bin/env python3
"""Keep two original ordinary112 owners live through independent qualification.

No instruction interpreter or modeled completion is available in this entry.
The fixed executor must pass its original prepared-source/model/binary checker.
Source/image/GNY2 checks precede execution responses and cleanup authorization;
final release follows actual same-DUT PTE revocation and executor success.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile

from compute_owner_oracle import SparseMemory
from native_sample_owner_controller import atomic, regular, wait_file
from native_sample_graphics_protocol import (strict_json, profile_bytes,
    held_bytes, retired_bytes, admission_bytes, cleanup_authorization,
    final_authorization, checked_cleanup)
from native_sample_graphics_owner_oracle import preflight, capture_identity, verify_result, verify_pair
from owner_process_lifetime import CleanupIncomplete, stop_group, remove_container
from owner_resource_contract import SAMPLE_PROFILE

ROOT = Path(__file__).resolve().parents[1]
KIND = 'native-sample-graphics'
CLEANUP_SCHEMA = 'opengpu-native-sample-graphics-consumer-cleanup-v1'
PRODUCER_SUCCESS = ('ACTUAL_SAMPLE_GRAPHICS_LIVE_RETIRED records=2 originalMesaABI1=true '
    'originalGUR=true completionBytes=16384 observed=true')
READY = dict(schema='opengpu-native-sample-graphics-ready-v1', ownerProfile=SAMPLE_PROFILE,
    recordCount=2, memory='union.memory', captures=['record-0.capture','record-1.capture'],
    sourcePlans=['record-0.source-plan.json','record-1.source-plan.json'],
    reflection='compiler-reflection.json', programs=['native-real-vs.words','native-real-fs.words'])


def require(ok, text):
    if not ok: raise ValueError('ordinary112 controller: '+text)


def ready_files(directory):
    ready = regular(directory/'ready.json')
    ready_hash = digest(ready)
    value = strict_json(ready)
    require(type(value) is dict and set(value) == set(READY) and all(
        type(value[k]) is type(v) and value[k] == v for k,v in READY.items()), 'wrong complete READY contract')
    names = ['ready.json', READY['memory'], *READY['sourcePlans'], READY['reflection'], *READY['programs']]
    files = [regular(directory/name) for name in names]
    first = regular(directory/READY['captures'][0]); files.append(first)
    second = directory/READY['captures'][1]
    require(not second.exists() and not second.is_symlink(), 'record1 capture preceded record0 observed retirement')
    require(len({(p.stat().st_dev,p.stat().st_ino) for p in files}) == len(files), 'aliased READY inputs')
    identities = {p:digest(p) for p in files}
    require(identities[ready] == ready_hash, 'READY changed while loading inputs')
    descriptions = [strict_json(directory/name) for name in READY['sourcePlans']]
    programs = [(directory/name).read_bytes() for name in READY['programs']]
    reflection = strict_json(directory/READY['reflection'])
    unchanged(identities)
    return descriptions, programs, reflection, identities


def digest(path):
    value = hashlib.sha256()
    with regular(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            value.update(block)
    return value.digest()


def unchanged(identities):
    require(all(digest(path) == value for path,value in identities.items()), 'original source/profile/capture input changed')


def bind_receipt(value, command, d, record):
    require(type(value) is dict and type(value.get('record')) is int and value['record'] == record and
            type(value.get('commandId')) is int and value['commandId'] == command and value.get('gnx', [])[6:9] == [d['context'],11,13],
            'actual held/retired command or context differs from original checked owner')


def exchange(directory, profile, bridge, start_executor, qualified, timeout):
    """Independent checks gate every phase; the caller owns process cleanup."""
    descriptions, programs, reflection, identities = ready_files(directory)
    for path in (directory/'profile.json', directory/'profile.bin'):
        identities[path] = digest(path)
    require(strict_json(directory/'profile.json') == profile and
            (directory/'profile.bin').read_bytes() == profile_bytes(profile), 'profile files differ from actual probe')
    capture0 = (directory/'record-0.capture').read_bytes()
    require(hashlib.sha256(capture0).digest() == identities[directory/'record-0.capture'], 'first capture changed')
    initial, results = directory/'union.memory', directory/'results'
    with SparseMemory(initial) as memory:
        first_command = preflight(memory, descriptions, programs, reflection, profile, capture0)
    unchanged(identities)
    running = start_executor(initial, results)
    held, retired, commands, captures = [], [], [first_command], [capture0]
    for record in range(2):
        stem='record-%d'%record
        if record:
            path=directory/(stem+'.capture');wait_file(path,bridge,running,timeout)
            capture_data=path.read_bytes()
            command=capture_identity(capture_data,profile,descriptions[record])
            require(command not in commands, 'second record reused an enqueue identity')
            commands.append(command);captures.append(capture_data)
            identities[path]=hashlib.sha256(capture_data).digest()
            unchanged(identities)
            atomic(directory/(stem+'.admit'),admission_bytes(capture_data))
        else: capture_data=capture0
        wait_file(directory/(stem+'.held.json'),bridge,running,timeout)
        observed=strict_json(directory/(stem+'.held.json'))
        bind_receipt(observed,commands[record],descriptions[record],record)
        response=held_bytes(observed,record)
        unchanged(identities)
        atomic(directory/(stem+'.held.bin'),response);held.append(observed)
        wait_file(directory/(stem+'.retired.json'),bridge,running,timeout)
        observed=strict_json(directory/(stem+'.retired.json'))
        bind_receipt(observed,commands[record],descriptions[record],record)
        response=retired_bytes(observed,held[record],record)
        result=results/(stem+'.memory');wait_file(result,bridge,running,timeout)
        identities[result]=digest(result)
        unchanged(identities)
        with SparseMemory(initial) as before, SparseMemory(result) as after:
            if record:
                with SparseMemory(results/'record-0.memory') as previous:
                    verify_result(before,after,descriptions,programs,reflection,profile,capture_data,record,previous)
            else: verify_result(before,after,descriptions,programs,reflection,profile,capture_data,record)
        unchanged(identities)
        require(bridge.poll() is None and running.poll() is None, 'owners/executor exited before independent result checks')
        atomic(directory/(stem+'.response.bin'),response);retired.append(observed)
    unchanged(identities)
    with SparseMemory(initial) as before, SparseMemory(results/'record-0.memory') as first, \
            SparseMemory(results/'record-1.memory') as second:
        checked_commands=verify_pair(before,first,second,descriptions,programs,reflection,profile,captures)
    require(list(checked_commands) == commands, 'complete result pair changed original enqueue identities')
    unchanged(identities)
    atomic(directory/'cleanup.authorize.bin',cleanup_authorization(retired,held))
    wait_file(directory/'cleanup.json',bridge,running,timeout)
    checked_cleanup(strict_json(directory/'cleanup.json'))
    require(running.wait(timeout=timeout) == 0, 'actual executor rejected cleanup')
    require(bridge.poll() is None, 'producer exited before final actual cleanup confirmation')
    qualified();unchanged(identities)
    atomic(directory/'response.bin',final_authorization(retired,held))
    require(bridge.wait(timeout=timeout) == 0, 'producer rejected final observed release')
    return commands


def producer_success(directory):
    """Check the original LIVE C entry before deleting its process log."""
    lines = regular(directory/'bridge.log').read_text().splitlines()
    require(lines.count(PRODUCER_SUCCESS) == 1,
            'actual Mesa LIVE producer did not confirm exactly one two-owner retirement')


def lock_owner(qualification):
    record=strict_json(regular(qualification))
    require(record.get('schema') == 'opengpu-owner-executor-v1' and record.get('kind') == KIND and
            record.get('profile') == SAMPLE_PROFILE and record.get('source_root') == str(ROOT),
            'wrong exact source/factory/executor kind')
    directory=Path(record['owner_output']).resolve(strict=True)
    target=directory/'executors'/KIND
    require(qualification.resolve(strict=True) == target/'qualification.json' and target.resolve(strict=True) == target,
            'qualification does not name the exact prepared ordinary112 executor')
    locks=[]
    try:
        for path in (directory/'.lock',target/'.lock'):
            require(not path.is_symlink(),'aliased owner lock')
            handle=path.open('a');locks.append(handle);fcntl.flock(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
        return locks
    except BaseException:
        for handle in locks:handle.close()
        raise


def cleanup_inputs(directory, running, bridge, container=None, *, receipt=None, executor=None, qualification=None):
    """Prove every owned consumer stopped before removing its source inputs."""
    try:
        try:
            stop_group(running)
        finally:
            try:
                stop_group(bridge)
            finally:
                if container:
                    remove_container(container)
    except CleanupIncomplete:
        print('Preserved inputs for a possibly active consumer: '+str(directory))
        raise
    shutil.rmtree(directory)
    if receipt is not None:
        value = dict(schema=CLEANUP_SCHEMA, kind=KIND, controllerPID=os.getpid(), sourceRoot=str(ROOT),
            executor=str(executor.absolute()), qualification=str(qualification.absolute()),
            consumerCleanupConfirmed=True)
        atomic(receipt,(json.dumps(value)+'\n').encode())


def receipt_output(path, parent):
    if path is None:
        return None
    path = path.absolute()
    require(path.is_relative_to(parent) and path.parent.is_dir() and
            path.parent.resolve(strict=True) == path.parent and
            not path.exists() and not path.is_symlink(), 'invalid/pre-existing consumer cleanup receipt output')
    return path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('executor','executor-qualification','executor-checker','executor-checker-python','bridge-command'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--bridge-container-name')
    parser.add_argument('--cleanup-receipt',type=Path,help='new managed file proving all owned consumers stopped')
    parser.add_argument('--timeout',type=int,default=3600)
    args=parser.parse_args();require(0 < args.timeout <= 86400,'timeout must be 1..86400 seconds')
    executor=regular(args.executor.absolute());qualification=regular(args.executor_qualification.absolute())
    checker=args.executor_checker.resolve(strict=True);python=regular(args.executor_checker_python.resolve(strict=True))
    require(checker == ROOT/'scripts/prepare_owner_executor.py','requires the original prepared executor checker')
    command=strict_json(regular(args.bridge_command))
    require(type(command) is list and command and all(type(a) is str and a for a in command) and
            any('{ipc}' in a for a in command),'bridge argv must consume this active IPC directory')
    docker=Path(command[0]).name == 'docker'
    require(not docker or (args.bridge_container_name and
        re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]*',args.bridge_container_name) and len(command)>3 and
        command[1]=='run' and command.count('--name')==1 and not any(a.startswith('--name=') for a in command) and
        command.index('--name')+1<len(command) and command[command.index('--name')+1]==args.bridge_container_name),
        'Docker bridge requires its exact owned container name')
    require(not args.bridge_container_name or docker,'container cleanup requires a direct Docker bridge')
    gpu=next(p for p in (ROOT,*ROOT.parents) if (p/'.git').is_dir())
    parent=gpu/'.artifacts';parent.mkdir(exist_ok=True)
    cleanup_receipt=receipt_output(args.cleanup_receipt,parent)
    directory=Path(tempfile.mkdtemp(prefix='g',dir=parent))
    env=dict(os.environ,TMPDIR=str(directory),PYTHONDONTWRITEBYTECODE='1',
        OPENGPU_NATIVE_HOST_TIMEOUT=str(args.timeout),OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY=str(directory),
        OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE=str(directory/'profile.bin'))
    bridge=running=None;locks=[]
    def terminate(_signal,_frame):raise KeyboardInterrupt('ordinary112 qualification terminated')
    previous=signal.signal(signal.SIGTERM,terminate)
    try:
        with (directory/'execution.log').open('w') as output:
            def qualified():
                subprocess.run([python,checker,'--stage','check','--kind',KIND,'--qualification',qualification,
                    '--executor',executor],env=env,stdout=output,stderr=subprocess.STDOUT,timeout=args.timeout,check=True)
            qualified();locks=lock_owner(qualification);qualified()
            subprocess.run([executor,'--probe-profile',directory/'profile.json'],env=env,
                stdout=output,stderr=subprocess.STDOUT,timeout=args.timeout,check=True)
            profile=strict_json(directory/'profile.json');atomic(directory/'profile.bin',profile_bytes(profile))
            with (directory/'bridge.log').open('w') as log:
                bridge=subprocess.Popen([a.replace('{ipc}',str(directory)) for a in command],env=env,
                    stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
                wait_file(directory/'ready.json',bridge,None,args.timeout)
                def start_executor(initial,results):
                    nonlocal running
                    qualified()
                    running = subprocess.Popen([executor,'--active-owners',initial,results,directory],env=env,
                        stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
                    return running
                exchange(directory,profile,bridge,start_executor,qualified,args.timeout)
                producer_success(directory)
        print('Ordinary112 two-owner execution, independent images/GNY2 and actual cleanup PASS; clock/FPS unqualified')
    except BaseException:
        try:
            atomic(directory/'error',b'qualification failed; no owner-release authorization\n')
        except (OSError,ValueError):
            pass
        for name in ('bridge.log','execution.log'):
            path=directory/name
            if path.is_file():print(path.read_text(errors='replace')[-6000:])
        raise
    finally:
        try:
            cleanup_inputs(directory,running,bridge,args.bridge_container_name,receipt=cleanup_receipt,
                executor=executor,qualification=qualification)
        finally:
            for handle in locks:handle.close()
            signal.signal(signal.SIGTERM,previous)


if __name__=='__main__':main()
