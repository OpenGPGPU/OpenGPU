#!/usr/bin/env python3
"""Keep actual Linux compute mappings alive through DUT execution and cleanup.

The caller supplies the complete-factory/source/tool-qualified executor. The
bridge argv JSON may use {ipc}; no shell is used. A CPU protocol peer is not an
execution qualification. All temporary process data is deleted on exit.
"""
import argparse
import hashlib
from contextlib import nullcontext
import fcntl
import os
from pathlib import Path
import subprocess
import struct
import tempfile
import time
from compute_owner_protocol import (strict_json, profile_bytes, held_bytes,
    retired_bytes, failed_held_bytes, failed_retired_bytes, cleanup_authorization, checked_cleanup,
    uint, required_flags, RECOVERY_CASE, RECOVERY_BASES, RECOVERY_COMMAND_SCHEMA)
from compute_owner_oracle import SparseMemory, verify, union_original_memory

ROOT = Path(__file__).resolve().parents[1]


def require(value, text):
    if not value:
        raise ValueError(text)


def atomic(path, data):
    require(not path.exists() and not path.is_symlink(), 'pre-existing response')
    temporary = path.with_suffix(path.suffix + '.tmp')
    with temporary.open('xb') as stream:
        stream.write(data)
    os.link(temporary, path)
    temporary.unlink()


def wait_file(path, bridge, executor, timeout):
    deadline = time.monotonic() + timeout
    while not path.exists():
        require(not (path.parent / 'error').exists(), 'producer reported an active-session failure')
        require(bridge.poll() is None, 'producer exited with a live compute owner')
        require(executor is None or executor.poll() is None, 'executor exited before ' + path.name)
        require(time.monotonic() < deadline, 'timeout waiting for ' + path.name)
        time.sleep(0.05)
    require(path.is_file() and not path.is_symlink(), 'invalid IPC file ' + path.name)
    require(bridge.poll() is None, 'producer exited before live receipt ' + path.name)


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def lock_owner(qualification):
    record = strict_json(qualification)
    require(record.get('schema') == 'opengpu-owner-executor-v1' and record.get('kind') == 'compute',
            'wrong owner lock qualification kind')
    require(type(record.get('owner_output')) is str, 'missing prepared owner directory')
    owner = Path(record['owner_output']).resolve(strict=True)
    require(qualification.resolve(strict=True) == owner / 'executors/compute/qualification.json',
            'qualification does not name the prepared owner')
    path = owner / '.lock'
    require(not path.is_symlink(), 'owner lock must not be aliased')
    lock = path.open('a')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BaseException:
        lock.close()
        raise
    return lock


def input_digest(path):
    require(path.is_file() and not path.is_symlink(), 'counter input missing or aliased')
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()


