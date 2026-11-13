#!/usr/bin/env python3
"""Execute compute/native/compute/native on the complete assertion-enabled v2 host."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash

ROOT = Path(__file__).resolve().parents[1]
MARKER = ('NATIVE_ALIGNMENT_SEQUENCE PASS scenes=6 commands=24 resets=6 '
          'resetsBetweenCommands=0 computeUnits=2 lanes=16 warps=8 nativeEngines=4 '
          'frame=32x32 clockQualified=false fpsQualified=false')


def validate_rows(rows):
    expected = [(name, epoch) for name in common.SCENES for epoch in range(2)]
    if [(r.get('scene'), r.get('epoch')) for r in rows] != expected:
        raise RuntimeError('All six scenes and both ordered compute/native epochs are required')
    for row in rows:
        if (type(row.get('epoch')) is not int
                or any(type(row.get(key)) is not int or row[key] <= 0
                for key in ('computeCycles', 'nativeCycles'))
                or row.get('resetBetweenCommands') is not False
                or row.get('independentImagesRequired') is not True
                or row.get('fpsQualified') is not False):
            raise RuntimeError('Incomplete compute/native sequence execution')


def verify_marker(log):
    lines = log.read_text().splitlines()
    expected = [f'NATIVE_ALIGNMENT_SEQUENCE_IMAGE scene={name} epoch={epoch} '
                'exactImagesComputeAndReadOnlyGuards=true'
                for name in common.SCENES for epoch in range(2)]
    images = [line for line in lines if line.startswith('NATIVE_ALIGNMENT_SEQUENCE_IMAGE ')]
    if images != expected or [line for line in lines if line.startswith('NATIVE_ALIGNMENT_SEQUENCE PASS')] != [MARKER]:
        raise RuntimeError('Complete independent image/compute/read-only verification is missing')


class Runner(flash.Runner):
    def execute(self, stage, command, log, cwd=ROOT):
        with tempfile.TemporaryDirectory(prefix='sequence-tmp-', dir=self.out) as temporary:
            # Keep JVM and native/Python child fixtures inside this GPU task.
            # A unique owned directory also makes cleanup safe after a failure.
            previous = os.environ.get('TMPDIR')
            os.environ['TMPDIR'] = temporary
            try:
                if command[0] == 'sbt':
                    command = command[:1] + ['-Djava.io.tmpdir=' + temporary] + command[1:]
                start = time.monotonic()
                with log.open('w') as stream:
                    child = subprocess.Popen(command, cwd=cwd, stdout=stream,
                                             stderr=subprocess.STDOUT, start_new_session=True)
                    try:
                        self.state(stage, child_pid=child.pid, command=command, log=str(log))
                        code = child.wait()
                    except BaseException:
                        try: os.killpg(child.pid, signal.SIGTERM)
                        except ProcessLookupError: pass
                        try: child.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            try: os.killpg(child.pid, signal.SIGKILL)
                            except ProcessLookupError: pass
                            child.wait()
                        raise
                self.state(stage + '-terminal', exit_code=code, elapsed_seconds=time.monotonic()-start)
                if code:
                    raise RuntimeError(f'{stage} failed with exit {code}; inspect {log}')
            finally:
                if previous is None: os.environ.pop('TMPDIR', None)
                else: os.environ['TMPDIR'] = previous

    def driver_path(self):
        return ROOT / 'scripts/native_alignment_sequence_driver.cpp'

    def driver_sources(self):
        return [name for name in flash.DRIVER if name != 'scripts/native_product_driver.cpp'] + [
            'scripts/native_alignment_sequence_driver.cpp',
            'scripts/qualify_native_alignment_sequence.py']

    def run(self):
        prepared = self.prepared()
        self.checked_build(prepared)
        directory = self.out / 'sequence-output'
        directory.mkdir(exist_ok=True)
        # Invalidate a previous success before any command can fail. Outputs
        # from an incomplete run cannot satisfy the ordered metrics gate.
        for name in ('sequence-execution.json', 'sequence-qualification.json'):
            (self.out / name).unlink(missing_ok=True)
        self.execute('compute-native-sequence', [str(self.out / self.executable_name),
                     str(self.out / 'fixtures'), str(directory)], self.out / 'sequence-execution.log')
        rows = [json.loads(line) for line in (self.out / 'sequence-execution.log').read_text().splitlines()]
        validate_rows(rows)
        self.prepared()
        self.checked_build(prepared)
        common.write_json(self.out / 'sequence-execution.json', dict(
            exit_code=0, rows=rows, output_sha256=common.tree_hashes(directory),
            preparation_sha256=common.sha(self.out / 'preparation.json'),
            build_sha256=common.sha(self.out / 'build.json')))

    def verify(self):
        (self.out / 'sequence-qualification.json').unlink(missing_ok=True)
        prepared = self.prepared()
        self.checked_build(prepared)
        path = self.out / 'sequence-execution.json'
        execution = json.loads(path.read_text())
        directory = self.out / 'sequence-output'
        if (execution.get('exit_code') != 0
                or execution['output_sha256'] != common.tree_hashes(directory)
                or execution['preparation_sha256'] != common.sha(self.out / 'preparation.json')
                or execution['build_sha256'] != common.sha(self.out / 'build.json')):
            raise RuntimeError('Sequence outputs or executing model changed before verification')
        validate_rows(execution['rows'])
        identity = common.sha(path)
        log = self.out / 'sequence-verify.log'
        self.execute('verify-compute-native-sequence', self.sbt(
            'opengpu.system.VerifyNativeAlignmentSequence --output ' + json.dumps(str(directory)), '3G'), log)
        verify_marker(log)
        self.prepared()
        self.checked_build(prepared)
        if identity != common.sha(path) or execution['output_sha256'] != common.tree_hashes(directory):
            raise RuntimeError('Sequence execution changed during independent verification')
        common.write_json(self.out / 'sequence-qualification.json', dict(
            passed=True, execution_sha256=identity, clockQualified=False, fpsQualified=False,
            scope='Complete two-CU v2 host, six 32x32 scenes, compute/native/compute/native '
                  'without intervening reset. Exact images, compute outputs and read-only bytes. '
                  'No tessellation execution, complete feature/API, physical timing or FPS claim.'))
        print(MARKER, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--flashsim-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=('all', 'prepare', 'generate', 'build', 'run', 'verify'), default='all')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--shards', type=int, default=32)
    parser.add_argument('--reshard-dir', type=Path)
    parser.add_argument('--reshard-tool', type=Path)
    parser.add_argument('--precompiled-header', action='store_true')
    parser.add_argument('--conservative-alias', action='store_true')
    if (args := parser.parse_args()).jobs < 1 or args.shards < 1:
        parser.error('--jobs and --shards must be positive')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    with (out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = Runner(out, False, args.flashsim_dir, shards=args.shards,
                        reshard=args.reshard_dir, reshard_tool=args.reshard_tool,
                        precompiled_header=args.precompiled_header,
                        conservative_alias=args.conservative_alias,
                        api_capacity=True, two_cu_vga=True, alignment_v2=True)
        try:
            for stage in ('prepare', 'build', 'run', 'verify'):
                if args.stage in ('all', stage):
                    if stage == 'build': runner.build(args.jobs)
                    else: getattr(runner, stage)()
            if args.stage == 'generate': runner.generate()
        except BaseException as error:
            runner.state('stopped-for-audit', error=str(error), automatic_retry=False)
            raise


if __name__ == '__main__':
    main()
