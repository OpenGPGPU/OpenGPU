#!/usr/bin/env python3
"""Opt-in original linked five-stage native compiler/65-view lease CPU proof.

Immutable original Mesa libraries only; no device submission or shader VM.
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
import re
import shutil
from native_sample_compiler_inputs import MESA_LIBRARIES,MESA_UNITS,sha256
from native_sample_graphics_inputs import snapshot
from owner_process_lifetime import stop_group
ROOT=Path(__file__).resolve().parents[1]
from test_native_joint import GPU_UNITS as FOUR_GPU_UNITS
GPU_UNITS=tuple(p for p in FOUR_GPU_UNITS if p not in (
 'userspace/tests/native_joint_resources.c','userspace/tests/native_joint_frontend.cpp',
 'userspace/tests/graphics_uniform_frontend.cpp','userspace/gallium/opengpu_native_joint_frontend.cpp',
 'userspace/gallium/opengpu_native_joint_program.c','userspace/gallium/opengpu_native_joint_constants.c'))+(
 'userspace/gallium/opengpu_native_geometry_emit.c','userspace/gallium/opengpu_native_fragment_v3.c',
 'userspace/gallium/opengpu_native_five_program.c','userspace/gallium/opengpu_native_five_constants.c',
 'userspace/tests/native_five_resources.c','userspace/tests/native_five_frontend.cpp')
def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--build',type=Path,required=True)

    ap.add_argument('--reuse-objects',action='store_true',help='reuse only private objects with exact argv/object/MMD-input SHA receipts from an earlier active build');ap.add_argument('--sanitize',action='store_true');ap.add_argument('--negative-controls',action='store_true');a=ap.parse_args()
    out=a.build.resolve();out.mkdir(parents=True,exist_ok=a.reuse_objects)
    mesa=Path(os.environ['OPENGPU_MESA_DIR']).resolve();mb=Path(os.environ['OPENGPU_MESA_BUILD']).resolve()
    drm=Path(os.environ['OPENGPU_DRM_INCLUDE']).absolute();gpu=mesa.parents[1];libraries=drm.parent/'lib'
    external=snapshot(mesa,mb,libraries,drm,gpu)
    # All actual quoted GPU inputs including .inc, Python, three public schemas
    # are fixed BEFORE compilation. Actual MMD must be a subset, not posthoc.
    bound={str(p.resolve()):sha256(p) for root in ('userspace','driver','scripts','docs/abi')
           for p in (ROOT/root).rglob('*') if p.is_file() and not p.is_symlink() and
           p.suffix in ('.c','.cpp','.h','.inc','.py','.json','.vert','.frag','.tesc','.tese','.geom','.glsl')}
    private={};commands=[];objects=[]
    def unchanged():
        assert all(sha256(Path(p))==h for p,h in {**bound,**private}.items())
    def run(argv,label,expected=0,failure=None):
        argv=list(map(str,argv));unchanged()
        if '-c' in argv:
            src=Path(argv[argv.index('-c')+1]).resolve(strict=True)
            assert str(src) in bound or str(src) in private or str(src) in external
        cache=out/(label+'.compile-inputs.json')
        if a.reuse_objects and '-c' in argv and cache.is_file():
            saved=json.loads(cache.read_text())
            if saved.get('compilerInputs')==compiler_inputs and saved['argv']==argv and all(Path(p).is_file() and sha256(Path(p))==h for p,h in saved['files'].items()):
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
        if failure:
            log=(out/(label+'.log')).read_text()
            assert code and re.search(r'assertion.*failed',log,re.I|re.S) and re.search(failure,log),(label,code,log[-6000:])
        elif code!=expected:raise RuntimeError(f'{label}: exit {code}, expected {expected}\n'+(out/(label+'.log')).read_text()[-6000:])
        if not code and '-o' in argv:
            target=Path(argv[argv.index('-o')+1]);private[str(target)]=sha256(target)
            if '-MF' in argv:
                dep=Path(argv[argv.index('-MF')+1]);private[str(dep)]=sha256(dep)
                inputs={}
                for name in shlex.split(dep.read_text().replace('\\\n','').split(':',1)[1]):
                    path=Path(name).resolve(strict=True);inputs[str(path)]=sha256(path)
                    assert str(path) in bound or str(path) in private or str(path) in external
                outputs={str(target):sha256(target),str(dep):sha256(dep)}
                cache.write_text(json.dumps(dict(argv=argv,files={**inputs,**outputs},outputs=outputs,compilerInputs=compiler_inputs))+'\n')
    def generated(path,text):path.write_text(text);private[str(path)]=sha256(path)
    cc,cxx=(shlex.split(os.environ.get(k,v)) for k,v in (('CC','cc'),('CXX','c++')))
    compiler_inputs={str(Path(shutil.which(command[0]) or command[0]).resolve(strict=True)):sha256(Path(shutil.which(command[0]) or command[0]).resolve(strict=True)) for command in (cc,cxx)}
    bound.update(compiler_inputs)
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
    primitive_units=('userspace/pipe_opengpu.c','userspace/opengpu.c')
    common=['-std=c11','-O1','-UNDEBUG','-DHAVE_ENDIAN_H','-D_POSIX_C_SOURCE=200809L','-Wall','-Wextra','-Werror',
            '-Wno-unused-parameter','-Wno-unused-function','-Wno-sign-compare','-ffunction-sections','-fdata-sections']
    links={}
    for unit in GPU_UNITS:
        obj=out/(Path(unit).stem+'.o');cpp=unit.endswith('.cpp')
        extra=['-D'+n+'=cs_fixture_'+n for n in ('ioctl','mmap','munmap','dup','close')] if unit in primitive_units else []
        run((cxx if cpp else cc)+(flags(MESA_UNITS[0])+['-UNDEBUG','-Wall','-Wextra','-Werror','-Wno-unused-parameter'] if cpp else common)+extra+inc+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',ROOT/unit,'-o',obj],obj.stem);objects.append(obj)
    binary=out/'native-five'
    link=cxx+san+['-rdynamic','-Wl,--gc-sections']+objects+[mb/'src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o',
         '-Wl,--start-group']+[mb/p for p in MESA_LIBRARIES]+['-Wl,--end-group']+shlex.split(os.environ['OPENGPU_MESA_LINK_LIBS'])+['-ldl','-lm','-pthread','-o',binary]
    run(link,'link')
    dependencies=set()
    for dep in (obj.with_suffix('.d') for obj in objects):
        for name in shlex.split(dep.read_text().replace('\\\n',' ').split(':',1)[1]):
            path=Path(name).resolve(strict=True);dependencies.add(path)
            assert str(path) in bound or str(path) in private or str(path) in external,'unbound MMD '+str(path)
    assert snapshot(mesa,mb,libraries,drm,gpu)==external;unchanged()
    sources=out/'sources';sources.mkdir(exist_ok=a.reuse_objects)
    for sample in (False,True):
        name='full7' if sample else 'full5';write_sources(sources,name,sample)
        for path in sources.iterdir():private[str(path)]=sha256(path)
        for suffix in ('.ognsf','.vs.words','.tcs.words','.tes.words','.gs.words','.fs.words'):
            (out/(name+suffix)).unlink(missing_ok=True)
        run([binary,*[sources/(name+'.'+suffix) for suffix in ('vert','tesc','tese','geom','frag')],out/name,int(sample)],'actual-'+name)
        for suffix in ('.ognsf','.vs.words','.tcs.words','.tes.words','.gs.words','.fs.words'):
            path=out/(name+suffix);private[str(path)]=sha256(path)
    write_sources(sources,'pixel-inputs',False)
    fragment=sources/'pixel-inputs.frag'
    generated(fragment,fragment.read_text().replace('color1=color0;', 'color0^=uvec4(uint(gl_HelperInvocation)^uint(gl_SampleMaskIn[0]));color1=color0;'))
    for path in sources.iterdir():private[str(path)]=sha256(path)
    for suffix in ('.ognsf','.vs.words','.tcs.words','.tes.words','.gs.words','.fs.words'):(out/('pixel-inputs'+suffix)).unlink(missing_ok=True)
    run([binary,*[sources/('pixel-inputs.'+s) for s in ('vert','tesc','tese','geom','frag')],out/'pixel-inputs',0],'actual-pixel-inputs-without-planes')
    for suffix in ('.ognsf','.vs.words','.tcs.words','.tes.words','.gs.words','.fs.words'):
        path=out/('pixel-inputs'+suffix);private[str(path)]=sha256(path)
    write_sources(sources,'pointcoord',False)
    fragment=sources/'pointcoord.frag';geometry=sources/'pointcoord.geom'
    generated(fragment,fragment.read_text().replace('color1=color0;', 'color0^=uvec4(floatBitsToUint(gl_PointCoord.x)^floatBitsToUint(gl_PointCoord.y));color1=color0;'))
    generated(geometry,geometry.read_text().replace('layout(line_strip,max_vertices=2)', 'layout(points,max_vertices=2)'))
    for path in sources.iterdir():private[str(path)]=sha256(path)
    for suffix in ('.ognsf','.vs.words','.tcs.words','.tes.words','.gs.words','.fs.words'):(out/('pointcoord'+suffix)).unlink(missing_ok=True)
    run([binary,*[sources/('pointcoord.'+s) for s in ('vert','tesc','tese','geom','frag')],out/'pointcoord',0],'actual-pointcoord-without-planes')
    for suffix in ('.ognsf','.vs.words','.tcs.words','.tes.words','.gs.words','.fs.words'):
        path=out/('pointcoord'+suffix);private[str(path)]=sha256(path)
    for name,mode in (('centroid',3),('depth',4)):
        write_sources(sources,name,False)
        fragment=sources/(name+'.frag');geometry=sources/(name+'.geom')
        if name=='centroid':
            generated(geometry,geometry.read_text().replace('flat out uvec4 forwarded;', 'out vec4 forwarded;').replace('forwarded=values[0]^uvec4(sum);','forwarded=uintBitsToFloat(values[0]^uvec4(sum));'))
            generated(fragment,fragment.read_text().replace('flat in uvec4 forwarded;', 'centroid in vec4 forwarded;').replace('color0=forwarded^', 'color0=floatBitsToUint(forwarded)^'))
        else:generated(fragment,fragment.read_text().replace('color1=color0;', 'gl_FragDepth=0.5;color1=color0;'))
        for path in sources.iterdir():private[str(path)]=sha256(path)
        for suffix in ('.ognsf','.vs.words','.tcs.words','.tes.words','.gs.words','.fs.words'):(out/(name+suffix)).unlink(missing_ok=True)
        run([binary,*[sources/(name+'.'+s) for s in ('vert','tesc','tese','geom','frag')],out/name,mode],'actual-'+name)
        for suffix in ('.ognsf','.vs.words','.tcs.words','.tes.words','.gs.words','.fs.words'):
            path=out/(name+suffix);private[str(path)]=sha256(path)
    write_sources(sources,'pixel-mask',False)
    fragment=sources/'pixel-mask.frag'
    generated(fragment,fragment.read_text().replace('color1=color0;', 'color0^=uvec4(uint(gl_HelperInvocation));gl_SampleMask[0]=gl_SampleMaskIn[0];color1=color0;'))
    for path in sources.iterdir():private[str(path)]=sha256(path)
    run([binary,*[sources/('pixel-mask.'+s) for s in ('vert','tesc','tese','geom','frag')],out/'pixel-mask',2],'original-pixel-mask-rejected')
    if a.negative_controls:
        controls=(
          ('gs-default-retention','userspace/gallium/opengpu_native_five_constants.c',
           'v->refs++;b->slots[s][k]=v;', 'if(s!=3||k){v->refs++;}b->slots[s][k]=v;',r'GS_DEFAULT_LEASE_LOST'),
          ('evaluation-component-map','userspace/gallium/opengpu_native_five_program.c',
           'component=c.vertex[slot].base+bits(c.vertex[slot].mask&((1u<<ch)-1));','component=0;',r'd\.evaluation_components\[4\]\.component\s*==\s*4'),
          ('geometry-layer-load','userspace/gallium/opengpu_native_uniform_emit.c',
           'arrays[c]=b->v2->requires_geometry_owner?9+4*b->layout.varyings:~0u;', 'arrays[c]=~0u;',r'found_layer'),
        )
        for name,unit,old,new,expected in controls:
            text=(ROOT/unit).read_text();assert text.count(old)==1,(name,text.count(old))
            changed=out/(name+'.c');generated(changed,text.replace(old,new))
            original=out/(Path(unit).stem+'.o')
            record=next(c for c in commands if c['label']==Path(unit).stem)
            argv=list(record['argv']);obj=out/(name+'.o')
            argv[argv.index('-c')+1]=changed;argv[argv.index('-o')+1]=obj;argv[argv.index('-MF')+1]=out/(name+'.d')
            run(argv,'compile-'+name)
            executable=out/name;argv=list(link);argv[argv.index(original)]=obj;argv[argv.index('-o')+1]=executable
            run(argv,'link-'+name)
            run([executable,*[sources/('full5.'+s) for s in ('vert','tesc','tese','geom','frag')],out/('rejected-'+name),0],name,failure=expected)
    unchanged();assert snapshot(mesa,mb,libraries,drm,gpu)==external
    print('NATIVE_FIVE_CPU retained65refs code5 PASS; actualMMD='+str(len(dependencies))+' GPU=false submit=false',flush=True)


def write_sources(out,name,sample):
    for stage,suffix in enumerate(('vert','tesc','tese','geom','frag')):
        text='#version 320 es\nprecision highp float;precision highp int;\n'
        text+='uniform uvec4 defaults[256];\n'
        for i in range(12):
            binding=stage*12+i if stage<4 else 60+i
            text+=f'layout(std140,binding={binding}) uniform B{stage}_{i}{{uvec4 data[1024];}} block{i};\n'
        if stage==0:text+='layout(location=0) in vec4 position;layout(location=15) out uvec4 payload;\n'
        elif stage==1:text+='layout(vertices=2) out;layout(location=15) in uvec4 payload[];layout(location=7) out uvec4 bridge[];\n'
        elif stage==2:text+='layout(isolines,equal_spacing) in;layout(location=7) in uvec4 bridge[];layout(location=13) flat out uvec4 values;\n'
        elif stage==3:text+='layout(lines) in;layout(line_strip,max_vertices=2) out;layout(location=13) flat in uvec4 values[];layout(location=15) flat out uvec4 forwarded;\n'
        else:text+='layout(location=15) flat in uvec4 forwarded;layout(location=0) out uvec4 color0;layout(location=1) out uvec4 color1;layout(location=2) out uvec4 color2;layout(location=3) out uvec4 color3;\n'
        text+='void main(){uint sum=defaults[0].x^defaults[255].w;\n'
        for i in range(12):text+=f'sum=(sum*33u)^block{i}.data[0].x^block{i}.data[1023].w;\n'
        if stage==0:text+='gl_Position=position;payload=uvec4(sum);}'
        elif stage==1:text+='gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;bridge[gl_InvocationID]=payload[gl_InvocationID]^uvec4(sum^uint(gl_PrimitiveID));gl_TessLevelOuter[gl_InvocationID]=1.;barrier();}'
        elif stage==2:text+='gl_Position=gl_in[0].gl_Position+vec4(gl_TessCoord.x,0.,0.,0.);values=bridge[0]^uvec4(sum^uint(gl_PrimitiveID));}'
        elif stage==3:text+='gl_Position=gl_in[0].gl_Position;forwarded=values[0]^uvec4(sum);gl_PrimitiveID=gl_PrimitiveIDIn;gl_Layer=0;EmitVertex();EndPrimitive();}'
        else:text+='color0=forwarded^uvec4(sum^uint(gl_PrimitiveID)^uint(gl_Layer)'+('^uint(gl_SampleID)' if sample else '')+');color1=color0;color2=color0;color3=color0;}'
        (out/(name+'.'+suffix)).write_text(text+'\n')

if __name__=='__main__':main()
