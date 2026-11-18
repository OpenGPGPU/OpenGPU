#!/usr/bin/env python3
"""Keep the actual Mesa/Linux producer live across ten phases on one DUT/RAM."""
import argparse
from contextlib import ExitStack
import fcntl
import os
from pathlib import Path
import signal
import struct
import subprocess
from compute_owner_protocol import (strict_json, profile_bytes, held_bytes, retired_bytes,
                                    checked_cleanup, uint)
import compute_indirect_protocol as indirect
from compute_indirect_phase_protocol import (KIND, PHASES, execution,
    cleanup_authorization, phase_response, execution_result)
from compute_indirect_oracle import Chain, RetainedMemory, Originals
from compute_owner_oracle import SparseMemory
from compute_owner_controller import atomic, wait_file, stop, input_digest, unchanged_inputs, require
from owner_resource_contract import IMAGE_FLOAT_PROFILE


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('executor', 'executor-qualification', 'executor-checker',
                 'executor-checker-python', 'bridge-command', 'managed-session-directory'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--timeout', type=int, default=3600)
    args = p.parse_args()
    require(0 < args.timeout <= 604800, 'invalid continuous timeout')
    command = strict_json(args.bridge_command)
    require(type(command) is list and command and all(type(x) is str and x for x in command) and
            any('{ipc}' in x for x in command), 'original bridge argv must consume IPC')
    qualification = args.executor_qualification.absolute()
    executor = args.executor.absolute()
    require(executor.is_file() and not executor.is_symlink() and qualification.is_file() and
            not qualification.is_symlink(), 'original executor/qualification required')
    record = strict_json(qualification)
    require(record.get('schema') == 'opengpu-owner-executor-v1' and record.get('kind') == KIND and
            record.get('profile') == IMAGE_FLOAT_PROFILE, 'distinct full-factory indirect executor required')
    owner = Path(record['owner_output']).resolve(strict=True)
    require(qualification.resolve(strict=True) == owner / ('executors/' + KIND + '/qualification.json'),
            'qualification does not name its actual owner')
    directory = args.managed_session_directory.absolute()
    gpu = next(parent for parent in (directory.parent, *directory.parents) if (parent / '.git').is_dir())
    require(directory.parent.resolve(strict=True) == directory.parent and
            directory.parent.is_relative_to(gpu / '.artifacts') and
            not directory.exists() and not directory.is_symlink(), 'new managed GPU-internal IPC required')
    directory.mkdir()
    env = dict(os.environ, TMPDIR=str(directory), OPENGPU_COMPUTE_HOST_TIMEOUT=str(args.timeout))
    def qualified(log):
        subprocess.run([args.executor_checker_python, args.executor_checker,
            '--stage', 'check', '--kind', KIND, '--qualification', qualification, '--executor', executor],
            env=env, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout, check=True)
    def terminate(_signal, _frame):
        raise KeyboardInterrupt('continuous qualification terminated')
    previous_handler = signal.signal(signal.SIGTERM, terminate)
    bridge = running = None
    lock = None
    try:
        with (directory / 'probe.log').open('w') as log:
            qualified(log)
            require(not (owner / '.lock').is_symlink(), 'aliased owner lock')
            lock = (owner / '.lock').open('a')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            qualified(log)
            subprocess.run([executor, '--probe-profile', directory / 'profile.json'], env=env,
                stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout, check=True)
        profile = strict_json(directory / 'profile.json')
        require(profile.get('ownerProfile') == IMAGE_FLOAT_PROFILE, 'probe returned another factory')
        atomic(directory / 'profile.bin', profile_bytes(profile))
        chain = Chain(profile)
        input_sets = []
        previous_memory = None
        faults = 0
        with ExitStack() as stack:
            log = stack.enter_context((directory / 'bridge.log').open('w'))
            output = stack.enter_context((directory / 'execution.log').open('w'))
            bridge = subprocess.Popen([x.replace('{ipc}', str(directory)) for x in command],
                                      env=env, stdout=log, stderr=subprocess.STDOUT)
            for index in range(PHASES):
                local = directory / ('phase-' + str(index))
                wait_file(local / 'READY', bridge, running, args.timeout)
                names = ('union.memory', 'record-0.capture', 'oracle.json', 'originals.bytes', 'code.words', 'mode')
                for name in names:
                    wait_file(local / name, bridge, running, args.timeout)
                inputs = {name: input_digest(local / name) for name in names}
                input_sets.append((local, inputs))
                upload = stack.enter_context(SparseMemory(local / 'union.memory'))
                baseline = RetainedMemory(previous_memory, upload, chain.retained) if index else upload
                chain.ready(local, baseline)
                require((local / 'mode').read_bytes() == b'OGCIM001' + struct.pack('<5I',
                    index, chain.opcode, 13 if index == 2 else 0, chain.context, index == PHASES-1),
                    'source mode differs from original recipe')
                if index == 2:
                    atomic(local / 'record-0.fault-plan', chain.fault_plan())
                    inputs['record-0.fault-plan'] = input_digest(local / 'record-0.fault-plan')
                atomic(local / 'BEGIN_EXECUTION', execution(index))
                if not index:
                    running = subprocess.Popen([executor, '--active-indirect', directory / 'results', directory],
                        env=env, stdout=output, stderr=subprocess.STDOUT)
                wait_file(local / 'record-0.held.json', bridge, running, args.timeout)
                held = strict_json(local / 'record-0.held.json')
                require(held.get('commandId') == chain.command, 'held identity differs from original capture')
                if chain.opcode == 14:
                    require(held.get('resultStatus') == (13 if index == 2 else 0), 'wrong actual indirect result')
                    actual = uint(held.get('physicalReadFaults'), 64)
                    require(actual == uint(held.get('injectedReadFaults'), 64) and
                            uint(held.get('physicalWriteFaults'), 64) == 0 and
                            (actual > faults if index == 2 else actual == faults),
                            'missing injected read failure or unexpected physical fault')
                    faults = actual
                atomic(local / 'record-0.held.response', (indirect.held_bytes if chain.opcode == 14 else held_bytes)(held))
                wait_file(local / 'record-0.retired.json', bridge, running, args.timeout)
                returned = strict_json(local / 'record-0.retired.json')
                response = (indirect.retired_bytes if chain.opcode == 14 else retired_bytes)(returned, held)
                if chain.opcode == 14:
                    for name in ('physicalReadFaults', 'injectedReadFaults', 'physicalWriteFaults'):
                        require(returned.get(name) == held.get(name), 'retirement changed physical fault observations')
                result = directory / 'results' / ('phase-' + str(index))
                after = stack.enter_context(SparseMemory(result / 'record-0.memory'))
                actual_originals = (result / 'record-0.originals').read_bytes()
                chain.returned(baseline, after, actual_originals)
                for source, frozen in input_sets:
                    unchanged_inputs(source, frozen)
                atomic(local / 'record-0.retired.response', response)
                wait_file(local / 'cleanup_wait', bridge, running, args.timeout)
                require(bridge.poll() is None and running.poll() is None, 'owner exited before independent byte checks')
                # This is a verbatim transfer of actual, independently checked DUT
                # bytes. Expected ESSL values are never supplied to the producer.
                atomic(local / 'returned.bytes', actual_originals)
                atomic(local / 'BEGIN_CLEANUP', cleanup_authorization(index))
                wait_file(local / 'cleanup.json', bridge, running, args.timeout)
                cleanup = strict_json(local / 'cleanup.json')
                if index + 1 == PHASES:
                    checked_cleanup(cleanup)
                    require(running.wait(timeout=args.timeout) == 0, 'DUT rejected full final cleanup')
                    execution_result(strict_json(directory / 'results/execution.json'), faults)
                    qualified(output)
                    for source, frozen in input_sets:
                        unchanged_inputs(source, frozen)
                    require(bridge.poll() is None, 'producer exited before final cleanup acknowledgment')
                    atomic(local / 'FINAL_RESPONSE', b'OGCMOK01' + struct.pack('<I', 1))
                    require(bridge.wait(timeout=args.timeout) == 0, 'original producer rejected final cleanup')
                else:
                    ack = phase_response(cleanup, command=chain.command, context=chain.context, index=index)
                    cleaned = stack.enter_context(SparseMemory(result / 'cleanup.memory'))
                    Originals(actual_originals).verify(cleaned, index, chain.opcode, chain.command)
                    chain.finish(cleaned)
                    previous_memory = cleaned
                    atomic(local / 'PHASE_RESPONSE', ack)
                print('continuous actual DUT phase %u/10 passed' % (index + 1), flush=True)
        print('original Mesa producer/indirect failure/reuse/zero-X/Y/Z: one DUT, one RAM, full bytes and cleanup PASS')
    except BaseException:
        if bridge is not None and bridge.poll() is None:
            try:
                atomic(directory / 'error', b'continuous controller failed; no further phase authorized\n')
            except (OSError, ValueError):
                pass
        for name in ('probe.log', 'bridge.log', 'execution.log'):
            path = directory / name
            if path.exists():
                print(path.read_text(errors='replace')[-8000:], end='')
        raise
    finally:
        try:
            stop(running)
        finally:
            stop(bridge)
            if lock is not None:
                lock.close()
            signal.signal(signal.SIGTERM, previous_handler)


if __name__ == '__main__':
    main()
