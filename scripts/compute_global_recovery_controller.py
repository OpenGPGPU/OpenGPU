"""One original compiler/producer and one complete DUT for all atomic recovery."""
import struct
import subprocess
import sys
import time
from compute_owner_protocol import (strict_json, held_bytes, retired_bytes,
    failed_held_bytes, failed_retired_bytes, checked_cleanup, cleanup_authorization)
from compute_owner_oracle import SparseMemory, verify, union_original_memory
from compute_image_source import captured_page_tables, verify_cleaned_memory
from compute_global_recovery import (PHASES, STEP, matrix_plan, fault_plan,
    fault_receipt, check_round, verify_fault)

PLAN_NAME='global-recovery.plan'
RUNNER_FLAG='--active-global-recovery-matrix'
LABEL='global recovery'
INACTIVE_CONTEXTS=0


def run_recovery_matrix(args, command, directory, executor, env, qualified, profile_name, *, protocol=None):
    from compute_owner_controller import (atomic, wait_file, stop, ready_inputs,
        unchanged_inputs, input_digest, require)
    deadline=time.monotonic()+args.timeout
    contract=sys.modules[__name__] if protocol is None else protocol
    def remaining():
        left=deadline-time.monotonic()
        require(left>0,'bounded original global recovery matrix timed out')
        return left
    profile=strict_json(directory/'profile.json')
    atomic(directory/contract.PLAN_NAME,contract.matrix_plan())
    inputs=[(directory,{name:input_digest(directory/name) for name in
        (contract.PLAN_NAME,'profile.json','profile.bin')})]
    bridge=running=None;local=None;stage=None
    previous=previous_clean=previous_digest=None
    old_words=0
    try:
        with (directory/'bridge.log').open('w') as log,(directory/'execution.log').open('w') as output:
            bridge=subprocess.Popen([x.replace('{ipc}',str(directory)) for x in command],
                env=env,stdout=log,stderr=subprocess.STDOUT)
            for stage in contract.PHASES:
                index,mode=stage['index'],stage['mode']
                local=directory/('round-'+str(index))
                for name in ('READY','union.memory','record-0.capture','oracle.json','code.words'):
                    wait_file(local/name,bridge,running,remaining())
                cache_alias=getattr(contract,'CACHE_ALIAS',False)
                description,frozen=ready_inputs(local,stage['case'],owner_profile=profile_name,profile=profile,
                    **(dict(uc_cache_alias=True) if getattr(contract,'UC_CACHE_ALIAS',False) else
                       dict(cache_alias=True) if cache_alias else {}))
                with SparseMemory(local/'union.memory') as upload:
                    require(upload.ranges and upload.ranges[0][0]==stage['base'] and
                        all(stage['base']<=a<b<=stage['base']+contract.STEP for a,b,_ in upload.ranges),
                        'original global allocator namespace differs or overlaps the next phase')
                    tables=captured_page_tables(upload,(local/'record-0.capture').read_bytes(),
                        inactive_contexts=contract.INACTIVE_CONTEXTS)
                    if index:
                        require(input_digest(previous_clean)==previous_digest,'previous cleaned RAM changed')
                        with SparseMemory(previous_clean) as old:
                            atomic(local/'global-before.memory',union_original_memory(old,upload))
                        frozen['global-before.memory']=input_digest(local/'global-before.memory')
                        baseline=local/'global-before.memory'
                    else:
                        baseline=local/'union.memory'
                if cache_alias:
                    atomic(local/'record-0.cache-target',contract.target_plan(description,index))
                    frozen['record-0.cache-target']=input_digest(local/'record-0.cache-target')
                if mode!='healthy':
                    atomic(local/'record-0.fault-plan',contract.fault_plan(description,mode))
                    frozen['record-0.fault-plan']=input_digest(local/'record-0.fault-plan')
                inputs.append((local,frozen))
                for path,identities in inputs:
                    unchanged_inputs(path,identities)
                atomic(local/'BEGIN_EXECUTION',struct.pack('<8sI',b'OGCMEX01',index))
                if not index:
                    running=subprocess.Popen([executor,contract.RUNNER_FLAG,directory/'results',directory],
                        env=env,stdout=output,stderr=subprocess.STDOUT)
                destination=directory/'results'/('round-'+str(index))
                wait_file(local/'record-0.held.json',bridge,running,remaining())
                held=strict_json(local/'record-0.held.json')
                if mode!='healthy':
                    contract.fault_receipt(held,index,previous)
                atomic(local/'record-0.held.response',(held_bytes if mode=='healthy' else failed_held_bytes)(held))
                wait_file(local/'record-0.retired.json',bridge,running,remaining())
                retired=strict_json(local/'record-0.retired.json')
                response=(retired_bytes if mode=='healthy' else failed_retired_bytes)(retired,held)
                if mode!='healthy':
                    require(contract.fault_receipt(retired,index,previous)==contract.fault_receipt(held,index,previous),
                            'physical error history changed during original retirement')
                    require(retired['faultTargetWriteAttempts']==held['faultTargetWriteAttempts'],
                            'target write attempts changed during original retirement')
                for path,identities in inputs:
                    unchanged_inputs(path,identities)
                with SparseMemory(baseline) as before,SparseMemory(destination/'record-0.memory') as after:
                    if mode=='healthy':
                        verify(before,after,description)
                        old_words+=(contract.healthy_old_words(description)
                            if hasattr(contract,'healthy_old_words') else 8)
                    else:
                        old_words+=contract.verify_fault(before,after,description,mode,
                            target_attempts=retired['faultTargetWriteAttempts'])
                if cache_alias:
                    wait_file(local/'record-0.cache-observation.json',bridge,running,remaining())
                    observation=contract.check_cache(strict_json(local/'record-0.cache-observation.json'),
                        index,description,held,previous)
                    frozen['record-0.cache-observation.json']=input_digest(local/'record-0.cache-observation.json')
                atomic(local/'record-0.retired.response',response)
                wait_file(local/'cleanup_wait',bridge,running,remaining())
                require(bridge.poll() is None and running.poll() is None,
                        'original global owner exited before independent cleanup authorization')
                atomic(local/'BEGIN_CLEANUP',cleanup_authorization())
                wait_file(local/'cleanup.json',bridge,running,remaining())
                checked_cleanup(strict_json(local/'cleanup.json'))
                wait_file(local/'executor.done',bridge,running,remaining())
                done=contract.check_round(strict_json(local/'executor.done'),index,held,previous)
                if cache_alias:contract.check_cache(observation,index,description,held,previous,done)
                cleaned=destination/'record-0.cleaned.memory'
                with SparseMemory(destination/'record-0.memory') as before,SparseMemory(cleaned) as after:
                    verify_cleaned_memory(before,after,tables)
                if index+1==len(contract.PHASES):
                    require(running.wait(timeout=remaining())==0,'continuous global DUT rejected final cleanup')
                else:
                    require(running.poll() is None,'global DUT exited between original source phases')
                previous,previous_clean,previous_digest=done,cleaned,input_digest(cleaned)
                for path,identities in inputs:
                    unchanged_inputs(path,identities)
                atomic(local/'FINAL_RESPONSE',b'OGCMOK01\x01\x00\x00\x00')
                print('original %s phase=%d source=%s mode=%s full bytes/cleanup PASS'%(
                    contract.LABEL,index,stage['case'],mode),flush=True)
            require(bridge.wait(timeout=remaining())==0,'original RV32 recovery producer failed')
            qualified(env,output)
            for path,identities in inputs:
                unchanged_inputs(path,identities)
        print('all %d original %s phases PASS; one DUT/RAM/reset, %d old words, '
              'readFaults=%d writeFaults=%d, original source/model postchecks'%(
                   len(contract.PHASES),contract.LABEL,old_words,
                   previous['physicalReadFaults'],previous['physicalWriteFaults']),flush=True)
    except BaseException as error:
        if local is not None:
            try:
                atomic(local/'error',b'global recovery failed; no further cleanup authorization\n')
            except (ValueError,OSError):
                pass
        print('%s failed at %r: %s'%(contract.LABEL,stage,error),flush=True)
        for name in ('execution.log','bridge.log'):
            path=directory/name
            if path.is_file():
                print(path.read_text(errors='replace')[-5000:],flush=True)
        raise
    finally:
        try:
            stop(running)
        finally:
            stop(bridge)
