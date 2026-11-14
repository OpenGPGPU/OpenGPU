#!/usr/bin/env python3
"""Keep the actual Linux sample owner live through DUT/oracle/ACK cleanup.

Low-level packet execution only. The caller must separately qualify original
ESSL compilation; a filename or CPU peer cannot establish that provenance.
"""
import argparse
import fcntl
import hashlib
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time

from native_sample_owner_protocol import (strict_json, profile_bytes, held_bytes,
    retired_bytes, cleanup_authorization, final_authorization, checked_cleanup)
from native_sample_owner_oracle import SparseMemory, verify, program, capture
from owner_process_lifetime import CleanupIncomplete, stop_group, remove_container
from owner_resource_contract import SAMPLE_PROFILE

ROOT = Path(__file__).resolve().parents[1]


def require(value, message):
    if not value:
        raise ValueError(message)


def atomic(path, data):
    require(not path.exists() and not path.is_symlink(), 'pre-existing sample response')
    temporary = path.with_suffix(path.suffix + '.tmp')
    with temporary.open('xb') as stream:
        stream.write(data)
    os.link(temporary, path)
    temporary.unlink()


def regular(path):
    require(path.is_file() and not path.is_symlink(), 'missing/aliased input: ' + str(path))
    return path


def wait_file(path, bridge, executor, timeout):
    deadline = time.monotonic() + timeout
    while not path.exists():
        require(not (path.parent / 'error').exists(), 'producer reported active sample failure')
        require(bridge.poll() is None, 'producer exited with a live sample owner')
        require(executor is None or executor.poll() is None, 'executor exited before ' + path.name)
        require(time.monotonic() < deadline, 'timeout waiting for ' + path.name)
        time.sleep(.05)
    regular(path)
    require(bridge.poll() is None, 'producer exited before receipt ' + path.name)


def lock_owner(qualification):
    record = strict_json(qualification)
    require(record.get('schema') == 'opengpu-owner-executor-v1' and
            record.get('kind') == 'native-sample' and record.get('profile') == SAMPLE_PROFILE and
            record.get('source_root') == str(ROOT), 'wrong sample executor qualification/source')
    owner = Path(record['owner_output']).resolve(strict=True)
    target = owner / 'executors/native-sample'
    require(qualification.resolve(strict=True) == target / 'qualification.json' and
            target.resolve(strict=True) == target, 'qualification does not name exact prepared sample owner')
    locks = []
    try:
        for path in (owner / '.lock', target / '.lock'):
            require(not path.is_symlink(), 'sample owner lock must not be aliased')
            lock = path.open('a'); locks.append(lock)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return locks
    except BaseException:
        for lock in locks:
            lock.close()
        raise


