#!/usr/bin/env python3
"""Opt-in real Mesa22.3.6 ESSL320 tessellation and geometry frontend tests.

Link configured, immutable Mesa libraries; compile only private scaffolding
and GPU frontend/tests. Use a fresh --build directory; delete ended outputs.
"""
import argparse, json, os, shlex, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
LIBS=("src/compiler/glsl/libglsl.a","src/compiler/nir/libnir.a","src/compiler/libcompiler.a",
      "src/compiler/glsl/glcpp/libglcpp.a","src/gallium/auxiliary/libgallium.a","src/util/libmesa_util.a",
      "src/util/format/libmesa_format.a","src/c11/impl/libmesa_util_c11.a")
def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--build',type=Path,required=True)
    ap.add_argument('--sanitize',action='store_true');ap.add_argument('--baseline-source',type=Path,help='explicit original v2 production emitter for old-byte comparison, never a replacement runtime');ap.add_argument('--regressions',action='store_true',help='with --language, reuse this immutable build for old v1/v2 ESSL suites');mode=ap.add_mutually_exclusive_group();mode.add_argument('--dynamic',action='store_true',help='private TES v2 CPU compiler cases');mode.add_argument('--language',action='store_true',help='private TCS/TES v3 language input-patch ID CPU cases')
    mode.add_argument('--geometry', action='store_true', help='linked VS/GS/FS and native ABI7 CPU compiler case')
    a=ap.parse_args()
    if a.regressions and not a.language:ap.error('--regressions requires --language')
    if a.baseline_source and not a.regressions:ap.error('--baseline-source requires --regressions')
    out=a.build.resolve();out.mkdir(parents=True,exist_ok=False)
    mesa=Path(os.environ.get('OPENGPU_MESA_DIR',ROOT/'depends/mesa')).resolve()
    mb=Path(os.environ.get('OPENGPU_MESA_BUILD',ROOT/'depends/mesa-build')).resolve()
    assert (mesa/'VERSION').read_text().strip()=='22.3.6'
    database=json.loads((mb/'compile_commands.json').read_text());commands=[]
    cc=shlex.split(os.environ.get('CC','cc'));cxx=shlex.split(os.environ.get('CXX','c++'))
    san=['-fsanitize=address,undefined','-fno-omit-frame-pointer'] if a.sanitize else []
    def run(argv,label):
        argv=list(map(str,argv));commands.append(dict(argv=argv,cwd=str(out),label=label))
        (out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
        subprocess.run(argv,cwd=out,check=True)
    def flags(relative):
        source=mesa/'src'/relative
        entry=next(e for e in database if (Path(e['directory'])/e['file']).resolve()==source)
        raw=entry.get('arguments') or shlex.split(entry['command']);result=[];skip=False
        for v in raw[1:]:
            if skip:skip=False;continue
            if v in ('-o','-MF','-MQ','-MT'):skip=True;continue
            if v in ('-c','-MD','-MMD'):continue
            if not v.startswith('-') and (Path(entry['directory'])/v).resolve()==source:continue
            if v.startswith('-I') and not Path(v[2:]).is_absolute():v='-I'+str((Path(entry['directory'])/v[2:]).resolve())
            result.append(v)
        return result
    standalone=(mesa/'src/compiler/glsl/standalone.cpp').read_text()
    anchor='   if (options->lower_precision) {';assert standalone.count(anchor)==1
    standalone=standalone.replace(anchor,'   opengpu_tess_frontend_limits(ctx);\n\n'+anchor)
    staged=out/'standalone.cpp';staged.write_text('#include "main/mtypes.h"\nextern "C" void opengpu_tess_frontend_limits(struct gl_context *);\n'+standalone)
    units=("compiler/glsl/standalone.cpp","compiler/glsl/standalone_scaffolding.cpp",
        "compiler/glsl/ir_builder_print_visitor.cpp","compiler/glsl/glcpp/pp_standalone_scaffolding.c",
        "mesa/main/extensions_table.c","mesa/program/prog_parameter.c","mesa/program/symbol_table.c","mesa/program/dummy_errors.c")
    objects=[]
    for name in units:
        obj=out/(name.replace('/','_')+'.o');source=staged if name==units[0] else mesa/'src'/name
        run((cxx if name.endswith('.cpp') else cc)+flags(name)+['-UNDEBUG']+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',source,'-o',obj],'compile-'+obj.stem);objects.append(obj)
    inc=['-I'+str(p) for p in (ROOT/'userspace/gallium',ROOT/'userspace',ROOT/'driver',mesa/'src/gallium/include',
        mesa/'src/gallium/auxiliary',ROOT/'userspace/tests',Path(os.environ.get('OPENGPU_DRM_INCLUDE',ROOT/'depends/aarch64-root/include')),mesa/'src/compiler/nir',mb/'src/compiler/nir',mesa/'src',mesa/'include')]
    test_source = ('geometry_link_frontend_test.cpp' if a.geometry else
                   'tessellation_language_frontend_test.cpp' if a.language else
                   'tessellation_dynamic_frontend_test.cpp' if a.dynamic else
                   'tessellation_uniform_frontend_test.cpp')
    for name in ('userspace/gallium/opengpu_tess_frontend.cpp', 'userspace/tests/' + test_source):
        obj=out/(Path(name).stem+'.o')
        run(cxx+flags(units[0])+['-UNDEBUG','-Wall','-Wextra','-Werror','-Wno-unused-parameter']+inc+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',ROOT/name,'-o',obj],'compile-'+obj.stem);objects.append(obj)
    gpu_units=('userspace/gallium/opengpu_native_tess_uniform_emit.c',
        'userspace/gallium/opengpu_native_uniform_emit.c','userspace/gallium/opengpu_native_fragment_v2.c',
        'userspace/gallium/opengpu_native_tess_constants.c',
        'userspace/gallium/opengpu_native_tess_program.c','userspace/gallium/opengpu_native_tess_context.c','userspace/tests/tessellation_uniform_resources_test.c',
        'userspace/gallium/opengpu_context.c','userspace/gallium/opengpu_screen.c',
        'userspace/gallium/opengpu_cs_emit.c','userspace/gallium/opengpu_fs_emit.c','userspace/gallium/opengpu_vs_emit.c',
        'userspace/pipe_opengpu.c','userspace/opengpu.c')
    if a.geometry:
        gpu_units += ('userspace/gallium/opengpu_native_fs_emit.c',)
    for name in gpu_units:
        obj=out/(Path(name).stem+'.o')
        extra=['-D'+n+'=cs_fixture_'+n for n in ('ioctl','mmap','munmap','dup','close')] if name in ('userspace/pipe_opengpu.c','userspace/opengpu.c') else []
        run(cc+['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-unused-function',
            '-Wno-sign-compare','-DHAVE_ENDIAN_H','-D_POSIX_C_SOURCE=200809L','-ffunction-sections','-fdata-sections']+inc+extra+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',ROOT/name,'-o',obj],'compile-'+obj.stem);objects.append(obj)
    if a.baseline_source:
        baseline=a.baseline_source.resolve();assert baseline.is_file()
        obj=out/'baseline-tess-emitter.o'
        rename=['-D'+n+'=baseline_'+n for n in ('opengpu_native_tess_uniform_emit',
            'opengpu_native_tess_control_emit','opengpu_native_tess_dynamic_emit',
            'opengpu_native_tess_uniform_reflect','opengpu_native_tess_dynamic_reflect')]
        run(cc+['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-sign-compare',
            '-DHAVE_ENDIAN_H','-ffunction-sections','-fdata-sections']+inc+san+rename+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',baseline,'-o',obj],'compile-original-tess-baseline');objects.append(obj)
        obj=out/'old-byte-comparator.o'
        run(cc+['-std=c11','-O1','-Wall','-Wextra','-Werror']+inc+san+
            ['-MMD','-MF',obj.with_suffix('.d'),'-c',ROOT/'userspace/tests/tessellation_language_legacy_compare.c','-o',obj],
            'compile-old-byte-comparator');objects.append(obj)
    # Same checked typed-uniform lowering postimage as the original frontend.
    from apply_mesa_uniform_index_patch import AFTER
    import hashlib
    lower=mesa/'src/compiler/nir/nir_lower_io.c'
    assert hashlib.sha256(lower.read_bytes()).hexdigest()==AFTER
    obj=out/'checked-nir-lower-io.o'
    run(cc+flags('compiler/nir/nir_lower_io.c')+['-UNDEBUG']+san+
        ['-MMD','-MF',obj.with_suffix('.d'),'-c',lower,'-o',obj],'compile-checked-nir-lower-io');objects.append(obj)
    objects.append(mb/'src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o')
    binary=out/'tess-frontend'
    run(cxx+san+['-rdynamic','-Wl,--gc-sections']+objects+['-Wl,--start-group']+[mb/p for p in LIBS]+['-Wl,--end-group']+
        shlex.split(os.environ.get('OPENGPU_MESA_LINK_LIBS','-lz -lexpat'))+['-ldl','-lm','-pthread','-o',binary],'link')
    if a.geometry:
        run([binary, ROOT/'userspace/tests/geometry_essl', out/'geometry'], 'actual-geometry-ESSL320')
        return
    for name in (('language','language-conditional','language-full') if a.language else ('dynamic','adjacent','capacity','edge','component') if a.dynamic else ('uniform','full','conditional')):(out/'programs'/name).mkdir(parents=True)
    run([binary,ROOT/'userspace/tests/tess_uniform_essl',out/'programs'],'actual-ESSL320')
    if a.regressions:
        original_main=out/'tessellation_language_frontend_test.o'
        common=[o for o in objects if o!=original_main]
        for version,source,cases in (
            ('v1','tessellation_uniform_frontend_test.cpp',('uniform','full','conditional')),
            ('v2','tessellation_dynamic_frontend_test.cpp',('dynamic','adjacent','capacity','edge','component'))):
            obj=out/(version+'-main.o')
            run(cxx+flags(units[0])+['-UNDEBUG','-Wall','-Wextra','-Werror','-Wno-unused-parameter']+inc+san+(['-DOPENGPU_TESS_BYTE_BASELINE'] if a.baseline_source else [])+
                ['-MMD','-MF',obj.with_suffix('.d'),'-c',ROOT/'userspace/tests'/source,'-o',obj],'compile-'+version)
            executable=out/('tess-'+version)
            run(cxx+san+['-rdynamic','-Wl,--gc-sections']+common+[obj,'-Wl,--start-group']+[mb/p for p in LIBS]+['-Wl,--end-group']+
                shlex.split(os.environ.get('OPENGPU_MESA_LINK_LIBS','-lz -lexpat'))+['-ldl','-lm','-pthread','-o',executable],'link-'+version)
            programs=out/'regressions'/version
            for name in cases:(programs/name).mkdir(parents=True)
            run([executable,ROOT/'userspace/tests/tess_uniform_essl',programs],'actual-ESSL320-'+version)
            if a.baseline_source:print('Original production emitter native-byte comparison PASS: '+version,flush=True)

if __name__=='__main__':main()
