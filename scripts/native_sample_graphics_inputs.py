"""Original Mesa/compiler and actual LIVE graphics build input identities.

Temporary build receipts bind generated inputs before compilation. They are
never a device qualification, instruction interpreter or execution receipt.
"""
import argparse
import json
import os
from pathlib import Path
import runpy
import shlex
import shutil
import struct
import subprocess
import sys

from apply_mesa_uniform_index_patch import AFTER as LOWERING_SHA
from native_sample_compiler_inputs import (MESA_LIBRARIES, MESA_UNITS,
    sha256, snapshot as mesa_snapshot)
from native_sample_owner_protocol import strict_json

ROOT = Path(__file__).resolve().parents[1]
LIVE_FLAG = '-DOPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=1'
SANITIZERS = ['-fsanitize=address,undefined','-fno-omit-frame-pointer']
GPU_UNITS = (
    'userspace/tests/graphics_sample_kernel.c', 'userspace/tests/native_graphics_uniform_source.c',
    'userspace/tests/graphics_sample_owner_ipc.c', 'userspace/tests/graphics_sample_flow.c',
    'userspace/gallium/opengpu_context.c', 'userspace/gallium/opengpu_screen.c',
    'userspace/gallium/opengpu_cs_emit.c', 'userspace/gallium/opengpu_fs_emit.c',
    'userspace/gallium/opengpu_vs_emit.c', 'userspace/pipe_opengpu.c', 'userspace/opengpu.c',
    'userspace/gallium/opengpu_native_uniform_emit.c', 'userspace/gallium/opengpu_native_graphics.c',
    'userspace/gallium/opengpu_native_draw.c', 'userspace/opengpu_native_runtime.c',
    'driver/opengpu_native_frame.c', 'userspace/gallium/opengpu_native_sample_program.c',
    'userspace/opengpu_native_sample_graphics.c', 'userspace/tests/graphics_uniform_frontend.cpp')
LIVE_UNITS = frozenset(('graphics_sample_kernel.c', 'graphics_sample_flow.c'))
SCHEMAS = ('docs/abi/native-layered-graphics-v2.json', 'docs/abi/native-binned-graphics-v1.json',
           'docs/abi/typed-fragment-state-v1.json')
BUILD_SCHEMA = 'opengpu-native-sample-graphics-build-inputs-v1'


def require(ok, message):
    if not ok: raise ValueError('ordinary112 inputs: '+message)


def regular(path):
    require(path.is_file() and not path.is_symlink(), 'missing/aliased input: '+str(path))
    return path


def drm_inputs(root, gpu):
    """Bind SDK directory aliases as resolutions, not permissive exclusions.

    Installed libdrm normally exposes include/drm -> libdrm/drm. Preserve that
    actual alias identity and every reachable file; reject cycles and escapes.
    The old compiler-tree policy remains unchanged for Mesa and LD inputs.
    """
    require(root.is_dir() and not root.is_symlink(),'missing/aliased DRM include root')
    result = {}
    def visit(directory, ancestors):
        resolved = directory.resolve(strict=True)
        require(resolved.is_relative_to(gpu) and resolved not in ancestors,'DRM include escape/cycle')
        if directory.is_symlink():result[str(directory)] = [str(resolved),'directory']
        for path in sorted(directory.iterdir()):
            target = path.resolve(strict=True)
            require(target.is_relative_to(gpu),'DRM include escaped GPU workspace')
            if target.is_dir():visit(path,(*ancestors,resolved))
            else:
                require(target.is_file(),'invalid DRM include input')
                identity = [str(target),sha256(target)]
                result[str(path)] = identity
                result[str(target)] = identity
    visit(root,())
    return result


def snapshot(mesa, build, libraries, drm, gpu):
    result = mesa_snapshot(mesa, build, libraries, gpu)
    paths = [build/'src/mesa/libmesa.a.p'/('state_tracker_st_atom_'+s+'.c.o')
             for s in ('constbuf', 'storagebuf')]
    for path in paths:
        resolved = path.resolve(strict=True)
        require(resolved.is_file() and resolved.is_relative_to(gpu), 'external input escaped GPU workspace')
        result[str(path)] = [str(resolved), sha256(resolved)]
    result.update(drm_inputs(drm,gpu))
    require(sha256(mesa/'src/compiler/nir/nir_lower_io.c') == LOWERING_SHA,
            'stage the exact checked Mesa lowering postimage before qualification')
    # JSON round-trips must not change tuple/list identity at the build boundary.
    return {path:list(value) for path,value in result.items()}


