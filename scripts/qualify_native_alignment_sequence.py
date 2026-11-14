#!/usr/bin/env python3
"""Execute compute/native/compute/native on a complete assertion-enabled host."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time

import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash
import prepare_native_owner_flashsim as owner

ROOT = Path(__file__).resolve().parents[1]
MARKER = ('NATIVE_ALIGNMENT_SEQUENCE PASS scenes=6 commands=24 resets=6 '
          'resetsBetweenCommands=0 computeUnits=2 lanes=16 warps=8 nativeEngines=4 '
          'frame=32x32 clockQualified=false fpsQualified=false')


def temporary_root():
    # sbt's native Unix socket cannot use a long worktree output path on macOS.
    # Resolve the main GPU checkout without placing files beside that checkout.
    git = Path(subprocess.check_output(['git', 'rev-parse', '--git-common-dir'],
                                      cwd=ROOT, text=True).strip())
    directory = (ROOT / git).resolve().parent / '.artifacts'
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def checked_owner_output(path):
    """Reuse one exact-source owner across worktrees in this GPU repository.

    The current original preparer still checks every Scala/build/SRAM/helper
    byte, all discovery words, all RTL and assertion bytes. Changing a driver
    script never authorizes relabeling a different factory or hardware source.
    """
    out = Path(path).resolve()
    if not out.is_dir() or not out.is_relative_to(temporary_root()):
        raise RuntimeError('Owner output must be an existing prepared DUT inside this GPU repository .artifacts')
    owner.prepared(out)
    if (out / '.lock').is_symlink():
        raise RuntimeError('Owner build/execution lock must not be aliased')
    return out


def validate_rows(rows, owner_profile=False):
    expected = [(name, epoch) for name in common.SCENES for epoch in range(2)]
    if [(r.get('scene'), r.get('epoch')) for r in rows] != expected:
        raise RuntimeError('All six scenes and both ordered compute/native epochs are required')
    for row in rows:
        if (type(row.get('epoch')) is not int
                or row.get('profile') != (owner.PROFILE if owner_profile else 'two-cu-vga60-v2')
                or type(row.get('resourceDiscoveryWords')) is not int
                or row['resourceDiscoveryWords'] != (96 if owner_profile else 72)
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
    owner_profile = False

    def execute(self, stage, command, log, cwd=ROOT):
        with tempfile.TemporaryDirectory(prefix='n', dir=temporary_root()) as temporary:
            # Keep JVM and native/Python child fixtures inside this GPU task.
            # A unique owned directory also makes cleanup safe after a failure.
            previous = os.environ.get('TMPDIR')
            os.environ['TMPDIR'] = temporary
            try:
                if command[0] == 'sbt':
                    if sys.platform == 'darwin' and len(os.fsencode(temporary)) + 51 >= 104:
                        raise RuntimeError('GPU temporary path exceeds sbt\'s macOS Unix socket limit')
                    command = command[:1] + ['-J-Djava.io.tmpdir=' + temporary,
                                            '-J-XX:ActiveProcessorCount=2'] + command[1:]
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
        for name in ('sequence-execution.json', 'sequence-qualification.json'):
            (self.out / name).unlink(missing_ok=True)
        prepared = self.prepared()
        self.checked_build(prepared)
        directory = self.out / 'sequence-output'
        directory.mkdir(exist_ok=True)
        # Invalidate a previous success before any command can fail. Outputs
        # from an incomplete run cannot satisfy the ordered metrics gate.
        self.execute('compute-native-sequence', [str(self.out / self.executable_name),
                     str(self.out / 'fixtures'), str(directory)], self.out / 'sequence-execution.log')
        rows = [json.loads(line) for line in (self.out / 'sequence-execution.log').read_text().splitlines()]
        validate_rows(rows, self.owner_profile)
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
        validate_rows(execution['rows'], self.owner_profile)
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
            profile=owner.PROFILE if self.owner_profile else 'two-cu-vga60-v2',
            scope='Complete two-CU host, six 32x32 scenes, compute/native/compute/native '
                  'without intervening reset. Exact images, compute outputs and read-only bytes. '
                  'No tessellation execution, complete feature/API, physical timing or FPS claim.'))
        print(MARKER, flush=True)


class OwnerRunner(Runner):
    """Add original fixtures to one prepared owner DUT without emitting another."""
    owner_profile = True

    def __init__(self, *args, tile_log2=3, **kwargs):
        if type(tile_log2) is not int or tile_log2 not in (3, 4):
            raise ValueError('Owner fixtures require 8x8 or 16x16 tiles')
        self.tile_log2 = tile_log2
        super().__init__(*args, **kwargs)

    def fixture_manifest(self):
        manifest = json.loads((self.out / 'owner-fixture-manifest.json').read_text())
        owner.check_manifest(manifest, rtl_emitted=False)
        width, height = common.frame_size(self.product, True)
        expected = dict(workloadProduct=self.product, workloadWidth=width, workloadHeight=height,
                        workloadTileLog2=self.tile_log2,
                        scenes=common.SCENES)
        if any(type(manifest.get(k)) is not type(v) or manifest[k] != v
               for k, v in expected.items()):
            raise RuntimeError('Owner fixtures differ from the selected six original recipes')

    def fixture_inputs(self):
        files = common.tree_hashes(self.out / 'fixtures')
        expected = {'suite.txt'} | {n + suffix for n in common.SCENES
                                   for suffix in ('.memory', '.commands')}
        if (set(files) != expected or
                (self.out / 'fixtures/suite.txt').read_text().splitlines() != common.SCENES):
            raise RuntimeError('Incomplete or reordered owner sequence fixture set')
        return dict(fixture_sha256=files,
                    manifest_sha256=common.sha(self.out / 'owner-fixture-manifest.json'),
                    driver_sha256={n: common.sha(ROOT / n) for n in self.driver_sources()})

    def prepared(self):
        record = owner.prepared(self.out)
        self.fixture_manifest()
        if json.loads((self.out / 'sequence-fixtures.json').read_text()) != self.fixture_inputs():
            raise RuntimeError('Owner sequence fixtures or driver changed after preparation')
        return record

    def prepare(self):
        # Validate full emitted RTL before creating any fixture or changing any
        # record. A describe-only manifest can never authorize model generation.
        record = owner.prepared(self.out)
        for name in ('fixtures', 'owner-fixture-manifest.json', 'sequence-fixtures.json'):
            if (self.out / name).exists():
                raise RuntimeError('Owner sequence preparation requires fresh fixtures')
        log = self.out / 'sequence-prepare.log'
        mode = '--product' if self.product else '--smoke'
        width, height = common.frame_size(self.product, True)
        self.execute('prepare-owner-sequence-fixtures', self.sbt(
            'opengpu.system.PrepareNativeOwnerFixtures ' + mode + ' --output ' +
            json.dumps(str(self.out)) + ' --tile-log2 ' + str(self.tile_log2), '3G'), log)
        common.verify_prepare_completion(log,
            f'NATIVE_OWNER_FIXTURES scenes=6 frame={width}x{height} words=96 rtlEmitted=false gpuExecution=false')
        if owner.prepared(self.out) != record:
            raise RuntimeError('Owner DUT changed during sequence fixture preparation')
        self.fixture_manifest()
        common.write_json(self.out / 'sequence-fixtures.json', self.fixture_inputs())
        self.prepared()


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
    parser.add_argument('--owner-profile', action='store_true',
                        help='reuse an already prepared 96-word owner DUT; prepare fixtures only')
    if (args := parser.parse_args()).jobs < 1 or args.shards < 1:
        parser.error('--jobs and --shards must be positive')
    out = args.output.resolve()
    if args.owner_profile:
        try:out=checked_owner_output(out)
        except RuntimeError as error:parser.error(str(error))
    elif not out.is_relative_to(ROOT / '.artifacts'):
        parser.error('--output must be inside this checkout .artifacts')
    out.mkdir(parents=True, exist_ok=True)
    with (out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = (OwnerRunner if args.owner_profile else Runner)(out, False, args.flashsim_dir, shards=args.shards,
                        reshard=args.reshard_dir, reshard_tool=args.reshard_tool,
                        precompiled_header=args.precompiled_header,
                        conservative_alias=args.conservative_alias,
                        api_capacity=True, two_cu_vga=True, alignment_v2=not args.owner_profile)
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
