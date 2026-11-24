#!/usr/bin/env python3
"""Execute six original Mesa joint submissions and check actual DUT receipts."""
import argparse
import fcntl
import hashlib
import os
from pathlib import Path
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import uuid

from build_native_flashsim import digest
from compute_owner_protocol import required_flags, strict_json, uint
from native_graphics_uniform_oracle import SparseMemory, require
from native_joint_live_oracle import RECORDS, capture, original_graph, original_records, verify_image
from native_sample_graphics_controller import wait_cleanup
from native_sample_owner_controller import atomic, regular, wait_file
from owner_process_lifetime import CleanupIncomplete, remove_container, stop_group
from prepare_owner_executor import DEFAULT_DEPENDENCIES, driver_files

ROOT = Path(__file__).resolve().parents[1]
ESSL = ROOT / 'userspace/tests/tess_joint_live_essl'
IMAGE = 'sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72'
REFERENCE = 'a794c93c2b865a3f4a9069656ff39b5a329c1ca8:userspace/gallium/opengpu_native_uniform_emit.c'


def driver_qualification(build, dut):
    receipt = strict_json(regular(build/'native-flashsim-build.json'))
    files = driver_files('native')
    paths = {str((ROOT/name).resolve()):digest(regular(ROOT/name))
             for name in (*files,*DEFAULT_DEPENDENCIES)}
    executor = regular(build/'native_product_driver')
    require(receipt.get('driver_source') == str(ROOT/files[0]) and receipt.get('driver_inputs') == paths and
            receipt.get('executable_sha256') == digest(executor) and
            receipt.get('dut_header_sha256') == digest(regular(dut/'dut.h')),
            'executor does not match the current owner driver and prepared FlashSim model')
    shards = {path.name:digest(regular(path)) for path in dut.glob('dut_*.cpp')}
    require(shards and receipt.get('dut_sources') == shards, 'executor used different FlashSim shards')
    return executor, {Path(path):sha for path,sha in paths.items()} | {executor:receipt['executable_sha256']}


def build_executor(build, dut, jobs):
    files = driver_files('native')
    command = [sys.executable,'-B',ROOT/'scripts/build_native_flashsim.py','--dut-dir',dut,
               '--build-dir',build,'--driver',ROOT/files[0],'--jobs',str(jobs),
               '--precompiled-header','--conservative-alias']
    for name in files[1:]:
        command.extend(('--driver-dependency',ROOT/name))
    subprocess.run(command,cwd=ROOT,check=True)


def profile_words(profile):
    required_flags(profile,dict(schema='opengpu-protected-joint-owner-profile-v1', resetCount=1,gpuExecution=False))
    sections = []
    for key, count in (('words',92),('registers',5)):
        rows = profile.get(key)
        require(type(rows) is list and len(rows)==count, 'incomplete joint discovery from DUT')
        require(all(type(row) is dict and set(row)=={'address','value'} for row in rows), 'invalid joint discovery word')
        sections.append([(uint(row['address']),uint(row['value'])) for row in rows])
    words, registers = sections
    values = dict(words+registers)
    require(len(values)==97 and [address for address,_ in registers]==[0,0x60,0x68,0x320,0x158] and
            tuple(values.get(address) for address in (0x368,0x36c,0x370,0x374,0x550,0x554,0x558))==
                (0x4367ff,2,16,8,0x474a5001,0x4ff,0x08100207) and values.get(0x55c) in (1,2,3,4),
            'DUT discovery lacks the protected joint profile')
    wire = bytearray(b'OGNP0003')
    for section in sections:
        wire.extend(struct.pack('<I',len(section)))
        for address,value in section:
            wire.extend(struct.pack('<II',address,value))
    return words+registers, values[0x55c], bytes(wire)


def receipt_words(value, phase, command, context, row):
    required_flags(value,dict(phase=phase, record=row['record'], commandId=command,
        completion=0x18700|command,heldIrq=3,heldIrqAsserted=True,heldCompletionDrained=True,
        readOnlyGuards=True,sourceQualificationRequired=True))
    detail = value.get('gnz2')
    require(type(detail) is list and len(detail) == 16, 'held GNZ2 is incomplete')
    detail = tuple(uint(word) for word in detail)
    read = uint(value.get('metadataReadBytes'),64)
    legacy, status = uint(value.get('heldLegacyStatus')),uint(value.get('heldStatus'))
    require(detail[:10]==(0x474e5a02,0,0,context,row['pass'],row['generation'],1,1,2,0) and
            0 < (detail[10] | detail[11]<<32) and detail[12:]==(row['imageBytes'],0,0,0) and
            read and not read&63 and status&2 and not status&~0x23,
            'GNZ2 does not describe the expected complete two-instance render')
    return struct.pack('<19IQ4I',row['record'],command,value['completion'],*detail,read,legacy,status,3,1)


