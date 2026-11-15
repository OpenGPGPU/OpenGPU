#!/usr/bin/env python3
"""Opt-in original Mesa22.3.6 ESSL320 no-GS FS8/9 CPU compiler checks.

No device/ISA execution, submission or GPU qualification. Run in the immutable
512MiB combined-memory/one-CPU/no-network image, with original inputs read-only.
Mesa libraries are reused; only private frontend/scaffolding units are compiled.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
from native_sample_compiler_inputs import MESA_LIBRARIES, MESA_UNITS, snapshot, sha256

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--build', type=Path, required=True)
    ap.add_argument('--legacy-source',type=Path,required=True,help='read-only original bcdbdfd70 emitter sources')
    ap.add_argument('--negative-controls',action='store_true')
    ap.add_argument('--sanitize', action='store_true')
    a = ap.parse_args()
    out = a.build.resolve(); out.mkdir(parents=True, exist_ok=False)
    mesa = Path(os.environ.get('OPENGPU_MESA_DIR', ROOT/'depends/mesa')).resolve()
    mb = Path(os.environ.get('OPENGPU_MESA_BUILD', ROOT/'depends/mesa-build')).resolve()
    sdk = Path(os.environ['OPENGPU_DRM_INCLUDE']).resolve().parent
    gpu = mesa.parents[1]
    external = snapshot(mesa, mb, sdk/'lib', gpu)
    # Bind the actual direct Mesa object even when GC discards unused callbacks.
    st_object = mb/'src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o'
    direct = {str(st_object): sha256(st_object)}
    owned = ('userspace/gallium/opengpu_native_fragment_v2.h','userspace/gallium/opengpu_native_fragment_v2.c',
             'userspace/gallium/opengpu_native_fs_emit.h','userspace/gallium/opengpu_native_fs_emit.c',
             'userspace/gallium/opengpu_native_uniform_emit.h','userspace/gallium/opengpu_native_uniform_emit.c',
             'userspace/tests/fs_glsl_frontend.h','userspace/tests/fs_glsl_frontend.cpp',
             'userspace/tests/graphics_uniform_frontend.h','userspace/tests/graphics_uniform_frontend.cpp',
             'userspace/tests/native_fragment_reflection_v2.h','userspace/tests/native_fragment_v2_compile.c',
             'scripts/test_native_fragment_v2.py','scripts/native_sample_compiler_inputs.py',
             'scripts/apply_mesa_uniform_index_patch.py','scripts/native_fragment_v2_cpu_oracle.py',
             'scripts/native_fs_cpu_oracle.py')
    sources = {str(ROOT/p): sha256(ROOT/p) for p in owned}
    baseline=a.legacy_source.resolve(strict=True)
    sources.update({str(baseline/name):sha256(baseline/name) for name in ('opengpu_native_fs_emit.c','opengpu_native_uniform_emit.c')})
    cases=ROOT/'userspace/tests/native_fragment_v2_essl'
    source_cases={str(p):sha256(p) for p in cases.iterdir() if p.is_file()}
    sources.update(source_cases)
    sources.update({str(p):sha256(p) for p in (ROOT/'userspace/tests/native_sample_essl').glob('*.frag')})
    database = json.loads((mb/'compile_commands.json').read_text())
    cc, cxx = (shlex.split(os.environ.get(k,d)) for k,d in (('CC','cc'),('CXX','c++')))
    san = ['-fsanitize=address,undefined','-fno-omit-frame-pointer'] if a.sanitize else []
    commands = []; private_inputs={}
    def run(argv,label,expected=0):
        argv = list(map(str,argv))
        if '-c' in argv:
            source=Path(argv[argv.index('-c')+1]).resolve(strict=True)
            bound=sha256(source)
            if source.is_relative_to(out):private_inputs[str(source)]=bound
            else:assert bound==sources.get(str(source),external.get(str(source),(None,None))[1])
        # Before linking/running, all private generated sources, prior objects
        # and dependency files must still match their creation/compile snapshot.
        assert all(sha256(Path(p))==h for p,h in private_inputs.items())
        commands.append({'argv':argv,'cwd':str(out),'label':label})
        (out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
        with (out/(label+'.log')).open('w') as f:
            r = subprocess.run(argv,cwd=out,stdout=f,stderr=subprocess.STDOUT)
        assert all(sha256(Path(p))==h for p,h in private_inputs.items())
        if r.returncode==0 and '-c' in argv:
            obj=Path(argv[argv.index('-o')+1]);private_inputs[str(obj)]=sha256(obj)
            dep=Path(argv[argv.index('-MF')+1]);private_inputs[str(dep)]=sha256(dep)
        if r.returncode != expected:
            raise RuntimeError(f'{label}: actual {r.returncode}, expected {expected}\n'+(out/(label+'.log')).read_text()[-5000:])
    def flags(relative):
        source = mesa/'src'/relative
        e = next(e for e in database if (Path(e['directory'])/e['file']).resolve()==source)
        raw = e.get('arguments') or shlex.split(e['command']); result=[]; skip=False
        for v in raw[1:]:
            if skip: skip=False; continue
            if v in ('-o','-MF','-MT','-MQ'): skip=True; continue
            if v in ('-c','-MD','-MMD'): continue
            if not v.startswith('-') and (Path(e['directory'])/v).resolve()==source: continue
            if v.startswith('-I') and not Path(v[2:]).is_absolute(): v='-I'+str((Path(e['directory'])/v[2:]).resolve())
            result.append(v)
        return result
    inc=['-I'+str(p) for p in (ROOT/'userspace/gallium',ROOT/'userspace/tests',mesa/'src/gallium/include',
         mesa/'src/gallium/auxiliary',mesa/'src',mesa/'include',mesa/'src/compiler/nir',mb/'src/compiler/nir')]
    objects=[]
    for unit in MESA_UNITS[1:]:
        obj=out/(unit.replace('/','_')+'.o')
        run((cxx if unit.endswith('.cpp') else cc)+flags(unit)+['-UNDEBUG','-ffunction-sections','-fdata-sections']+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',mesa/'src'/unit,'-o',obj],obj.stem);objects.append(obj)
    # Checked lowering is an explicit original-Mesa postimage, not a guessed bank.
    from apply_mesa_uniform_index_patch import AFTER
    lower=mesa/'src/compiler/nir/nir_lower_io.c'
    assert sha256(lower)==AFTER
    obj=out/'checked-lower.o'
    run(cc+flags('compiler/nir/nir_lower_io.c')+['-UNDEBUG']+san+
        ['-MMD','-MF',obj.with_suffix('.d'),'-c',lower,'-o',obj],obj.stem);objects.append(obj)
    for unit in ('userspace/gallium/opengpu_native_fragment_v2.c','userspace/gallium/opengpu_native_fs_emit.c',
                 'userspace/gallium/opengpu_native_uniform_emit.c'):
        obj=out/(Path(unit).stem+'.o')
        run(cc+['-std=c11','-O1','-DHAVE_ENDIAN_H','-UNDEBUG','-Wall','-Wextra','-Werror','-ffunction-sections','-fdata-sections']+inc+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',ROOT/unit,'-o',obj],obj.stem);objects.append(obj)
    # Frozen original source, compiled under private symbols, compares the old
    # public entry points byte-for-byte rather than asserting only word counts.
    for name,renames in (
        ('opengpu_native_fs_emit.c',{'opengpu_native_fs_emit':'reference_fs_emit','opengpu_native_fs_write':'reference_fs_write'}),
        ('opengpu_native_uniform_emit.c',{'opengpu_native_uniform_emit':'reference_uniform_emit',
            'opengpu_native_uniform_compile':'reference_uniform_compile','opengpu_native_vertex_tess_compile':'reference_vertex_tess_compile'})):
        original=(baseline/name).read_bytes()
        private=out/('reference-'+name);private.write_bytes(original);obj=private.with_suffix('.o')
        run(cc+['-std=c11','-O1','-DHAVE_ENDIAN_H','-UNDEBUG','-ffunction-sections','-fdata-sections']+
            ['-D'+k+'='+v for k,v in renames.items()]+inc+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',private,'-o',obj],obj.stem);objects.append(obj)
    base=(mesa/'src/compiler/glsl/standalone.cpp').read_text()
    anchor='   if (options->lower_precision) {'; assert base.count(anchor)==1
    base=base.replace(anchor,'   opengpu_frontend_compiler_limits(ctx);\n\n'+anchor)
    binaries={};links={}
    for kind in ('raw','uniform'):
        text=base
        if kind=='raw':
            anchor='   whole_program->data->InfoLog = ralloc_strdup(whole_program->data, "");'
            assert text.count(anchor)==1
            text=text.replace(anchor,anchor+'\n   whole_program->SeparateShader = true;')
        private=out/(kind+'-standalone.cpp')
        private.write_text('#include "main/mtypes.h"\nextern void opengpu_frontend_compiler_limits(gl_context *);\n'+text)
        obj=out/(kind+'-standalone.o')
        run(cxx+flags('compiler/glsl/standalone.cpp')+['-UNDEBUG','-ffunction-sections','-fdata-sections']+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',private,'-o',obj],obj.stem)
        link=[*objects,obj,st_object]
        for unit in ('userspace/tests/'+('fs_glsl_frontend.cpp' if kind=='raw' else 'graphics_uniform_frontend.cpp'),
                     'userspace/tests/native_fragment_v2_compile.c'):
            obj=out/(kind+'-'+Path(unit).stem+'.o')
            opts=flags('compiler/glsl/standalone.cpp') if unit.endswith('.cpp') else ['-std=c11','-O1','-DHAVE_ENDIAN_H']
            run((cxx if unit.endswith('.cpp') else cc)+opts+['-UNDEBUG','-Wall','-Wextra','-Werror','-Wno-unused-parameter',
                '-ffunction-sections','-fdata-sections']+(['-DRAW_FRONTEND'] if kind=='raw' else [])+inc+san+
                ['-MMD','-MF',obj.with_suffix('.d'),'-c',ROOT/unit,'-o',obj],obj.stem);link.append(obj)
        binary=out/(kind+'-fragment-v2')
        run(cxx+san+['-Wl,--gc-sections']+link+['-Wl,--start-group']+[mb/p for p in MESA_LIBRARIES]+['-Wl,--end-group']+
            shlex.split(os.environ.get('OPENGPU_MESA_LINK_LIBS','-lz -lexpat'))+['-ldl','-lm','-pthread','-o',binary],kind+'-link')
        binaries[kind]=binary;links[kind]=commands[-1]['argv']
    generated={str(p):sha256(p) for p in out.iterdir() if p.is_file() and p.suffix in ('.c','.cpp','.o','.d')}
    generated.update({str(p):sha256(p) for p in binaries.values()})
    assert snapshot(mesa,mb,sdk/'lib',gpu)==external
    assert all(sha256(Path(p))==h for p,h in {**sources,**direct,**generated}.items())
    # MMD dependencies are checked against the before-build source/external map.
    actual=set()
    for dep in out.glob('*.d'):
        for name in shlex.split(dep.read_text().replace('\\\n','').split(':',1)[1]):
            path=Path(name).resolve(strict=True); actual.add(path)
            if path.is_relative_to(out): assert str(path) in generated
            else: assert str(path) in sources or str(path) in external, 'unbound input '+str(path)
    assert len(list(out.glob('*.d')))==19
    cases=ROOT/'userspace/tests/native_fragment_v2_essl'
    vs=cases/'ordinary.vert'
    source_cases={str(p):sha256(p) for p in cases.iterdir() if p.is_file()}
    from native_fragment_v2_cpu_oracle import verify
    for name,abi,static,uniforms in (
        ('primitive',8,192,0),('sample',9,67,0),('mask',8,68,0),('helper',8,320,0),
        ('static-sample',9,65,0),('flat-mrt',8,64,0),('depth-mask',9,65,0),('uniforms',8,64,1)):
        for kind,binary in binaries.items():
            if uniforms and kind=='raw':continue
            target=out/(kind+'-'+name+'.ognsf')
            run([binary,vs,cases/(name+'.frag'),target,abi,static,uniforms],kind+'-'+name)
            run([binary,'--reject-legacy-frontend',vs,cases/(name+'.frag')],kind+'-'+name+'-legacy-frontend')
            packets=[target.with_name(target.name+'.uniform')]
            if not uniforms:packets.append(target)
            for packet in packets:
                data=packet.read_bytes();assert data[:8]==b'OGNSF002'
                h=struct.unpack_from('<42I',data,8)
                assert h[0:5]==(2,abi,73 if abi==8 else 76,512,46592 if abi==8 else 48128)
                assert h[9:14]==(int(abi==9),0,128,16,4) and h[15:18]==(1,uniforms,static)
                assert h[41]==0 and 0<h[14]<=512 and len(data)==176+h[14]*4
                if name=='flat-mrt':assert h[18:24]==(0,0xf0000000,0,0xf0000000,0,0) and h[6]==15
                if name=='uniforms':assert h[24:37]==(4096,)+(16384,)*12
                for count in (1,2,4):verify(packet,name,count)
                for label,mut in (('oldmagic',b'OGNSF001'+data[8:]),('extra',data+b'\0'),('short',data[:-1]),
                                  ('reserved',data[:172]+struct.pack('<I',1)+data[176:]),
                                  ('wideprefix',data[:24]+struct.pack('<I',0xffffffff)+data[28:]),
                                  ('oldabi',data[:12]+struct.pack('<I',6)+data[16:])):
                    bad=out/'bad.ognsf';bad.write_bytes(mut)
                    run([binary,'--reject-container',bad],kind+'-'+name+'-'+label)
                    bad.unlink()
    for name in ('sample','mask-depth','mask-depth-keep','mrt','varying','signedness','truncation'):
        for abi in (6,7):
            original=ROOT/'userspace/tests/native_sample_essl'/(name+'.frag')
            target=out/'legacy.ognsf'
            run([binaries['raw'],'--legacy-raw',original,abi,target],'legacy-'+name+'-'+str(abi))
            run([binaries['raw'],'--reject-container',target],'reject-001-'+name+'-'+str(abi));target.unlink()
    vs310=out/'legacy.vert';vs310.write_text(vs.read_text().replace('#version 320','#version 310'))
    fs310=out/'legacy.frag';fs310.write_text((cases/'uniforms.frag').read_text().replace('#version 320','#version 310').replace('uint(gl_PrimitiveID)^',''))
    run([binaries['uniform'],'--legacy-uniform',vs310,fs310],'legacy-original-uniform-bytes')
    # ESSL310 is not silently admitted by the explicit new frontend.
    old_source=out/'old-language.frag'
    old_source.write_text((cases/'primitive.frag').read_text().replace('#version 320','#version 310'))
    for kind,binary in binaries.items():
        run([binary,vs,old_source,out/'never.ognsf',8,192,0],kind+'-reject-old-language',3)
    if a.negative_controls:
        def mutant(name,unit,old,new):
            original=(ROOT/unit).read_text();assert original.count(old)==1
            path=out/(name+'.c');path.write_text(original.replace(old,new));obj=path.with_suffix('.o')
            bound=sha256(path)
            run(cc+['-std=c11','-O1','-DHAVE_ENDIAN_H','-UNDEBUG']+inc+san+
                ['-MMD','-MF',obj.with_suffix('.d'),'-c',path,'-o',obj],name+'-compile')
            binary=out/name
            original_obj=str(out/(Path(unit).stem+'.o'))
            argv=[str(obj) if v==original_obj else v for v in links['raw']]
            assert argv.count(str(obj))==1
            argv[-1]=str(binary);run(argv,name+'-link')
            assert sha256(path)==bound
            return binary
        gate=mutant('mutant-prerequisite','userspace/gallium/opengpu_native_fragment_v2.c',
                    'c->primitive_transport!=1||','')
        run([gate,vs,cases/'sample.frag',out/'mutant-gate.ognsf',9,67,0],'mutant-prerequisite-rejected',-6)
        assert 'opengpu_native_fs_emit_v2(tokens,n,r,c,&p)' in (out/'mutant-prerequisite-rejected.log').read_text()
        offset=mutant('mutant-primitive-offset','userspace/gallium/opengpu_native_fs_emit.c',
                      '*slice=8+4*b->v2->varying_slots;','*slice=7+4*b->v2->varying_slots;')
        target=out/'mutant-offset.ognsf'
        run([offset,vs,cases/'primitive.frag',target,8,192,0],'mutant-primitive-offset-produced')
        try:verify(target,'primitive',1)
        except AssertionError as error:assert error.args[0][0]=='primitive'
        else:raise AssertionError('incorrect original PrimitiveID load was not rejected')
    assert snapshot(mesa,mb,sdk/'lib',gpu)==external
    assert all(sha256(Path(p))==h for p,h in {**sources,**source_cases,**direct,**generated}.items())
    print(f'FRAGMENT_V2_CPU PASS actualMesa22.3.6 dependencies={len(actual)} ABI8/9 originalESSL programs=15 execution=false')

if __name__=='__main__':main()
