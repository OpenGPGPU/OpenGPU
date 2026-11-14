#!/usr/bin/env python3
"""Build/check fixed native or compute executors for an existing complete owner DUT."""
import argparse
from contextlib import ExitStack
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
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
from owner_resource_contract import COMPUTE_STAGE, FULL_COMPUTE_CONTRACTS, PROFILE as FULL_PROFILE, SAMPLE_PROFILE

ROOT = Path(__file__).resolve().parents[1]
KINDS = ('native', 'compute', 'native-sample', 'native-sample-graphics')
SAMPLE_KINDS = ('native-sample', 'native-sample-graphics')
SHARED = ('scripts/native_owner_runtime.hpp', 'scripts/native_owner_capture.hpp',
          'scripts/owner_factory.hpp',
          'driver/gpu_abi.h', 'driver/opengpu_formats.h', 'driver/opengpu_shader_resources.h')
DEFAULT_DEPENDENCIES = ('scripts/flashsim_native/VGpuHostSystemAxi.h',
    'scripts/flashsim_native/verilated.h', 'scripts/native_host_simulation.hpp',
    'scripts/native_execution_progress.hpp', 'scripts/native_frame_axi.hpp',
    'scripts/native_frame_fixture.hpp')
CHECKERS = ('scripts/prepare_owner_executor.py', 'scripts/build_native_flashsim.py',
    'scripts/prepare_native_owner_flashsim.py', 'scripts/qualify_native_product_flashsim.py',
    'scripts/qualify_native_product_compiled.py', 'scripts/owner_resource_contract.py',
    'scripts/compute_owner_protocol.py', 'scripts/compute_owner_controller.py',
    'scripts/compute_owner_oracle.py', 'scripts/compute_owner_cases.py',
    'scripts/qualify_compute_owner.py', 'scripts/test_gallium_compute_host.py',
    'scripts/test_gallium_compute_uniforms.py', 'scripts/test_gallium_compute_shared.py',
    'userspace/tests/compute_host_frontend.c', 'userspace/tests/cs_shared_frontend.c',
    'userspace/gallium/opengpu_cs_emit.c',
    'driver/tests/opengpu_compute_host_fixture.c')
SAMPLE_CHECKERS = (
    'scripts/qualify_native_sample_owner.py', 'scripts/native_sample_compiler_inputs.py',
    'scripts/native_sample_owner_protocol.py', 'scripts/native_sample_owner_controller.py',
    'scripts/native_sample_owner_oracle.py', 'scripts/native_sample_gny2_oracle.py',
    'scripts/native_sample_owner_recipe.py',
    'docs/abi/native-layered-graphics-v2.json', 'scripts/owner_process_lifetime.py',
    'scripts/test_native_sample_submit_bridge.py',
    'scripts/test_native_submit.py', 'scripts/test_native_mmio_capture.py',
    'scripts/test_native_sample_essl.py', 'scripts/native_fs_cpu_oracle.py',
    'userspace/gallium/opengpu_native_fs_emit.c', 'userspace/gallium/opengpu_native_fs_emit.h',
    'userspace/tests/fs_glsl_frontend.cpp', 'userspace/tests/fs_glsl_frontend.h',
    'userspace/tests/native_fs_compile.c',
    *(f'userspace/tests/native_sample_essl/{name}.frag' for name in
      ('sample', 'mask-depth', 'mask-depth-keep', 'mrt', 'varying', 'signedness', 'truncation')))
# These generated includes are fully determined by the registered fixture
# generators and original production bodies below. No missing arbitrary include
# or required consumer is silently ignored.
SAMPLE_GENERATED_INCLUDES = frozenset((
    'native_sample_expected_profile.inc', 'snapshot_submission_under_test.inc',
    'native_enqueue_under_test.inc', 'native_scheduler_under_test.inc',
    'native_admission_under_test.inc', 'native_submit_under_test.inc',
    *(f'native_mmio_{name}_under_test.inc' for name in
      ('fault', 'fence', 'fault_record', 'job_id', 'reset_pending', 'result_error',
       'drain', 'irq', 'enable_irqs', 'quiesce', 'kick', 'set_satp', 'activate_locked',
       'busy', 'wait_idle', 'activate', 'enable', 'flush', 'flush_asid', 'disable',
       'submit', 'invalidate'))))
