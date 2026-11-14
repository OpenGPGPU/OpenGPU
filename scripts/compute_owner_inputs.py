"""Frozen actual counter producer/compiler inputs; no instruction execution."""
import argparse
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
from native_sample_graphics_inputs import (snapshot as base_snapshot, dependency_inputs,
    generated_inputs, unchanged, regular)
from native_sample_compiler_inputs import sha256, MESA_UNITS, MESA_LIBRARIES
from compute_owner_protocol import strict_json
from compute_owner_cases import COUNTER_CASES, counter_parameters

ROOT = Path(__file__).resolve().parents[1]


def require(ok, message):
    if not ok: raise ValueError('counter inputs: '+message)


def snapshot(mesa, build, libraries, drm, gpu):
    bound=base_snapshot(mesa,build,libraries,drm,gpu)
    path=build/'src/mesa/libmesa.a.p/state_tracker_st_atom_atomicbuf.c.o'
    resolved=path.resolve(strict=True)
    require(resolved.is_relative_to(gpu) and resolved.is_file(), 'original atomic buffer object missing/escaped')
    bound[str(path)]=[str(resolved),sha256(resolved)]
    return bound


def guarded(request_path, builder_argv):
    request=strict_json(regular(request_path))
    require(set(request)=={'bound','qualified','mesa','build','libraries','drm','gpu','ntt'}, 'wrong bound input record')
    paths={k:Path(request[k]) for k in ('mesa','build','libraries','drm','gpu','ntt')}
    bound,qualified=request['bound'],request['qualified']
    def external():
        require(snapshot(*(paths[k] for k in ('mesa','build','libraries','drm','gpu')))==bound,
                'original Mesa/atomic object/DRM/libraries changed')
    external()
    from stage_mesa_shared_memory import AFTER
    require(sha256(regular(paths['ntt']))==AFTER, 'checked NTT postimage required')
    dependencies=dict(bound)
    dependencies[str(paths['ntt'])]=[str(paths['ntt']),AFTER]
    argv=list(builder_argv)
    require('--case' in argv and argv[argv.index('--case')+1] in COUNTER_CASES and '--sanitize' in argv,
            'strict original counter source and sanitized producer required')
    case=argv[argv.index('--case')+1]
    matrix='--capture-counter-matrix' in argv
    require(not matrix or '--capture-only' in argv, 'counter matrix is CPU capture only')
    launched=set()
    expected_cases=set(COUNTER_CASES if matrix else (case,))
    out=Path(argv[argv.index('--build')+1]).absolute()
    generated={};seen=set();compiled=False
    # These are all actual host translation units, not the CPU VM fixture.
    units={str(ROOT/name) for name in (
        'driver/tests/opengpu_compute_host_fixture.c', 'userspace/tests/compute_host_frontend.c',
        'userspace/tests/compute_host_gallium_stubs.c', 'userspace/tests/cs_graphics_unused_stubs.c',
        'userspace/tests/cs_glsl_frontend.cpp', 'userspace/gallium/opengpu_context.c',
        'userspace/gallium/opengpu_screen.c', 'userspace/gallium/opengpu_cs_emit.c',
        'userspace/gallium/opengpu_fs_emit.c', 'userspace/gallium/opengpu_vs_emit.c',
        'userspace/pipe_opengpu.c', 'userspace/opengpu.c')}
    units.update(str(paths['mesa']/'src'/name) for name in MESA_UNITS[1:])
    units.update((str(out/'standalone-profile.cpp'),str(paths['mesa']/'src/compiler/nir/nir_lower_io.c'),str(paths['ntt'])))
    actual_run=subprocess.run
    products={};objects=[]
    libraries=[str(paths['build']/name) for name in MESA_LIBRARIES]
    libraries[4]=str(out/'libgallium.a')
    def expected_link():
        trackers=[str(paths['build']/'src/mesa/libmesa.a.p'/('state_tracker_st_atom_'+n+'.c.o'))
                  for n in ('constbuf','storagebuf','atomicbuf')]
        return ['c++','-fsanitize=address,undefined','-fno-omit-frame-pointer',
            '-Wl,--gc-sections','-Wl,-t,-t','-Wl,-Map='+str(out/'link.map'),*objects,*trackers,
            '-Wl,--start-group',*libraries,'-Wl,--end-group','-l:libz.so.1',
            str(paths['libraries']/'libexpat.so.1.6.7'),'-ldl','-lm','-pthread','-o',str(out/'compute_host_frontend')]

    def run(command,*args,**kwargs):
        nonlocal compiled
        command=list(map(str,command))
        if '-c' in command:
            require(all(command.count(flag)==1 for flag in ('-c','-o','-MMD','-MF')), 'actual host compile needs complete MMD')
            unit=command[command.index('-c')+1]
            require(unit in units and unit not in seen and '-fsanitize=address,undefined' in command,
                    'unexpected/repeated/unsanitized producer translation unit')
            seen.add(unit)
            if unit != str(paths['ntt']):objects.append(command[command.index('-o')+1])
            fresh=generated_inputs(out)
            if generated:unchanged(generated)
            generated.update(fresh)
            result=actual_run(command,*args,**kwargs)
            dependency_inputs(Path(command[command.index('-MF')+1]),cpu=out,source=ROOT,
                bound=dependencies,qualified=qualified,generated=generated)
            return result
        if '-o' in command and command[command.index('-o')+1] == str(out/'compute_host_frontend'):
            require(command == expected_link(), 'actual counter link has unbound/replaced objects or libraries')
        if command[0]==str(out/'compute_host_frontend'):
            require(seen==units, 'incomplete actual host compiler closure')
            source_case=command[4] if len(command) in (5,6) else None
            require(source_case in expected_cases and source_case not in launched and
                    command[3]==str(out/'glsl'/counter_parameters(source_case)['source']),
                    'counter producer source/recipe repeated or replaced')
            require(not matrix or command[-1]=='--capture-only', 'counter matrix attempted actual execution')
            launched.add(source_case)
            if products:unchanged({str(p):v for p,v in products.items()})
            external();unchanged(generated)
            require(sha256(paths['ntt'])==AFTER, 'NTT changed before actual producer')
            products.update({p:sha256(regular(p)) for p in
                (out/'compute_host_frontend',*(out/'glsl').glob('*.comp'),*out.rglob('*.d'))})
            command_log=sha256(regular(out/'commands.json'))
            compiled=True
        result=actual_run(command,*args,**kwargs)
        if compiled:
            require(sha256(out/'commands.json')==command_log, 'counter command log changed during producer')
            unchanged({str(p):v for p,v in products.items()});external();unchanged(generated)
        return result
    try:
        subprocess.run=run
        sys.argv=[str(ROOT/'scripts/test_gallium_compute_host.py'),*argv]
        runpy.run_path(sys.argv[0],run_name='__main__')
    finally:subprocess.run=actual_run
    require(compiled and launched==expected_cases,'actual producer matrix was incomplete')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bound-inputs',type=Path,required=True)
    args,rest=p.parse_known_args();guarded(args.bound_inputs,rest)
