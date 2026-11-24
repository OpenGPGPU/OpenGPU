#!/usr/bin/env python3
"""Replay checked TCS/TES uniforms on the qualified full FlashSim owner."""
import argparse
from contextlib import ExitStack
import fcntl
import json
from pathlib import Path
import shutil
import signal
import sys
import tempfile

import prepare_owner_executor as owner
from owner_process_lifetime import CleanupIncomplete
from owner_resource_contract import TESSELLATION_UNIFORM_PROFILE
from run_owner_bounded import run_bounded

KIND = 'native-sample'
CASES = ('distinct', 'invalidate', 'capacity')
DRIVER = 'scripts/tessellation_uniform_owner_replay.cpp'
DEPENDENCIES = (
    'scripts/native_sample_owner_runtime.hpp',
    'scripts/native_sample_owner_capture.hpp',
    'scripts/native_owner_runtime.hpp',
    'scripts/native_owner_capture.hpp',
    'scripts/owner_factory.hpp',
    'driver/gpu_abi.h',
    'driver/opengpu_formats.h',
    'driver/opengpu_shader_resources.h',
)


def replay(qualification, fixture, cases):
    owner.require(not qualification.is_symlink(), 'aliased executor qualification')
    qualification = owner.inside(qualification)
    fixture = owner.inside(fixture)
    owner.require(fixture.is_dir(), 'fixture directory is missing')
    with ExitStack() as locks:
        for path in owner.build_lock_paths(qualification.parents[2], KIND):
            lock = locks.enter_context(path.open('rb'))
            fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        record = owner.verify_qualification(qualification, kind=KIND, backend='flashsim')
        owner.require(record['profile'] == TESSELLATION_UNIFORM_PROFILE,
                      'replay requires the complete checked-uniform factory')
        build = json.loads((qualification.parent / 'builder.json').read_text())
        donor = Path(record.get('reshard_donor', qualification))
        work = Path(tempfile.mkdtemp(prefix='tessellation-uniform-replay-', dir=owner.ROOT / '.artifacts'))
        cleanup_known = True
        try:
            cache = work / 'shards.json'
            cache.write_text(json.dumps({
                'directory': str(donor.parents[2] / 'flashsim-build'),
                'objects': build['objects'],
            }))
            output = work / 'build'
            options = record['options']
            command = [
                sys.executable, '-B', str(owner.ROOT / 'scripts/build_native_flashsim.py'),
                '--dut-dir', record.get('compiled_dut_directory', record['model']['dut_directory']),
                '--build-dir', str(output),
                '--driver', str(owner.ROOT / DRIVER), '--cxx', build['base_flags'][0],
                '--shard-cache', str(cache), '--jobs', '1',
                '--shard-opt=' + options['shard_opt'], '--commit-opt=' + options['commit_opt'],
            ]
            for name in ('precompiled_header', 'conservative_alias', 'strict_flex_arrays'):
                if options[name]:
                    command.append('--' + name.replace('_', '-'))
            for dependency in DEPENDENCIES:
                command.extend(('--driver-dependency', str(owner.ROOT / dependency)))
            owner.require(run_bounded(command, 8192, 1200) == 0,
                          'checked-uniform replay driver failed to link')
            built = json.loads((output / 'native-flashsim-build.json').read_text())
            progress = json.loads((output / 'native-flashsim-progress.json').read_text())
            owner.require(all(built[key] == build[key] for key in
                              ('dut_header_sha256', 'dut_sources', 'objects', 'base_flags'))
                          and progress['compiled_shards'] == 0
                          and progress['cached_shards'] == len(build['objects']),
                          'replay did not reuse the exact qualified FlashSim objects')
            executable = output / 'native_product_driver'
            for case in cases:
                owner.require(run_bounded([str(executable), str(fixture), case], 8192, 900) == 0,
                              'checked-uniform replay failed: ' + case)
        except CleanupIncomplete:
            cleanup_known = False
            print('Replay consumer exit unproven; preserve inputs: ' + str(work), file=sys.stderr)
            raise
        finally:
            if cleanup_known:
                shutil.rmtree(work)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--qualification', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--case', action='append', choices=CASES)
    args = parser.parse_args()

    def interrupted(signum, _frame):
        raise InterruptedError('checked-uniform qualification interrupted by signal ' + str(signum))

    previous = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        replay(args.qualification, args.fixture, args.case or CASES)
        return 0
    except (OSError, RuntimeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    raise SystemExit(main())