def ready_inputs(directory, case, *, owner_profile=None, permission=None, profile=None, global_cached=False, cache_alias=False, uc_cache_alias=False):
    from compute_counter_source import SCHEMA as COUNTER_SCHEMA, ready as counter_ready
    from compute_p12_counter_source import SCHEMA as P12_COUNTER_SCHEMA, ready as p12_counter_ready
    from compute_p12_shared_source import SCHEMA as P12_SHARED_SCHEMA, ready as p12_shared_ready
    from compute_p12_texture_source import SCHEMA as P12_TEXTURE_SCHEMA, ready as p12_texture_ready
    from compute_owner_cases import (COUNTER_CASES, P12_COUNTER_CASES, P12_SHARED_CASES, P12_TEXTURE_CASES, IMAGE_CASES, MIXED_ATOMIC_CASES,
        GLOBAL_ATOMIC_CASES)
    from compute_image_source import SCHEMA as IMAGE_SCHEMA, CONTENDED_SCHEMA, ready as image_ready
    from compute_storage_atomic_source import SCHEMA as STORAGE_SCHEMA, ready as storage_ready
    from compute_storage_atomic_cases import CASES as STORAGE_CASES
    from compute_storage_publication_source import SCHEMA as PUBLICATION_SCHEMA, ready as publication_ready
    from compute_storage_publication_cases import CASES as PUBLICATION_CASES
    from compute_image_publication_source import SCHEMA as IMAGE_PUBLICATION_SCHEMA, ready as image_publication_ready
    from compute_image_publication_cases import CASES as IMAGE_PUBLICATION_CASES
    from compute_texture_publication_source import SCHEMA as TEXTURE_PUBLICATION_SCHEMA, ready as texture_publication_ready
    from compute_texture_publication_cases import CASES as TEXTURE_PUBLICATION_CASES
    from compute_counter_publication_source import SCHEMA as COUNTER_PUBLICATION_SCHEMA, ready as counter_publication_ready
    from compute_counter_publication_cases import CASES as COUNTER_PUBLICATION_CASES
    from compute_shared_publication_source import SCHEMA as SHARED_PUBLICATION_SCHEMA, ready as shared_publication_ready
    from compute_shared_publication_cases import CASES as SHARED_PUBLICATION_CASES
    from compute_dma_publication_source import SCHEMA as DMA_PUBLICATION_SCHEMA, ready as dma_publication_ready
    from compute_dma_publication_cases import CASES as DMA_PUBLICATION_CASES
    from compute_l1_publication_source import SCHEMA as L1_PUBLICATION_SCHEMA, ready as l1_publication_ready
    from compute_l1_publication_cases import CASES as L1_PUBLICATION_CASES
    from compute_mixed_atomic_source import SCHEMA as MIXED_SCHEMA, ready as mixed_ready
    from compute_global_atomic_source import SCHEMA as GLOBAL_SCHEMA, CACHED_SCHEMA, ready as global_ready
    from compute_cache_alias_source import SCHEMA as ALIAS_SCHEMA, ready as alias_ready
    from compute_uc_cache_alias_source import SCHEMA as UC_ALIAS_SCHEMA, ready as uc_alias_ready
    require(type(uc_cache_alias) is bool and (not uc_cache_alias or
        (case in GLOBAL_ATOMIC_CASES and not global_cached and not cache_alias)),
        'UC cache alias qualification requires its distinct original source')
    require(type(cache_alias) is bool and (not cache_alias or (case in GLOBAL_ATOMIC_CASES and not global_cached)),
            'cache alias qualification requires its distinct original source')
    require(type(global_cached) is bool and (not global_cached or case in GLOBAL_ATOMIC_CASES),
            'cached global qualification requires its original source')
    require(permission is None or (case==RECOVERY_CASE and permission in ('read','write')),
            'image permission denial requires the original float buffer case')
    description=strict_json(directory/'oracle.json')
    counter=description.get('schema')==COUNTER_SCHEMA
    p12_counter=description.get('schema')==P12_COUNTER_SCHEMA
    p12_shared=description.get('schema')==P12_SHARED_SCHEMA
    p12_texture=description.get('schema')==P12_TEXTURE_SCHEMA
    image=description.get('schema') in (IMAGE_SCHEMA,CONTENDED_SCHEMA)
    contended=description.get('schema')==CONTENDED_SCHEMA
    mixed=description.get('schema')==MIXED_SCHEMA
    storage=description.get('schema')==STORAGE_SCHEMA
    publication=description.get('schema')==PUBLICATION_SCHEMA
    image_publication=description.get('schema')==IMAGE_PUBLICATION_SCHEMA
    texture_publication=description.get('schema')==TEXTURE_PUBLICATION_SCHEMA
    counter_publication=description.get('schema')==COUNTER_PUBLICATION_SCHEMA
    shared_publication=description.get('schema')==SHARED_PUBLICATION_SCHEMA
    dma_publication=description.get('schema')==DMA_PUBLICATION_SCHEMA
    l1_publication=description.get('schema')==L1_PUBLICATION_SCHEMA
    global_atomic=description.get('schema') in (GLOBAL_SCHEMA,CACHED_SCHEMA,ALIAS_SCHEMA,UC_ALIAS_SCHEMA)
    require(not global_atomic or description.get('schema')==(UC_ALIAS_SCHEMA if uc_cache_alias else ALIAS_SCHEMA if cache_alias else CACHED_SCHEMA if global_cached else GLOBAL_SCHEMA),
            'cached and uncached global READY contracts differ')
    require(counter==(case in COUNTER_CASES and case not in P12_COUNTER_CASES),
            'counter source requires distinct qualification/case binding')
    require(p12_counter==(case in P12_COUNTER_CASES), 'P12 counter source requires distinct qualification/case binding')
    require(p12_shared==(case in P12_SHARED_CASES), 'P12 shared source requires distinct qualification/case binding')
    require(p12_texture==(case in P12_TEXTURE_CASES), 'P12 texture source requires distinct qualification/case binding')
    require((image or mixed)==(case in IMAGE_CASES), 'image source requires distinct qualification/case binding')
    require(mixed==(case in MIXED_ATOMIC_CASES),'mixed atomic source requires its distinct alias contract')
    require(storage==(case in STORAGE_CASES), 'storage atomic source requires distinct qualification/case binding')
    require(publication==(case in PUBLICATION_CASES), 'storage publication source requires distinct qualification/case binding')
    require(image_publication==(case in IMAGE_PUBLICATION_CASES),
            'image publication source requires distinct qualification/case binding')
    require(texture_publication==(case in TEXTURE_PUBLICATION_CASES),
            'texture publication source requires distinct qualification/case binding')
    require(counter_publication==(case in COUNTER_PUBLICATION_CASES),
            'counter publication source requires distinct qualification/case binding')
    require(shared_publication==(case in SHARED_PUBLICATION_CASES),
            'shared publication source requires distinct qualification/case binding')
    require(dma_publication==(case in DMA_PUBLICATION_CASES),
            'dma publication source requires distinct qualification/case binding')
    require(l1_publication==(case in L1_PUBLICATION_CASES),
            'L1 publication source requires distinct qualification/case binding')
    require(global_atomic==(case in GLOBAL_ATOMIC_CASES), 'raw global source requires distinct qualification/case binding')
    inputs={}
    if counter or p12_counter or p12_shared or p12_texture or image or storage or publication or image_publication or texture_publication or counter_publication or shared_publication or dma_publication or l1_publication or mixed or global_atomic:
        from owner_resource_contract import SAMPLE_PROFILE
        owner_profile=SAMPLE_PROFILE if owner_profile is None else owner_profile
        require(description.get('sourceCase')==case, 'source READY case differs from qualification')
        with SparseMemory(directory/'union.memory') as before:
            data=(directory/'record-0.capture').read_bytes()
            if p12_counter:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'P12 counter coexist requires the full float owner')
                p12_counter_ready(before,description,data,owner_profile=owner_profile)
            elif p12_shared:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'P12 shared coexist requires the full float owner')
                p12_shared_ready(before,description,data,owner_profile=owner_profile)
            elif p12_texture:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'P12 texture coexist requires the full float owner')
                p12_texture_ready(before,description,data,owner_profile=owner_profile)
            elif global_atomic:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'global atomics require the full float owner')
                source_ready=uc_alias_ready if uc_cache_alias else alias_ready if cache_alias else global_ready
                source_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                    strict_json(directory/'profile.json') if profile is None else profile,
                    **({} if cache_alias or uc_cache_alias else dict(cached=global_cached)))
            elif l1_publication:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'L1 publication requires the full float owner')
                l1_publication_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                                     strict_json(directory/'profile.json') if profile is None else profile)
            elif dma_publication:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'dma publication requires the full float owner')
                dma_publication_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                                      strict_json(directory/'profile.json') if profile is None else profile)
            elif shared_publication:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'shared publication requires the full float owner')
                shared_publication_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                                          strict_json(directory/'profile.json') if profile is None else profile)
            elif counter_publication:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'counter publication requires the full float owner')
                counter_publication_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                                          strict_json(directory/'profile.json') if profile is None else profile)
            elif texture_publication:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'texture publication requires the full float owner')
                texture_publication_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                                          strict_json(directory/'profile.json') if profile is None else profile)
            elif image_publication:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'image publication requires the full float owner')
                image_publication_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                                        strict_json(directory/'profile.json') if profile is None else profile)
            elif publication:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'storage publication requires the full float owner')
                publication_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                                  strict_json(directory/'profile.json') if profile is None else profile)
            elif storage:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE, 'storage atomics require the full float owner')
                storage_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                              strict_json(directory/'profile.json') if profile is None else profile)
            elif mixed:
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                require(owner_profile==IMAGE_FLOAT_PROFILE,'mixed atomics require the full float owner')
                mixed_ready(before,description,data,(directory/'code.words').read_text().splitlines(),
                    strict_json(directory/'profile.json') if profile is None else profile)
            elif image:image_ready(before,description,data,owner_profile=owner_profile,permission=permission,
                code_words=(directory/'code.words').read_text().splitlines() if contended else None)
            else:counter_ready(before,description,data,owner_profile=owner_profile)
        inputs={name:input_digest(directory/name) for name in ('union.memory','record-0.capture','oracle.json')}
        if storage or publication or image_publication or texture_publication or counter_publication or shared_publication or dma_publication or l1_publication or contended or mixed or global_atomic:
            inputs['code.words']=input_digest(directory/'code.words')
    return description,inputs