def retired_receipt(value, held, command, context, row):
    required_flags(value,dict(ownerReplay=True,retiredStatus=1,retiredIrq=1,
        retiredIrqAsserted=False,externalDrained=True,resets=1,resetsBetweenRecords=False,
        independentImagesRequired=True,clockQualified=False,fpsQualified=False))
    payload = receipt_words(value,'retired',command,context,row)
    require(payload==held, 'retired DUT receipt changed held ownership or counters')
    require(uint(value.get('serviceCycles'),64)>0 and uint(value.get('writeBusBytes'),64)>0,
            'no observed service or writes for the joint frame')
    return b'OGJR0001'+payload+struct.pack('<4I',1,1,0,1)


def cleanup_checked(value):
    required_flags(value,dict(phase='cleanup',ownerCleanupObserved=True,contextCleanupObserved=True,
        records=RECORDS,resetCount=1,ramReplaced=False,imagesPreserved=True,
        externalDrained=True,sourceQualificationRequired=True,clockQualified=False,fpsQualified=False))
    for key in ('batches','pteClears','shootdowns'):
        require(uint(value.get(key))>0,'missing joint cleanup '+key)


def docker_command(gpu, directory, name, timeout):
    mesa, build, sdk = (gpu/'depends'/dep for dep in ('mesa','mesa-build','aarch64-root'))
    command = ['docker','run','--rm','--init','--pull=never','--memory=512m','--memory-swap=512m',
               '--cpus=1','--network=none','--read-only','--name',name]
    for source,dest,mode in ((mesa,mesa,'ro'),(build,build,'ro'),(sdk,sdk,'ro'),
                             (ROOT,'/joint-src','ro'),(directory,'/joint-out','rw')):
        command.extend(('-v',f'{source}:{dest}:{mode}'))
    environment = dict(OPENGPU_MESA_DIR=mesa,OPENGPU_MESA_BUILD=build,OPENGPU_DRM_INCLUDE=sdk/'include',
        LD_LIBRARY_PATH=sdk/'lib',OPENGPU_MESA_LINK_LIBS=f'-l:libz.so.1 {sdk}/lib/libexpat.so.1.6.7',
        TMPDIR='/joint-out',PYTHONDONTWRITEBYTECODE=1,OPENGPU_NATIVE_HOST_TIMEOUT=timeout,
        ASAN_OPTIONS='detect_leaks=0:halt_on_error=1',UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1')
    for key,value in environment.items():
        command.extend(('-e',f'{key}={value}'))
    return [*command,'-w','/joint-src',IMAGE,'/bin/bash','-ec',
        'python3 -B scripts/build_native_joint_live_producer.py --sanitize --build /joint-out/build '
        '--legacy-source /joint-out/reference-uniform.c && '
        'exec /joint-out/build/native-joint userspace/tests/tess_joint_live_essl /joint-out/profile.bin /joint-out/session']


def exchange(directory, executor, bridge, discovery, engines, identities, timeout):
    session, results = directory/'session',directory/'results'
    memory, layout = session/'union.memory',session/'original-inputs.json'
    first = session/'record-0.capture'
    for path in (layout,memory,first):
        wait_file(path,bridge,None,timeout)
    with SparseMemory(memory) as initial:
        context, rows = original_records(strict_json(layout),initial,engines)
    identities.update({path:digest(regular(path)) for path in (memory,layout,first)})
    running = executor(memory,results,session)
    try:
        commands, shaders, records = set(),[],[]
        previous = memory
        with SparseMemory(memory) as initial:
            for index,row in enumerate(rows):
                stem = f'record-{index}'
                packet = session/(stem+'.capture')
                wait_file(packet,bridge,running,timeout)
                data = packet.read_bytes()
                identities[packet] = hashlib.sha256(data).hexdigest()
                command, regs, writable = capture(data,discovery,context,row)
                require(command not in commands,'joint command identity was reused')
                for other in rows:
                    if other is row:
                        continue
                    start = other['allocation']
                    require(all(last<=start or first>=start+other['allocationBytes'] for first,last in writable),
                            'joint command includes a foreign image allocation in its writable ranges')
                commands.add(command)
                shaders.append(original_graph(initial,regs,context,row))
                if index&1:
                    require(shaders[-2]!=shaders[-1],'original VS mutation did not change executable bytes')
                held_path = session/(stem+'.held.json')
                wait_file(held_path,bridge,running,timeout)
                held = receipt_words(strict_json(held_path),'held',command,context,row)
                atomic(session/(stem+'.held.response'),b'OGJH0001'+held)
                retired_path = session/(stem+'.retired.json')
                wait_file(retired_path,bridge,running,timeout)
                retired = strict_json(retired_path)
                response = retired_receipt(retired,held,command,context,row)
                result = results/(stem+'.memory')
                wait_file(result,bridge,running,timeout)
                with SparseMemory(previous) as old, SparseMemory(result) as after:
                    verify_image(old,after,row,writable)
                previous = result
                identities[result] = digest(regular(result))
                atomic(session/(stem+'.retired.response'),response)
                records.append(retired)
                print(f'Joint record {index} verified in {retired["serviceCycles"]} service cycles',flush=True)
        check_inputs(identities)
        atomic(session/'oracle-authorized.response',b'OGJBA001'+struct.pack('<I',RECORDS))
        wait_cleanup(session/'cleanup.json',bridge,running,timeout)
        cleanup_checked(strict_json(session/'cleanup.json'))
        require(running.wait(timeout=timeout)==0,'DUT rejected joint cleanup')
        check_inputs(identities)
        atomic(session/'final-authorized.response',b'OGJOK001'+struct.pack('<I',RECORDS))
        require(bridge.wait(timeout=timeout)==0,'original joint driver rejected final release')
        return records
    finally:
        stop_group(running)


def check_inputs(identities):
    require(all(digest(regular(path))==value for path,value in identities.items()),'joint qualification input changed')


def run(build, dut, timeout):
    git_common = subprocess.check_output(['git','rev-parse','--path-format=absolute','--git-common-dir'],
                                         cwd=ROOT,text=True).strip()
    gpu = Path(git_common).parent
    artifact = ROOT/'.artifacts'
    artifact.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='joint-live-',dir=artifact))
    name = 'opengpu-joint-' + uuid.uuid4().hex
    bridge = probe = None
    keep = False

    def terminate(_number, _stack):
        raise KeyboardInterrupt('joint replay interrupted')

    previous_sigterm = signal.signal(signal.SIGTERM,terminate)
    try:
        with regular(build/'.native-flashsim-build.lock').open('rb') as lock:
            fcntl.flock(lock,fcntl.LOCK_SH|fcntl.LOCK_NB)
            executable, identities = driver_qualification(build,dut)
            identities.update({path:digest(regular(path)) for path in ESSL.iterdir()})
            env = dict(os.environ,OPENGPU_NATIVE_HOST_TIMEOUT=str(timeout))
            profile = directory/'profile.json'
            with (directory/'probe.log').open('xb') as log:
                probe = subprocess.Popen([executable,'--probe-joint-profile',profile],env=env,
                                         stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
                require(probe.wait(timeout=timeout)==0,'protected joint DUT discovery failed')
            discovery, engines, profile_packet = profile_words(strict_json(regular(profile)))
            atomic(directory/'profile.bin',profile_packet)
            ref = subprocess.check_output(['git','show',REFERENCE],cwd=ROOT)
            atomic(directory/'reference-uniform.c',ref)
            (directory/'session').mkdir()
            identities.update({path:digest(regular(path)) for path in (profile,directory/'profile.bin')})
            with (directory/'producer.log').open('xb') as log, (directory/'executor.log').open('xb') as run_log:
                bridge = subprocess.Popen(docker_command(gpu,directory,name,timeout),stdout=log,
                                          stderr=subprocess.STDOUT,start_new_session=True)

                def start(memory,results,session):
                    return subprocess.Popen([executable,'--active-joint-owner-series',memory,results,session,str(RECORDS)],
                                            stdout=run_log,stderr=subprocess.STDOUT,env=env,start_new_session=True)

                records = exchange(directory,start,bridge,discovery,engines,identities,timeout)
            require('Six original Mesa tessellation submissions retired with acknowledged cleanup' in
                    (directory/'producer.log').read_text().splitlines(), 'original producer did not confirm cleanup')
            return records
    except BaseException as error:
        keep = isinstance(error,CleanupIncomplete)
        for path in (directory/'probe.log',directory/'producer.log',directory/'executor.log'):
            if path.is_file():
                print(''.join(path.read_text(errors='replace').splitlines(keepends=True)[-15:]),file=sys.stderr,end='')
        raise
    finally:
        try:
            try:
                stop_group(probe)
            finally:
                try:
                    stop_group(bridge)
                finally:
                    if bridge is not None:
                        remove_container(name)
        except BaseException:
            keep = True
            raise
        finally:
            signal.signal(signal.SIGTERM,previous_sigterm)
            if keep:
                print(f'Retained possibly active joint inputs at {directory}',file=sys.stderr)
            else:
                shutil.rmtree(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor-build',type=Path,required=True)
    parser.add_argument('--dut-dir',type=Path,required=True)
    parser.add_argument('--timeout',type=int,default=900)
    parser.add_argument('--build-only',action='store_true',help='link the joint owner driver against the prepared FlashSim model')
    parser.add_argument('--jobs',type=int,default=2,help='C++ compilation jobs in build-only mode')
    args = parser.parse_args()
    require(0 < args.timeout <= 86400 and args.jobs > 0, 'invalid joint timeout or C++ job count')
    dut = args.dut_dir.resolve(strict=True)
    if args.build_only:
        build_executor(args.executor_build.resolve(),dut,args.jobs)
        return
    records = run(args.executor_build.resolve(strict=True),dut,args.timeout)
    print(f'Joint FlashSim run passed {len(records)} image oracles, actual driver retirement and acknowledged cleanup.')


if __name__ == '__main__':
    main()