def unchanged(paths):
    require(type(paths) is dict and paths, 'missing input identities')
    for name, digest in paths.items():
        require(type(name) is str and type(digest) is str and
                sha256(regular(Path(name))) == digest, 'changed bound input: '+str(name))


def generated_inputs(cpu):
    return {str(regular(p)):sha256(p) for p in cpu.rglob('*')
            if p.is_file() and p.suffix in ('.c', '.cpp', '.h', '.inc')}


def dependency_inputs(path, *, cpu, source, bound, qualified, generated):
    text = regular(path).read_text().replace('\\\n', ' ')
    require(':' in text, 'malformed actual make dependencies')
    observed = set()
    for name in shlex.split(text.split(':', 1)[1]):
        item = Path(name)
        require(item.is_absolute(), 'relative actual compiler dependency')
        resolved = item.resolve(strict=True)
        if resolved.is_relative_to(cpu):
            require(generated.get(str(resolved)) == sha256(regular(resolved)),
                    'generated compiler input was not bound before compilation: '+str(item))
        elif resolved.is_relative_to(source):
            name = str(resolved.relative_to(source))
            require(qualified.get(name) == sha256(regular(resolved)),
                    'actual GPU compiler dependency is not qualified: '+name)
        else:
            require(bound.get(str(item), bound.get(str(resolved))) == [str(resolved),sha256(resolved)],
                    'unbound/changed actual Mesa/DRM/compiler input: '+str(item))
        observed.add(str(resolved))
    require(observed, 'empty actual make dependencies')
    return observed


def check_build(cpu, source, *, mesa, mesa_build, libraries, bound, qualified, generated):
    """Check the actual command log and exact 28-unit MMD closure."""
    producer = cpu/'producer'
    commands = strict_json(regular(producer/'commands.json'))
    require(type(commands) is list and len(commands) == 29, 'exact 28 compiles plus one link required')
    expected = {str(source/name) for name in GPU_UNITS}
    expected.update(str(mesa/'src'/name) for name in MESA_UNITS[1:])
    expected.update((str(producer/'standalone-profile.cpp'), str(cpu/'checked-nir-lower-io.c')))
    seen, dependencies = set(), set()
    for command in commands[:-1]:
        require(type(command) is dict and command.get('cwd') == str(producer), 'wrong build command directory')
        argv = command.get('argv')
        require(type(argv) is list and all(type(a) is str for a in argv) and
                all(argv.count(a) == 1 for a in ('-c','-o','-MMD','-MF')), 'actual compile lacks strict MMD/output')
        unit = argv[argv.index('-c')+1]
        require(unit in expected and unit not in seen, 'unexpected/repeated actual compiler translation unit')
        seen.add(unit)
        live = [a for a in argv if 'OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE' in a]
        require(live == ([LIVE_FLAG] if Path(unit).name in LIVE_UNITS else []), 'wrong LIVE compile-unit gate')
        require('-fsanitize=address,undefined' in argv, 'actual graphics compiler unit is not sanitized')
        dep = Path(argv[argv.index('-MF')+1])
        obj = Path(argv[argv.index('-o')+1])
        require(dep.parent == producer and obj.parent == producer and
                dep.suffix == '.d' and obj.suffix == '.o' and dep == obj.with_suffix('.d'),
                'compiler output/dependency escaped private build')
        require(dep not in dependencies, 'reused actual compiler dependency unit')
        dependencies.add(dep)
        dependency_inputs(dep,cpu=cpu,source=source,bound=bound,qualified=qualified,generated=generated)
    require(seen == expected and dependencies == set(cpu.rglob('*.d')), 'incomplete/extraneous actual 28-unit closure')
    link = commands[-1]
    require(type(link) is dict, 'missing actual link command')
    argv = link.get('argv')
    objects = [c['argv'][c['argv'].index('-o')+1] for c in commands[:-1]]
    objects += [str(mesa_build/'src/mesa/libmesa.a.p'/('state_tracker_st_atom_'+s+'.c.o'))
                for s in ('constbuf','storagebuf')]
    expected_link = ['c++',*SANITIZERS,'-Wl,--gc-sections','-Wl,-t,-t',
        '-Wl,-Map='+str(producer/'link.map'),*objects,'-Wl,--start-group',
        *(str(mesa_build/name) for name in MESA_LIBRARIES),'-Wl,--end-group',
        '-l:libz.so.1',str(libraries/'libexpat.so.1.6.7'),'-ldl','-lm','-pthread',
        '-o',str(producer/'cs_uniform_flow')]
    require(link.get('label') == 'link' and link.get('cwd') == str(producer) and
            argv == expected_link, 'actual link differs from its exact original-library/object recipe')
    unchanged(generated)
    require(sha256(regular(cpu/'checked-nir-lower-io.c')) == LOWERING_SHA, 'private lowering changed')
    return dependencies