def unchanged_inputs(directory, inputs):
    for name,digest in inputs.items():
        require(input_digest(directory/name)==digest, 'counter immutable READY input changed: '+name)


def recovery_commands(value, *, permission=None, reset=None):
    require(permission is None or permission in ('read','write'),'unknown image permission denial')
    require(reset is None or (reset in ('read','write') and permission is None),'unknown/ambiguous image reset')
    require(type(value) is dict and set(value)=={'schema','rounds'} and
            value['schema']==RECOVERY_COMMAND_SCHEMA and type(value['rounds']) is list and
            len(value['rounds'])==2,'two original recovery producer commands required')
    for index,command in enumerate(value['rounds']):
        require(type(command) is list and command and all(type(x) is str and x for x in command) and
                any('{ipc}' in x for x in command),'recovery producer must consume its original IPC directory')
        require('--capture-only' not in command and
                command.count('--expect-image-source-fault')==(1 if index==0 and permission is None and reset is None else 0) and
                command.count('--expect-image-permission-fault')==(1 if index==0 and permission is not None else 0) and
                command.count('--expect-image-reset')==(1 if index==0 and reset is not None else 0),
                'recovery must run one live source fault followed by one healthy producer')
        if index==0 and permission is not None:
            position=command.index('--expect-image-permission-fault')
            require(position+1<len(command) and command[position+1]==permission,'wrong denied image permission')
        if index==0 and reset is not None:
            position=command.index('--expect-image-reset')
            require(position+1<len(command) and command[position+1]==reset,'wrong delayed image response channel')
        for flag,wanted in [('--case',RECOVERY_CASE),('--physical-base',str(RECOVERY_BASES[index]))]:
            require(command.count(flag)==1 and command.index(flag)+1<len(command) and
                    command[command.index(flag)+1]==wanted,'wrong original recovery recipe/allocation namespace')
    return value['rounds']


def checked_recovery_round(value,index,held,previous=None, *, permission=None, reset=None):
    from compute_image_source import source_read_fault_proof,image_permission_fault_proof
    require(permission is None or permission in ('read','write'),'unknown image permission denial')
    require(reset is None or (reset in ('read','write') and permission is None),'unknown/ambiguous image reset')
    require(type(index) is int and index in (0,1),'unknown recovery round')
    require((index==0)==(previous is None),'recovery round lacks its preceding fault result')
    required_flags(value,dict(schema='opengpu-compute-image-'+('reset-recovery' if reset else 'recovery')+'-round-v1',round=index,
        commandId=uint(held.get('commandId')),resetCount=1,ramReplaced=False,externalDrained=True,
        sourceQualificationRequired=True,independentBytesRequired=True,physicalWriteFaults=0))
    if reset:
        from compute_image_reset import reset_history
        required_flags(value,dict(channel=reset,unifiedResetRequests=1))
        reset_history(value,reset,held if previous is None else previous)
    else:
        proof=source_read_fault_proof if permission is None else image_permission_fault_proof
        proof(value,held if previous is None else previous)
    start,end=uint(value.get('startCycle'),64),uint(value.get('endCycle'),64)
    require(start<end,'recovery cycle interval is empty or reversed')
    if previous is not None:
        require(index==1 and value['commandId']==previous['commandId'] and start>=previous['endCycle'],
                'healthy recovery changed the command ID or restarted its execution clock')
    return value


def exchange_reset_receipts(args,local,bridge,running,destination,baseline,description,input_sets,channel):
    from compute_image_reset import reset_receipt_bytes, verify_cancelled_memory
    previous=None
    for phase in ('held','busy','ack','retired'):
        wait_file(local/('record-0.reset.'+phase+'.json'),bridge,running,args.timeout)
        require(bridge.poll() is None and running.poll() is None,'reset owner exited before '+phase+' observation')
        observed=strict_json(local/('record-0.reset.'+phase+'.json'))
        response=reset_receipt_bytes(observed,phase,channel,previous)
        if phase=='ack':
            for path,identities in input_sets:unchanged_inputs(path,identities)
            with SparseMemory(baseline) as before,SparseMemory(destination/'record-0.memory') as after:
                proof=verify_cancelled_memory(before,after,description,channel)
            print('image reset bytes verified channel=%s changedSourceWords=%d'%(channel,proof['changedSourceWords']),flush=True)
        atomic(local/('record-0.reset.'+phase+'.response'),response)
        previous=observed
    return previous


