#!/usr/bin/env python3
"""Qualify the actual Mesa/Linux continuous producer on an existing full DUT."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid
from compute_owner_protocol import strict_json
from compute_indirect_phase_protocol import KIND
from compute_indirect_sources import CASE
from compute_owner_inputs import snapshot
from qualify_compute_owner import require, inside, sha256
from owner_resource_contract import IMAGE_FLOAT_PROFILE
from owner_process_lifetime import CleanupIncomplete, stop_group, remove_container

ROOT = Path(__file__).resolve().parents[1]


def checked_inputs(args):
    for path in (args.executor, args.executor_qualification):
        require(path.is_file() and not path.is_symlink(), 'original qualified indirect executor required')
    record = strict_json(args.executor_qualification)
    require(record.get('schema') == 'opengpu-owner-executor-v1' and record.get('kind') == KIND and
            record.get('profile') == IMAGE_FLOAT_PROFILE, 'distinct full-factory indirect qualification required')
    subprocess.run([args.executor_checker_python, args.executor_checker, '--stage', 'check', '--kind', KIND,
        '--qualification', args.executor_qualification, '--executor', args.executor], check=True)
    return sha256(args.executor_qualification), sha256(args.executor)


def producer_command(args, *, gpu, source, ntt, image, name, inputs):
    command = ['docker', 'run', '--rm', '--init', '--name', name, '--memory', '1g',
        '--memory-swap', '1g', '--cpus', '2', '--pids-limit', '128', '--network=none', '--read-only',
        '--tmpfs', '/tmp:rw,exec,size=256m,mode=1777',
        '-v', f'{gpu}:{gpu}:ro', '-v', '{ipc}:{ipc}:rw', '-w', str(source)]
    environment = dict(PYTHONDONTWRITEBYTECODE='1', CC='cc', CXX='c++',
        OPENGPU_MESA_DIR=str(args.mesa), OPENGPU_MESA_BUILD=str(args.mesa_build),
        OPENGPU_DRM_INCLUDE=str(args.drm_include), OPENGPU_MESA_LINK_LIBS=args.mesa_link_libs,
        LD_LIBRARY_PATH=str(args.library_directory), TMPDIR='{ipc}',
        XDG_CACHE_HOME='{ipc}/cache', MESA_SHADER_CACHE_DISABLE='true',
        OPENGPU_COMPUTE_HOST_TIMEOUT=str(args.timeout))
    for key, value in environment.items():
        command += ['-e', key + '=' + value]
    return command + [image, 'python3', '-B', 'scripts/compute_owner_inputs.py',
        '--bound-inputs', str(inputs), '--build', '{ipc}/build', '--sanitize',
        '--mesa-shared-ntt', str(ntt), '--profile-wire', '{ipc}/profile.bin',
        '--session', '{ipc}', '--case', CASE]


def main():
    gpu = next(p for p in (ROOT, *ROOT.parents) if (p / '.git').is_dir())
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('executor', 'executor-qualification', 'executor-checker', 'executor-checker-python'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--mesa', type=Path, default=gpu / 'depends/mesa')
    p.add_argument('--mesa-build', type=Path, default=gpu / 'depends/mesa-build')
    p.add_argument('--drm-include', type=Path, default=gpu / 'depends/aarch64-root/include')
    p.add_argument('--library-directory', type=Path, default=gpu / 'depends/aarch64-root/lib')
    p.add_argument('--mesa-link-libs', default='-l:libz.so.1 ' + str(gpu / 'depends/aarch64-root/lib/libexpat.so.1.6.7'))
    p.add_argument('--compiler-image', default='gpu-gtk-ci-check:latest')
    p.add_argument('--timeout', type=int, default=3600)
    args = p.parse_args()
    require(0 < args.timeout <= 604800, 'timeout must be 1..604800 seconds')
    for key in ('executor', 'executor_qualification', 'executor_checker', 'mesa', 'mesa_build',
                'drm_include', 'library_directory'):
        setattr(args, key, inside(getattr(args, key), gpu))
    args.executor_checker_python = args.executor_checker_python.resolve(strict=True)
    identity = checked_inputs(args)
    require(not subprocess.check_output(['git', '-C', ROOT, 'status', '--porcelain']).strip(),
            'commit source before qualification; fixed archive must match this clean checkout')
    revision = subprocess.check_output(['git', '-C', ROOT, 'rev-parse', 'HEAD'], text=True).strip()
    image = subprocess.check_output(['docker', 'image', 'inspect', args.compiler_image,
        '--format', '{{.Id}}'], text=True).strip()
    require(image.startswith('sha256:') and len(image) == 71, 'immutable local compiler image required')
    name = 'opengpu-indirect-' + uuid.uuid4().hex
    temporary = None
    process = None
    preserve = False
    def terminate(_signal, _frame):
        raise KeyboardInterrupt('indirect qualification terminated')
    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        temporary = Path(tempfile.mkdtemp(prefix='indirect-owner-', dir=gpu / '.artifacts'))
        source = temporary / 'source'
        source.mkdir()
        archive = subprocess.Popen(['git', '-C', ROOT, 'archive', revision], stdout=subprocess.PIPE)
        try:
            subprocess.run(['tar', '-x', '-C', source], stdin=archive.stdout, check=True)
        finally:
            archive.stdout.close()
            require(archive.wait() == 0, 'fixed source archive failed')
        subprocess.run([sys.executable, source / 'scripts/stage_mesa_shared_memory.py', '--mesa', args.mesa,
            '--out', temporary / 'checked-ntt'], check=True,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(temporary)))
        ntt = temporary / 'checked-ntt/src/gallium/auxiliary/nir/nir_to_tgsi.c'
        bound = snapshot(args.mesa, args.mesa_build, args.library_directory, args.drm_include, gpu)
        qualified = strict_json(args.executor_qualification).get('source_sha256')
        require(type(qualified) is dict, 'complete original producer/checker source closure required')
        for path, digest in qualified.items():
            require(sha256(source / path) == digest, 'archive differs from qualified source: ' + path)
        inputs = temporary / 'compiler-inputs.json'
        inputs.write_text(json.dumps(dict(bound=bound, qualified=qualified, mesa=str(args.mesa),
            build=str(args.mesa_build), libraries=str(args.library_directory), drm=str(args.drm_include),
            gpu=str(gpu), ntt=str(ntt))) + '\n')
        argv = producer_command(args, gpu=gpu, source=source, ntt=ntt, image=image, name=name, inputs=inputs)
        command = temporary / 'producer.argv.json'
        command.write_text(json.dumps(argv) + '\n')
        process = subprocess.Popen([sys.executable, '-B', source / 'scripts/compute_indirect_controller.py',
            '--executor', args.executor, '--executor-qualification', args.executor_qualification,
            '--executor-checker', args.executor_checker, '--executor-checker-python', args.executor_checker_python,
            '--bridge-command', command, '--timeout', str(args.timeout),
            '--managed-session-directory', temporary / 'session'], start_new_session=True,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(temporary)))
        require(process.wait(timeout=args.timeout) == 0, 'actual continuous indirect qualification failed')
        require(snapshot(args.mesa, args.mesa_build, args.library_directory, args.drm_include, gpu) == bound,
                'original Mesa/compiler inputs changed during execution')
        require(checked_inputs(args) == identity, 'qualified model/executor changed during execution')
    finally:
        try:
            try:
                stop_group(process)
            finally:
                remove_container(name)
        except CleanupIncomplete:
            preserve = True
            print('Active consumer exit could not be proved; preserved inputs at ' + str(temporary), file=sys.stderr)
            raise
        finally:
            if temporary is not None and not preserve:
                shutil.rmtree(temporary)
            signal.signal(signal.SIGTERM, previous)
    print('actual full DUT original-Mesa indirect chain, failure/reuse, zero axes and final cleanup PASS')


if __name__ == '__main__':
    main()
