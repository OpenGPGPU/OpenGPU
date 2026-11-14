#!/usr/bin/env python3
"""Run original Mesa compute sources on an already qualified full DUT.

This entry never builds a DUT or supplies completion data. The real producer,
executor, independent byte oracle and synchronous cleanup remain live under
compute_owner_controller. All private CPU builds and IPC are removed on exit.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import uuid
from compute_owner_protocol import strict_json
from compute_owner_cases import CASES, DYNAMIC_CASES
from owner_resource_contract import COMPUTE_STAGE
from owner_process_lifetime import CleanupIncomplete, stop_group, remove_container

ROOT = Path(__file__).resolve().parents[1]


def require(value, message):
    if not value:
        raise ValueError(message)


def inside(path, gpu):
    path = Path(path).resolve(strict=True)
    require(path.is_relative_to(gpu), 'input must be inside the GPU workspace')
    return path


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def checked_inputs(args):
    # Refuse absent qualification before any archive, container or CPU build.
    require(args.executor_qualification.is_file() and not args.executor_qualification.is_symlink(),
            'a real guarded compute executor qualification is required')
    require(args.executor.is_file() and not args.executor.is_symlink(), 'qualified executor is missing or aliased')
    subprocess.run([args.executor_checker_python, args.executor_checker, '--stage', 'check', '--kind', 'compute',
                    '--qualification', args.executor_qualification, '--executor', args.executor], check=True)
    record = strict_json(args.executor_qualification)
    require(record.get('schema') == 'opengpu-owner-executor-v1' and record.get('kind') == 'compute',
            'wrong executor qualification kind')
    if getattr(args, 'case', None) in (*DYNAMIC_CASES, 'mandatory-compute'):
        require(record.get('profile') == COMPUTE_STAGE,
                'shared atomic execution requires the distinct shared-enabled complete owner factory')
    return sha256(args.executor_qualification), sha256(args.executor)


def producer_command(args, *, gpu, source, ntt, image, name, case):
    require(case in CASES, 'unknown original compute source')
    # The configured Mesa object database uses these original absolute paths.
    # Keep that mount unchanged; all newly generated files live in {ipc}.
    command = ['docker', 'run', '--rm', '--init', '--name', name, '--memory', '2g', '--memory-swap', '2g',
               '--cpus', '2', '--network=none', '--read-only',
               '-v', f'{gpu}:{gpu}:ro', '-v', '{ipc}:{ipc}:rw', '-w', str(source)]
    environment = dict(PYTHONDONTWRITEBYTECODE='1', OPENGPU_MESA_DIR=str(args.mesa),
        OPENGPU_MESA_BUILD=str(args.mesa_build), OPENGPU_DRM_INCLUDE=str(args.drm_include),
        OPENGPU_MESA_LINK_LIBS=args.mesa_link_libs, LD_LIBRARY_PATH=str(args.library_directory), TMPDIR='{ipc}',
        HOME='{ipc}', XDG_CACHE_HOME='{ipc}/cache', MESA_SHADER_CACHE_DISABLE='true',
        OPENGPU_COMPUTE_HOST_TIMEOUT=str(args.timeout))
    for key, value in environment.items():
        command += ['-e', key + '=' + value]
    return command + [image, 'python3', 'scripts/test_gallium_compute_host.py',
        '--build', '{ipc}/build', '--sanitize', '--mesa-shared-ntt', str(ntt),
        '--profile-wire', '{ipc}/profile.bin', '--session', '{ipc}', '--case', case]


def run_case(args, *, gpu, source, temporary, ntt, image, case):
    name = 'opengpu-compute-' + uuid.uuid4().hex
    argv = producer_command(args, gpu=gpu, source=source, ntt=ntt, image=image, name=name, case=case)
    command_file = temporary / (case + '.argv.json')
    command_file.write_text(json.dumps(argv) + '\n')
    process = None
    try:
        process = subprocess.Popen([sys.executable, source / 'scripts/compute_owner_controller.py',
            '--executor', args.executor, '--executor-qualification', args.executor_qualification,
            '--executor-checker', args.executor_checker, '--executor-checker-python', args.executor_checker_python,
            '--bridge-command', command_file, '--timeout', str(args.timeout),
            '--managed-session-directory', temporary / (case + '-session')], start_new_session=True,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(temporary)))
        require(process.wait() == 0, 'actual compute case failed: ' + case)
    finally:
        # A terminated Docker client alone does not prove its container exited.
        try:
            stop_group(process)
        finally:
            remove_container(name)


def main():
    gpu = next(p for p in (ROOT, *ROOT.parents) if (p / '.git').is_dir())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor', type=Path, required=True)
    parser.add_argument('--executor-qualification', type=Path, required=True)
    parser.add_argument('--executor-checker', type=Path, required=True,
                        help='original prepare_owner_executor.py, never a copied checker')
    parser.add_argument('--executor-checker-python', type=Path, required=True,
                        help='generation-matched interpreter; preserve the fixed CIRCT environment')
    parser.add_argument('--mesa', type=Path, default=gpu / 'depends/mesa')
    parser.add_argument('--mesa-build', type=Path, default=gpu / 'depends/mesa-build')
    parser.add_argument('--drm-include', type=Path, default=gpu / 'depends/aarch64-root/include')
    parser.add_argument('--library-directory', type=Path, default=gpu / 'depends/aarch64-root/lib')
    parser.add_argument('--mesa-link-libs', default='-l:libz.so.1 ' + str(gpu / 'depends/aarch64-root/lib/libexpat.so.1.6.7'))
    parser.add_argument('--compiler-image', default='gpu-gtk-ci-check:latest')
    parser.add_argument('--case', choices=('both', 'mandatory-compute', *CASES), default='both')
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    require(0 < args.timeout <= 604800, 'timeout must be 1..604800 seconds')
    args.executor = args.executor.absolute(); args.executor_qualification = args.executor_qualification.absolute()
    identity = checked_inputs(args)
    for key in ('executor', 'executor_qualification', 'executor_checker', 'mesa', 'mesa_build', 'drm_include', 'library_directory'):
        setattr(args, key, inside(getattr(args, key), gpu))
    args.executor_checker_python = args.executor_checker_python.resolve(strict=True)
    require(not subprocess.check_output(['git', '-C', ROOT, 'status', '--porcelain']).strip(),
            'commit the producer source before qualification; the fixed archive must match this clean checkout')
    revision = subprocess.check_output(['git', '-C', ROOT, 'rev-parse', 'HEAD'], text=True).strip()
    image = subprocess.check_output(['docker', 'image', 'inspect', args.compiler_image, '--format', '{{.Id}}'], text=True).strip()
    require(image.startswith('sha256:') and len(image) == 71, 'compiler image must resolve to an immutable local image')
    def terminate(_signal, _frame):
        raise KeyboardInterrupt('compute qualification terminated')
    previous = signal.signal(signal.SIGTERM, terminate)
    temporary = None
    preserve = False
    try:
        temporary = Path(tempfile.mkdtemp(prefix='cq', dir=gpu / '.artifacts'))
        directory = str(temporary); source = temporary / 'source'; source.mkdir()
        archive = subprocess.Popen(['git', '-C', ROOT, 'archive', revision], stdout=subprocess.PIPE)
        try:
            subprocess.run(['tar', '-x', '-C', source], stdin=archive.stdout, check=True)
        finally:
            archive.stdout.close()
            require(archive.wait() == 0, 'fixed source archive failed')
        subprocess.run([sys.executable, source / 'scripts/stage_mesa_shared_memory.py', '--mesa', args.mesa,
                        '--out', temporary / 'checked-ntt'], check=True,
                       env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=directory))
        ntt = temporary / 'checked-ntt/src/gallium/auxiliary/nir/nir_to_tgsi.c'
        cases = ('shared', 'uniforms') if args.case == 'both' else CASES if args.case == 'mandatory-compute' else (args.case,)
        for case in cases:
            require(checked_inputs(args) == identity, 'qualified model/executor changed between source cases')
            run_case(args, gpu=gpu, source=source, temporary=temporary, ntt=ntt, image=image, case=case)
            require(checked_inputs(args) == identity, 'qualified model/executor changed during a source case')
    except CleanupIncomplete:
        preserve = True
        print('Active consumers could not be excluded; preserved their inputs at ' + str(temporary), file=sys.stderr)
        raise
    finally:
        if temporary is not None and not preserve:
            shutil.rmtree(temporary)
        signal.signal(signal.SIGTERM, previous)
    print('original Mesa compute ' + args.case + ': actual full-DUT returned bytes and context cleanup PASS')


if __name__ == '__main__':
    main()
