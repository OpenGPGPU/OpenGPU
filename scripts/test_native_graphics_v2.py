#!/usr/bin/env python3
"""Opt-in original Mesa FS8/9 program/source/draw lifecycle CPU test.

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
 'userspace/pipe_opengpu.c','userspace/opengpu.c','userspace/tests/native_graphics_v2_resources.c',
 'userspace/tests/graphics_uniform_frontend.cpp','userspace/tests/native_graphics_v2_frontend.cpp')

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


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--build',type=Path,required=True)
    ap.add_argument('--legacy-source',type=Path,required=True)
    ap.add_argument('--sanitize',action='store_true');ap.add_argument('--negative-controls',action='store_true');a=ap.parse_args()
    out=a.build.resolve();out.mkdir(parents=True,exist_ok=False)
    mesa=Path(os.environ['OPENGPU_MESA_DIR']).resolve();mb=Path(os.environ['OPENGPU_MESA_BUILD']).resolve()
    drm=Path(os.environ['OPENGPU_DRM_INCLUDE']).absolute();gpu=mesa.parents[1];libraries=drm.parent/'lib'
    external=snapshot(mesa,mb,libraries,drm,gpu)
    # All actual quoted GPU inputs including .inc, Python, three public schemas
    # are fixed BEFORE compilation. Actual MMD must be a subset, not posthoc.
    bound={str(p.resolve()):sha256(p) for root in ('userspace','driver','scripts','docs/abi')
           for p in (ROOT/root).rglob('*') if p.is_file() and not p.is_symlink() and
           p.suffix in ('.c','.cpp','.h','.inc','.py','.json')}
    baseline=a.legacy_source.resolve(strict=True);bound[str(baseline)]=sha256(baseline)
    private={};commands=[];objects=[]
    def unchanged():
        assert all(sha256(Path(p))==h for p,h in {**bound,**private}.items())
    def run(argv,label,expected=0):
        argv=list(map(str,argv));unchanged()
        if '-c' in argv:
            src=Path(argv[argv.index('-c')+1]).resolve(strict=True)
            assert str(src) in bound or str(src) in private or str(src) in external
        commands.append(dict(argv=argv,cwd=str(out),label=label));(out/'commands.json').write_text(json.dumps(commands)+'\n')
        with (out/(label+'.log')).open('w') as f:
            p=subprocess.Popen(argv,cwd=out,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
            try:code=p.wait(timeout=180)
            finally:stop_group(p)
        unchanged()
        if code!=expected:raise RuntimeError(f'{label}: exit {code}, expected {expected}\n'+(out/(label+'.log')).read_text()[-6000:])
        if not code and '-o' in argv:
            target=Path(argv[argv.index('-o')+1]);private[str(target)]=sha256(target)
            if '-MF' in argv:
                dep=Path(argv[argv.index('-MF')+1]);private[str(dep)]=sha256(dep)
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
    base=base.replace(anchor,'   opengpu_frontend_compiler_limits(ctx);\n\n'+anchor)
    generated(out/'standalone.cpp','#include "main/mtypes.h"\nextern void opengpu_frontend_compiler_limits(gl_context *);\n'+base)
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
    obj=out/'reference-uniform.o'
    run(cc+common+['-Dopengpu_native_uniform_emit=reference_uniform_emit','-Dopengpu_native_uniform_compile=reference_uniform_compile',
          '-Dopengpu_native_vertex_tess_compile=reference_vertex_tess_compile']+inc+san+
          ['-MMD','-MF',obj.with_suffix('.d'),'-c',baseline,'-o',obj],obj.stem);objects.append(obj)
    binary=out/'native-graphics-v2'
    link=cxx+san+['-rdynamic','-Wl,--gc-sections']+objects+[mb/'src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o',
         '-Wl,--start-group']+[mb/p for p in MESA_LIBRARIES]+['-Wl,--end-group']+shlex.split(os.environ['OPENGPU_MESA_LINK_LIBS'])+['-ldl','-lm','-pthread','-o',binary]
    run(link,'link')
    dependencies=set()
    for dep in out.glob('*.d'):
        for name in shlex.split(dep.read_text().replace('\\\n','').split(':',1)[1]):
            path=Path(name).resolve(strict=True);dependencies.add(path)
            assert str(path) in bound or str(path) in private or str(path) in external,'unbound MMD '+str(path)
    assert len(list(out.glob('*.d')))==30
    assert snapshot(mesa,mb,libraries,drm,gpu)==external;unchanged()
    from test_gallium_graphics_uniforms import write_sources
    sources=out/'sources';sources.mkdir();write_sources(sources)
    for p in sources.iterdir():private[str(p)]=sha256(p)
    run([binary,sources/'full.vert',sources/'full.frag','legacy'],'legacy-original-308-301')
    generated(sources/'full320.vert',(sources/'full.vert').read_text().replace('#version 310','#version 320'))
    for name,expr in (('full8','uint(gl_PrimitiveID)'),('full9','uint(gl_PrimitiveID)^uint(gl_SampleID)')):
        generated(sources/(name+'.frag'),(sources/'full.frag').read_text().replace('#version 310','#version 320').replace('color=varyingValue ^ uvec4(sum);','color=varyingValue ^ uvec4(sum ^ '+expr+');'))
    cases=ROOT/'userspace/tests/native_fragment_v2_essl'
    generated(sources/'centroid.vert','#version 320 es\nprecision highp float;layout(location=0) in vec4 position;layout(location=15) out vec4 values;void main(){gl_Position=position;values=position;}\n')
    generated(sources/'centroid.frag','#version 320 es\nprecision highp float;precision highp int;layout(location=15) centroid in vec4 values;layout(location=0) out vec4 color;void main(){color=values+vec4(float(gl_PrimitiveID));}\n')
    cases_to_run=[('full8',sources/'full320.vert',sources/'full8.frag',1),('full9',sources/'full320.vert',sources/'full9.frag',1),
      ('flat',cases/'ordinary.vert',cases/'flat-mrt.frag',0),('depth',cases/'ordinary.vert',cases/'depth-mask.frag',0),
      ('centroid',sources/'centroid.vert',sources/'centroid.frag',0)]
    for name,vs,fs,full in cases_to_run:
        target=out/name;run([binary,vs,fs,target,full],'actual-'+name);verify_records(target)
    run([binary,sources/'full.vert',sources/'full.frag','legacy'],'legacy-original-after-v2')
    if a.negative_controls:
        def mutant(name,unit,old,new,args,needle):
            source=(ROOT/unit).read_text();assert source.count(old)==1
            path=out/(name+'.c');generated(path,source.replace(old,new));obj=out/(name+'.o')
            run(cc+common+inc+san+['-MMD','-MF',obj.with_suffix('.d'),'-c',path,'-o',obj],name+'-compile')
            changed=[str(obj) if str(v)==str(out/(Path(unit).stem+'.o')) else str(v) for v in link]
            assert changed.count(str(obj))==1;changed[-1]=str(out/name);run(changed,name+'-link')
            run([out/name,*args],name+'-rejected',-6)
            assert needle in (out/(name+'-rejected.log')).read_text()
        mutant('mutant-no-resource-retain','userspace/gallium/opengpu_context.c',
          'pipe_resource_reference(&lease.resources[stage][slot],cb->buffer);',
          '(void)cb;', [sources/'full320.vert',sources/'full8.frag',out/'mutant-refs',1], 'bos[handle].handle')
        mutant('mutant-abi-retag','userspace/gallium/opengpu_native_draw_v2.c',
          'f[F(FRAGMENT_VERSION)]=fs->abi_version;','f[F(FRAGMENT_VERSION)]=1;',
          [cases/'ordinary.vert',cases/'flat-mrt.frag',out/'mutant-abi',0], 'e.state.words[56]==m->abi_version')
    unchanged();assert snapshot(mesa,mb,libraries,drm,gpu)==external
    print(f'NATIVE_GRAPHICS_V2_CPU PASS actualMesa lifecycle5 legacy308/301 negativeControls={a.negative_controls} units30 MMDdeps={len(dependencies)} external={len(external)} GPU=false submit=false')
if __name__=='__main__':main()