def exchange_recovery(args,commands,directory,executor,env,qualified,qualified_profile):
    from compute_image_source import (source_read_fault_plan,source_read_fault_proof,
        verify_source_read_fault,captured_page_tables,verify_cleaned_memory,
        image_permission_fault_proof,verify_image_permission_fault)
    permission=getattr(args,'image_permission_recovery',None)
    reset=getattr(args,'image_reset_recovery',None)
    proof=source_read_fault_proof if permission is None else image_permission_fault_proof
    running=bridge=None;previous=None;previous_clean=None;previous_digest=None;input_sets=[]
    try:
        with (directory/'execution.log').open('w') as output:
            for index,command in enumerate(commands):
                local=directory/('round-'+str(index));local.mkdir()
                atomic(local/'profile.bin',(directory/'profile.bin').read_bytes())
                with (local/'bridge.log').open('w') as log:
                    bridge=subprocess.Popen([x.replace('{ipc}',str(local)) for x in command],
                        env=dict(env,TMPDIR=str(local)),stdout=log,stderr=subprocess.STDOUT)
                    wait_file(local/'READY',bridge,running,args.timeout)
                    for name in ('union.memory','record-0.capture','oracle.json'):
                        wait_file(local/name,bridge,running,args.timeout)
                    description,inputs=ready_inputs(local,args.case,owner_profile=qualified_profile,
                        permission=permission if index==0 else None)
                    with SparseMemory(local/'union.memory') as upload:
                        require(upload.ranges and upload.ranges[0][0]==RECOVERY_BASES[index],
                                'original allocation namespace was ignored or replaced')
                        tables=captured_page_tables(upload,(local/'record-0.capture').read_bytes())
                        if index==0:
                            if reset:
                                from compute_image_reset import reset_plan
                                atomic(local/'record-0.reset.plan',reset_plan(upload,description,reset,owner_profile=qualified_profile))
                                inputs['record-0.reset.plan']=input_digest(local/'record-0.reset.plan')
                            elif permission is None:
                                atomic(local/'record-0.fault-plan',source_read_fault_plan(
                                    upload,description,owner_profile=qualified_profile))
                                inputs['record-0.fault-plan']=input_digest(local/'record-0.fault-plan')
                            baseline=local/'union.memory'
                        else:
                            require(input_digest(previous_clean)==previous_digest,'retained cleaned RAM changed before new upload')
                            with SparseMemory(previous_clean) as old:
                                atomic(local/'global-before.memory',union_original_memory(old,upload))
                            inputs['global-before.memory']=input_digest(local/'global-before.memory')
                            baseline=local/'global-before.memory'
                    input_sets.append((local,inputs))
                    for path,identities in input_sets:unchanged_inputs(path,identities)
                    atomic(local/'BEGIN_EXECUTION',struct.pack('<8sI',b'OGCMEX01',index))
                    if index==0:
                        execution_mode='--active-image-reset-recovery' if reset else '--active-image-permission-recovery' if permission else '--active-image-source-recovery'
                        running=subprocess.Popen([executor,execution_mode,directory/'results',directory,*([reset] if reset else [])],
                            env=env,stdout=output,stderr=subprocess.STDOUT)
                    destination=directory/'results'/('round-'+str(index))
                    if reset and index==0:
                        held=exchange_reset_receipts(args,local,bridge,running,destination,baseline,description,input_sets,reset)
                    else:
                        wait_file(local/'record-0.held.json',bridge,running,args.timeout)
                        held=strict_json(local/'record-0.held.json')
                        if index==0:proof(held)
                        atomic(local/'record-0.held.response',(held_bytes if index else failed_held_bytes)(held))
                        wait_file(local/'record-0.retired.json',bridge,running,args.timeout)
                        retired=strict_json(local/'record-0.retired.json')
                        response=(retired_bytes if index else failed_retired_bytes)(retired,held)
                        if index==0:proof(retired,held)
                        for path,identities in input_sets:unchanged_inputs(path,identities)
                        with SparseMemory(baseline) as before,SparseMemory(destination/'record-0.memory') as after:
                            if index:verify(before,after,description)
                            elif permission:verify_image_permission_fault(before,after,description,permission=permission)
                            else:verify_source_read_fault(before,after,description)
                        atomic(local/'record-0.retired.response',response)
                    wait_file(local/'cleanup_wait',bridge,running,args.timeout)
                    require(bridge.poll() is None and running.poll() is None,'recovery owner exited before independent cleanup authorization')
                    atomic(local/'BEGIN_CLEANUP',cleanup_authorization())
                    wait_file(local/'cleanup.json',bridge,running,args.timeout)
                    checked_cleanup(strict_json(local/'cleanup.json'))
                    wait_file(local/'executor.done',bridge,running,args.timeout)
                    done=checked_recovery_round(strict_json(local/'executor.done'),index,held,previous,permission=permission,reset=reset)
                    cleaned=destination/'record-0.cleaned.memory'
                    if reset and index==0:
                        from compute_image_reset import verify_cancelled_cleanup
                        with SparseMemory(baseline) as initial,SparseMemory(destination/'record-0.memory') as cancelled,SparseMemory(cleaned) as after:
                            verify_cancelled_cleanup(initial,cancelled,after,description,reset,tables)
                    else:
                        with SparseMemory(destination/'record-0.memory') as before,SparseMemory(cleaned) as after:
                            verify_cleaned_memory(before,after,tables)
                    if index==0:require(running.poll() is None,'recovery execution DUT exited between the two original jobs')
                    else:require(running.wait(timeout=args.timeout)==0,'recovery DUT rejected final context cleanup')
                    qualified(env,output)
                    for path,identities in input_sets:unchanged_inputs(path,identities)
                    previous,previous_clean,previous_digest=done,cleaned,input_digest(cleaned)
                    atomic(local/'FINAL_RESPONSE',b'OGCMOK01\x01\x00\x00\x00')
                    require(bridge.wait(timeout=args.timeout)==0,'original recovery producer rejected actual cleanup')
                    bridge=None
                    print('image recovery verified round=%d commandId=%d cycles=%d..%d retainedReadFaults=%d'%(
                        index,done['commandId'],done['startCycle'],done['endCycle'],done['physicalReadFaults']),flush=True)
    except BaseException:
        for local in directory.glob('round-*'):
            for suffix in ('error','record-0.reset.error') if reset else ('error',):
                if not (local/suffix).exists():
                    atomic(local/suffix,b'controller failed; original owner cleanup not authorized\n')
            path=local/'bridge.log'
            if path.is_file():print(path.read_text(errors='replace')[-10000:],end='')
        raise
    finally:
        stop(running);stop(bridge)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor', type=Path, required=True)
    parser.add_argument('--executor-qualification', type=Path, required=True)
    parser.add_argument('--executor-checker', type=Path, required=True,
                        help='original guarded prepare_owner_executor.py; do not copy it into the bridge tree')
    parser.add_argument('--executor-checker-python', type=Path, required=True,
                        help='the generation-matched Python interpreter, with its fixed CIRCT environment')
    parser.add_argument('--bridge-command', type=Path, required=True)
    parser.add_argument('--managed-session-directory', type=Path,
                        help='new GPU-internal session owned/removed by qualify_compute_owner after container exit')
    parser.add_argument('--timeout', type=int, default=3600)
    from compute_owner_cases import (COUNTER_CASES, P12_COUNTER_CASES, P12_SHARED_CASES, P12_TEXTURE_CASES, IMAGE_CASES, STORAGE_ATOMIC_CASES,
        STORAGE_PUBLICATION_CASES, IMAGE_PUBLICATION_CASES, TEXTURE_PUBLICATION_CASES,
        COUNTER_PUBLICATION_CASES, SHARED_PUBLICATION_CASES, DMA_PUBLICATION_CASES,
        L1_PUBLICATION_CASES, GLOBAL_ATOMIC_CASES)
    parser.add_argument('--case', choices=(*COUNTER_CASES,*P12_SHARED_CASES,*P12_TEXTURE_CASES,*IMAGE_CASES,*STORAGE_ATOMIC_CASES,
        *STORAGE_PUBLICATION_CASES,*IMAGE_PUBLICATION_CASES,*TEXTURE_PUBLICATION_CASES,
        *COUNTER_PUBLICATION_CASES,*SHARED_PUBLICATION_CASES,*DMA_PUBLICATION_CASES,
        *L1_PUBLICATION_CASES,*GLOBAL_ATOMIC_CASES),
        help='distinct final112 source contract')
    parser.add_argument('--storage-matrix',action='store_true')
    parser.add_argument('--storage-delayed-ack',action='store_true')
    parser.add_argument('--storage-recovery-matrix',action='store_true')
    parser.add_argument('--storage-accepted-matrix',action='store_true')
    parser.add_argument('--storage-publication-matrix',action='store_true')
    parser.add_argument('--image-publication-matrix',action='store_true')
    parser.add_argument('--texture-publication-matrix',action='store_true')
    parser.add_argument('--counter-publication-matrix',action='store_true')
    parser.add_argument('--shared-publication-matrix',action='store_true')
    parser.add_argument('--dma-publication-matrix',action='store_true')
    parser.add_argument('--l1-publication-matrix',action='store_true')
    parser.add_argument('--image-accepted-matrix',action='store_true')
    parser.add_argument('--contended-image-matrix',action='store_true')
    parser.add_argument('--mixed-atomic-matrix',action='store_true')
    parser.add_argument('--global-atomic-matrix',action='store_true')
    parser.add_argument('--global-cached-matrix',action='store_true')
    parser.add_argument('--global-delayed-ack',action='store_true')
    parser.add_argument('--global-recovery-matrix',action='store_true')
    parser.add_argument('--global-accepted-matrix',action='store_true')
    parser.add_argument('--cache-alias-matrix',action='store_true')
    parser.add_argument('--uc-cache-alias-matrix',action='store_true')
    parser.add_argument('--p12-counter-matrix',action='store_true')
    parser.add_argument('--p12-shared-matrix',action='store_true')
    parser.add_argument('--p12-texture-matrix',action='store_true')
    parser.add_argument('--reservation-matrix',action='store_true')
    mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--expect-image-source-fault', action='store_true')
    mode.add_argument('--image-source-recovery', action='store_true')
    mode.add_argument('--image-permission-recovery',choices=('read','write'))
    mode.add_argument('--image-reset-recovery',choices=('read','write'))
    args = parser.parse_args()
    if args.reservation_matrix:
        from compute_reservation_wait import mode
        mode(args,args.case)
    require(not args.uc_cache_alias_matrix or (args.case==GLOBAL_ATOMIC_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,args.global_atomic_matrix,
        args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,args.global_accepted_matrix,
        args.cache_alias_matrix,args.expect_image_source_fault,args.image_source_recovery,
        args.image_permission_recovery,args.image_reset_recovery))),
        'UC cache alias recovery requires its distinct original-source matrix')
    require(not args.cache_alias_matrix or (args.case==GLOBAL_ATOMIC_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,args.global_atomic_matrix,
        args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,args.global_accepted_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,args.image_reset_recovery))),
        'cache alias recovery requires its distinct original-source matrix')
    require(not args.p12_counter_matrix or (args.case in P12_COUNTER_CASES and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.image_publication_matrix,args.texture_publication_matrix,
        args.counter_publication_matrix,args.shared_publication_matrix,args.dma_publication_matrix,
        args.l1_publication_matrix,args.image_accepted_matrix,args.contended_image_matrix,
        args.mixed_atomic_matrix,args.global_atomic_matrix,args.global_cached_matrix,
        args.global_delayed_ack,args.global_recovery_matrix,args.global_accepted_matrix,
        args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,
        args.image_permission_recovery,args.image_reset_recovery))),
        'P12 counter coexist requires its distinct full float owner source')
    require(not args.p12_shared_matrix or (args.case in P12_SHARED_CASES and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.image_publication_matrix,args.texture_publication_matrix,
        args.counter_publication_matrix,args.shared_publication_matrix,args.dma_publication_matrix,
        args.l1_publication_matrix,args.p12_counter_matrix,args.image_accepted_matrix,args.contended_image_matrix,
        args.mixed_atomic_matrix,args.global_atomic_matrix,args.global_cached_matrix,
        args.global_delayed_ack,args.global_recovery_matrix,args.global_accepted_matrix,
        args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,
        args.image_permission_recovery,args.image_reset_recovery))),
        'P12 shared coexist requires its distinct full float owner source')
    require(not args.p12_texture_matrix or (args.case in P12_TEXTURE_CASES and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.image_publication_matrix,args.texture_publication_matrix,
        args.counter_publication_matrix,args.shared_publication_matrix,args.dma_publication_matrix,
        args.l1_publication_matrix,args.p12_counter_matrix,args.p12_shared_matrix,args.image_accepted_matrix,
        args.contended_image_matrix,args.mixed_atomic_matrix,args.global_atomic_matrix,args.global_cached_matrix,
        args.global_delayed_ack,args.global_recovery_matrix,args.global_accepted_matrix,
        args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,
        args.image_permission_recovery,args.image_reset_recovery))),
        'P12 texture coexist requires its distinct full float owner source')
    require(not args.global_cached_matrix or (args.global_atomic_matrix and not args.global_delayed_ack and
        not args.global_recovery_matrix and not args.global_accepted_matrix),
        'cached global matrix requires its separate normal source sequence')
    from compute_contended_image_cases import CASES as CONTENDED_IMAGE_CASES
    require(not args.image_accepted_matrix or (args.case==CONTENDED_IMAGE_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.contended_image_matrix,args.mixed_atomic_matrix,args.global_atomic_matrix,args.global_delayed_ack,
        args.global_recovery_matrix,args.global_accepted_matrix,args.expect_image_source_fault,args.image_source_recovery,
        args.image_permission_recovery,args.image_reset_recovery))),
        'image accepted-write recovery requires its distinct original physical-fault matrix')
    require(not args.global_delayed_ack or args.global_atomic_matrix,'global ACK requires its exact complete source matrix')
    require(not args.storage_accepted_matrix or (args.case==STORAGE_ATOMIC_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.contended_image_matrix,
        args.mixed_atomic_matrix,args.global_atomic_matrix,args.global_delayed_ack,args.global_recovery_matrix,
        args.global_accepted_matrix,args.expect_image_source_fault,args.image_source_recovery,
        args.image_permission_recovery,args.image_reset_recovery))),
        'storage accepted-write recovery requires its distinct original physical-fault matrix')
    require(not args.global_accepted_matrix or (args.case==GLOBAL_ATOMIC_CASES[0] and not any((
        args.global_atomic_matrix,args.global_recovery_matrix,args.global_delayed_ack,args.storage_matrix,
        args.storage_delayed_ack,args.storage_recovery_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))), 'accepted-write recovery requires its distinct original physical-fault matrix')
    require(not args.global_recovery_matrix or (args.case==GLOBAL_ATOMIC_CASES[0] and not any((
        args.global_atomic_matrix,args.global_accepted_matrix,args.global_delayed_ack,args.storage_matrix,args.storage_delayed_ack,
        args.storage_recovery_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))), 'global recovery requires its distinct original physical-fault matrix')
    require(not args.global_atomic_matrix or (args.case==GLOBAL_ATOMIC_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.contended_image_matrix,
        args.mixed_atomic_matrix,args.expect_image_source_fault,args.image_source_recovery,
        args.image_permission_recovery,args.image_reset_recovery))), 'global matrix requires its exact first source and normal independent owners')
    from compute_contended_image_cases import CASES as CONTENDED_IMAGE_CASES
    from compute_mixed_atomic_cases import CASES as MIXED_ATOMIC_CASES
    require(not args.mixed_atomic_matrix or (args.case==MIXED_ATOMIC_CASES[0] and
        not (args.storage_matrix or args.storage_delayed_ack or args.storage_recovery_matrix or args.contended_image_matrix or
             args.expect_image_source_fault or args.image_source_recovery or args.image_permission_recovery or args.image_reset_recovery)),
        'mixed atomic matrix requires its exact first source and distinct original alias owners')
    require(not args.contended_image_matrix or (args.case==CONTENDED_IMAGE_CASES[0] and
        not (args.storage_matrix or args.storage_delayed_ack or args.storage_recovery_matrix or
             args.expect_image_source_fault or args.image_source_recovery or
             args.image_permission_recovery or args.image_reset_recovery)),
        'contended image matrix requires its exact first source and separate original owners')
    require(not args.storage_recovery_matrix or (args.case==STORAGE_ATOMIC_CASES[0] and
        not (args.storage_matrix or args.storage_delayed_ack or args.expect_image_source_fault or
             args.image_source_recovery or args.image_permission_recovery or args.image_reset_recovery)),
        'storage recovery requires its exact original matrix and distinct failed receipts')
    require(not args.storage_publication_matrix or (args.case==STORAGE_PUBLICATION_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.image_publication_matrix,args.texture_publication_matrix,args.counter_publication_matrix,args.shared_publication_matrix,args.dma_publication_matrix,args.l1_publication_matrix,args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.global_atomic_matrix,args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,
        args.global_accepted_matrix,args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))),
        'storage publication requires its exact warm source and distinct continuous receipts')
    require(not args.image_publication_matrix or (args.case==IMAGE_PUBLICATION_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.texture_publication_matrix,args.counter_publication_matrix,args.shared_publication_matrix,args.dma_publication_matrix,args.l1_publication_matrix,args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.global_atomic_matrix,args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,
        args.global_accepted_matrix,args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))),
        'image publication requires its exact warm source and distinct continuous receipts')
    require(not args.texture_publication_matrix or (args.case==TEXTURE_PUBLICATION_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.image_publication_matrix,args.counter_publication_matrix,args.shared_publication_matrix,args.dma_publication_matrix,args.l1_publication_matrix,args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.global_atomic_matrix,args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,
        args.global_accepted_matrix,args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))),
        'texture publication requires its exact warm source and distinct continuous receipts')
    require(not args.counter_publication_matrix or (args.case==COUNTER_PUBLICATION_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.image_publication_matrix,args.texture_publication_matrix,args.shared_publication_matrix,args.dma_publication_matrix,args.l1_publication_matrix,args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.global_atomic_matrix,args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,
        args.global_accepted_matrix,args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))),
        'counter publication requires its exact warm source and distinct continuous receipts')
    require(not args.shared_publication_matrix or (args.case==SHARED_PUBLICATION_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.image_publication_matrix,args.texture_publication_matrix,args.counter_publication_matrix,args.dma_publication_matrix,args.l1_publication_matrix,args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.global_atomic_matrix,args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,
        args.global_accepted_matrix,args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))),
        'shared publication requires its exact warm source and distinct continuous receipts')
    require(not args.dma_publication_matrix or (args.case==DMA_PUBLICATION_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.image_publication_matrix,args.texture_publication_matrix,args.counter_publication_matrix,args.shared_publication_matrix,args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.global_atomic_matrix,args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,
        args.global_accepted_matrix,args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))),
        'dma publication requires its exact warm source and distinct continuous receipts')
    require(not args.l1_publication_matrix or (args.case==L1_PUBLICATION_CASES[0] and not any((
        args.storage_matrix,args.storage_delayed_ack,args.storage_recovery_matrix,args.storage_accepted_matrix,
        args.storage_publication_matrix,args.image_publication_matrix,args.texture_publication_matrix,args.counter_publication_matrix,args.shared_publication_matrix,args.dma_publication_matrix,args.image_accepted_matrix,args.contended_image_matrix,args.mixed_atomic_matrix,
        args.global_atomic_matrix,args.global_cached_matrix,args.global_delayed_ack,args.global_recovery_matrix,
        args.global_accepted_matrix,args.cache_alias_matrix,args.uc_cache_alias_matrix,args.reservation_matrix,
        args.expect_image_source_fault,args.image_source_recovery,args.image_permission_recovery,
        args.image_reset_recovery))),
        'L1 publication requires its exact warm source and distinct continuous receipts')
    require(not args.storage_delayed_ack or args.storage_matrix,
            'storage ACK qualification requires the original complete source matrix')
    require(not args.storage_matrix or (args.case==STORAGE_ATOMIC_CASES[0] and
        not (args.expect_image_source_fault or args.image_source_recovery or args.image_permission_recovery or args.image_reset_recovery)),
        'storage matrix requires its exact original source and normal receipts')
    require(0 < args.timeout <= 604800, 'timeout must be 1..604800 seconds')
    command = strict_json(args.bridge_command)
    if args.image_source_recovery or args.image_permission_recovery or args.image_reset_recovery:
        command=recovery_commands(command,permission=args.image_permission_recovery,reset=args.image_reset_recovery)
    else:
        require(type(command) is list and command and all(type(x) is str and x for x in command),
                'bridge command must be a nonempty argv array')
        require(any('{ipc}' in x for x in command), 'bridge command must consume the active IPC directory')
    executor = args.executor.absolute()
    qualification = args.executor_qualification.absolute()
    require(executor.is_file() and not executor.is_symlink() and qualification.is_file() and not qualification.is_symlink(),
            'executor/qualification must be original regular files')
    qualified_profile=strict_json(qualification).get('profile')
    require(not (args.cache_alias_matrix or args.uc_cache_alias_matrix) or strict_json(qualification).get('backend')=='verilator',
            'cache alias recovery requires the actual bound Verilator cache-state observer')
    storage_view_fault=False
    if args.case in STORAGE_ATOMIC_CASES:
        from compute_storage_atomic_cases import parameters as storage_parameters
        storage_view_fault=bool(storage_parameters(args.case)['result_status'])
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE,'storage atomic execution requires the complete float owner')
    if args.case in STORAGE_PUBLICATION_CASES or args.storage_publication_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE,'storage publication requires the complete float owner')
    if args.case in IMAGE_PUBLICATION_CASES or args.image_publication_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE,'image publication requires the complete float owner')
    if args.case in TEXTURE_PUBLICATION_CASES or args.texture_publication_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE,'texture publication requires the complete float owner')
    if args.case in COUNTER_PUBLICATION_CASES or args.counter_publication_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE,'counter publication requires the complete float owner')
    if args.case in SHARED_PUBLICATION_CASES or args.shared_publication_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE,'shared publication requires the complete float owner')
    if args.case in DMA_PUBLICATION_CASES or args.dma_publication_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE,'dma publication requires the complete float owner')
    if args.case in L1_PUBLICATION_CASES or args.l1_publication_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE,'L1 publication requires the complete float owner')
    if args.expect_image_source_fault or args.image_source_recovery or args.image_permission_recovery or args.image_reset_recovery:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(args.case=='image-atomic-buffer-r32f' and qualified_profile==IMAGE_FLOAT_PROFILE,
                'source fault requires the original buffer exchange and qualified float executor')
    if args.case in GLOBAL_ATOMIC_CASES:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE, 'global atomics require the complete float owner')
    if args.case in P12_COUNTER_CASES or args.p12_counter_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE, 'P12 counter coexist requires the complete float owner')
    if args.case in P12_SHARED_CASES or args.p12_shared_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE, 'P12 shared coexist requires the complete float owner')
    if args.case in P12_TEXTURE_CASES or args.p12_texture_matrix:
        from owner_resource_contract import IMAGE_FLOAT_PROFILE
        require(qualified_profile==IMAGE_FLOAT_PROFILE, 'P12 texture coexist requires the complete float owner')
    if args.case in (*COUNTER_CASES,*IMAGE_CASES):
        from owner_resource_contract import SAMPLE_FACTORY_CONTRACTS
        require(type(qualified_profile) is str and qualified_profile in SAMPLE_FACTORY_CONTRACTS,
                'source requires a known complete final112 executor')
        from compute_image_cases import IMAGE_FLOAT_EXCHANGE_CASES
        if args.case in IMAGE_FLOAT_EXCHANGE_CASES or args.contended_image_matrix or args.image_accepted_matrix or args.case in MIXED_ATOMIC_CASES or (
                args.case in CONTENDED_IMAGE_CASES and '-r32f-' in args.case):
            from owner_resource_contract import IMAGE_FLOAT_PROFILE
            require(qualified_profile==IMAGE_FLOAT_PROFILE,'R32F exchange requires its qualified float owner')
    checker = args.executor_checker.resolve(strict=True)
    checker_python = args.executor_checker_python.resolve(strict=True)
    def qualified(env, log):
        subprocess.run([checker_python, checker, '--stage', 'check', '--kind', 'compute',
                        '--qualification', qualification, '--executor', executor],
                       env=env, stdout=log, stderr=subprocess.STDOUT,
                       timeout=args.timeout, check=True)
    gpu = next(p for p in (ROOT, *ROOT.parents) if (p / '.git').is_dir())
    parent = gpu / '.artifacts'; parent.mkdir(exist_ok=True)
    if args.managed_session_directory is not None:
        managed = args.managed_session_directory.absolute()
        require(managed.parent.resolve(strict=True).is_relative_to(parent.resolve(strict=True)) and
                managed.parent.resolve(strict=True) == managed.parent and not managed.exists() and not managed.is_symlink(),
                'managed session must be a new original path below GPU/.artifacts')
        managed.mkdir()
        session = nullcontext(str(managed))
    else:
        session = tempfile.TemporaryDirectory(prefix='c', dir=parent)
    with session as temporary:
        directory = Path(temporary)
        env = dict(os.environ, TMPDIR=temporary, OPENGPU_COMPUTE_HOST_TIMEOUT=str(args.timeout))
        bridge = running = owner_lock = None
        try:
            with (directory / 'probe.log').open('w') as log:
                qualified(env, log)
                owner_lock = lock_owner(qualification)
                # The first read-only check locates the owner. Recheck after
                # taking the exact shared product/build lock, before any probe.
                qualified(env, log)
                subprocess.run([executor, '--probe-profile', directory / 'profile.json'], env=env,
                    stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout, check=True)
            atomic(directory / 'profile.bin', profile_bytes(strict_json(directory / 'profile.json')))
            if args.uc_cache_alias_matrix:
                from compute_global_recovery_controller import run_recovery_matrix
                import compute_uc_cache_alias_recovery as protocol
                run_recovery_matrix(args,command,directory,executor,env,qualified,qualified_profile,protocol=protocol)
                return
            if args.cache_alias_matrix:
                from compute_global_recovery_controller import run_recovery_matrix
                import compute_cache_alias_recovery as protocol
                run_recovery_matrix(args,command,directory,executor,env,qualified,qualified_profile,protocol=protocol)
                return
            if args.global_recovery_matrix or args.global_accepted_matrix:
                from compute_global_recovery_controller import run_recovery_matrix
                if args.global_accepted_matrix:
                    import compute_global_accepted_recovery as protocol
                    run_recovery_matrix(args,command,directory,executor,env,qualified,qualified_profile,protocol=protocol)
                else:
                    run_recovery_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.storage_accepted_matrix:
                from compute_global_recovery_controller import run_recovery_matrix
                import compute_storage_accepted_recovery as protocol
                run_recovery_matrix(args,command,directory,executor,env,qualified,qualified_profile,protocol=protocol)
                return
            if args.image_accepted_matrix:
                from compute_global_recovery_controller import run_recovery_matrix
                import compute_image_accepted_recovery as protocol
                run_recovery_matrix(args,command,directory,executor,env,qualified,qualified_profile,protocol=protocol)
                return
            if args.storage_publication_matrix:
                from compute_storage_publication_controller import run_publication_matrix
                run_publication_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.image_publication_matrix:
                from compute_image_publication_controller import run_publication_matrix
                run_publication_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.texture_publication_matrix:
                from compute_texture_publication_controller import run_publication_matrix
                run_publication_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.counter_publication_matrix:
                from compute_counter_publication_controller import run_publication_matrix
                run_publication_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.shared_publication_matrix:
                from compute_shared_publication_controller import run_publication_matrix
                run_publication_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.dma_publication_matrix:
                from compute_dma_publication_controller import run_publication_matrix
                run_publication_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.l1_publication_matrix:
                from compute_l1_publication_controller import run_publication_matrix
                run_publication_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.storage_recovery_matrix:
                from compute_storage_recovery_controller import run_recovery_matrix
                run_recovery_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.reservation_matrix or args.storage_matrix or args.contended_image_matrix or args.mixed_atomic_matrix or args.global_atomic_matrix:
                from compute_storage_atomic_controller import run_matrix
                run_matrix(args,command,directory,executor,env,qualified,qualified_profile)
                return
            if args.image_source_recovery or args.image_permission_recovery or args.image_reset_recovery:
                exchange_recovery(args,command,directory,executor,env,qualified,qualified_profile)
                print('actual compute '+('delayed '+args.image_reset_recovery+' response reset' if args.image_reset_recovery else
                      'image permission fault' if args.image_permission_recovery else 'source fault')+
                      ' then healthy same-ID recovery, retained RAM and context cleanup: PASS')
                return
            with (directory / 'bridge.log').open('w') as log, (directory / 'execution.log').open('w') as output:
                bridge = subprocess.Popen([x.replace('{ipc}', temporary) for x in command], env=env,
                    stdout=log, stderr=subprocess.STDOUT)
                wait_file(directory / 'READY', bridge, None, args.timeout)
                for name in ('union.memory', 'record-0.capture', 'oracle.json'):
                    wait_file(directory / name, bridge, None, args.timeout)
                description,inputs=ready_inputs(directory,args.case,owner_profile=qualified_profile)
                if args.expect_image_source_fault:
                    from compute_image_source import source_read_fault_plan
                    with SparseMemory(directory/'union.memory') as before:
                        atomic(directory/'record-0.fault-plan',source_read_fault_plan(
                            before,description,owner_profile=qualified_profile))
                    inputs['record-0.fault-plan']=input_digest(directory/'record-0.fault-plan')
                results = directory / 'results'
                execution_mode=('--active-storage-view-fault' if storage_view_fault else
                                '--active-image-source-fault' if args.expect_image_source_fault else '--active-owner')
                running = subprocess.Popen([executor, execution_mode, directory / 'union.memory', results, directory],
                    env=env, stdout=output, stderr=subprocess.STDOUT)
                wait_file(directory / 'record-0.held.json', bridge, running, args.timeout)
                held = strict_json(directory / 'record-0.held.json')
                if args.expect_image_source_fault:
                    from compute_image_source import source_read_fault_proof
                    source_read_fault_proof(held)
                atomic(directory / 'record-0.held.response', (failed_held_bytes if args.expect_image_source_fault or storage_view_fault else held_bytes)(held))
                wait_file(directory / 'record-0.retired.json', bridge, running, args.timeout)
                returned = strict_json(directory / 'record-0.retired.json')
                response = (failed_retired_bytes if args.expect_image_source_fault or storage_view_fault else retired_bytes)(returned, held)
                if args.expect_image_source_fault:
                    source_read_fault_proof(returned,held)
                unchanged_inputs(directory,inputs)
                with SparseMemory(directory / 'union.memory') as before, SparseMemory(results / 'record-0.memory') as after:
                    if args.expect_image_source_fault:
                        from compute_image_source import verify_source_read_fault
                        verify_source_read_fault(before,after,description)
                    else:verify(before, after, description)
                atomic(directory / 'record-0.retired.response', response)
                wait_file(directory / 'cleanup_wait', bridge, running, args.timeout)
                require(bridge.poll() is None and running.poll() is None, 'owner did not remain live through all oracles')
                unchanged_inputs(directory,inputs)
                atomic(directory / 'BEGIN_CLEANUP', cleanup_authorization())
                wait_file(directory / 'cleanup.json', bridge, running, args.timeout)
                checked_cleanup(strict_json(directory / 'cleanup.json'))
                require(running.wait(timeout=args.timeout) == 0, 'DUT executor rejected final context cleanup')
                require(bridge.poll() is None, 'producer freed resources before successful DUT/context receipt')
                qualified(env, output)
                unchanged_inputs(directory,inputs)
                atomic(directory / 'FINAL_RESPONSE', b'OGCMOK01\x01\x00\x00\x00')
                require(bridge.wait(timeout=args.timeout) == 0, 'producer rejected observed final cleanup')
            print('actual compute '+('source-read fault, unchanged physical bytes' if args.expect_image_source_fault else 'returned bytes, physical guards')+
                  ' and observed context cleanup: PASS')
        except BaseException:
            if bridge is not None and bridge.poll() is None:
                try:
                    atomic(directory / 'error', b'controller failed; cleanup not authorized\n')
                except (OSError, ValueError):
                    pass
            for name in ('probe.log', 'bridge.log', 'execution.log'):
                path = directory / name
                if path.exists():
                    print(path.read_text(errors='replace')[-10000:], end='')
            raise
        finally:
            stop(running)
            stop(bridge)
            if owner_lock is not None:
                owner_lock.close()


if __name__ == '__main__':
    main()