def digest(path):
    return hashlib.sha256(regular(path).read_bytes()).digest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor', type=Path, required=True)
    parser.add_argument('--executor-qualification', type=Path, required=True)
    parser.add_argument('--executor-checker', type=Path, required=True)
    parser.add_argument('--executor-checker-python', type=Path, required=True)
    parser.add_argument('--bridge-command', type=Path, required=True, help='argv JSON, {ipc} placeholder, no shell')
    parser.add_argument('--bridge-container-name', help='exact owned Docker --name; exit is proved before deleting IPC')
    parser.add_argument('--program', required=True, help='original OGNSF file; may contain {ipc} for a live compiler')
    parser.add_argument('--case', choices=('sample', 'mask-depth', 'mask-depth-keep', 'mrt', 'varying', 'signedness', 'truncation'), required=True)
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    require(0 < args.timeout <= 86400, 'timeout must be 1..86400 seconds')
    executor = regular(args.executor.absolute())
    qualification = regular(args.executor_qualification.absolute())
    checker = args.executor_checker.resolve(strict=True)
    require(checker == ROOT / 'scripts/prepare_owner_executor.py', 'sample controller requires its original guarded source checker')
    checker_python = regular(args.executor_checker_python.resolve(strict=True))
    command = strict_json(regular(args.bridge_command))
    require(type(command) is list and command and all(type(x) is str and x for x in command) and
            any('{ipc}' in x for x in command), 'bridge command must consume exact active IPC directory')
    docker = Path(command[0]).name == 'docker'
    require(not docker or (args.bridge_container_name and '--name' in command and
            command[command.index('--name') + 1] == args.bridge_container_name), 'Docker bridge requires its exact owned container name')
    require(not args.bridge_container_name or docker, 'container cleanup requires direct Docker bridge argv')
    gpu = next(p for p in (ROOT, *ROOT.parents) if (p / '.git').is_dir())
    parent = gpu / '.artifacts'; parent.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='ns', dir=parent))
    env = dict(os.environ, TMPDIR=str(directory), OPENGPU_NATIVE_HOST_TIMEOUT=str(args.timeout), PYTHONDONTWRITEBYTECODE='1')
    bridge = running = None
    locks = []
    preserve = False
    def terminate(_signal, _frame):
        raise KeyboardInterrupt('sample qualification terminated')
    previous = signal.signal(signal.SIGTERM, terminate)

    def qualified(log):
        subprocess.run([checker_python, checker, '--stage', 'check', '--kind', 'native-sample',
            '--qualification', qualification, '--executor', executor], env=env,
            stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout, check=True)

    try:
        with (directory / 'probe.log').open('w') as log:
            qualified(log)
            locks = lock_owner(qualification)
            qualified(log)
            subprocess.run([executor, '--probe-profile', directory / 'profile.json'], env=env,
                stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout, check=True)
        observed = strict_json(directory / 'profile.json')
        atomic(directory / 'profile.bin', profile_bytes(observed))
        with (directory / 'bridge.log').open('w') as log, (directory / 'execution.log').open('w') as output:
            bridge = subprocess.Popen([x.replace('{ipc}', str(directory)) for x in command], env=env,
                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            wait_file(directory / 'READY', bridge, None, args.timeout)
            for name in ('union.memory', 'record-0.capture', 'sample-inputs.json'):
                wait_file(directory / name, bridge, None, args.timeout)
            packet = regular(Path(args.program.replace('{ipc}', str(directory))).absolute())
            original = packet.read_bytes()
            program(original, args.case)
            description = strict_json(directory / 'sample-inputs.json')
            require(description.get('caseId') == args.case, 'live driver changed source case identity')
            guarded = [packet, directory / 'record-0.capture', directory / 'sample-inputs.json', directory / 'union.memory']
            identities = [digest(path) for path in guarded]
            capture_data = (directory / 'record-0.capture').read_bytes()
            command_id, _, _ = capture(capture_data, observed)
            with SparseMemory(directory / 'union.memory') as before:
                verify(before, None, description, original, capture_data, observed)
            qualified(output)
            results = directory / 'results'
            running = subprocess.Popen([executor, '--active-owner', directory / 'union.memory', results, directory],
                env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            wait_file(directory / 'record-0.held.json', bridge, running, args.timeout)
            held = strict_json(directory / 'record-0.held.json')
            require(held.get('commandId') == command_id and held.get('gnx', [])[6:9] == [7, 8, 9],
                    'actual held receipt changed original enqueue/context identity')
            atomic(directory / 'record-0.held.response', held_bytes(held))
            wait_file(directory / 'record-0.retired.json', bridge, running, args.timeout)
            retired = strict_json(directory / 'record-0.retired.json')
            response = retired_bytes(retired, held)
            require([digest(path) for path in guarded] == identities, 'original live source inputs changed during DUT execution')
            with SparseMemory(directory / 'union.memory') as before, SparseMemory(regular(results / 'record-0.memory')) as after:
                verify(before, after, description, original, capture_data, observed)
            require(bridge.poll() is None and running.poll() is None, 'owner did not remain live through sample/GNY2/guard oracles')
            atomic(directory / 'record-0.retired.response', response)
            atomic(directory / 'oracle-authorized.response', cleanup_authorization())
            wait_file(directory / 'cleanup.json', bridge, running, args.timeout)
            checked_cleanup(strict_json(directory / 'cleanup.json'))
            require(running.wait(timeout=args.timeout) == 0, 'actual executor rejected sample context cleanup')
            require(bridge.poll() is None, 'producer freed source leases before observed cleanup')
            qualified(output)
            atomic(directory / 'final-authorized.response', final_authorization())
            require(bridge.wait(timeout=args.timeout) == 0, 'producer rejected final observed cleanup')
        print('actual sample packet returned images, full GNY2, physical guards and observed context cleanup: PASS; original ESSL provenance, clock and FPS require separate qualification')
    except BaseException:
        if bridge is not None and bridge.poll() is None:
            try:
                atomic(directory / 'error', b'sample controller failed; no cleanup authorization\n')
            except (OSError, ValueError):
                pass
        for name in ('probe.log', 'bridge.log', 'execution.log'):
            path = directory / name
            if path.exists():
                print(path.read_text(errors='replace')[-10000:], end='')
        raise
    finally:
        try:
            try:
                stop_group(running)
            finally:
                try:
                    stop_group(bridge)
                finally:
                    if args.bridge_container_name:
                        remove_container(args.bridge_container_name)
        except CleanupIncomplete:
            preserve = True
            print('Preserved inputs for a possibly active consumer: ' + str(directory))
            raise
        finally:
            try:
                for lock in locks:
                    lock.close()
                if not preserve:
                    shutil.rmtree(directory)
            finally:
                signal.signal(signal.SIGTERM, previous)


if __name__ == '__main__':
    main()
