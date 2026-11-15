#!/usr/bin/env python3
"""Opt-in original linked four-stage Mesa program/source lease CPU test.

Reuse immutable Mesa22.3.6 libraries. This never submits or executes a shader.
Run only in the fixed 512MiB combined-memory, one-CPU/no-network Linux image.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import time
import resource
from native_sample_compiler_inputs import MESA_LIBRARIES,MESA_UNITS,sha256
from native_sample_graphics_inputs import snapshot
from owner_process_lifetime import stop_group
ROOT=Path(__file__).resolve().parents[1]
GPU_UNITS=(
 'userspace/gallium/opengpu_native_fragment_v2.c','userspace/gallium/opengpu_native_uniform_emit.c',
 'userspace/gallium/opengpu_native_graphics.c','userspace/gallium/opengpu_native_draw.c',
 'userspace/gallium/opengpu_native_graphics_v2.c','userspace/gallium/opengpu_native_draw_v2.c',
 'userspace/gallium/opengpu_native_sample_program.c','userspace/opengpu_native_sample_graphics.c',
 'userspace/opengpu_native_runtime.c','driver/opengpu_native_frame.c',
 'userspace/gallium/opengpu_context.c','userspace/gallium/opengpu_screen.c',
 'userspace/gallium/opengpu_cs_emit.c','userspace/gallium/opengpu_fs_emit.c','userspace/gallium/opengpu_vs_emit.c',
 'userspace/pipe_opengpu.c','userspace/opengpu.c','userspace/tests/native_joint_resources.c',
 'userspace/tests/graphics_uniform_frontend.cpp','userspace/tests/native_joint_frontend.cpp',
 'userspace/gallium/opengpu_tess_frontend.cpp','userspace/gallium/opengpu_native_tess_uniform_emit.c',
 'userspace/gallium/opengpu_native_joint_frontend.cpp','userspace/gallium/opengpu_native_joint_program.c',
 'userspace/gallium/opengpu_native_joint_constants.c')

GPU_UNIT_FLAGS={}  # Explicit per-TU test-stub includes; empty for the original build.
EXTRA_NEGATIVE_CONTROLS=None
EXTRA_CASES=None  # Optional actual-source cases in the explicit BUILD proof runner.
GENERATED_INPUTS={}  # Exact test-only production-body captures, bound before compile.


def source_inputs():
    return tuple(path for root in ('userspace','driver','scripts','docs/abi')
                 for path in (ROOT/root).rglob('*') if path.is_file() and not path.is_symlink() and
                 path.suffix in ('.c','.cpp','.h','.inc','.py','.json','.vert','.frag','.tesc','.tese','.geom','.glsl'))


def verify_records(output):
    """Canonical reserved/high bits and all private addresses from public ABI.

    Expected fragment metadata comes from actual original compiler artifact;
    precise ABI/rate/prefix and flat/depth cases are also independently fixed
    by their source recipes. No device output is manufactured or interpreted.
    """
    m=struct.unpack_from('<42I',output.with_suffix('.ognsf').read_bytes(),8)
    expected_abi=9 if output.name in ('full9','depth') else 8
    assert m[1]==expected_abi and m[4]==(m[2]+4*m[13]+2)*512
    for suffix,schema in (('.gnd',json.loads((ROOT/'docs/abi/native-binned-graphics-v1.json').read_text())['draw']),
                          ('.gfs',json.loads((ROOT/'docs/abi/typed-fragment-state-v1.json').read_text()))):
        data=output.with_suffix(suffix).read_bytes();assert len(data)==schema['bytes']
        words=struct.unpack('<%dI'%(len(data)//4),data);masks=[0]*len(words);fields={}
        assert words[:3]==(int(schema['magic'],16),len(data),1)
        for i in range(8):masks[i]=0xffffffff
        for f in schema['fields']:
            n=(f['bits']+31)//32
            value=sum(words[f['word']+i]<<(32*i) for i in range(n));assert value>>f['bits']==0
            fields[f['name']]=value
            for i in range(n):masks[f['word']+i]|=min((1<<max(0,f['bits']-32*i))-1,0xffffffff)
            if f['name'].split('.')[-1] in ('shaderPc','base','limit','allocationBase','allocationLimit',
                  'address','frameBase','frameLimit','drawBase','drawLimit'):assert value==0,f['name']
        assert all(w&~mask==0 for w,mask in zip(words,masks))
        if suffix=='.gfs':
            assert fields['fragment.version']==expected_abi
            assert fields['fragment.allowDepthOverride']==(output.name=='depth')
            assert fields['fragment.varyingSlots']==m[12] and fields['fragment.colorSlots']==m[13]
            assert fields['fragment.colorMask']==m[6]
            assert fields['fragment.centroidMask']==m[22]+(m[23]<<32)
        else:assert fields['draw.vertex.geometry.flatMask']==m[20]+(m[21]<<32)
    if output.name=='flat':assert m[12:14]==(16,4) and m[20:22]==(0,0xf0000000)
    if output.name=='centroid':assert m[22:24]==(0,0xf0000000)
    if output.name.startswith('full'):assert m[24:37]==(4096,)+(16384,)*12


def main(*, build_only=False):
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--build',type=Path,required=True)
    ap.add_argument('--legacy-source',type=Path,required=True)
    ap.add_argument('--joint-baseline-fixture',type=Path,help='fixed original native_joint_resources.c for exact old-case packet comparisons')
    ap.add_argument('--domains',action='store_true',help='compile separately named original triangle/quad matrix ESSL and checked domain-v1 receipts')
    if not build_only:
        ap.add_argument('--tess-baseline-source',type=Path,required=True)
        ap.add_argument('--negative-controls',action='store_true')
    ap.add_argument('--reuse-objects',action='store_true',help='reuse only private objects with exact argv/object/MMD-input SHA receipts from an earlier active build');ap.add_argument('--sanitize',action='store_true');a=ap.parse_args()
    if a.domains and not a.joint_baseline_fixture:ap.error('--domains requires --joint-baseline-fixture')
    out=a.build.resolve();out.mkdir(parents=True,exist_ok=a.reuse_objects)
    mesa=Path(os.environ['OPENGPU_MESA_DIR']).resolve();mb=Path(os.environ['OPENGPU_MESA_BUILD']).resolve()
    drm=Path(os.environ['OPENGPU_DRM_INCLUDE']).absolute();gpu=mesa.parents[1];libraries=drm.parent/'lib'
    external=snapshot(mesa,mb,libraries,drm,gpu)
    # All actual quoted GPU inputs including .inc, Python, three public schemas
    # are fixed BEFORE compilation. Actual MMD must be a subset, not posthoc.
    bound={str(p.resolve()):sha256(p) for p in source_inputs()}
    baseline=a.legacy_source.resolve(strict=True);bound[str(baseline)]=sha256(baseline)
    if not build_only:
        tess_baseline=a.tess_baseline_source.resolve(strict=True);bound[str(tess_baseline)]=sha256(tess_baseline)
    if a.joint_baseline_fixture:
        joint_baseline=a.joint_baseline_fixture.resolve(strict=True);bound[str(joint_baseline)]=sha256(joint_baseline)
    private={};commands=[];objects=[]
    def unchanged():
        assert all(sha256(Path(p))==h for p,h in {**bound,**private}.items())
    def run(argv,label,expected=0):
        argv=list(map(str,argv));unchanged()
        if '-c' in argv:
            src=Path(argv[argv.index('-c')+1]).resolve(strict=True)
            assert str(src) in bound or str(src) in private or str(src) in external
        cache=out/(label+'.compile-inputs.json')
        if a.reuse_objects and '-c' in argv and cache.is_file():
            saved=json.loads(cache.read_text())
            if saved['argv']==argv and all(Path(p).is_file() and sha256(Path(p))==h for p,h in saved['files'].items()):
                private.update(saved['outputs']);unchanged()
                commands.append(dict(argv=argv,cwd=str(out),label=label,reusedExactInputs=True))
                return
        commands.append(dict(argv=argv,cwd=str(out),label=label));(out/'commands.json').write_text(json.dumps(commands)+'\n')
        started=time.monotonic()
        with (out/(label+'.log')).open('w') as f:
            p=subprocess.Popen(argv,cwd=out,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
            try:code=p.wait(timeout=180)
            finally:stop_group(p)
        commands[-1]['seconds']=round(time.monotonic()-started,3)
        commands[-1]['exitCode']=code
        (out/'commands.json').write_text(json.dumps(commands)+'\n')
        unchanged()
        if code!=expected:raise RuntimeError(f'{label}: exit {code}, expected {expected}\n'+(out/(label+'.log')).read_text()[-6000:])
        if not code and '-o' in argv:
            target=Path(argv[argv.index('-o')+1]);private[str(target)]=sha256(target)
            if '-MF' in argv:
                dep=Path(argv[argv.index('-MF')+1]);private[str(dep)]=sha256(dep)
                inputs={}
                for name in shlex.split(dep.read_text().replace('\\\n','').split(':',1)[1]):
                    path=Path(name).resolve(strict=True);inputs[str(path)]=sha256(path)
                    assert str(path) in bound or str(path) in private or str(path) in external
                outputs={str(target):sha256(target),str(dep):sha256(dep)}
                cache.write_text(json.dumps(dict(argv=argv,files={**inputs,**outputs},outputs=outputs))+'\n')
    def generated(path,text):path.write_text(text);private[str(path)]=sha256(path)
    cc,cxx=(shlex.split(os.environ.get(k,v)) for k,v in (('CC','cc'),('CXX','c++')))
    database=json.loads((mb/'compile_commands.json').read_text());san=['-fsanitize=address,undefined','-fno-omit-frame-pointer'] if a.sanitize else []
    def flags(unit):
        src=mesa/'src'/unit;e=next(e for e in database if (Path(e['directory'])/e['file']).resolve()==src)
        raw=e.get('arguments') or shlex.split(e['command']);r=[];skip=False
        for v in raw[1:]:
            if skip:skip=False;continue
            if v in ('-o','-MF','-MT','-MQ'):skip=True;continue
            if v in ('-c','-MD','-MMD'):continue
            if not v.startswith('-') and (Path(e['directory'])/v).resolve()==src:continue
            if v.startswith('-I') and not Path(v[2:]).is_absolute():v='-I'+str((Path(e['directory'])/v[2:]).resolve())
            r.append(v)
        return r
    base=(mesa/'src/compiler/glsl/standalone.cpp').read_text();anchor='   if (options->lower_precision) {';assert base.count(anchor)==1
    base=base.replace(anchor,'   opengpu_tess_frontend_limits(ctx);\n\n'+anchor)
    generated(out/'standalone.cpp','#include "main/mtypes.h"\nextern "C" void opengpu_tess_frontend_limits(gl_context *);\n'+base)
    inc=['-I'+str(p) for p in (out,ROOT/'userspace/gallium',ROOT/'userspace/tests',ROOT/'userspace',ROOT/'driver',drm,
         mesa/'src/gallium/include',mesa/'src/gallium/auxiliary',mesa/'src',mesa/'include',mesa/'src/compiler/nir',mb/'src/compiler/nir')]
    from owner_resource_contract import SAMPLE_WORDS
    aux=[(0,0x47550002),(0x60,sum(1<<b for b in (6,22,23,25,29))|128<<8),(0x68,0x160),(0x320,1048576),(0x158,0x02020810)]
    profile=[(w['address'],w['value']) for w in SAMPLE_WORDS]+aux
    generated(out/'native_graphics_v2_profile.inc','/* Explicit synthetic CPU observations, NOT device discovery. */\nstatic const struct opengpu_native_sample_word cpu_profile[117]={\n'+''.join('{%du,%du},\n'%p for p in profile)+'};\n')
    for unit in (*MESA_UNITS,'compiler/nir/nir_lower_io.c'):
        src=out/'standalone.cpp' if unit==MESA_UNITS[0] else mesa/'src'/unit
        obj=out/(unit.replace('/','_')+'.o')
        run((cxx if src.suffix=='.cpp' else cc)+flags(unit)+['-UNDEBUG','-ffunction-sections','-fdata-sections']+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',src,'-o',obj],obj.stem);objects.append(obj)
    for name,text in GENERATED_INPUTS.items():generated(out/name,text)
    primitive_units=('userspace/pipe_opengpu.c','userspace/opengpu.c')
    common=['-std=c11','-O1','-UNDEBUG','-DHAVE_ENDIAN_H','-D_POSIX_C_SOURCE=200809L','-Wall','-Wextra','-Werror',
            '-Wno-unused-parameter','-Wno-unused-function','-Wno-sign-compare','-ffunction-sections','-fdata-sections']
    links={}
    for unit in GPU_UNITS:
        obj=out/(Path(unit).stem+'.o');cpp=unit.endswith('.cpp')
        extra=['-D'+n+'=cs_fixture_'+n for n in ('ioctl','mmap','munmap','dup','close')] if unit in primitive_units else []
        extra+=GPU_UNIT_FLAGS.get(unit,[])
        run((cxx if cpp else cc)+(flags(MESA_UNITS[0])+['-UNDEBUG','-Wall','-Wextra','-Werror','-Wno-unused-parameter'] if cpp else common)+extra+inc+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',ROOT/unit,'-o',obj],obj.stem);objects.append(obj)
    obj=out/'reference-uniform.o'
    run(cc+common+['-Dopengpu_native_uniform_emit=reference_uniform_emit','-Dopengpu_native_uniform_compile=reference_uniform_compile',
          '-Dopengpu_native_vertex_tess_compile=reference_vertex_tess_compile']+inc+san+
          ['-MMD','-MF',obj.with_suffix('.d'),'-c',baseline,'-o',obj],obj.stem);objects.append(obj)
    binary=out/'native-joint'
    link=cxx+san+['-rdynamic','-Wl,--gc-sections']+objects+[mb/'src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o',
         '-Wl,--start-group']+[mb/p for p in MESA_LIBRARIES]+['-Wl,--end-group']+shlex.split(os.environ['OPENGPU_MESA_LINK_LIBS'])+['-ldl','-lm','-pthread','-o',binary]
    run(link,'link')
    def mmd_paths():
        return {Path(c['argv'][c['argv'].index('-MF')+1]) for c in commands if '-MF' in c['argv']}
    dependencies=set()
    for dep in mmd_paths():
        for name in shlex.split(dep.read_text().replace('\\\n','').split(':',1)[1]):
            path=Path(name).resolve(strict=True);dependencies.add(path)
            assert str(path) in bound or str(path) in private or str(path) in external,'unbound MMD '+str(path)
    assert len(mmd_paths())==len(objects)
    assert snapshot(mesa,mb,libraries,drm,gpu)==external;unchanged()
    if build_only:
        print(f'Mesa joint producer compiled with {len(objects)} objects and {len(dependencies)} bound dependencies.')
        return
    sources=out/'sources';sources.mkdir(exist_ok=a.reuse_objects)
    from test_gallium_graphics_uniforms import write_sources as legacy_sources
    legacy_sources(sources)
    for path in sources.iterdir():private[str(path)]=sha256(path)
    run([binary,sources/'full.vert',sources/'full.frag','legacy'],'legacy-original-308-301-before')
    for name,vs_slot,fs_slot,sample in (('full8',15,0,False),('full9',1,15,True)):
        write_sources(sources,name,vs_slot,fs_slot,sample)
        for p in sources.iterdir():private[str(p)]=sha256(p)
        clear_packets(out/name)
        run([binary,*[sources/(name+'.'+suffix) for suffix in ('vert','tesc','tese','frag')],out/name,1,vs_slot,fs_slot],'actual-'+name)
        for path in packet_paths(out/name):private[str(path)]=sha256(path)
    write_matrix(sources)
    for p in sources.iterdir():private[str(p)]=sha256(p)
    clear_packets(out/'matrix')
    run([binary,*[sources/('matrix.'+s) for s in ('vert','tesc','tese','frag')],out/'matrix',0,15,15],'actual-matrix')
    for path in packet_paths(out/'matrix'):private[str(path)]=sha256(path)
    # Real original ESSL VS floating payload variation. Other linked sources
    # stay exact; no word patching or metadata relabeling is allowed.
    generated(sources/'matrix-payload.vert',(sources/'matrix.vert').read_text().replace(
        'payload=position;', 'payload=position+vec4(0.125,0.25,0.5,0.0);'))
    clear_packets(out/'matrix-payload')
    run([binary,sources/'matrix-payload.vert',sources/'matrix.tesc',sources/'matrix.tese',sources/'matrix.frag',
         out/'matrix-payload',0,15,15],'original-VS-floating-payload')
    for path in packet_paths(out/'matrix-payload'):private[str(path)]=sha256(path)
    assert (out/'matrix-payload.vs.words').read_bytes()!=(out/'matrix.vs.words').read_bytes()
    for suffix in ('.tcs.words','.tes.words','.ognsf'):
        assert (out/('matrix-payload'+suffix)).read_bytes()==(out/('matrix'+suffix)).read_bytes()
    generated(sources/'matrix-levels.tese',(sources/'matrix.tese').read_text().replace('matrices[indices.x][indices.y]+vectors[indices.z]', 'matrices[indices.x][indices.y]+vectors[indices.z]+vec3(gl_TessLevelOuter[0],gl_TessLevelOuter[1],gl_TessLevelInner[0])'))
    clear_packets(out/'matrix-levels')
    run([binary,sources/'matrix.vert',sources/'matrix.tesc',sources/'matrix-levels.tese',sources/'matrix.frag',out/'matrix-levels',3,15,15],'actual-matrix-levels')
    for path in packet_paths(out/'matrix-levels'):private[str(path)]=sha256(path)
    generated(sources/'matrix-indirect.tesc',(sources/'matrix.tesc').read_text().replace('vectors[0]=vec3(1.,2.,3.);vectors[1]=vec3(4.,5.,6.);','vectors[gl_InvocationID]=payload[gl_InvocationID].xyz;'))
    clear_packets(out/'matrix-indirect')
    run([binary,sources/'matrix.vert',sources/'matrix-indirect.tesc',sources/'matrix.tese',sources/'matrix.frag',out/'matrix-indirect',2,15,15],'original-indirect-store')
    for path in packet_paths(out/'matrix-indirect'):private[str(path)]=sha256(path)
    generated(sources/'matrix-indirect-simt.tesc',(sources/'matrix-indirect.tesc').read_text().replace('vectors[gl_InvocationID]=payload[gl_InvocationID].xyz;', 'if(gl_InvocationID==0){vectors[gl_InvocationID]=payload[gl_InvocationID].xyz;}else{vectors[gl_InvocationID]=payload[gl_InvocationID].zyx;}'))
    clear_packets(out/'matrix-indirect-simt')
    run([binary,sources/'matrix.vert',sources/'matrix-indirect-simt.tesc',sources/'matrix.tese',sources/'matrix.frag',out/'matrix-indirect-simt',2,15,15],'original-indirect-store-simt')
    for path in packet_paths(out/'matrix-indirect-simt'):private[str(path)]=sha256(path)
    if EXTRA_CASES:
        for name,files,full,vs_slot,fs_slot in EXTRA_CASES(sources,generated):
            target=out/name;clear_packets(target)
            run([binary,*files,target,full,vs_slot,fs_slot],'actual-'+name)
            for path in packet_paths(target):private[str(path)]=sha256(path)
    run([binary,sources/'full.vert',sources/'full.frag','legacy'],'legacy-original-308-301-after')
    # Original independent v2 production emitter, not a current-helper self-test.
    rename=['-D'+n+'=baseline_'+n for n in ('opengpu_native_tess_uniform_emit','opengpu_native_tess_control_emit',
        'opengpu_native_tess_dynamic_emit','opengpu_native_tess_uniform_reflect','opengpu_native_tess_dynamic_reflect')]
    legacy_objects=[]
    for unit in ('userspace/gallium/opengpu_native_tess_constants.c','userspace/gallium/opengpu_native_tess_program.c',
                 'userspace/gallium/opengpu_native_tess_context.c','userspace/tests/tessellation_uniform_resources_test.c',
                 'userspace/tests/tessellation_language_legacy_compare.c',str(tess_baseline)):
        src=Path(unit) if Path(unit).is_absolute() else ROOT/unit
        obj=out/('legacy-'+src.stem+'.o')
        run(cc+common+inc+san+(rename if src==tess_baseline else [])+['-MMD','-MF',obj.with_suffix('.d'),'-c',src,'-o',obj],obj.stem)
        legacy_objects.append(obj)
    # Keep all original Mesa/link bodies; replace only the test main and OS fixture.
    for version,main,cases in (('v1','tessellation_uniform_frontend_test.cpp',('uniform','full','conditional')),
                              ('v2','tessellation_dynamic_frontend_test.cpp',('dynamic','adjacent','capacity','edge','component'))):
        src=ROOT/'userspace/tests'/main;obj=out/(version+'-main.o')
        run(cxx+flags(MESA_UNITS[0])+['-UNDEBUG','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-DOPENGPU_TESS_BYTE_BASELINE']+
            inc+san+['-MMD','-MF',obj.with_suffix('.d'),'-c',src,'-o',obj],version+'-compile')
        changed=[v for v in link if str(v) not in (str(out/'native_joint_frontend.o'),str(out/'native_joint_resources.o'),str(out/'native_joint_build_flow.o'),str(out/'native_joint_build_kernel.o'))]
        assert changed[-1]==binary;changed[-1]=out/('old-'+version)
        changed[changed.index('-Wl,--start-group'):changed.index('-Wl,--start-group')]=[*legacy_objects,obj]
        run(changed,version+'-link')
        programs=out/'regressions'/version
        for case in cases:
            directory=programs/case
            directory.mkdir(parents=True,exist_ok=a.reuse_objects)
            if a.reuse_objects:
                # Only explicit active-build reruns; same exclusive output
                # policy and caller consumer-release rule as clear_packets.
                for path in directory.iterdir():
                    assert path.is_file() and not path.is_symlink()
                    assert path.name in ('native-tcs.words','native-tes.words') or (path.name.startswith('actual.') and path.name.endswith('.tgsi.txt'))
                    path.unlink()
        run([changed[-1],ROOT/'userspace/tests/tess_uniform_essl',programs],version+'-original-bytes')
    if a.negative_controls:
        def mutant(name,unit,old,new,args,needle,expected=-6):
            source=(ROOT/unit).read_text();assert source.count(old)==1
            path=out/(name+'.c');generated(path,source.replace(old,new));obj=out/(name+'.o')
            run(cc+common+inc+san+['-MMD','-MF',obj.with_suffix('.d'),'-c',path,'-o',obj],name+'-compile')
            changed=[str(obj) if str(v)==str(out/(Path(unit).stem+'.o')) else str(v) for v in link]
            assert changed.count(str(obj))==1;changed[-1]=str(out/name);run(changed,name+'-link')
            target=Path(args[4]);assert target.parent==out;clear_packets(target)
            run([out/name,*args],name+'-rejected',expected)
            assert needle in (out/(name+'-rejected.log')).read_text()
        mutant('mutant-no-backing-retain','userspace/gallium/opengpu_native_joint_constants.c','v->refs++;','(void)v;',
            [*[sources/('full8.'+s) for s in ('vert','tesc','tese','frag')],out/'mutant-refs',1,15,0],
            'bos[saved.uniforms[s].defaults.handle].handle')
        mutant('mutant-wrong-array-stride','userspace/gallium/opengpu_native_tess_uniform_emit.c','word(b,vec(0x25,3,29,3,29));','word(b,vec(0x25,3,29,4,29));',
            [*[sources/('matrix.'+s) for s in ('vert','tesc','tese','frag')],out/'mutant-stride',0,15,15],'vec3Stride>=6')
        mutant('mutant-no-store-params','userspace/gallium/opengpu_native_tess_uniform_emit.c','word(b,patch(20,0,22+c,0));','word(b,0x00000013u);',
            [sources/'matrix.vert',sources/'matrix-indirect.tesc',sources/'matrix.tese',sources/'matrix.frag',out/'mutant-params',2,15,15],
            '(params&0xfe007fffu)==(20u<<26|1u<<25|0x2bu)')
        mutant('mutant-store-bound','userspace/gallium/opengpu_native_tess_uniform_emit.c','unsigned remaining=span->slots-(index-span->first);','unsigned remaining=span->slots+1-(index-span->first);',
            [sources/'matrix.vert',sources/'matrix-indirect.tesc',sources/'matrix.tese',sources/'matrix.frag',out/'mutant-bound',2,15,15],
            'd.tess[0].code[i-1]==0x00200513u')
    if a.negative_controls and EXTRA_NEGATIVE_CONTROLS:EXTRA_NEGATIVE_CONTROLS(mutant,sources,out)
    if a.domains:
        from test_native_joint_domains import verify_packet,write_domain_sources
        original={str(p):sha256(p) for name in ('full8','full9','matrix','matrix-payload',
            'matrix-levels','matrix-indirect','matrix-indirect-simt') for p in packet_paths(out/name)}
        for name,mode in (('matrix-triangle',4),('matrix-quad',5)):
            write_domain_sources(sources,name)
            for p in sources.iterdir():private[str(p)]=sha256(p)
            clear_packets(out/name)
            receipt=(out/name).with_suffix('.domain.json')
            if receipt.exists():receipt.unlink()
            run([binary,*[sources/(name+'.'+s) for s in ('vert','tesc','tese','frag')],out/name,mode,15,15],name)
            for p in [*packet_paths(out/name),receipt]:private[str(p)]=sha256(p)
            verify_packet(out/name)
        assert all(sha256(Path(p))==h for p,h in original.items())
        # Same original linked sources, actual backend and libraries; only the
        # fixed pre-domain OS fixture body differs. Compare complete packets,
        # including old source JSON/layout bytes, not instruction counts.
        obj=out/'reference-joint-fixture.o'
        run(cc+common+inc+san+['-MMD','-MF',obj.with_suffix('.d'),'-c',joint_baseline,'-o',obj], 'reference-joint-compile')
        changed=[str(obj) if str(v)==str(out/'native_joint_resources.o') else str(v) for v in link]
        changed[-1]=str(out/'reference-joint');run(changed,'reference-joint-link')
        reference=out/'reference-original';reference.mkdir(exist_ok=a.reuse_objects)
        for name,mode,vs_slot,fs_slot,files in (
            ('full8',1,15,0,('full8.vert','full8.tesc','full8.tese','full8.frag')),
            ('full9',1,1,15,('full9.vert','full9.tesc','full9.tese','full9.frag')),
            ('matrix',0,15,15,('matrix.vert','matrix.tesc','matrix.tese','matrix.frag')),
            ('matrix-payload',0,15,15,('matrix-payload.vert','matrix.tesc','matrix.tese','matrix.frag')),
            ('matrix-levels',3,15,15,('matrix.vert','matrix.tesc','matrix-levels.tese','matrix.frag')),
            ('matrix-indirect',2,15,15,('matrix.vert','matrix-indirect.tesc','matrix.tese','matrix.frag')),
            ('matrix-indirect-simt',2,15,15,('matrix.vert','matrix-indirect-simt.tesc','matrix.tese','matrix.frag'))):
            run([out/'reference-joint',*[sources/f for f in files],reference/name,mode,vs_slot,fs_slot],'reference-'+name)
            for expected,actual in zip(packet_paths(out/name),packet_paths(reference/name)):
                assert expected.read_bytes()==actual.read_bytes(),'old packet byte drift '+name+' '+expected.suffix
                private[str(actual)]=sha256(actual)
        if a.negative_controls:
            for field in ('domain','spacing','clockwise','point_mode'):
                mutant('mutant-domain-'+field,'userspace/gallium/opengpu_native_tess_uniform_emit.c',
                    'if(v!=layout->'+field+')ret=-1;', '(void)v;',
                    [*[sources/('matrix-triangle.'+s) for s in ('vert','tesc','tese','frag')],out/('mutant-domain-'+field),4,15,15],
                    'opengpu_native_tess_linked_emit(input->tokens[2],input->words[2],&wrong')
    unchanged();assert snapshot(mesa,mb,libraries,drm,gpu)==external
    actual_dependencies=set()
    for dep in mmd_paths():
        for name in shlex.split(dep.read_text().replace('\\\n','').split(':',1)[1]):
            path=Path(name).resolve(strict=True);actual_dependencies.add(path)
            assert str(path) in bound or str(path) in private or str(path) in external,'unbound final MMD '+str(path)
    compiles=[c for c in commands if '-c' in c['argv']]
    fresh=sum(not c.get('reusedExactInputs') for c in compiles);reused=len(compiles)-fresh
    print(f'NATIVE_JOINT_CPU PASS actualMesa linked4stage baseCases3 storeCases2 levelCases1 full48blocks/4defaults/API71 retained52views old308/301/v1/v2 bytes domains={a.domains} mutants={a.negative_controls} compilesFresh={fresh} exactReuse={reused} MMDdeps={len(actual_dependencies)} external={len(external)} childMaxRssKiB={resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss} GPU=false submit=false')


def packet_paths(target):
    return [target.with_suffix(s) for s in ('.ognsf','.vs.words','.tcs.words','.tes.words','.layout','.source.json')]


def clear_packets(target):
    # Private active-build reruns only. Caller must release all packet consumers
    # before --reuse-objects; original emitters retain exclusive-create policy.
    for path in packet_paths(target):
        if path.exists():
            assert not path.is_symlink() and path.is_file();path.unlink()


def write_sources(out,name,vs_slot,fs_slot,sample):
    # Each stage's original binding order is reflected by Mesa. API binding71
    # is FS block11; no global API index is confused with private stage slot.
    for s,suffix in enumerate(('vert','tesc','tese','frag')):
        text='#version 320 es\nprecision highp float;precision highp int;\n'
        text+='uniform uvec4 defaults[256];\n'
        for i in range(12):
            b=(s*12+i) if s<3 else 60+i
            text+=f'layout(std140,binding={b}) uniform B{s}_{i}{{uvec4 data[1024];}} block{i};\n'
        if s==0:text+=f'layout(location=0) in vec4 position;layout(location={vs_slot}) out uvec4 payload;\n'
        elif s==1:text+=f'layout(vertices=2) out;layout(location={vs_slot}) in uvec4 payload[];layout(location=7) out uvec4 bridge[];\n'
        elif s==2:text+=f'layout(isolines,equal_spacing) in;layout(location=7) in uvec4 bridge[];layout(location={fs_slot}) flat out uvec4 values;\n'
        else:text+=f'layout(location={fs_slot}) flat in uvec4 values;layout(location=0) out uvec4 color;\n'
        text+='void main(){uint sum=defaults[0].x^defaults[255].w;\n'
        for i in range(12):text+=f'sum=(sum*33u)^block{i}.data[0].x^block{i}.data[1023].w;\n'
        if s==0:text+='gl_Position=position;payload=uvec4(sum);}'
        elif s==1:text+='gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;bridge[gl_InvocationID]=payload[gl_InvocationID]^uvec4(sum^uint(gl_PrimitiveID));gl_TessLevelOuter[gl_InvocationID]=1.;barrier();}'
        elif s==2:text+='gl_Position=gl_in[0].gl_Position+vec4(gl_TessCoord.x,0.,0.,0.);values=bridge[0]^uvec4(sum^uint(gl_PrimitiveID));}'
        else:text+='color=values^uvec4(sum^uint(gl_PrimitiveID)'+('^uint(gl_SampleID)' if sample else '')+');}'
        (out/(name+'.'+suffix)).write_text(text+'\n')

def write_matrix(out):
    bodies={
    'vert':'layout(location=0) in vec4 position;layout(location=15) out vec4 payload;void main(){gl_Position=position;payload=position;}',
    'tesc':'layout(vertices=2) out;layout(location=15) in vec4 payload[];layout(location=2) out mat2x3 matrices[];patch out vec3 vectors[2];void main(){gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;matrices[gl_InvocationID]=mat2x3(payload[gl_InvocationID].xyz,payload[gl_InvocationID].zyx);vectors[0]=vec3(1.,2.,3.);vectors[1]=vec3(4.,5.,6.);gl_TessLevelOuter[gl_InvocationID]=1.;barrier();}',
    'tese':'layout(isolines,equal_spacing) in;layout(location=2) in mat2x3 matrices[];patch in vec3 vectors[2];uniform uvec4 indices;layout(location=15) flat out vec4 values;void main(){gl_Position=gl_in[indices.x].gl_Position;values=vec4(matrices[indices.x][indices.y]+vectors[indices.z],float(gl_PrimitiveID));}',
    'frag':'layout(location=15) flat in vec4 values;layout(location=0) out vec4 color;void main(){color=values+vec4(float(gl_PrimitiveID));}'}
    for suffix,body in bodies.items():(out/('matrix.'+suffix)).write_text('#version 320 es\nprecision highp float;precision highp int;\n'+body+'\n')

if __name__=='__main__':main()
