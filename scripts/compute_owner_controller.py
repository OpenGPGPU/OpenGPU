#!/usr/bin/env python3
"""Keep actual Linux compute mappings alive through DUT execution and cleanup.

The caller supplies the complete-factory/source/tool-qualified executor. The
bridge argv JSON may use {ipc}; no shell is used. A CPU protocol peer is not an
execution qualification. All temporary process data is deleted on exit.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import time
from compute_owner_protocol import (strict_json, profile_bytes, held_bytes,
    retired_bytes, cleanup_authorization, checked_cleanup)
from compute_owner_oracle import SparseMemory, verify

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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor', type=Path, required=True)
    parser.add_argument('--executor-qualification', type=Path, required=True)
    parser.add_argument('--executor-checker', type=Path, required=True,
                        help='original guarded prepare_owner_executor.py; do not copy it into the bridge tree')
    parser.add_argument('--executor-checker-python', type=Path, required=True,
                        help='the generation-matched Python interpreter, with its fixed CIRCT environment')
    parser.add_argument('--bridge-command', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    require(args.timeout > 0, 'positive timeout required')
    command = strict_json(args.bridge_command)
    require(type(command) is list and command and all(type(x) is str and x for x in command),
            'bridge command must be a nonempty argv array')
    require(any('{ipc}' in x for x in command), 'bridge command must consume the active IPC directory')
    executor = args.executor.resolve(strict=True)
    qualification = args.executor_qualification.resolve(strict=True)
    checker = args.executor_checker.resolve(strict=True)
    checker_python = args.executor_checker_python.resolve(strict=True)
    def qualified(env, log):
        subprocess.run([checker_python, checker, '--stage', 'check', '--kind', 'compute',
                        '--qualification', qualification, '--executor', executor],
                       env=env, stdout=log, stderr=subprocess.STDOUT,
                       timeout=args.timeout, check=True)
    gpu = next(p for p in (ROOT, *ROOT.parents) if (p / '.git').is_dir())
    parent = gpu / '.artifacts'; parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='c', dir=parent) as temporary:
        directory = Path(temporary)
        env = dict(os.environ, TMPDIR=temporary)
        bridge = running = None
        try:
            with (directory / 'probe.log').open('w') as log:
                qualified(env, log)
                subprocess.run([executor, '--probe-profile', directory / 'profile.json'], env=env,
                    stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout, check=True)
            atomic(directory / 'profile.bin', profile_bytes(strict_json(directory / 'profile.json')))
            with (directory / 'bridge.log').open('w') as log, (directory / 'execution.log').open('w') as output:
                bridge = subprocess.Popen([x.replace('{ipc}', temporary) for x in command], env=env,
                    stdout=log, stderr=subprocess.STDOUT)
                wait_file(directory / 'READY', bridge, None, args.timeout)
                for name in ('union.memory', 'record-0.capture', 'oracle.json'):
                    wait_file(directory / name, bridge, None, args.timeout)
                results = directory / 'results'
                running = subprocess.Popen([executor, '--active-owner', directory / 'union.memory', results, directory],
                    env=env, stdout=output, stderr=subprocess.STDOUT)
                wait_file(directory / 'record-0.held.json', bridge, running, args.timeout)
                held = strict_json(directory / 'record-0.held.json')
                atomic(directory / 'record-0.held.response', held_bytes(held))
                wait_file(directory / 'record-0.retired.json', bridge, running, args.timeout)
                returned = strict_json(directory / 'record-0.retired.json')
                response = retired_bytes(returned, held)
                with SparseMemory(directory / 'union.memory') as before, SparseMemory(results / 'record-0.memory') as after:
                    verify(before, after, strict_json(directory / 'oracle.json'))
                atomic(directory / 'record-0.retired.response', response)
                wait_file(directory / 'cleanup_wait', bridge, running, args.timeout)
                require(bridge.poll() is None and running.poll() is None, 'owner did not remain live through all oracles')
                atomic(directory / 'BEGIN_CLEANUP', cleanup_authorization())
                wait_file(directory / 'cleanup.json', bridge, running, args.timeout)
                checked_cleanup(strict_json(directory / 'cleanup.json'))
                require(running.wait(timeout=args.timeout) == 0, 'DUT executor rejected final context cleanup')
                require(bridge.poll() is None, 'producer freed resources before successful DUT/context receipt')
                qualified(env, output)
                atomic(directory / 'FINAL_RESPONSE', b'OGCMOK01\x01\x00\x00\x00')
                require(bridge.wait(timeout=args.timeout) == 0, 'producer rejected observed final cleanup')
            print('actual compute returned bytes, physical guards and observed context cleanup: PASS')
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


if __name__ == '__main__':
    main()
