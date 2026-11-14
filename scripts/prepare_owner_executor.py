#!/usr/bin/env python3
"""Build/check fixed native or compute executors for an existing complete owner DUT."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True

import build_native_flashsim as builder
import prepare_native_owner_flashsim as owner
import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash
from owner_resource_contract import COMPUTE_STAGE

ROOT = Path(__file__).resolve().parents[1]
KINDS = ('native', 'compute')
SHARED = ('scripts/native_owner_runtime.hpp', 'scripts/native_owner_capture.hpp',
          'scripts/owner_factory.hpp',
          'driver/gpu_abi.h', 'driver/opengpu_formats.h', 'driver/opengpu_shader_resources.h')
DEFAULT_DEPENDENCIES = ('scripts/flashsim_native/VGpuHostSystemAxi.h',
    'scripts/flashsim_native/verilated.h', 'scripts/native_host_simulation.hpp',
    'scripts/native_execution_progress.hpp', 'scripts/native_frame_axi.hpp',
    'scripts/native_frame_fixture.hpp')
CHECKERS = ('scripts/prepare_owner_executor.py', 'scripts/build_native_flashsim.py',
    'scripts/prepare_native_owner_flashsim.py', 'scripts/qualify_native_product_flashsim.py',
    'scripts/qualify_native_product_compiled.py', 'scripts/owner_resource_contract.py')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def profile_contract(profile, kind):
    require(kind in KINDS, 'unknown owner executor kind')
    require(profile == owner.PROFILE or (profile == COMPUTE_STAGE and kind == 'compute'),
            'unsupported factory for the selected owner executor kind')
    fields = dict(profile=profile)
    if profile == COMPUTE_STAGE:
        fields.update(ownerResourceVersion=2, ownerResourceStage='compute', fullResourceQualification=False)
    return fields


def driver_files(kind, profile=owner.PROFILE):
    require(kind in KINDS, 'unknown owner executor kind')
    profile_contract(profile, kind)
    if profile == COMPUTE_STAGE:
        return ['scripts/compute_stage_owner_driver.cpp', 'scripts/compute_owner_driver.cpp',
                *SHARED, 'scripts/compute_owner_capture.hpp']
    return ([f'scripts/{kind}_owner_driver.cpp', *SHARED] +
            (['scripts/compute_owner_capture.hpp'] if kind == 'compute' else []))


def hashes(names):
    return {name: common.sha(ROOT / name) for name in names}


def inside(path):
    path = Path(path).resolve(strict=True)
    require(path.is_relative_to(ROOT / '.artifacts'), 'owner executor paths must be inside this checkout .artifacts')
    return path


def compiler_identity(path):
    path = Path(path).resolve(strict=True)
    return dict(path=str(path), sha256=common.sha(path),
                version=subprocess.check_output([str(path), '--version'], text=True))


def model_identity(directory, flashsim, shards):
    require(type(shards) is int and shards > 0, 'invalid owner shard count')
    prepared = owner.prepared(directory)
    # prepared() binds this strict factory manifest to the emitted RTL and all
    # source hashes. Never choose an executor from a requested record label.
    manifest_bytes = (directory / 'owner-manifest.json').read_bytes()
    require(hashlib.sha256(manifest_bytes).hexdigest() == prepared['manifest_sha256'],
            'owner factory changed after prepared RTL validation')
    manifest = json.loads(manifest_bytes)
    generated = flash.generated_sources(directory / 'flashsim-dut', shards)
    record = json.loads((directory / 'flashsim-dut/generation.json').read_text())
    generator = flash.generator_inputs(flashsim)
    require(record.get('context') == dict(generator=generator, rtl_sha256=prepared['rtl_sha256']) and
            record.get('generated') == generated and
            record.get('mlir_sha256') == common.sha(directory / 'flashsim-dut/hw.mlir'),
            'owner generation does not match the complete prepared RTL, assertions or toolchain')
    return dict(profile=manifest['profile'], dut_directory=str(directory / 'flashsim-dut'),
                preparation_sha256=common.sha(directory / 'preparation.json'),
                profile_manifest_sha256=prepared['manifest_sha256'],
                generation_sha256=common.sha(directory / 'flashsim-dut/generation.json'),
                rtl_sha256=prepared['rtl_sha256'], generated=generated, generator=generator)


def checked_builder(record, model, kind, options, compiler):
    files = driver_files(kind, model['profile'])
    require(type(options) is dict and set(options) == {'shard_opt', 'commit_opt',
            'conservative_alias', 'strict_flex_arrays', 'precompiled_header'} and
            options['shard_opt'] in ('-O0', '-O1', '-O2', '-O3') and options['commit_opt'] == '-O0' and
            options['strict_flex_arrays'] is False and
            all(type(options[k]) is bool for k in ('conservative_alias', 'precompiled_header')),
            'incomplete or unsupported owner executor build options')
    expected_inputs = {str((ROOT / name).resolve()): digest for name, digest in
                       hashes([files[0], *DEFAULT_DEPENDENCIES, *files[1:]]).items()}
    require(record.get('dut_header_sha256') == model['generated']['files']['dut.h'] and
            record.get('dut_sources') == {n: h for n, h in model['generated']['files'].items() if n != 'dut.h'} and
            record.get('driver_source') == str((ROOT / files[0]).resolve()) and
            record.get('driver_inputs') == expected_inputs and
            str(Path(record['compiler']).resolve()) == compiler['path'] and
            record.get('compiler_version') == compiler['version'] and
            record.get('environment') == {k: os.environ.get(k, '') for k in builder.ENVIRONMENT},
            'owner executor builder does not match the model, driver dependencies or compiler')
    for name, expected in options.items():
        require(type(record.get(name)) is type(expected) and record[name] == expected,
                'owner executor compiler options differ: ' + name)
    flags = ['c++', '-std=gnu++17', '-fPIC', '-fPIE', '-w']
    if 'clang' in compiler['version'].lower():
        flags += ['-Wno-parentheses-equality', '-fbracket-depth=4096']
    flags += ['-I', model['dut_directory']]
    driver_flags = flags[:-2] + ['-I', str(ROOT / 'scripts/flashsim_native'),
                                '-I', str(ROOT / 'scripts'), *flags[-2:], '-O1']
    require(record.get('base_flags') == flags and record.get('driver_flags') == driver_flags and
            set(record.get('objects', {})) == {Path(n).stem + '.o' for n in record['dut_sources']} and
            all(type(h) is str and len(h) == 64 and all(c in '0123456789abcdef' for c in h)
                for h in record['objects'].values()),
            'owner executor compile flags or linked shard set differ')


def verify_qualification(path, *, executor=None, kind='compute'):
    """Recheck original source/tool/model/build/binary identity; executes no GPU job."""
    require(not Path(path).is_symlink(), 'owner qualification must not be aliased')
    path = inside(path)
    record = json.loads(path.read_text())
    required = dict(schema='opengpu-owner-executor-v1', version=1, kind=kind,
                    executorBuildVerified=True,
                    sourceQualificationRequired=True, gpuExecution=False,
                    clockQualified=False, fpsQualified=False)
    require(all(type(record.get(k)) is type(v) and record[k] == v for k, v in required.items()),
            'owner executor qualification has the wrong kind, schema, profile or scope')
    directory = inside(record['owner_output'])
    model = model_identity(directory, Path(record['flashsim']), record['shards'])
    contract = profile_contract(model['profile'], kind)
    require(all(type(record.get(k)) is type(v) and record[k] == v for k, v in contract.items()),
            'owner executor profile differs from its exact prepared factory')
    if model['profile'] == owner.PROFILE:
        require(not any(k in record for k in ('ownerResourceVersion', 'ownerResourceStage', 'fullResourceQualification')),
                'frozen owner executor cannot carry compute-stage labels')
    files = driver_files(kind, model['profile'])
    require(record.get('source_root') == str(ROOT) and
            path == directory / 'executors' / kind / 'qualification.json',
            'owner qualification was moved or uses another source checkout')
    executable = path.parent / 'executor'
    require(executable.is_file() and not executable.is_symlink() and os.access(executable, os.X_OK),
            'qualified owner executable is missing or aliased')
    if executor is not None:
        require(Path(executor).absolute() == executable and not Path(executor).is_symlink(),
                'controller selected an unqualified owner executable')
    require(record.get('source_sha256') == hashes([*files, *DEFAULT_DEPENDENCIES, *CHECKERS]),
            'owner executor source or checker changed')
    require(record.get('model') == model, 'owner executor no longer matches the complete model')
    compiler = compiler_identity(record['compiler']['path'])
    require(record['compiler'] == compiler, 'owner executor compiler changed')
    snapshot = path.parent / 'builder.json'
    require(snapshot.is_file() and not snapshot.is_symlink() and
            record.get('builder_sha256') == common.sha(snapshot),
            'owner executor build snapshot changed or is aliased')
    built = json.loads(snapshot.read_text())
    checked_builder(built, model, kind, record['options'], compiler)
    require(record.get('executable_sha256') == common.sha(executable) == built.get('executable_sha256'),
            'owner executor binary changed or belongs to another build')
    return record


def build_executor(directory, flashsim, kind, *, shards=32, jobs=1,
                   precompiled_header=False, conservative_alias=False, shard_opt='-O2'):
    directory = inside(directory)
    require(kind in KINDS and type(jobs) is int and jobs > 0, 'invalid executor kind or compiler jobs')
    require(shard_opt in ('-O0', '-O1', '-O2', '-O3'), 'invalid owner shard optimization')
    target = directory / 'executors' / kind
    require(target.resolve() == target, 'owner executor output directory must not be aliased')
    target.mkdir(parents=True, exist_ok=True)
    require(all(not (target / name).is_symlink() for name in ('qualification.json', 'builder.json', 'executor')),
            'owner executor output files must not be aliased')
    path = target / 'qualification.json'
    # A failed fresh attempt cannot leave an earlier success reusable.
    path.unlink(missing_ok=True)
    model = model_identity(directory, flashsim, shards)
    contract = profile_contract(model['profile'], kind)
    files = driver_files(kind, model['profile'])
    sources = hashes([*files, *DEFAULT_DEPENDENCIES, *CHECKERS])
    compiler_path = shutil.which('c++')
    require(compiler_path, 'owner executor requires c++')
    compiler = compiler_identity(compiler_path)
    options = dict(shard_opt=shard_opt, commit_opt='-O0', conservative_alias=conservative_alias,
                   strict_flex_arrays=False, precompiled_header=precompiled_header)
    # Share only the guarded shard/object cache, not a mutable executor identity.
    build = directory / 'flashsim-build'
    require(build.resolve() == build, 'owner shard cache must not be aliased')
    command = [sys.executable, str(ROOT / 'scripts/build_native_flashsim.py'),
               '--dut-dir', str(directory / 'flashsim-dut'), '--build-dir', str(build),
               '--driver', str(ROOT / files[0]), '--jobs', str(jobs),
               '--cxx', 'c++', '--shard-opt=' + shard_opt]
    for name in files[1:]:
        command += ['--driver-dependency', str(ROOT / name)]
    if precompiled_header:
        command += ['--precompiled-header']
    if conservative_alias:
        command += ['--conservative-alias']
    with tempfile.TemporaryDirectory(prefix='executor-', dir=ROOT / '.artifacts') as temporary:
        process = subprocess.Popen(command, cwd=ROOT, start_new_session=True,
            env=dict(os.environ, TMPDIR=temporary, PYTHONDONTWRITEBYTECODE='1'))
        try:
            code = process.wait()
        except BaseException:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait()  # The builder handles SIGTERM and joins its compilers.
            raise
        require(code == 0, 'owner executor compilation failed')
    require(model == model_identity(directory, flashsim, shards) and
            sources == hashes([*files, *DEFAULT_DEPENDENCIES, *CHECKERS]) and
            compiler == compiler_identity(compiler['path']), 'owner inputs changed during executor build')
    snapshot = build / 'native-flashsim-build.json'
    built = json.loads(snapshot.read_text())
    checked_builder(built, model, kind, options, compiler)
    executable = build / 'native_product_driver'
    require(built.get('executable_sha256') == common.sha(executable), 'owner executable differs from builder')
    shutil.copy2(executable, target / 'executor')
    shutil.copyfile(snapshot, target / 'builder.json')
    common.write_json(path, dict(schema='opengpu-owner-executor-v1', version=1, kind=kind,
        **contract, executorBuildVerified=True, sourceQualificationRequired=True,
        gpuExecution=False, clockQualified=False, fpsQualified=False, source_root=str(ROOT),
        owner_output=str(directory), flashsim=str(flashsim.resolve()), shards=shards,
        source_sha256=sources, model=model, compiler=compiler, options=options,
        builder_sha256=common.sha(target / 'builder.json'), executable_sha256=common.sha(target / 'executor')))
    try:
        verify_qualification(path, executor=target / 'executor', kind=kind)
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', required=True, choices=('build', 'check'))
    parser.add_argument('--kind', required=True, choices=KINDS)
    parser.add_argument('--owner-output', type=Path)
    parser.add_argument('--flashsim-dir', type=Path)
    parser.add_argument('--qualification', type=Path)
    parser.add_argument('--executor', type=Path)
    parser.add_argument('--shards', type=int, default=32)
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--precompiled-header', action='store_true')
    parser.add_argument('--conservative-alias', action='store_true')
    parser.add_argument('--shard-opt', choices=('-O0', '-O1', '-O2', '-O3'), default='-O2')
    args = parser.parse_args()
    if args.stage == 'check':
        if args.qualification is None:
            parser.error('--stage check requires --qualification')
        verify_qualification(args.qualification, executor=args.executor, kind=args.kind)
    else:
        if args.owner_output is None or args.flashsim_dir is None:
            parser.error('--stage build requires --owner-output and --flashsim-dir')
        directory = inside(args.owner_output)
        def terminate(_signal, _frame):
            raise KeyboardInterrupt('owner executor build terminated')
        previous = signal.signal(signal.SIGTERM, terminate)
        try:
            with (directory / '.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                path = build_executor(directory, args.flashsim_dir.resolve(), args.kind,
                    shards=args.shards, jobs=args.jobs, precompiled_header=args.precompiled_header,
                    conservative_alias=args.conservative_alias, shard_opt=args.shard_opt)
        finally:
            signal.signal(signal.SIGTERM, previous)
        print('OWNER_EXECUTOR_QUALIFICATION ' + str(path), flush=True)
    print('OWNER_EXECUTOR_INPUTS_PASS kind=' + args.kind +
          ' gpuExecution=false clockQualified=false fpsQualified=false', flush=True)


if __name__ == '__main__':
    main()