def compiled_outputs(cpu):
    producer = cpu/'producer';binary = regular(producer/'cs_uniform_flow')
    require(os.access(binary,os.X_OK), 'actual Linux LIVE producer is not executable')
    with binary.open('rb') as stream: header = stream.read(64)
    require(header[:6] == b'\x7fELF\x02\x01' and len(header) == 64 and
            struct.unpack_from('<H',header,18)[0] == 183, 'actual AArch64 Linux producer required')
    for name in ('result.json','actual-acceptance.log','glsl/ready.json','glsl/union.memory'):
        require(not (producer/name).exists(), 'prepare-only build unexpectedly executed a producer')
    paths = [binary, regular(producer/'glsl/full.vert'), regular(producer/'glsl/full.frag'),
             regular(producer/'commands.json'),regular(cpu/'build-inputs.json')]
    paths.extend(regular(p) for p in cpu.rglob('*.d'))
    return {str(path):sha256(path) for path in paths}


def guarded_build(request_path):
    """Inside the immutable build container; no producer is ever started."""
    request = strict_json(regular(request_path))
    cpu, source, mesa, build, libraries, drm, gpu = (Path(request[k]) for k in
        ('cpu','source','mesa','mesa_build','libraries','drm','gpu'))
    require(source == ROOT, 'build helper must run from the checked source archive')
    bound, qualified = request['bound'], request['qualified']
    require(snapshot(mesa,build,libraries,drm,gpu) == bound, 'compiler changed before actual build')
    lowering = cpu/'checked-nir-lower-io.c'
    require(not lowering.exists(), 'private lowering output already exists')
    shutil.copyfile(mesa/'src/compiler/nir/nir_lower_io.c',lowering)
    require(sha256(lowering) == LOWERING_SHA, 'private checked lowering postimage required')
    generated = generated_inputs(cpu)
    actual_run = subprocess.run
    def compile_only(argv,*args,**kwargs):
        argv = list(map(str,argv))
        require(Path(argv[0]).name in ('cc','c++') and '-o' in argv,
                'prepare-only build attempted to run a producer')
        fresh = generated_inputs(cpu)
        unchanged(generated);generated.update(fresh)
        result = actual_run(argv,*args,**kwargs)
        if '-c' in argv:
            dependency_inputs(Path(argv[argv.index('-MF')+1]),cpu=cpu,source=source,
                              bound=bound,qualified=qualified,generated=generated)
        return result
    try:
        subprocess.run = compile_only
        sys.argv = [str(source/'scripts/test_gallium_sample_graphics_uniforms.py'),
            '--build',str(cpu/'producer'),'--sanitize','--native-kernel-root',str(source),
            '--mesa-nir-lower-io',str(lowering),'--prepare-live-producer']
        runpy.run_path(sys.argv[0],run_name='__main__')
    finally:
        subprocess.run = actual_run
    require(snapshot(mesa,build,libraries,drm,gpu) == bound, 'actual compiler changed during build')
    check_build(cpu,source,mesa=mesa,mesa_build=build,libraries=libraries,
                bound=bound,qualified=qualified,generated=generated)
    (cpu/'build-inputs.json').write_text(json.dumps(dict(schema=BUILD_SCHEMA,execution=False,generated=generated))+'\n')
    compiled_outputs(cpu)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-request',type=Path,required=True)
    guarded_build(parser.parse_args().build_request)
