#!/usr/bin/env python3
"""Ordinary112 qualification rejection/identity/lifetime tests; no GPU/model."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import copy
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import qualify_native_sample_graphics_owner as q
import native_sample_graphics_inputs as inputs


class OrdinaryQualificationTests(unittest.TestCase):
    def setUp(self):
        parent=q.ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='ordinary112-qualification-test-',dir=parent)
        self.addCleanup(self.temp.cleanup);self.out=Path(self.temp.name)
        self.args=argparse.Namespace(executor=self.out/'executor',executor_qualification=self.out/'qualification.json',
            executor_checker=q.ROOT/'scripts/prepare_owner_executor.py',executor_checker_python=Path(sys.executable),
            mesa=self.out/'mesa',mesa_build=self.out/'mesa-build',drm_include=self.out/'drm',
            library_directory=self.out/'lib',timeout=30)

    def qualification(self):
        self.args.executor.write_bytes(b'CPU identity fixture, never executed');self.args.executor.chmod(0o755)
        record=dict(schema='opengpu-owner-executor-v1',version=1,kind=q.KIND,profile=q.SAMPLE_PROFILE,
            source_root=str(q.ROOT),ownerResourceVersion=2,ownerResourceStage='geometry-compute-sample',
            fullResourceQualification=False,executorBuildVerified=True,sourceQualificationRequired=True,
            gpuExecution=False,clockQualified=False,fpsQualified=False,
            source_sha256={name:inputs.sha256(q.ROOT/name) for name in q.PROVENANCE})
        self.args.executor_qualification.write_text(json.dumps(record));return record

    def argv(self):
        result=['qualifier']
        for name in ('executor','executor_qualification','executor_checker','executor_checker_python',
                     'mesa','mesa_build','drm_include','library_directory'):
            result+=['--'+name.replace('_','-'),str(getattr(self.args,name))]
        return result

    def test_missing_qualification_refuses_before_all_archive_docker_and_compiler_actions(self):
        with patch.object(sys,'argv',self.argv()),patch.object(q.subprocess,'run') as run, \
             patch.object(q.subprocess,'Popen') as start,patch.object(q.subprocess,'check_output') as output, \
             patch.object(q.inputs,'snapshot') as scan,patch.object(q.tempfile,'mkdtemp') as temporary:
            with self.assertRaisesRegex(ValueError,'qualification is required'):q.main()
            for action in (run,start,output,scan,temporary):action.assert_not_called()

    def test_exact_two_owner_contract_scope_and_mandatory_original_source(self):
        good=self.qualification()
        with patch.object(q.subprocess,'run') as run:
            for key,value in (('kind','native-sample'),('kind','native'),('profile','two-cu-vga60-owner-v2'),
                    ('profile','two-cu-vga60-owner-v1'),('version',True),('ownerResourceVersion',True),
                    ('ownerResourceStage','geometry-compute'),('source_root','/copy'),
                    ('fullResourceQualification',True),('gpuExecution',True),('source_sha256',{})):
                bad=copy.deepcopy(good);bad[key]=value;self.args.executor_qualification.write_text(json.dumps(bad))
                with self.subTest(key=key,value=value),self.assertRaises(ValueError):q.checked_inputs(self.args)
            run.assert_not_called()
            for name in (*inputs.SCHEMAS,'userspace/tests/graphics_sample_entry.h',
                         'userspace/tests/graphics_sample_live.inc','userspace/tests/graphics_sample_owner_ipc.c'):
                bad=copy.deepcopy(good);del bad['source_sha256'][name]
                self.args.executor_qualification.write_text(json.dumps(bad))
                with self.subTest(omitted=name),self.assertRaises(ValueError):q.checked_inputs(self.args)
            run.assert_not_called()
        self.args.executor_qualification.write_text(json.dumps(good))
        with patch.object(q.subprocess,'run') as run:
            identity,sources=q.checked_inputs(self.args)
            self.assertEqual(sources,good['source_sha256']);self.assertEqual(len(identity),3)
            self.assertEqual(run.call_args.args[0][run.call_args.args[0].index('--kind')+1],q.KIND)

    def test_aliased_or_copied_checker_and_modified_archive_are_rejected(self):
        self.qualification();copy_path=self.out/'checker.py';copy_path.write_bytes(self.args.executor_checker.read_bytes())
        self.args.executor_checker=copy_path
        with patch.object(q.subprocess,'run') as run:
            with self.assertRaisesRegex(ValueError,'original ordinary112'):q.checked_inputs(self.args)
            run.assert_not_called()
        source=self.out/'archive';name=inputs.SCHEMAS[0];path=source/name;path.parent.mkdir(parents=True)
        path.write_bytes((q.ROOT/name).read_bytes());expected={name:inputs.sha256(path)};q.check_archive(source,expected)
        path.write_bytes(b'changed initial and returned source')
        with self.assertRaises(ValueError):q.check_archive(source,expected)
        for bad in ({'../outside':'0'*64},{name:True}):
            with self.assertRaises(ValueError):q.check_archive(source,bad)

    def compiler_fixture(self):
        a=self.args
        for path in (a.mesa/'src',a.mesa/'include',a.mesa_build/'src',a.mesa_build/'include',a.drm_include,a.library_directory):
            path.mkdir(parents=True,exist_ok=True)
        (a.mesa/'VERSION').write_text('22.3.6\n');(a.mesa_build/'compile_commands.json').write_text('[]')
        lower=a.mesa/'src/compiler/nir/nir_lower_io.c';lower.parent.mkdir(parents=True);lower.write_bytes(b'explicit CPU lowering fixture')
        for name in inputs.MESA_LIBRARIES:
            p=a.mesa_build/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'original library fixture')
        for stage in ('constbuf','storagebuf'):
            p=a.mesa_build/'src/mesa/libmesa.a.p'/('state_tracker_st_atom_'+stage+'.c.o')
            p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'actual direct object identity fixture')
        (a.drm_include/'drm.h').write_bytes(b'original DRM fixture')
        (a.library_directory/'libexpat.so.1.6.7').write_bytes(b'original expat fixture')
        (a.library_directory/'other.so.2').write_bytes(b'LD_LIBRARY_PATH fixture')
        return lower

    def test_external_binding_includes_drm_two_direct_objects_eight_archives_and_all_ld_files(self):
        lower=self.compiler_fixture();a=self.args
        with patch.object(inputs,'LOWERING_SHA',inputs.sha256(lower)):
            bound=inputs.snapshot(a.mesa,a.mesa_build,a.library_directory,a.drm_include,self.out)
            required=[a.drm_include/'drm.h',a.library_directory/'other.so.2',
                a.mesa_build/'src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o',
                a.mesa_build/'src/mesa/libmesa.a.p/state_tracker_st_atom_storagebuf.c.o',
                *(a.mesa_build/name for name in inputs.MESA_LIBRARIES)]
            for path in required:
                self.assertIn(str(path),bound);old=path.read_bytes();path.write_bytes(old+b'changed')
                self.assertNotEqual(bound,inputs.snapshot(a.mesa,a.mesa_build,a.library_directory,a.drm_include,self.out));path.write_bytes(old)
        with self.assertRaisesRegex(ValueError,'checked Mesa lowering'):inputs.snapshot(a.mesa,a.mesa_build,a.library_directory,a.drm_include,self.out)

    def test_actual_dependency_requires_before_compilation_generated_identity_and_qualified_gpu(self):
        cpu=self.out/'cpu';source=self.out/'source';cpu.mkdir();source.mkdir()
        generated=cpu/'generated.inc';generated.write_bytes(b'before compile')
        gpu=source/'private.h';gpu.write_bytes(b'original GPU header')
        external=self.out/'external.h';external.write_bytes(b'external original')
        bound={str(external):[str(external),inputs.sha256(external)]};qualified={'private.h':inputs.sha256(gpu)}
        dep=cpu/'unit.d';dep.write_text(f'{cpu}/unit.o: {gpu} \\\n {generated} {external}\n')
        kwargs=dict(cpu=cpu,source=source,bound=bound,qualified=qualified,generated={str(generated):inputs.sha256(generated)})
        self.assertEqual(len(inputs.dependency_inputs(dep,**kwargs)),3)
        for key in ('bound','qualified','generated'):
            bad=dict(kwargs);bad[key]={}
            with self.assertRaises(ValueError):inputs.dependency_inputs(dep,**bad)
        generated.write_bytes(b'changed after compile')
        with self.assertRaisesRegex(ValueError,'before compilation'):inputs.dependency_inputs(dep,**kwargs)

    def planned_build(self):
        cpu=self.out/'cpu';producer=cpu/'producer';source=self.out/'source';producer.mkdir(parents=True);source.mkdir()
        a=self.args;a.mesa.mkdir();a.mesa_build.mkdir();a.library_directory.mkdir()
        files=[source/name for name in inputs.GPU_UNITS]+[a.mesa/'src'/name for name in inputs.MESA_UNITS[1:]]
        files += [producer/'standalone-profile.cpp',cpu/'checked-nir-lower-io.c']
        for path in files:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'CPU command/dependency fixture')
        qualified={str(p.relative_to(source)):inputs.sha256(p) for p in files if p.is_relative_to(source)}
        bound={str(p):[str(p),inputs.sha256(p)] for p in files if p.is_relative_to(a.mesa)}
        generated=inputs.generated_inputs(cpu);commands=[];objects=[]
        for index,path in enumerate(files):
            obj=producer/('%d.o'%index);dep=obj.with_suffix('.d');objects.append(str(obj))
            argv=['cc','-fsanitize=address,undefined',*([inputs.LIVE_FLAG] if path.name in inputs.LIVE_UNITS else []),
                  '-MMD','-MF',str(dep),'-c',str(path),'-o',str(obj)]
            commands.append(dict(argv=argv,cwd=str(producer),label='compile'))
            dep.write_text(f'{obj}: {path}\n')
        objects += [str(a.mesa_build/'src/mesa/libmesa.a.p'/('state_tracker_st_atom_'+s+'.c.o')) for s in ('constbuf','storagebuf')]
        argv=['c++',*inputs.SANITIZERS,'-Wl,--gc-sections','-Wl,-t,-t','-Wl,-Map='+str(producer/'link.map'),
              *objects,'-Wl,--start-group',*(str(a.mesa_build/name) for name in inputs.MESA_LIBRARIES),
              '-Wl,--end-group','-l:libz.so.1',str(a.library_directory/'libexpat.so.1.6.7'),'-ldl','-lm','-pthread',
              '-o',str(producer/'cs_uniform_flow')]
        commands.append(dict(argv=argv,cwd=str(producer),label='link'))
        log=producer/'commands.json';log.write_text(json.dumps(commands))
        return commands,log,dict(cpu=cpu,source=source,mesa=a.mesa,mesa_build=a.mesa_build,libraries=a.library_directory,
            bound=bound,qualified=qualified,generated=generated)

    def test_exact_28_units_live_gates_and_link_reject_extra_inputs_or_injection(self):
        commands,log,kwargs=self.planned_build()
        with patch.object(inputs,'LOWERING_SHA',inputs.sha256(kwargs['cpu']/'checked-nir-lower-io.c')):
            self.assertEqual(len(inputs.check_build(**kwargs)),28)
            mutants=[]
            for extra in ('unexpected.o','unexpected.a','unexpected.so','@response','-Wl,--whole-archive','-include','/tmp/injected.h'):
                bad=copy.deepcopy(commands);bad[-1]['argv'].insert(1,extra);mutants.append(bad)
            bad=copy.deepcopy(commands);bad[0]['argv'].remove(inputs.LIVE_FLAG);mutants.append(bad)
            bad=copy.deepcopy(commands);bad[3]['argv'] += ['-DOPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=0'];mutants.append(bad)
            bad=copy.deepcopy(commands);bad.pop(0);mutants.append(bad)
            bad=copy.deepcopy(commands);bad[-1]['argv'].remove(str(self.args.mesa_build/inputs.MESA_LIBRARIES[0]));mutants.append(bad)
            for bad in mutants:
                log.write_text(json.dumps(bad))
                with self.assertRaises(ValueError):inputs.check_build(**kwargs)
            log.write_text(json.dumps(commands));(kwargs['cpu']/'extra.d').write_text('bad')
            with self.assertRaises(ValueError):inputs.check_build(**kwargs)

    def test_built_products_require_real_elf_sources_and_no_execution_outputs(self):
        cpu=self.out/'cpu';p=cpu/'producer';(p/'glsl').mkdir(parents=True)
        binary=p/'cs_uniform_flow';header=bytearray(64);header[:6]=b'\x7fELF\x02\x01';struct.pack_into('<H',header,18,183)
        binary.write_bytes(header);binary.chmod(0o755)
        for name in ('full.vert','full.frag'):(p/'glsl'/name).write_text('synthetic source identity, never executed')
        (p/'commands.json').write_text('[]');(cpu/'build-inputs.json').write_text('{}')
        self.assertEqual(len(inputs.compiled_outputs(cpu)),5)
        for name in ('result.json','actual-acceptance.log','glsl/ready.json','glsl/union.memory'):
            path=p/name;path.write_text('forbidden execution output')
            with self.assertRaises(ValueError):inputs.compiled_outputs(cpu)
            path.unlink()
        binary.write_bytes(b'filename alone is not a Linux binary')
        with self.assertRaises(ValueError):inputs.compiled_outputs(cpu)

    def test_direct_producer_argv_fixed_resources_new_env_and_original_controller(self):
        gpu=q.ROOT;source=self.out/'source';cpu=self.out/'cpu';image='sha256:'+'1'*64
        command=q.producer_command(self.args,gpu=gpu,source=source,cpu=cpu,image=image,name='owned')
        self.assertEqual(command[-2:],[str(cpu/'producer/cs_uniform_flow'),str(cpu/'producer/glsl')])
        for flag,value in (('--memory','512m'),('--memory-swap','512m'),('--cpus','1')):
            self.assertEqual(command[command.index(flag)+1],value)
        self.assertIn('--network=none',command);self.assertIn('--read-only',command)
        self.assertIn(f'{gpu}:{gpu}:ro',command);self.assertIn('{ipc}:{ipc}:rw',command)
        self.assertIn('OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY={ipc}',command)
        self.assertIn('OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE={ipc}/profile.bin',command)
        self.assertNotIn('--cpu-capture-only',command);self.assertNotIn('python3',command)
        self.assertFalse(any('OPENGPU_NATIVE_HOST_PROFILE=' in a for a in command))
        process=Mock(pid=43210);process.wait.return_value=1;events=[]
        def container_cleanup(name):
            events.append('container')
            self.cleanup_receipt(process.pid)
        with patch.object(q.subprocess,'Popen',return_value=process) as start, \
             patch.object(q,'stop_group',side_effect=lambda p:events.append('group')), \
             patch.object(q,'remove_container',side_effect=container_cleanup):
            with self.assertRaises(ValueError):q.run_controller(self.args,gpu=gpu,source=source,cpu=cpu,temporary=self.out,image=image)
            argv=start.call_args.args[0];self.assertEqual(argv[1],q.ROOT/'scripts/native_sample_graphics_controller.py')
            self.assertIn('--bridge-container-name',argv)
            self.assertEqual(argv[argv.index('--cleanup-receipt')+1],self.out/'controller-cleanup.json')
        self.assertEqual(events,['group','container'])

    def cleanup_receipt(self,pid):
        record=dict(schema='opengpu-native-sample-graphics-consumer-cleanup-v1',kind=q.KIND,
            controllerPID=pid,sourceRoot=str(q.ROOT),executor=str(self.args.executor.absolute()),
            qualification=str(self.args.executor_qualification.absolute()),consumerCleanupConfirmed=True)
        (self.out/'controller-cleanup.json').write_text(json.dumps(record));return record

    def test_consumer_cleanup_requires_exact_controller_pid_source_and_complete_confirmation(self):
        path=self.out/'controller-cleanup.json';pid=43210
        with self.assertRaises(q.CleanupIncomplete):q.consumer_cleanup(path,self.args,pid)
        good=self.cleanup_receipt(pid);q.consumer_cleanup(path,self.args,pid)
        for key,value in (('schema','opengpu-sample-cleanup-v1'),('kind','native-sample'),
                ('controllerPID',pid+1),('controllerPID',True),('sourceRoot','/archive'),
                ('executor','/other'),('qualification','/other'),('consumerCleanupConfirmed',1)):
            bad=dict(good);bad[key]=value;path.write_text(json.dumps(bad))
            with self.subTest(key=key),self.assertRaises(q.CleanupIncomplete):q.consumer_cleanup(path,self.args,pid)
        for bad in ({**good,'extra':True},{k:v for k,v in good.items() if k!='kind'}):
            path.write_text(json.dumps(bad))
            with self.assertRaises(q.CleanupIncomplete):q.consumer_cleanup(path,self.args,pid)
        other=self.out/'aliased';other.write_text(json.dumps(good));path.unlink();path.symlink_to(other)
        with self.assertRaises(q.CleanupIncomplete):q.consumer_cleanup(path,self.args,pid)

    def test_controller_nonzero_without_cleanup_proof_quarantines_outer_inputs(self):
        process=Mock(pid=43210);process.wait.return_value=1
        with patch.object(q.subprocess,'Popen',return_value=process),patch.object(q,'stop_group'),patch.object(q,'remove_container'):
            with self.assertRaises(q.CleanupIncomplete):q.run_controller(self.args,gpu=q.ROOT,source=self.out/'source',
                cpu=self.out/'cpu',temporary=self.out,image='sha256:'+'1'*64)

    def test_build_timeout_stops_group_then_container_without_running_any_producer(self):
        process=Mock();process.wait.side_effect=subprocess.TimeoutExpired('build',30);events=[]
        with patch.object(q.subprocess,'Popen',return_value=process) as start, \
             patch.object(q,'stop_group',side_effect=lambda p:events.append('group')), \
             patch.object(q,'remove_container',side_effect=lambda n:events.append('container')):
            with self.assertRaises(subprocess.TimeoutExpired):q.run_build(self.args,gpu=q.ROOT,source=self.out/'source',
                temporary=self.out,image='sha256:'+'1'*64,compiler={},qualified={})
            argv=start.call_args.args[0]
            self.assertIn('--build-request',argv);self.assertNotIn('cs_uniform_flow',argv)
            self.assertIn(f'{self.out}/cpu:{self.out}/cpu:rw',argv)
        self.assertEqual(events,['group','container'])

    def test_unknown_exit_preserves_inputs_and_proven_failure_cleans(self):
        for error in (ValueError('build failed'),q.CleanupIncomplete('consumer status unknown')):
            managed=self.out/('unknown' if isinstance(error,q.CleanupIncomplete) else 'known');managed.mkdir()
            for name in ('mesa','mesa_build','library_directory','drm_include'):getattr(self.args,name).mkdir(exist_ok=True)
            archive=Mock(stdout=io.BytesIO());archive.wait.return_value=0
            def fail(*args,**kwargs):(managed/'active-input').write_bytes(b'preserve until consumers gone');raise error
            with patch.object(sys,'argv',self.argv()),patch.object(q,'checked_inputs',return_value=(('q','e'),{})), \
                 patch.object(q,'revision',return_value='fixed'),patch.object(q.inputs,'snapshot',return_value={}), \
                 patch.object(q.subprocess,'check_output',return_value='sha256:'+'1'*64), \
                 patch.object(q.tempfile,'mkdtemp',return_value=str(managed)),patch.object(q.subprocess,'Popen',return_value=archive), \
                 patch.object(q.subprocess,'run'),patch.object(q,'stop_group'),patch.object(q,'check_archive'), \
                 patch.object(q,'run_build',side_effect=fail),redirect_stderr(io.StringIO()):
                with self.assertRaises(type(error)):q.main()
            if isinstance(error,q.CleanupIncomplete):
                self.assertTrue((managed/'active-input').is_file());shutil.rmtree(managed)
            else:self.assertFalse(managed.exists())


if __name__ == '__main__':unittest.main()
