#!/usr/bin/env python3
"""Fast private112 graphics admission/entry tests; no Mesa build or GPU."""
import os
from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import runpy
import shlex
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from owner_resource_contract import SAMPLE_WORDS,V1_WORDS,FULL_WORDS
ROOT=Path(__file__).resolve().parents[1]
AUX=[(0,0x47550002),(0x60,sum(1<<b for b in (6,22,23,25,29))|128<<8),(0x68,0x160),(0x320,1048576),(0x158,0x02020810)]

def profile(words=SAMPLE_WORDS,magic=b'OGNP0002'):
    return magic+struct.pack('<I',len(words))+b''.join(struct.pack('<II',w['address'],w['value']) for w in words)+struct.pack('<I',5)+b''.join(struct.pack('<II',*p) for p in AUX)

class PrivateMesaBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.tmp=tempfile.TemporaryDirectory(prefix='mesa112-guards-',dir=ROOT/'.artifacts');cls.out=Path(cls.tmp.name)
        runpy.run_path(str(ROOT/'scripts/test_native_mmio_capture.py'))['write_bodies'](cls.out)
        flags=['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-unused-function','-D_POSIX_C_SOURCE=200809L']
        if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
        inc=[cls.out,ROOT/'driver',ROOT/'driver/tests',ROOT/'driver/tests/private_snapshot_stubs',ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs',ROOT/'userspace',ROOT/'userspace/tests']
        cls.binary=cls.out/'profile-check'
        subprocess.run([*shlex.split(os.environ.get('CC','cc')),*flags,*['-I'+str(p) for p in inc],
            str(ROOT/'userspace/tests/graphics_sample_profile_test.c'),str(ROOT/'userspace/tests/native_graphics_uniform_source.c'),
            str(ROOT/'userspace/opengpu_native_sample_graphics.c'),str(ROOT/'driver/opengpu_native_frame.c'),'-o',str(cls.binary)],check=True)
        cls.entry=[]
        for mode in ('cpu','live'):
            binary=cls.out/('entry-'+mode);cls.entry.append(binary)
            subprocess.run([*shlex.split(os.environ.get('CC','cc')),*flags,
                *(['-DOPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=1'] if mode=='live' else []),
                '-MMD','-MF',str(binary.with_suffix('.d')),
                str(ROOT/'userspace/tests/graphics_sample_entry_test.c'),'-o',str(binary)],check=True)
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def test_cpu_and_live_argument_environment_isolation_before_any_mesa_or_owner_creation(self):
        for binary in self.entry:
            subprocess.run([str(binary)],check=True)
            self.assertIn('graphics_sample_entry.h',binary.with_suffix('.d').read_text())
    def test_zero_live_build_flag_cannot_select_a_cpu_fallback(self):
        result=subprocess.run([*shlex.split(os.environ.get('CC','cc')),'-std=c11',
            '-D_POSIX_C_SOURCE=200809L','-DOPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=0','-fsyntax-only',
            str(ROOT/'userspace/tests/graphics_sample_entry_test.c')],text=True,capture_output=True)
        self.assertNotEqual(result.returncode,0);self.assertIn('explicit value of one',result.stderr)
    def test_actual_new_kernel_bootstrap_and_old_profile_isolation(self):
        path=self.out/'valid.ognp';path.write_bytes(profile())
        subprocess.run([str(self.binary),str(path),'valid'],check=True,timeout=15)
        four=bytearray(profile());struct.pack_into('<I',four,len(four)-4,0x02040810)
        path=self.out/'legal-four-banks.ognp';path.write_bytes(four)
        subprocess.run([str(self.binary),str(path),'valid'],check=True,timeout=15)
    def test_wrong_version_counts_raw_shader_config_and_canonical_eof(self):
        good=profile();variants=[profile(V1_WORDS),profile(FULL_WORDS),profile(magic=b'OGNP0001'),good+b'\0',good[:-1]]
        bad=bytearray(good);struct.pack_into('<I',bad,len(bad)-4,0x02030810);variants.append(bytes(bad))
        for i,value in enumerate(variants):
            path=self.out/('bad%d.ognp'%i);path.write_bytes(value)
            subprocess.run([str(self.binary),str(path),'invalid'],check=True,timeout=15)
    def test_cpu_entry_rejects_live_or_missing_private_kernel_before_build(self):
        build=self.out/'must-not-create';base=[sys.executable,str(ROOT/'scripts/test_gallium_sample_graphics_uniforms.py'),
            '--build',str(build),'--cpu-capture-only','--profile-wire',str(self.out/'no-profile')]
        cases=[(base,dict(os.environ))]
        for name in ('OPENGPU_NATIVE_HOST_DIRECTORY','OPENGPU_NATIVE_HOST_PROFILE',
                     'OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY','OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE'):
            for value in ('forbidden',''):
                cases.append((base+['--native-kernel-root',str(ROOT)],dict(os.environ,**{name:value})))
        for args,env in cases:
            result=subprocess.run(args,text=True,capture_output=True,env=env)
            self.assertNotEqual(result.returncode,0);self.assertIn('requires --native-kernel-root and no live environment',result.stderr);self.assertFalse(build.exists())
    def test_prepare_and_cpu_modes_are_explicit_exclusive_and_profile_is_cpu_only(self):
        build=self.out/'mode-must-not-create'
        base=[sys.executable,str(ROOT/'scripts/test_gallium_sample_graphics_uniforms.py'),
              '--build',str(build),'--native-kernel-root',str(ROOT)]
        cases=[([],{}),(['--cpu-capture-only','--prepare-live-producer'],{}),
               (['--cpu-capture-only'],{}),(['--prepare-live-producer','--profile-wire','unused'],{})]
        for name in ('OPENGPU_NATIVE_HOST_DIRECTORY','OPENGPU_NATIVE_HOST_PROFILE',
                     'OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY','OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE'):
            for value in ('present',''):cases.append((['--prepare-live-producer'],{name:value}))
        for args,environment in cases:
            result=subprocess.run(base+args,env=dict(os.environ,**environment),capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0);self.assertIn('error:',result.stderr);self.assertFalse(build.exists())
    def test_prepare_commands_compile_both_live_units_and_new_codec_without_executing(self):
        # Command planning only, using explicitly synthetic configured-Mesa
        # files. No fake archive/binary is ever executed or called qualified.
        import test_gallium_compute_uniforms as builder
        from native_sample_compiler_inputs import MESA_LIBRARIES
        top=self.out/'planned-mesa';mesa=top/'mesa';mb=top/'build';output=top/'producer'
        mesa.mkdir(parents=True);mb.mkdir();(mesa/'VERSION').write_text('22.3.6\n')
        units=['compiler/glsl/standalone.cpp','compiler/glsl/standalone_scaffolding.cpp',
               'compiler/glsl/ir_builder_print_visitor.cpp','compiler/glsl/glcpp/pp_standalone_scaffolding.c',
               'mesa/main/extensions_table.c','mesa/program/prog_parameter.c',
               'mesa/program/symbol_table.c','mesa/program/dummy_errors.c','compiler/nir/nir_lower_io.c']
        database=[]
        for unit in units:
            source=mesa/'src'/unit;source.parent.mkdir(parents=True,exist_ok=True)
            source.write_text('   if (options->lower_precision) {\n')
            database.append(dict(directory=str(mb),file=str(source),arguments=['cc','-c',str(source),'-o','unused.o']))
        (mb/'compile_commands.json').write_text(json.dumps(database))
        for relative in (*MESA_LIBRARIES,'src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o',
                         'src/mesa/libmesa.a.p/state_tracker_st_atom_storagebuf.c.o'):
            path=mb/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'synthetic command fixture')
        commands=[]
        def planned(argv,**kwargs):
            argv=list(map(str,argv));commands.append(argv)
            self.assertIn('-o',argv,'build-only must never execute a producer')
            Path(argv[argv.index('-o')+1]).write_bytes(b'synthetic compiler output, never executable')
        environment={k:v for k,v in os.environ.items() if k not in (
            'OPENGPU_NATIVE_HOST_DIRECTORY','OPENGPU_NATIVE_HOST_PROFILE',
            'OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY','OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE')}
        environment.update(OPENGPU_MESA_DIR=str(mesa),OPENGPU_MESA_BUILD=str(mb))
        stdout=io.StringIO()
        argv=['builder','--build',str(output),'--native-kernel-root',str(ROOT),'--prepare-live-producer']
        checked=hashlib.sha256((mesa/'src/compiler/nir/nir_lower_io.c').read_bytes()).hexdigest()
        with patch.object(sys,'argv',argv),patch.dict(os.environ,environment,clear=True), \
             patch.object(builder,'CHECKED_NIR_SHA',checked),patch.object(builder.subprocess,'run',side_effect=planned), \
             redirect_stdout(stdout):
            builder.main(graphics_uniforms=True,sample_graphics=True)
        compiled={Path(c[c.index('-c')+1]).name:c for c in commands if '-c' in c}
        self.assertEqual(len(compiled),28)
        for name in ('graphics_sample_flow.c','graphics_sample_kernel.c'):
            self.assertIn('-DOPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=1',compiled[name])
            self.assertIn('-MMD',compiled[name]);self.assertIn('-MF',compiled[name])
        self.assertIn('graphics_sample_owner_ipc.c',compiled);self.assertNotIn('graphics_native_owner_ipc.c',compiled)
        self.assertEqual(sum('-DOPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=1' in c for c in commands),2)
        self.assertIn(str(output/'graphics_sample_owner_ipc.o'),commands[-1])
        self.assertTrue((output/'glsl/full.vert').is_file() and (output/'glsl/full.frag').is_file())
        self.assertIn('BUILT, execution=false',stdout.getvalue());self.assertFalse((output/'result.json').exists())
        self.assertFalse((output/'actual-acceptance.log').exists());self.assertFalse((output/'glsl/ready.json').exists())

if __name__=='__main__':unittest.main()