SAMPLE_PRODUCER_ROOTS = (
    'scripts/native_sample_live_producer.c', 'scripts/native_sample_submit_producer.c',
    'driver/opengpu_native_frame.c', 'driver/opengpu_native_frame_internal.h',
    'userspace/opengpu_native_sample_graphics.c', 'userspace/opengpu_native_sample_graphics.h',
    'driver/opengpu_hw.c', 'driver/opengpu_device.h',
    'driver/opengpu_scheduler.c', 'driver/opengpu_admission.c', 'driver/opengpu_native_submit.c')
GRAPHICS_CHECKERS = (
    'scripts/qualify_native_sample_graphics_owner.py', 'scripts/native_sample_graphics_inputs.py',
    'scripts/native_sample_graphics_controller.py', 'scripts/native_sample_graphics_protocol.py',
    'scripts/native_sample_graphics_owner_oracle.py', 'scripts/native_sample_graphics_recipe.py',
    'scripts/native_sample_graphics_source_oracle.py', 'scripts/native_sample_graphics_gny2_oracle.py',
    'scripts/test_gallium_compute_uniforms.py', 'scripts/test_gallium_sample_graphics_uniforms.py',
    'scripts/test_gallium_graphics_uniforms.py', 'scripts/apply_mesa_uniform_index_patch.py',
    'scripts/patches/mesa-22.3.6-checked-uniform-index.patch',
    'docs/abi/native-layered-graphics-v2.json', 'docs/abi/native-binned-graphics-v1.json',
    'docs/abi/typed-fragment-state-v1.json')
# These are the actual GPU translation units compiled by the Mesa graphics
# builder, plus the production bodies used by its generated Linux includes.
GRAPHICS_PRODUCER_ROOTS = (
    'userspace/tests/graphics_sample_flow.c', 'userspace/tests/graphics_sample_kernel.c',
    'userspace/tests/graphics_sample_owner_ipc.c', 'userspace/tests/native_graphics_uniform_source.c',
    'userspace/tests/graphics_uniform_frontend.cpp',
    'userspace/gallium/opengpu_context.c', 'userspace/gallium/opengpu_screen.c',
    'userspace/gallium/opengpu_cs_emit.c', 'userspace/gallium/opengpu_fs_emit.c',
    'userspace/gallium/opengpu_vs_emit.c', 'userspace/pipe_opengpu.c', 'userspace/opengpu.c',
    'userspace/gallium/opengpu_native_uniform_emit.c', 'userspace/gallium/opengpu_native_graphics.c',
    'userspace/gallium/opengpu_native_draw.c', 'userspace/opengpu_native_runtime.c',
    'userspace/gallium/opengpu_native_sample_program.c',
    *SAMPLE_PRODUCER_ROOTS)
# Explicit external Mesa headers, not a wildcard exemption for missing GPU
# quoted includes. Actual Mesa headers/objects must be bound separately by a
# execution qualifier; this record qualifies the GPU checkout and executor.
GRAPHICS_MESA_INCLUDES = frozenset((
    'compiler/glsl/gl_nir.h', 'compiler/glsl/gl_nir_linker.h',
    'compiler/glsl/glsl_to_nir.h', 'compiler/glsl/standalone.h', 'compiler/nir/nir.h',
    'frontend/winsys_handle.h', 'main/mtypes.h', 'nir/nir_to_tgsi.h',
    'pipe/p_context.h', 'pipe/p_defines.h', 'pipe/p_format.h', 'pipe/p_screen.h', 'pipe/p_state.h',
    'program/prog_parameter.h', 'state_tracker/st_atom.h', 'state_tracker/st_context.h',
    'tgsi/tgsi_build.h', 'tgsi/tgsi_dump.h', 'tgsi/tgsi_parse.h', 'tgsi/tgsi_text.h', 'tgsi/tgsi_ureg.h',
    'util/u_inlines.h', 'util/u_memory.h', 'util/u_pack_color.h', 'util/u_surface.h',
    'util/u_transfer.h', 'util/u_upload_mgr.h'))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def profile_contract(profile, kind):
    require(kind in KINDS, 'unknown owner executor kind')
    require((profile == SAMPLE_PROFILE if kind in SAMPLE_KINDS else
             profile == owner.PROFILE or ((profile == COMPUTE_STAGE or profile in FULL_COMPUTE_CONTRACTS) and kind == 'compute')),
            'unsupported factory for the selected owner executor kind')
    fields = dict(profile=profile)
    if profile == COMPUTE_STAGE:
        fields.update(ownerResourceVersion=2, ownerResourceStage='compute', fullResourceQualification=False)
    elif profile in FULL_COMPUTE_CONTRACTS:
        fields.update(ownerResourceVersion=2, ownerResourceStage=FULL_COMPUTE_CONTRACTS[profile][0], fullResourceQualification=False)
    return fields


