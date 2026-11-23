#!/usr/bin/env python3
"""Run the original linked Mesa geometry draw on the guarded float FlashSim DUT."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

from compute_owner_protocol import required_flags, strict_json
from native_geometry_owner_inputs import ROOT, program_paths
from native_geometry_owner_oracle import verify
from native_sample_compiler_inputs import check_dependencies, sha256, snapshot
from native_sample_graphics_inputs import drm_inputs
from native_sample_owner_controller import run
from owner_process_lifetime import CleanupIncomplete, remove_container, stop_group
from owner_resource_contract import IMAGE_FLOAT_PROFILE, IMAGE_FLOAT_STAGE
from qualify_native_sample_owner import check_archive, docker_command, regular, require, revision

BUILD_SOURCE = '''
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path.cwd() / 'scripts'))
from native_geometry_owner_inputs import build_producer

out = Path(sys.argv[1])
subprocess.run([sys.executable, '-B', 'scripts/test_tessellation_uniform_frontend.py',
                '--geometry', '--sanitize', '--build', str(out / 'compiler')], check=True)
live = out / 'live'
live.mkdir()
build_producer(live, sanitize=True)
'''
GEOMETRY_BUILD_UNITS = 29  # Linked geometry compiler and the two C producer objects.


def checked_executor(args):
    qualification = regular(args.executor_qualification)
    executor = regular(args.executor)
    record = strict_json(qualification)
    required_flags(record, dict(schema='opengpu-owner-executor-v1', version=1,
        kind='native-sample', profile=IMAGE_FLOAT_PROFILE, ownerResourceVersion=2,
        ownerResourceStage=IMAGE_FLOAT_STAGE, source_root=str(ROOT), fullResourceQualification=False,
        executorBuildVerified=True, sourceQualificationRequired=True,
        gpuExecution=False, clockQualified=False, fpsQualified=False))
    require(record.get('backend', 'flashsim') == 'flashsim', 'geometry requires a FlashSim executor')
    checker = ROOT / 'scripts/prepare_owner_executor.py'
    subprocess.run([args.executor_checker_python, checker, '--stage', 'check', '--kind', 'native-sample',
        '--backend', 'flashsim', '--qualification', qualification, '--executor', executor],
        timeout=args.timeout, check=True)
    return (sha256(qualification), sha256(executor)), record['source_sha256']


def compiler_inputs(args, gpu):
    bound = snapshot(args.mesa, args.mesa_build, args.library_directory, gpu)
    atom = args.mesa_build / 'src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o'
    path = atom.resolve(strict=True)
    require(path.is_file() and path.is_relative_to(gpu), 'geometry compiler input escapes the GPU workspace')
    bound[str(atom)] = (str(path), sha256(path))
    bound.update((name, tuple(identity)) for name, identity in drm_inputs(args.drm_include, gpu).items())
    return bound


def archive_source(output, original, qualified_sources):
    source = output / 'source'
    source.mkdir()
    archive = subprocess.Popen(['git', '-C', ROOT, 'archive', original], stdout=subprocess.PIPE,
                               start_new_session=True)
    try:
        subprocess.run(['tar', '-x', '-C', source], stdin=archive.stdout, check=True)
        require(archive.wait() == 0, 'fixed geometry source archive failed')
    finally:
        archive.stdout.close()
        stop_group(archive)
    check_archive(source, qualified_sources)
    return source


def run_build(args, *, gpu, source, cpu, image):
    name = 'opengpu-geometry-build-' + uuid.uuid4().hex
    cpu.mkdir()
    command = docker_command(args, gpu=gpu, source=source, image=image, name=name, writable=cpu)
    command[-1:-1] = ['-e', 'OPENGPU_DRM_INCLUDE=' + str(args.drm_include),
                      '-e', 'UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1']
    process = None
    try:
        process = subprocess.Popen([*command, 'python3', '-B', '-c', BUILD_SOURCE, str(cpu)],
                                   start_new_session=True)
        require(process.wait(timeout=args.timeout) == 0, 'original geometry compiler/producer build failed')
    finally:
        try:
            stop_group(process)
        finally:
            remove_container(name)


def source_dependencies(cpu, source, external):
    dependencies = check_dependencies(cpu, source, external, expected_count=GEOMETRY_BUILD_UNITS)
    for path in map(Path, dependencies):
        if path.is_relative_to(source):
            original = regular(ROOT / path.relative_to(source))
            require(sha256(path) == sha256(original), 'geometry compiler archive source differs: ' + str(path))
    return dependencies


def run_geometry(args, *, gpu, source, output, image, binary, programs):
    name = 'opengpu-geometry-live-' + uuid.uuid4().hex
    command = docker_command(args, gpu=gpu, source=source, image=image, name=name, writable='{ipc}')
    command[-1:-1] = ['-e', 'UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1']
    args.bridge_container_name = name
    args.bridge_command = output / 'bridge.json'
    args.bridge_command.write_text(json.dumps([
        *command, str(binary), '{ipc}/profile.bin', '{ipc}', *map(str, programs)
    ]) + '\n')
    args.executor_checker = ROOT / 'scripts/prepare_owner_executor.py'
    run(args, owner_profile=IMAGE_FLOAT_PROFILE, input_name='geometry-inputs.json',
        source_paths=programs, verify_case=verify, context=(7, 11, 13), backend='flashsim',
        geometry_feedback=True)


def main():
    gpu = next(p for p in (ROOT, *ROOT.parents) if (p / '.git').is_dir())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor', type=Path, required=True)
    parser.add_argument('--executor-qualification', type=Path, required=True)
    parser.add_argument('--executor-checker-python', type=Path, default=Path(sys.executable))
    parser.add_argument('--compiler-image', required=True)
    parser.add_argument('--mesa', type=Path, default=gpu/'depends/mesa')
    parser.add_argument('--mesa-build', type=Path, default=gpu/'depends/mesa-build')
    parser.add_argument('--library-directory', type=Path, default=gpu/'depends/aarch64-root/lib')
    parser.add_argument('--drm-include', type=Path, default=gpu/'depends/aarch64-root/include')
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    require(0 < args.timeout <= 86400, 'timeout must be 1..86400 seconds')
    args.executor = args.executor.absolute()
    args.executor_qualification = args.executor_qualification.absolute()
    args.executor_checker_python = regular(args.executor_checker_python.resolve(strict=True))
    identity, qualified_sources = checked_executor(args)
    original = revision()
    for name in ('mesa', 'mesa_build', 'library_directory', 'drm_include'):
        path = getattr(args, name).resolve(strict=True)
        require(path.is_relative_to(gpu), 'geometry compiler input must be inside the GPU workspace')
        setattr(args, name, path)
    image = subprocess.check_output(['docker', 'image', 'inspect', args.compiler_image, '--format', '{{.Id}}'],
                                    text=True).strip()
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', image) is not None, 'local immutable compiler image required')
    external = compiler_inputs(args, gpu)

    def terminate(_signal, _frame):
        raise KeyboardInterrupt('original geometry qualification terminated')

    previous = signal.signal(signal.SIGTERM, terminate)
    output = None
    preserve = False
    try:
        output = Path(tempfile.mkdtemp(prefix='geometry-original-', dir=gpu/'.artifacts'))
        source = archive_source(output, original, qualified_sources)
        cpu = output / 'cpu'
        run_build(args, gpu=gpu, source=source, cpu=cpu, image=image)
        programs = program_paths(cpu/'compiler/geometry')
        binary = regular(cpu/'live/native-geometry-live-producer')
        require(os.access(binary, os.X_OK), 'original Linux geometry producer is not executable')
        built = {path: sha256(path) for path in (binary, *programs)}
        required_deps = source_dependencies(cpu, source, external)
        require(compiler_inputs(args, gpu) == external and revision() == original,
                'original Mesa or geometry source changed during compilation')
        run_geometry(args, gpu=gpu, source=source, output=output, image=image, binary=binary, programs=programs)
        require(checked_executor(args) == (identity, qualified_sources) and revision() == original,
                'geometry source or guarded FlashSim changed during execution')
        require(built == {path: sha256(regular(path)) for path in built} and
                compiler_inputs(args, gpu) == external and
                source_dependencies(cpu, source, external) == required_deps,
                'original geometry compiler inputs or products changed during execution')
        check_archive(source, qualified_sources)
    except CleanupIncomplete:
        preserve = True
        print('Preserved inputs for possibly active geometry consumers at ' + str(output), file=sys.stderr)
        raise
    finally:
        try:
            if output is not None and not preserve:
                shutil.rmtree(output)
        finally:
            signal.signal(signal.SIGTERM, previous)
    print('ORIGINAL_GEOMETRY_FLASH_SIM_PASS images=4 layers=2 samples=4 fullGNY2=true '
          'rawFeedbackPrimitives=2 feedbackCommitAck=true resetCount=1 cleanupACK=true')


if __name__ == '__main__':
    main()
