#!/usr/bin/env python3
"""Original owner executors linked to the complete, assertion-enabled Verilator model."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

import build_owner_verilator as native
import generate_owner_verilator as generator
import prepare_owner_executor as owner
from compute_owner_protocol import strict_json
from owner_resource_contract import SAMPLE_PROFILE, IMAGE_FLOAT_PROFILE
from run_owner_bounded import run_bounded

ROOT = owner.ROOT
KINDS = ('native-sample', 'native-sample-graphics', 'compute', owner.BLIT_KIND, owner.INDIRECT_KIND)
DEPENDENCIES = tuple(name for name in owner.DEFAULT_DEPENDENCIES
                     if not name.startswith('scripts/flashsim_native/')) + (
                         'scripts/native_verilator_initial_time.hpp',)
CHECKERS = ('scripts/prepare_owner_verilator_executor.py', 'scripts/generate_owner_verilator.py',
            'scripts/build_owner_verilator.py', 'scripts/run_owner_bounded.py',
            'scripts/owner_process_memory.py', 'scripts/owner_process_lifetime.py',
            'scripts/qualification/check_owner_verilator_clock.py')
require = owner.require
CACHE_MODEL_HEADERS = ('VGpuHostSystemAxi___024root.h',
    'VGpuComputeUnit/VGpuComputeUnit.h', 'VGpuComputeUnit/VGpuComputeUnit___024root.h')


def regular(path):
    require(path.is_file() and not path.is_symlink() and path.resolve() == path,
            'Missing or aliased compiler input: ' + str(path))
    return path


def cache_model_headers(model, kind, profile=SAMPLE_PROFILE):
    return tuple(regular(model/name) for name in CACHE_MODEL_HEADERS) if kind=='compute' and profile==IMAGE_FLOAT_PROFILE else ()


def driver_inputs(kind, model, kit, profile=SAMPLE_PROFILE):
    """Reject undeclared GPU includes, adapter substitution and standard-header shadows."""
    require(kind in KINDS, 'Unsupported complete owner Verilator executor kind')
    files = owner.driver_files(kind, profile)
    declared = {ROOT / name for name in (*files, *DEPENDENCIES)}
    expected = {path.name: path for path in declared}
    require(len(expected) == len(declared), 'Ambiguous original driver dependency names')
    expected.update({'VGpuHostSystemAxi.h': model / 'VGpuHostSystemAxi.h',
                     'verilated.h': kit / 'include/verilated.h'})
    search = (ROOT / 'scripts', model, kit / 'include', kit / 'include/vltstd')
    for path in declared:
        regular(path)
        for match in owner.builder.INCLUDE.finditer(path.read_bytes()):
            directive = re.sub(rb'^[ \t]*#[ \t]*include[ \t]*', b'', match[0]).strip()
            directive = directive.split(b'//', 1)[0].strip()
            parsed = re.fullmatch(rb'([<"])([^>"]+)[>"]', directive)
            require(parsed is not None, 'Unsupported original driver include: ' + str(path))
            name = os.fsdecode(parsed[2])
            require(not Path(name).is_absolute(), 'Absolute original driver include')
            if parsed[1] == b'"':
                candidates = (path.parent, *search)
                actual = next(((directory / name).resolve() for directory in candidates
                               if (directory / name).is_file()), None)
                canonical = expected.get(Path(name).name)
                require(canonical is not None and actual == canonical.resolve(),
                        'Undeclared or shadowed original driver include: ' + name)
            else:
                require(not any((directory / name).exists() for directory in search),
                        'Local file shadows a standard driver include: ' + name)
    return tuple(sorted(str(path.relative_to(ROOT)) for path in declared))


def context(directory, kind):
    built = native.checked_build(directory)
    generated = generator.checked_generation(directory)
    profile = generated['context']['profile']
    owner.profile_contract(profile, kind)
    target = generator.output(directory)
    model, kit = target / 'model', Path(generated['context']['verilator_root'])
    files = driver_inputs(kind, model, kit, profile)
    cache_headers=cache_model_headers(model,kind,profile)
    sources = owner.hashes((*files, *owner.checker_files(kind), *CHECKERS))
    flags = ['-std=c++17', '-O1', '-g0', '-faligned-new', '-pthread']
    if 'clang' in built['context']['compiler']['version'].lower():
        flags += ['-fbracket-depth=4096']
    for name in ('VM_COVERAGE', 'VM_SC', 'VM_TIMING', 'VM_TRACE', 'VM_TRACE_FST',
                 'VM_TRACE_VCD', 'VM_TRACE_SAIF', 'VM_VPI'):
        flags += ['-D' + name + '=0']
    for path in (ROOT / 'scripts', model, kit / 'include', kit / 'include/vltstd'):
        flags += ['-I', str(path)]
    sdk = built['context']['sdk']
    if sdk is not None:
        flags += ['-isysroot', sdk['path']]
    flags += ['-include', str(ROOT / 'scripts/native_verilator_initial_time.hpp')]
    if cache_headers:
        flags+=['-DOPENGPU_NATIVE_CACHE_STATE=1']
        for path in cache_headers:flags+=['-include',str(path)]
    linkers = {}
    if sys.platform == 'darwin':
        # Apple's newer linker rejects compact-unwind offsets across these
        # large generated functions. The installed classic linker preserves
        # normal C++ exception handling without that 24-bit layout failure.
        for name in ('ld', 'ld-classic'):
            path = Path(subprocess.check_output(['xcrun', '--find', name], text=True).strip()).resolve(strict=True)
            linkers[name] = dict(path=str(path), sha256=owner.common.sha(path))
    return dict(kind=kind, source_sha256=sources, compiler=built['context']['compiler'], flags=flags,
        cache_observer_headers={str(path):owner.common.sha(path) for path in cache_headers},
        linkers=linkers,
        driver=str(ROOT / owner.driver_files(kind, profile)[0]),
        model=dict(backend='verilator', profile=profile, dut_directory=str(model),
            preparation_sha256=owner.common.sha(directory / 'preparation.json'),
            profile_manifest_sha256=owner.common.sha(directory / 'owner-manifest.json'),
            generation_sha256=owner.common.sha(target / 'generation.json'),
            build_sha256=owner.common.sha(target / 'build.json')),
        runtime_objects=built['runtime_objects'])


def commands(bound, target):
    compiler = bound['compiler']['path']
    compile_command = [compiler, *bound['flags'], '-MD', '-MF', str(target / 'driver.d'),
                       '-c', bound['driver'], '-o', str(target / 'driver.o')]
    model = Path(bound['model']['dut_directory'])
    link_flags = ['-Wl,-ld_classic'] if bound['linkers'] else []
    link = [compiler, *bound['flags'], *link_flags, str(target / 'driver.o'),
            *(str(model / name) for name in bound['runtime_objects']),
            str(model / 'VGpuHostSystemAxi__ALL.a'), '-o', str(target / 'executor')]
    return compile_command, link


def dependency_hashes(path):
    text = path.read_text().replace('\\\n', ' ')
    require(': ' in text, 'Missing actual compiler dependency list')
    paths = {Path(name).resolve(strict=True) for name in shlex.split(text.split(': ', 1)[1])}
    return {str(path): owner.common.sha(regular(path)) for path in sorted(paths)}


def check_dependencies(dependencies, bound):
    require(type(dependencies) is dict and dependencies, 'Missing actual compiler input identities')
    for name, digest in dependencies.items():
        require(type(name) is str and Path(name).is_absolute() and
                Path(name).resolve() == Path(name) and owner.common.sha(regular(Path(name))) == digest,
                'Actual compiler header/source changed: ' + str(name))
    model = Path(bound['model']['dut_directory'])
    generated = generator.checked_generation(Path(model).parents[1])
    kit = Path(generated['context']['verilator_root'])
    for path in (Path(bound['driver']), model / 'VGpuHostSystemAxi.h', kit / 'include/verilated.h',
                 ROOT / 'scripts/native_verilator_initial_time.hpp',
                 *cache_model_headers(model,bound['kind'],bound['model']['profile'])):
        require(str(path.resolve()) in dependencies, 'Compiler omitted a required actual driver/model header')
    declared = {str((ROOT / name).resolve()) for name in driver_inputs(bound['kind'], model, kit, bound['model']['profile'])}
    for name in dependencies:
        path = Path(name)
        if (path.is_relative_to(ROOT) and not path.is_relative_to(model)
                and not path.is_relative_to(kit)):
            require(name in declared, 'Actual compiler consumed an undeclared GPU driver dependency: ' + name)


def execute(log, command, memory_mib, timeout, *, expected_exit=0):
    print('Owner stage: ' + log.stem, flush=True)
    with log.open('w') as stream:
        sys.stdout.flush(); sys.stderr.flush()
        saved = os.dup(1), os.dup(2)
        try:
            os.dup2(stream.fileno(), 1); os.dup2(stream.fileno(), 2)
            code = run_bounded(command, memory_mib, timeout)
        finally:
            sys.stdout.flush(); sys.stderr.flush()
            os.dup2(saved[0], 1); os.dup2(saved[1], 2)
            for fd in saved:
                os.close(fd)
    text = log.read_text(errors='replace')
    require(code == expected_exit, 'Original owner stage failed:\n' + text[-6000:])
    print('\n'.join(text.splitlines()[-1:]), flush=True)
    log.unlink()


def verify_qualification(path, record, *, executor=None, kind):
    require(record == strict_json(path), 'Original qualification JSON changed during validation')
    directory = owner.inside(record['owner_output'])
    contract = owner.profile_contract(record.get('profile'), kind)
    require(record.get('backend') == 'verilator' and kind in KINDS and
            all(type(record.get(k)) is type(v) and record[k] == v for k, v in contract.items()),
            'Wrong original Verilator executor factory/kind/scope')
    target = directory / 'executors' / kind
    require(record.get('source_root') == str(ROOT) and path == target / 'qualification.json',
            'Original Verilator qualification moved to another checkout')
    binary = regular(target / 'executor')
    require(os.access(binary, os.X_OK), 'Original Verilator executor is not executable')
    if executor is not None:
        require(Path(executor).absolute() == binary and not Path(executor).is_symlink(),
                'Controller selected another Verilator executor')
    bound = context(directory, kind)
    require(record.get('profile') == bound['model']['profile'], 'Original executor profile differs from its complete model')
    require(all(record.get(name) == bound[name] for name in ('source_sha256', 'model', 'compiler')),
            'Original owner source, checker, compiler or complete model changed')
    snapshot = regular(target / 'builder.json')
    require(record.get('builder_sha256') == owner.common.sha(snapshot), 'Original executor build identity changed')
    built = strict_json(snapshot)
    compile_command, link_command = commands(bound, target)
    require(built.get('schema') == 'opengpu-owner-verilator-executor-build-v1'
            and built.get('context') == bound and built.get('compile_command') == compile_command
            and built.get('link_command') == link_command, 'Original executor native build inputs changed')
    check_dependencies(built.get('dependencies'), bound)
    require(record.get('executable_sha256') == owner.common.sha(binary) == built.get('executable_sha256'),
            'Original executor binary changed or belongs to another build')
    return record


def build_executor(directory, kind):
    directory = owner.inside(directory)
    target = directory / 'executors' / kind
    target.mkdir(parents=True, exist_ok=True)
    require(target.resolve() == target and all(not (target / name).exists() for name in
            ('executor', 'qualification.json', 'builder.json', 'driver.o', 'driver.d')),
            'Existing original executor outputs require verification or explicit diagnosis')
    bound = context(directory, kind)
    compile_command, link_command = commands(bound, target)
    execute(target / 'compile-driver.log', compile_command, 8192, 600)
    dependencies = dependency_hashes(target / 'driver.d')
    check_dependencies(dependencies, bound)
    require(context(directory, kind) == bound, 'Owner inputs changed during driver compilation')
    execute(target / 'link-driver.log', link_command, 8192, 600)
    require(context(directory, kind) == bound, 'Owner inputs changed during native link')
    executable_sha256 = owner.common.sha(regular(target / 'executor'))
    owner.common.write_json(target / 'builder.json', dict(
        schema='opengpu-owner-verilator-executor-build-v1', context=bound,
        compile_command=compile_command, link_command=link_command,
        dependencies=dependencies, executable_sha256=executable_sha256))
    path = target / 'qualification.json'
    owner.common.write_json(path, dict(schema='opengpu-owner-executor-v1', version=1,
        backend='verilator', kind=kind, **owner.profile_contract(bound['model']['profile'], kind),
        executorBuildVerified=True, sourceQualificationRequired=True, gpuExecution=False,
        clockQualified=False, fpsQualified=False, source_root=str(ROOT), owner_output=str(directory),
        source_sha256=bound['source_sha256'], model=bound['model'], compiler=bound['compiler'],
        builder_sha256=owner.common.sha(target / 'builder.json'), executable_sha256=executable_sha256))
    try:
        owner.verify_qualification(path, executor=target / 'executor', kind=kind)
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    (target / 'driver.o').unlink()
    (target / 'driver.d').unlink()
    return path