def driver_files(kind, profile=owner.PROFILE):
    require(kind in KINDS, 'unknown owner executor kind')
    profile_contract(profile, kind)
    if kind == 'native-sample-graphics':
        return ['scripts/native_sample_graphics_driver.cpp', 'scripts/native_sample_graphics_runtime.hpp',
                'scripts/native_sample_owner_runtime.hpp', 'scripts/native_sample_owner_capture.hpp', *SHARED]
    if kind == 'native-sample':
        return ['scripts/native_sample_owner_driver.cpp', 'scripts/native_sample_owner_runtime.hpp',
                'scripts/native_sample_owner_capture.hpp', *SHARED]
    if profile == COMPUTE_STAGE or profile in FULL_COMPUTE_CONTRACTS:
        wrapper = {COMPUTE_STAGE: 'compute_stage', FULL_PROFILE: 'geometry_compute',
                   SAMPLE_PROFILE: 'geometry_compute_sample'}[profile]
        return [f'scripts/{wrapper}_owner_driver.cpp', 'scripts/compute_owner_driver.cpp',
                *SHARED, 'scripts/compute_owner_capture.hpp']
    return ([f'scripts/{kind}_owner_driver.cpp', *SHARED] +
            (['scripts/compute_owner_capture.hpp'] if kind == 'compute' else []))


def sample_producer_files():
    """Bind actual C producer/include closure and extracted production bodies."""
    search = [ROOT / name for name in ('driver', 'driver/tests',
        'driver/tests/private_snapshot_stubs', 'driver/tests/mmu_stubs',
        'driver/tests/compute_submit_stubs')]
    pending, observed = [ROOT / name for name in SAMPLE_PRODUCER_ROOTS], set()
    while pending:
        path = pending.pop().resolve(strict=True)
        require(path.is_relative_to(ROOT), 'native sample source escapes its checkout')
        if path in observed:
            continue
        observed.add(path)
        for match in builder.INCLUDE.finditer(path.read_bytes()):
            directive = re.sub(rb'^[ \t]*#[ \t]*include[ \t]*', b'', match[0]).strip()
            directive = directive.split(b'//', 1)[0].strip()
            parsed = re.fullmatch(rb'([<"])([^>"]+)[>"]', directive)
            require(parsed is not None, 'unsupported native sample producer include')
            name = os.fsdecode(parsed[2])
            candidates = ([path.parent] if parsed[1] == b'"' else []) + search
            actual = next(((directory / name).resolve() for directory in candidates
                           if (directory / name).is_file()), None)
            if actual is not None:
                pending.append(actual)
            elif parsed[1] == b'"':
                require(name in SAMPLE_GENERATED_INCLUDES,
                        'missing native sample producer dependency: ' + name)
    return tuple(sorted(str(path.relative_to(ROOT)) for path in observed))


