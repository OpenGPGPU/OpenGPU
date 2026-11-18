"""One guarded compiler build, sequential original full-DUT atomic owners.

Each independent operation case resets its execution DUT. Continuous fault
recovery and delayed acknowledgments require their separate P10 gates.
"""
import subprocess
import time
from compute_owner_protocol import (strict_json, held_bytes, retired_bytes,
    failed_held_bytes, failed_retired_bytes, checked_cleanup, cleanup_authorization)
from compute_owner_oracle import SparseMemory, verify
from compute_storage_atomic_cases import CASES, parameters


def run_matrix(args, command, directory, executor, env, qualified, profile_name):
    from compute_owner_controller import (atomic, wait_file, stop, ready_inputs,
        unchanged_inputs, require)
    profile = strict_json(directory / 'profile.json')
    deadline = time.monotonic() + args.timeout
    def remaining():
        value = deadline - time.monotonic()
        require(value > 0, 'bounded storage atomic matrix timed out')
        return value
    bridge = running = None
    local = None
    inputs = []
    try:
        with (directory / 'bridge.log').open('w') as log, (directory / 'execution.log').open('w') as output:
            bridge = subprocess.Popen([x.replace('{ipc}', str(directory)) for x in command],
                env=env, stdout=log, stderr=subprocess.STDOUT)
            for case in CASES:
                failed=bool(parameters(case)['result_status'])
                local = directory / case
                wait_file(local / 'READY', bridge, None, remaining())
                for name in ('union.memory', 'record-0.capture', 'oracle.json', 'code.words'):
                    wait_file(local / name, bridge, None, remaining())
                description, frozen = ready_inputs(local, case, owner_profile=profile_name, profile=profile)
                inputs.append((local, frozen))
                result = local / 'results'
                running = subprocess.Popen([executor, '--active-storage-view-fault' if failed else '--active-owner',
                    local / 'union.memory', result, local],
                    env=env, stdout=output, stderr=subprocess.STDOUT)
                wait_file(local / 'record-0.held.json', bridge, running, remaining())
                held = strict_json(local / 'record-0.held.json')
                if failed:
                    require(all(type(held.get(key)) is int and held[key]==0 for key in
                        ('physicalReadFaults','injectedReadFaults','physicalWriteFaults')),
                        'storage view fault must not encounter a physical bus error')
                atomic(local / 'record-0.held.response', (failed_held_bytes if failed else held_bytes)(held))
                wait_file(local / 'record-0.retired.json', bridge, running, remaining())
                returned = strict_json(local / 'record-0.retired.json')
                response = (failed_retired_bytes if failed else retired_bytes)(returned, held)
                unchanged_inputs(local, frozen)
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
                require(bridge.poll() is None, 'original compiler/producer exited before cleanup ACK')
                unchanged_inputs(local, frozen)
                atomic(local / 'FINAL_RESPONSE', b'OGCMOK01\x01\x00\x00\x00')
                print('original storage atomic ' + case + ': full DUT bytes/old values/cleanup PASS', flush=True)
                running = None
            require(bridge.wait(timeout=remaining()) == 0, 'original source/compiler matrix failed')
            qualified(env, output)
            for local, frozen in inputs:
                unchanged_inputs(local, frozen)
        print('all seventeen original SSBO atomic cases: exact old values, view faults and full physical guards PASS; per-case reset')
    except BaseException:
        if local is not None:
            try:
                atomic(local / 'error', b'storage atomic matrix failed; no cleanup authorization\n')
            except (ValueError, OSError):
                pass
        raise
    finally:
        try:
            stop(running)
        finally:
            stop(bridge)
