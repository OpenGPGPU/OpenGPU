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
from compute_owner_cases import (COUNTER_CASES, counter_parameters, IMAGE_CASES, P12_SHARED_CASES, p12_shared_parameters,
    P12_TEXTURE_CASES, p12_texture_parameters, LEGACY_IMAGE_CASES, CONTENDED_IMAGE_CASES, MIXED_ATOMIC_CASES)
from compute_image_source import parameters as image_parameters
from compute_indirect_sources import CASE as INDIRECT_CASE
from compute_storage_atomic_cases import CASES as STORAGE_CASES, parameters as storage_parameters
from compute_storage_publication_cases import CASES as PUBLICATION_CASES, parameters as publication_parameters
from compute_image_publication_cases import CASES as IMAGE_PUBLICATION_CASES, parameters as image_publication_parameters
from compute_texture_publication_cases import CASES as TEXTURE_PUBLICATION_CASES, parameters as texture_publication_parameters
from compute_counter_publication_cases import CASES as COUNTER_PUBLICATION_CASES, parameters as counter_publication_parameters
from compute_shared_publication_cases import CASES as SHARED_PUBLICATION_CASES, parameters as shared_publication_parameters
from compute_dma_publication_cases import CASES as DMA_PUBLICATION_CASES, parameters as dma_publication_parameters
from compute_storage_recovery import PHASES as STORAGE_RECOVERY_PHASES, check_source_launch as check_storage_launch
from compute_storage_publication import PHASES as PUBLICATION_PHASES, check_source_launch as check_publication_launch
from compute_image_publication import PHASES as IMAGE_PUBLICATION_PHASES, check_source_launch as check_image_publication_launch
from compute_texture_publication import PHASES as TEXTURE_PUBLICATION_PHASES, check_source_launch as check_texture_publication_launch
from compute_counter_publication import PHASES as COUNTER_PUBLICATION_PHASES, check_source_launch as check_counter_publication_launch
from compute_shared_publication import PHASES as SHARED_PUBLICATION_PHASES, check_source_launch as check_shared_publication_launch
from compute_dma_publication import PHASES as DMA_PUBLICATION_PHASES, check_source_launch as check_dma_publication_launch

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
    require('--case' in argv and argv[argv.index('--case')+1] in (*COUNTER_CASES,*IMAGE_CASES,*STORAGE_CASES,*PUBLICATION_CASES,*IMAGE_PUBLICATION_CASES,*TEXTURE_PUBLICATION_CASES,*COUNTER_PUBLICATION_CASES,*SHARED_PUBLICATION_CASES,*DMA_PUBLICATION_CASES,*P12_SHARED_CASES,*P12_TEXTURE_CASES,INDIRECT_CASE) and '--sanitize' in argv,
            'strict original counter source and sanitized producer required')
    case=argv[argv.index('--case')+1]
    indirect=case==INDIRECT_CASE
    image_matrix='--capture-image-matrix' in argv
    counter_matrix='--capture-counter-matrix' in argv
    storage_matrix='--storage-matrix' in argv
    storage_recovery='--storage-recovery-matrix' in argv
    storage_accepted='--storage-accepted-matrix' in argv
    storage_publication='--storage-publication-matrix' in argv
    image_publication='--image-publication-matrix' in argv
    texture_publication='--texture-publication-matrix' in argv
    counter_publication='--counter-publication-matrix' in argv
    shared_publication='--shared-publication-matrix' in argv
    dma_publication='--dma-publication-matrix' in argv
    image_accepted='--image-accepted-matrix' in argv
    storage_phases,check_storage=STORAGE_RECOVERY_PHASES,check_storage_launch
    if storage_accepted:
        from compute_storage_accepted_recovery import PHASES as storage_phases, check_source_launch as check_storage
    if image_accepted:
        from compute_image_accepted_recovery import PHASES as storage_phases, check_source_launch as check_storage
    if storage_publication:
        storage_phases,check_storage=PUBLICATION_PHASES,check_publication_launch
    if image_publication:
        storage_phases,check_storage=IMAGE_PUBLICATION_PHASES,check_image_publication_launch
    if texture_publication:
        storage_phases,check_storage=TEXTURE_PUBLICATION_PHASES,check_texture_publication_launch
    if counter_publication:
        storage_phases,check_storage=COUNTER_PUBLICATION_PHASES,check_counter_publication_launch
    if shared_publication:
        storage_phases,check_storage=SHARED_PUBLICATION_PHASES,check_shared_publication_launch
    if dma_publication:
        storage_phases,check_storage=DMA_PUBLICATION_PHASES,check_dma_publication_launch
    contended_matrix='--contended-image-matrix' in argv
    mixed_matrix='--mixed-atomic-matrix' in argv
    require(sum((image_matrix,counter_matrix,storage_matrix,storage_recovery,storage_accepted,storage_publication,image_publication,texture_publication,counter_publication,shared_publication,dma_publication,image_accepted,contended_matrix,mixed_matrix))<=1,
            'source matrices must remain distinct')
    matrix=image_matrix or counter_matrix or storage_matrix or storage_recovery or storage_accepted or storage_publication or image_publication or texture_publication or counter_publication or shared_publication or dma_publication or image_accepted or contended_matrix or mixed_matrix
    source_fault='--expect-image-source-fault' in argv
    permission=None
    reset=None
    require(argv.count('--expect-image-reset')<=1,'repeated image reset')
    if '--expect-image-reset' in argv:
        index=argv.index('--expect-image-reset');require(index+1<len(argv),'missing image reset channel')
        reset=argv[index+1]
        require(reset in ('read','write') and not source_fault and '--expect-image-permission-fault' not in argv and
                case=='image-atomic-buffer-r32f' and not matrix and '--capture-only' not in argv,
                'reset requires one original live float buffer and distinct reset receipts')
    require(argv.count('--expect-image-permission-fault')<=1,'repeated image permission denial')
    if '--expect-image-permission-fault' in argv:
        index=argv.index('--expect-image-permission-fault')
        require(index+1<len(argv),'missing denied image access')
        permission=argv[index+1]
        require(permission in ('read','write') and not source_fault and case=='image-atomic-buffer-r32f' and
                not reset and not matrix and '--capture-only' not in argv,'permission denial requires one original live float buffer')
    require(not source_fault or (case=='image-atomic-buffer-r32f' and not matrix and '--capture-only' not in argv),
            'source fault requires one original live R32F buffer recipe')
    allocation_base=None
    require(argv.count('--physical-base')<=1,'repeated original allocation namespace')
    if '--physical-base' in argv:
        require(argv.index('--physical-base')+1<len(argv),'missing original allocation namespace')
        value=argv[argv.index('--physical-base')+1]
        allocation_base=int(value)
        require(str(allocation_base)==value and case=='image-atomic-buffer-r32f' and not matrix and
                '--capture-only' not in argv and 0<allocation_base<=0xfffff000 and allocation_base%4096==0,
                'allocation namespace requires one original live float buffer source')
    require(not (image_matrix or counter_matrix) or '--capture-only' in argv, 'counter/image matrix is CPU capture only')
    launched=set()
    require(not image_matrix or case==IMAGE_CASES[0], 'image matrix must start at its first original source')
    require(not counter_matrix or case==COUNTER_CASES[0], 'counter matrix must start at its first original source')
    require(argv.count('--storage-matrix')<=1 and (not storage_matrix or case==STORAGE_CASES[0]),
            'storage matrix must start at its first original source')
    require(argv.count('--contended-image-matrix')<=1 and (not contended_matrix or
        (case==CONTENDED_IMAGE_CASES[0] and not (storage_matrix or image_matrix or counter_matrix or storage_recovery))),
        'contended image matrix requires its exact first source and separate original sources')
    require(argv.count('--mixed-atomic-matrix')<=1 and (not mixed_matrix or case==MIXED_ATOMIC_CASES[0]),
        'mixed atomic matrix requires its exact first original same-GEM source')
    expected_cases=set(MIXED_ATOMIC_CASES if mixed_matrix else CONTENDED_IMAGE_CASES if contended_matrix else STORAGE_CASES if storage_matrix else
        LEGACY_IMAGE_CASES if image_matrix else COUNTER_CASES if counter_matrix else (case,))
    if storage_recovery or storage_accepted or image_accepted or storage_publication or image_publication or texture_publication or counter_publication or shared_publication or dma_publication:
        flag=('--image-accepted-matrix' if image_accepted else '--storage-accepted-matrix' if storage_accepted else
              '--shared-publication-matrix' if shared_publication else
              '--dma-publication-matrix' if dma_publication else
              '--counter-publication-matrix' if counter_publication else
              '--texture-publication-matrix' if texture_publication else
              '--image-publication-matrix' if image_publication else
              '--storage-publication-matrix' if storage_publication else '--storage-recovery-matrix')
        first=(SHARED_PUBLICATION_CASES[0] if shared_publication else DMA_PUBLICATION_CASES[0] if dma_publication else COUNTER_PUBLICATION_CASES[0] if counter_publication else TEXTURE_PUBLICATION_CASES[0] if texture_publication else IMAGE_PUBLICATION_CASES[0] if image_publication else PUBLICATION_CASES[0] if storage_publication else
               CONTENDED_IMAGE_CASES[0] if image_accepted else STORAGE_CASES[0])
        require(argv.count(flag)==1 and case==first and
            '--capture-only' not in argv and not source_fault and permission is None and reset is None and allocation_base is None,
            'storage recovery/publication requires the exact complete original live sequence')
        expected_cases={(stage['case'],stage['index']) for stage in storage_phases}
    out=Path(argv[argv.index('--build')+1]).absolute()
    generated={};seen=set();compiled=False
    # These are all actual host translation units, not the CPU VM fixture.
    units={str(ROOT/name) for name in (
        'driver/tests/opengpu_compute_host_fixture.c',
        'userspace/tests/compute_indirect_frontend.c' if indirect else 'userspace/tests/compute_host_frontend.c',
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
            source_case=command[4] if len(command)>=5 else None
            parameters=(p12_shared_parameters(source_case) if source_case in P12_SHARED_CASES else
                        p12_texture_parameters(source_case) if source_case in P12_TEXTURE_CASES else
                        shared_publication_parameters(source_case) if source_case in SHARED_PUBLICATION_CASES else
             dma_publication_parameters(source_case) if source_case in DMA_PUBLICATION_CASES else
                        counter_publication_parameters(source_case) if source_case in COUNTER_PUBLICATION_CASES else
                        texture_publication_parameters(source_case) if source_case in TEXTURE_PUBLICATION_CASES else
                        image_publication_parameters(source_case) if source_case in IMAGE_PUBLICATION_CASES else
                        publication_parameters(source_case) if source_case in PUBLICATION_CASES else
                        storage_parameters(source_case) if source_case in STORAGE_CASES else
                        image_parameters(source_case) if source_case in IMAGE_CASES else None if indirect else counter_parameters(source_case))
            expected_source=out/'glsl' if indirect else out/'glsl'/parameters['source']
            stage=None
            if storage_recovery or storage_accepted or image_accepted or storage_publication or image_publication or texture_publication or counter_publication or shared_publication or dma_publication:
                require(len(launched)<len(storage_phases),'extra original storage recovery phase')
                stage=storage_phases[len(launched)]
                check_storage(command,stage['index'],out,
                    Path(argv[argv.index('--session')+1]).absolute(),Path(argv[argv.index('--profile-wire')+1]).absolute())
            launch_key=(source_case,stage['index']) if stage is not None else source_case
            require(launch_key in expected_cases and launch_key not in launched and
                    command[3]==str(expected_source),
                    'counter producer source/recipe repeated or replaced')
            require(not (image_matrix or counter_matrix) or command[-1]=='--capture-only', 'counter/image matrix attempted actual execution')
            expected_tail=(['--expect-image-source-fault'] if source_fault else
                           ['--expect-image-permission-fault',permission] if permission else
                           ['--expect-image-reset',reset] if reset else
                           ['--capture-only'] if '--capture-only' in argv else [])
            if allocation_base is not None:expected_tail+=['--physical-base',str(allocation_base)]
            if stage is not None:
                mode=stage.get('mode')
                expected_tail=((['--expect-image-write-fault'] if image_accepted else
                    ['--expect-storage-fault','write' if mode=='accepted-write' else mode])
                    if mode not in (None,'healthy') else [])+[
                    '--physical-base',str(stage['base'])]
            require(command[5:]==expected_tail, 'original producer execution mode changed')
            launched.add(launch_key)
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
        sys.argv=[str(ROOT/('scripts/test_gallium_compute_indirect_host.py' if indirect else 'scripts/test_gallium_compute_host.py')),*argv]
        runpy.run_path(sys.argv[0],run_name='__main__')
    finally:subprocess.run=actual_run
    require(compiled and launched==expected_cases,'actual producer matrix was incomplete')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bound-inputs',type=Path,required=True)
    args,rest=p.parse_known_args();guarded(args.bound_inputs,rest)