def graphics_producer_files():
    """Conservatively bind all GPU include candidates in actual build paths.

    The flow and kernel units use different include-path orders. Recording
    their union also binds conditional includes and every possible local
    shadow; only explicitly named generated bodies and Mesa headers may be
    absent. Generated bodies are bound through their original production
    files and the mandatory extraction scripts in SAMPLE_CHECKERS.
    """
    search = [ROOT / name for name in ('userspace/tests', 'userspace/gallium', 'userspace',
        'driver', 'driver/tests', 'driver/tests/private_snapshot_stubs',
        'driver/tests/mmu_stubs', 'driver/tests/compute_submit_stubs')]
    pending, observed = [ROOT / name for name in GRAPHICS_PRODUCER_ROOTS], set()
    while pending:
        path = pending.pop()
        require(path.is_file(), 'missing native sample graphics producer: ' + str(path))
        require(path.resolve(strict=True) == path and path.is_relative_to(ROOT),
                'aliased native sample graphics producer: ' + str(path))
        if path in observed:
            continue
        observed.add(path)
        for match in builder.INCLUDE.finditer(path.read_bytes()):
            directive = re.sub(rb'^[ \t]*#[ \t]*include[ \t]*', b'', match[0]).strip()
            directive = directive.split(b'//', 1)[0].strip()
            directive = re.sub(rb'/\*.*?\*/', b'', directive).strip()
            parsed = re.fullmatch(rb'([<"])([^>"]+)[>"]', directive)
            require(parsed is not None,
                    'unsupported native sample graphics producer include in ' + str(path) + ': ' + repr(directive))
            name = os.fsdecode(parsed[2])
            require(not Path(name).is_absolute(),
                    'escaping native sample graphics include: ' + name)
            candidates = ([path.parent] if parsed[1] == b'"' else []) + search
            actual = {Path(os.path.abspath(directory / name)) for directory in candidates
                      if (directory / name).exists() or (directory / name).is_symlink()}
            if actual:
                pending.extend(actual)
            elif parsed[1] == b'"':
                require(name in SAMPLE_GENERATED_INCLUDES or name in GRAPHICS_MESA_INCLUDES,
                        'missing native sample graphics producer dependency: ' + name)
    return tuple(sorted(str(path.relative_to(ROOT)) for path in observed))


def checker_files(kind):
    require(kind in KINDS, 'unknown owner executor kind')
    if kind not in SAMPLE_KINDS:
        return CHECKERS
    # Every fixed consumer is mandatory even before it has a valid build. A
    # partially integrated checkout cannot qualify by omitting absent files.
    mandatory = (*SAMPLE_CHECKERS, *(GRAPHICS_CHECKERS if kind == 'native-sample-graphics' else ()))
    for name in mandatory:
        path = ROOT / name
        require(path.is_file(), 'missing required native sample checker: ' + name)
        require(path.resolve() == path, 'aliased native sample checker source: ' + name)
    producer = graphics_producer_files if kind == 'native-sample-graphics' else sample_producer_files
    return tuple(dict.fromkeys((*CHECKERS, *mandatory, *producer())))


def build_lock_paths(directory, kind):
    require(kind in KINDS, 'unknown owner executor kind')
    paths = [directory / '.lock']  # Existing common shard cache stays serialized.
    if kind in SAMPLE_KINDS:
        target = directory / 'executors' / kind
        require(target.resolve() == target, 'native sample executor lock must not be aliased')
        target.mkdir(parents=True, exist_ok=True)
        paths.append(target / '.lock')
        require(all(not path.is_symlink() for path in paths), 'native sample build lock must not be aliased')
    return tuple(paths)


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
    if kind in SAMPLE_KINDS:
        builder.validate_driver_includes([ROOT / name for name in
            [files[0], *DEFAULT_DEPENDENCIES, *files[1:]]],
            Path(model['dut_directory']) / 'dut.h', Path(model['dut_directory']))
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
    require(record.get('source_sha256') == hashes([*files, *DEFAULT_DEPENDENCIES, *checker_files(kind)]),
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
    checkers = checker_files(kind)
    sources = hashes([*files, *DEFAULT_DEPENDENCIES, *checkers])
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
            sources == hashes([*files, *DEFAULT_DEPENDENCIES, *checker_files(kind)]) and
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
            with ExitStack() as stack:
                for lock_path in build_lock_paths(directory, args.kind):
                    lock = stack.enter_context(lock_path.open('a'))
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
