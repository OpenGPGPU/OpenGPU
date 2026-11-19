"""One guarded compiler build, sequential original full-DUT atomic owners.

Storage, same-texel image and mixed-alias matrices retain separate original sources.
Each independent operation case resets its execution DUT. The optional ACK
mode gates actual target writes; continuous fault recovery is a separate gate.
"""
import subprocess
import time
from compute_owner_protocol import (strict_json, held_bytes, retired_bytes,
    failed_held_bytes, failed_retired_bytes, checked_cleanup, cleanup_authorization)
from compute_owner_oracle import SparseMemory, verify
from compute_storage_atomic_cases import CASES, parameters
from compute_storage_atomic_delay import plan_bytes, check_report


def run_matrix(args, command, directory, executor, env, qualified, profile_name):
    from compute_owner_controller import (atomic, wait_file, stop, ready_inputs,
        unchanged_inputs, require)
    image_matrix=getattr(args,'contended_image_matrix',False)
    mixed_matrix=getattr(args,'mixed_atomic_matrix',False)
    global_matrix=getattr(args,'global_atomic_matrix',False)
    global_delayed=getattr(args,'global_delayed_ack',False)
    global_cached=getattr(args,'global_cached_matrix',False)
    reservation=getattr(args,'reservation_matrix',False)
    if reservation:
        from compute_reservation_wait import mode
        mode(args,args.case)
    require(not global_cached or (global_matrix and not global_delayed),
        'cached global matrix requires its distinct normal execution')
    require(not global_delayed or global_matrix,'global ACK requires its original complete source matrix')
    if reservation:
        from compute_reservation_wait import PHASES as selected_cases
    elif global_matrix:
        from compute_global_atomic_cases import CASES as selected_cases
        require(not (image_matrix or mixed_matrix or getattr(args,'storage_matrix',False) or
                     getattr(args,'storage_delayed_ack',False)), 'raw global and other matrices must remain separate')
    elif mixed_matrix:
        from compute_mixed_atomic_cases import CASES as selected_cases
        require(not (image_matrix or getattr(args,'storage_matrix',False) or getattr(args,'storage_delayed_ack',False)),
                'mixed atomic and other source matrices must remain separate')
    elif image_matrix:
        from compute_contended_image_cases import CASES as selected_cases
        require(not (getattr(args,'storage_matrix',False) or getattr(args,'storage_delayed_ack',False)),
                'contended image and storage matrices must remain separate')
    else:selected_cases=CASES
    family='external producer reservation' if reservation else 'cached global RV32 atomic' if global_cached else 'global RV32 atomic' if global_matrix else 'mixed image/storage atomic' if mixed_matrix else 'contended image' if image_matrix else 'storage atomic'
    profile = strict_json(directory / 'profile.json')
    deadline = time.monotonic() + args.timeout
    def remaining():
        value = deadline - time.monotonic()
        require(value > 0, 'bounded '+family+' matrix timed out')
        return value
    bridge = running = None
    local = None
    inputs = []
    delay_inputs = []
    try:
        with (directory / 'bridge.log').open('w') as log, (directory / 'execution.log').open('w') as output:
            bridge = subprocess.Popen([x.replace('{ipc}', str(directory)) for x in command],
                env=env, stdout=log, stderr=subprocess.STDOUT)
            for selected in selected_cases:
                case=selected['case'] if reservation else selected
                failed=False if reservation or global_matrix or image_matrix or mixed_matrix else bool(parameters(case)['result_status'])
                local = directory / ('round-'+str(selected['index']) if reservation else case)
                prior={}
                if reservation:
                    from compute_reservation_wait import begin_owner
                    running,prior=begin_owner(args,local,selected['index'],executor,output,bridge,env,remaining)
                else:wait_file(local / 'READY', bridge, None, remaining())
                for name in ('union.memory', 'record-0.capture', 'oracle.json', 'code.words'):
                    wait_file(local / name, bridge, None, remaining())
                description, frozen = ready_inputs(local, case, owner_profile=profile_name, profile=profile,
                    **({'global_cached':selected['cached']} if reservation else {'global_cached':True} if global_cached else {}))
                frozen.update(prior)
                if reservation:
                    from compute_reservation_wait import execution_authorization
                    from compute_owner_controller import input_digest
                    from compute_image_source import captured_page_tables
                    with SparseMemory(local/'union.memory') as before:
                        tables=captured_page_tables(before,(local/'record-0.capture').read_bytes(),inactive_contexts=0)
                    unchanged_inputs(local,frozen)
                    atomic(local/'BEGIN_EXECUTION',execution_authorization(selected['index']))
                    frozen['BEGIN_EXECUTION']=input_digest(local/'BEGIN_EXECUTION')
                inputs.append((local, frozen))
                delayed = (getattr(args, 'storage_delayed_ack', False) or global_delayed) and not failed
                plan = plan_bytes(description) if delayed else None
                if delayed:
                    atomic(local / 'record-0.ack-delay-plan', plan)
                    delay_inputs.append((local, plan))
                result = local / 'results'
                mode = ('--active-storage-view-fault' if failed else
                        '--active-global-delayed-ack' if global_delayed else
                        '--active-storage-delayed-ack' if delayed else '--active-owner')
                if not reservation:
                    running = subprocess.Popen([executor, mode,
                        local / 'union.memory', result, local],
                        env=env, stdout=output, stderr=subprocess.STDOUT)
                wait_file(local / 'record-0.held.json', bridge, running, remaining())
                held = strict_json(local / 'record-0.held.json')
                if delayed:
                    wait_file(local / 'record-0.ack-delay.json', bridge, running, remaining())
                    check_report(strict_json(local / 'record-0.ack-delay.json'), description, held['commandId'])
                    require((local / 'record-0.ack-delay-plan').read_bytes() == plan,
                            'original storage ACK plan changed')
                if failed:
                    require(all(type(held.get(key)) is int and held[key]==0 for key in
                        ('physicalReadFaults','injectedReadFaults','physicalWriteFaults')),
                        'storage view fault must not encounter a physical bus error')
                atomic(local / 'record-0.held.response', (failed_held_bytes if failed else held_bytes)(held))
                wait_file(local / 'record-0.retired.json', bridge, running, remaining())
                returned = strict_json(local / 'record-0.retired.json')
                response = (failed_retired_bytes if failed else retired_bytes)(returned, held)
                unchanged_inputs(local, frozen)
                if delayed:
                    require((local / 'record-0.ack-delay-plan').read_bytes() == plan,
                            'original storage ACK plan changed after execution')
                with SparseMemory(local / 'union.memory') as before, SparseMemory(result / 'record-0.memory') as after:
                    verify(before, after, description)
                atomic(local / 'record-0.retired.response', response)
                wait_file(local / 'cleanup_wait', bridge, running, remaining())
                require(bridge.poll() is None and running.poll() is None,
                        'storage atomic owner exited before independent byte checks')
                atomic(local / 'BEGIN_CLEANUP', cleanup_authorization())
                wait_file(local / 'cleanup.json', bridge, running, remaining())
                checked_cleanup(strict_json(local / 'cleanup.json'))
                require(running.wait(timeout=remaining()) == 0, 'DUT rejected atomic owner cleanup')
                if reservation:
                    from compute_reservation_wait import checked_done
                    from compute_image_source import verify_cleaned_memory
                    checked_done(strict_json(local/'reservation.done.json'),strict_json(local/'reservation.idle.json'),selected['index'])
                    with SparseMemory(result/'record-0.memory') as before,SparseMemory(result/'record-0.cleaned.memory') as after:
                        verify_cleaned_memory(before,after,tables)
                    frozen['reservation.done.json']=input_digest(local/'reservation.done.json')
                require(bridge.poll() is None, 'original compiler/producer exited before cleanup ACK')
                unchanged_inputs(local, frozen)
                atomic(local / 'FINAL_RESPONSE', b'OGCMOK01\x01\x00\x00\x00')
                print('original '+family+' '+('phase='+str(selected['index'])+' cached='+str(selected['cached'])+' ' if reservation else '') + case + ': full DUT bytes/old values/cleanup PASS' +
                      ('; actual target B ACK held >=257 cycles; old outputs untouched before release' if global_delayed else
                       '; actual target B ACK held >=37 cycles' if delayed else ''), flush=True)
                running = None
            require(bridge.wait(timeout=remaining()) == 0, 'original source/compiler matrix failed')
            qualified(env, output)
            for local, frozen in inputs:
                unchanged_inputs(local, frozen)
            for local, plan in delay_inputs:
                require((local / 'record-0.ack-delay-plan').read_bytes() == plan,
                        'storage ACK plan changed before final source checks')
        print('all forty-eight external CPU/original-GEM producer waits: real blocked entry, native idle/release, original old values/full physical guards/cleanup PASS; per-case reset'
              if reservation else 'all twenty-four original global RV32 atomic cases: scalar-caller ordered old values and full physical guards PASS; per-case reset'
              if global_matrix else 'all sixty-six original same-GEM image/storage atomic cases: invocation-ordered old values, exact alias views and full physical guards PASS; per-case reset'
              if mixed_matrix else 'all fifty-five original same-texel image cases: invocation-ordered old values and full physical guards PASS; per-case reset'
              if image_matrix else 'all seventeen original SSBO atomic cases: exact old values, view faults and full physical guards PASS; per-case reset')
    except BaseException as error:
        print('original '+family+' matrix failed at '+(local.name if local is not None else 'startup')+
              ': '+str(error),flush=True)
        logs=[directory/'execution.log',directory/'bridge.log']
        if local is not None:logs.append(directory/'build'/('actual-compute-capture-'+local.name+'.log'))
        for path in logs:
            try:
                tail='\n'.join(path.read_text(errors='replace').splitlines()[-16:])[-4000:]
                if tail:print(path.name+' tail:\n'+tail,flush=True)
            except OSError:pass
        if local is not None:
            try:
                atomic(local / 'error', (family+' matrix failed; no cleanup authorization\n').encode())
            except (ValueError, OSError):
                pass
        raise
    finally:
        try:
            stop(running)
        finally:
            stop(bridge)
