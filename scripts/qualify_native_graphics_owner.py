#!/usr/bin/env python3
"""Execute original Mesa native owners on an already qualified complete DUT.

No model is built and no profile, completion or image is synthesized here.
The live controller owns actual execution, independent images and final MMU
cleanup. Private source copies, CPU builds and IPC are removed after exit proof.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

sys.dont_write_bytecode = True

from native_graphics_owner_bridge import strict_json
from owner_process_lifetime import CleanupIncomplete, stop_group, remove_container

ROOT = Path(__file__).resolve().parents[1]


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def checked_inputs(args):
    require(args.executor_qualification.is_file() and not args.executor_qualification.is_symlink(),
            'a real guarded native executor qualification is required')
    require(args.executor.is_file() and not args.executor.is_symlink(), 'qualified executor is missing or aliased')
    subprocess.run([args.executor_checker_python, args.executor_checker, '--stage', 'check', '--kind', 'native',
                    '--qualification', args.executor_qualification, '--executor', args.executor], check=True)
    record = strict_json(args.executor_qualification)
    require(record.get('schema') == 'opengpu-owner-executor-v1' and record.get('kind') == 'native',
            'wrong executor qualification kind')
    return sha(args.executor_qualification), sha(args.executor)


def archive_source(root, revision, destination):
    destination.mkdir()
    # Only CPU producer dependencies; no copied RTL, models, logs or caches.
    archive = subprocess.Popen(['git', '-C', root, 'archive', revision, 'driver', 'scripts', 'userspace'],
                               stdout=subprocess.PIPE)
    try:
        subprocess.run(['tar', '-x', '-C', destination], stdin=archive.stdout, check=True)
    finally:
        archive.stdout.close()
        require(archive.wait() == 0, 'fixed producer source archive failed')


def stage_lowering(mesa, source, destination):
    from apply_mesa_uniform_index_patch import BEFORE, AFTER
    path = destination / 'src/compiler/nir/nir_lower_io.c'
    path.parent.mkdir(parents=True)
    shutil.copy2(mesa / 'src/compiler/nir/nir_lower_io.c', path)
    if sha(path) == BEFORE:
        subprocess.run(['patch', '-p1', '-i', source / 'scripts/patches/mesa-22.3.6-checked-uniform-index.patch'],
                       cwd=destination, check=True, stdout=subprocess.DEVNULL,
                       env=dict(os.environ, TMPDIR=str(destination.parent), PYTHONDONTWRITEBYTECODE='1'))
    require(sha(path) == AFTER, 'unknown private Mesa uniform lowering source')
    return path


def producer_command(args, *, gpu, temporary, source, kernel, lowering, image, name):
    command = ['docker', 'run', '--rm', '--init', '--name', name, '--memory', '2g',
               '--memory-swap', '2g', '--cpus', '2', '-v', f'{gpu}:{gpu}:ro',
               '-v', f'{temporary}:{temporary}:rw', '-w', str(source)]
    environment = dict(PYTHONDONTWRITEBYTECODE='1', TMPDIR='{ipc}', CC='gcc', CXX='g++',
        OPENGPU_MESA_DIR=str(args.mesa), OPENGPU_MESA_BUILD=str(args.mesa_build),
        OPENGPU_DRM_INCLUDE=str(args.drm_include), OPENGPU_MESA_LINK_LIBS=args.mesa_link_libs,
        LD_LIBRARY_PATH=str(args.library_directory), OPENGPU_NATIVE_HOST_DIRECTORY='{ipc}',
        OPENGPU_NATIVE_HOST_PROFILE='{ipc}/profile.bin', OPENGPU_NATIVE_HOST_TIMEOUT=str(args.timeout))
    for key, value in environment.items():
        command += ['-e', key + '=' + value]
    return command + [image, 'python3', 'scripts/test_gallium_graphics_uniforms.py',
        '--build', '{ipc}/build', '--sanitize', '--mesa-nir-lower-io', str(lowering),
        '--native-kernel-root', str(kernel)]


def run_owner(args, *, gpu, temporary, source, kernel, lowering, image):
    name = 'opengpu-native-' + uuid.uuid4().hex
    command = producer_command(args, gpu=gpu, temporary=temporary, source=source,
        kernel=kernel, lowering=lowering, image=image, name=name)
    command_file = temporary / 'producer.argv.json'
    command_file.write_text(json.dumps(command) + '\n')
    process = None
    try:
        process = subprocess.Popen([sys.executable, source / 'scripts/native_graphics_owner_bridge.py',
            '--executor', args.executor, '--executor-qualification', args.executor_qualification,
            '--executor-checker', args.executor_checker, '--executor-checker-python', args.executor_checker_python,
            '--bridge-command', command_file, '--timeout', str(args.timeout),
            '--managed-session-directory', temporary / 'session'], start_new_session=True,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(temporary)))
        require(process.wait() == 0, 'actual native owner execution failed')
    finally:
        try:
            stop_group(process)
        finally:
            # A stopped Docker client is insufficient; exclude the real container.
            remove_container(name)


def main():
    gpu = next(p for p in (ROOT, *ROOT.parents) if (p / '.git').is_dir())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor', type=Path, required=True)
    parser.add_argument('--executor-qualification', type=Path, required=True)
    parser.add_argument('--executor-checker', type=Path, required=True,
                        help='original prepare_owner_executor.py, with its original source checkout')
    parser.add_argument('--executor-checker-python', type=Path, required=True,
                        help='generation-matched interpreter; preserve original CIRCT environment')
    parser.add_argument('--native-kernel-root', type=Path, default=ROOT)
    parser.add_argument('--native-kernel-revision', default='HEAD')
    parser.add_argument('--mesa', type=Path, default=gpu / 'depends/mesa')
    parser.add_argument('--mesa-build', type=Path, default=gpu / 'depends/mesa-build')
    parser.add_argument('--drm-include', type=Path, default=gpu / 'depends/aarch64-root/include')
    parser.add_argument('--library-directory', type=Path, default=gpu / 'depends/aarch64-root/lib')
    parser.add_argument('--mesa-link-libs', default='-l:libz.so.1 ' + str(gpu / 'depends/aarch64-root/lib/libexpat.so.1.6.7'))
    parser.add_argument('--compiler-image', default='gpu-gtk-ci-check:latest')
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    require(0 < args.timeout <= 86400, 'timeout must be within 1..86400 seconds')
    args.executor = args.executor.absolute()
    args.executor_qualification = args.executor_qualification.absolute()
    identity = checked_inputs(args)  # No temporary directory or Docker command first.
    for key in ('executor', 'executor_qualification', 'executor_checker', 'native_kernel_root',
                'mesa', 'mesa_build', 'drm_include', 'library_directory'):
        path = getattr(args, key).resolve(strict=True)
        require(path.is_relative_to(gpu), 'producer inputs must be inside the GPU workspace')
        setattr(args, key, path)
    args.executor_checker_python = args.executor_checker_python.resolve(strict=True)
    require(not subprocess.check_output(['git', '-C', ROOT, 'status', '--porcelain']).strip(),
            'commit producer source before qualification; the fixed archive must match this clean checkout')
    revision = subprocess.check_output(['git', '-C', ROOT, 'rev-parse', 'HEAD'], text=True).strip()
    kernel_revision = subprocess.check_output(['git', '-C', args.native_kernel_root, 'rev-parse', '--verify',
        args.native_kernel_revision + '^{commit}'], text=True).strip()
    image = subprocess.check_output(['docker', 'image', 'inspect', args.compiler_image,
                                    '--format', '{{.Id}}'], text=True).strip()
    require(image.startswith('sha256:') and len(image) == 71, 'compiler image must resolve to an immutable local image')
    parent = gpu / '.artifacts'
    parent.mkdir(exist_ok=True)
    def terminate(_signal, _frame):
        raise KeyboardInterrupt('native qualification terminated')
    previous = signal.signal(signal.SIGTERM, terminate)
    temporary = None
    preserve = False
    try:
        temporary = Path(tempfile.mkdtemp(prefix='nq', dir=parent))
        source = temporary / 'source'
        archive_source(ROOT, revision, source)
        if args.native_kernel_root == ROOT and kernel_revision == revision:
            kernel = source
        else:
            kernel = temporary / 'kernel'
            archive_source(args.native_kernel_root, kernel_revision, kernel)
        lowering = stage_lowering(args.mesa, source, temporary / 'private-mesa')
        require(checked_inputs(args) == identity, 'qualified model/executor changed before execution')
        run_owner(args, gpu=gpu, temporary=temporary, source=source, kernel=kernel, lowering=lowering, image=image)
        require(checked_inputs(args) == identity, 'qualified model/executor changed during execution')
    except CleanupIncomplete:
        preserve = True
        print('Active consumers could not be excluded; preserved inputs at ' + str(temporary), file=sys.stderr)
        raise
    finally:
        if temporary is not None and not preserve:
            shutil.rmtree(temporary)
        signal.signal(signal.SIGTERM, previous)
    print('original Mesa native owners: actual full-DUT images, guards and final context cleanup PASS')


if __name__ == '__main__':
    main()
