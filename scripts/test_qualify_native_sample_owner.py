#!/usr/bin/env python3
"""Negative/provenance/argv/lifetime construction only; never compile or run a DUT."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import qualify_native_sample_owner as q
import native_sample_compiler_inputs as inputs
import test_native_sample_submit_bridge as bridge


class Qualification(unittest.TestCase):
    def setUp(self):
        (q.ROOT/'.artifacts').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='original-sample-test-',dir=q.ROOT/'.artifacts')
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name)
        self.args = argparse.Namespace(executor=self.out/'executor',executor_qualification=self.out/'qualification.json',
            executor_checker=q.ROOT/'scripts/prepare_owner_executor.py',executor_checker_python=Path(sys.executable),
            mesa=self.out/'mesa',mesa_build=self.out/'mesa-build',library_directory=self.out/'lib',timeout=30)

    def qualification(self):
        self.args.executor.write_bytes(b'tiny identity fixture, never a DUT');self.args.executor.chmod(0o755)
        record=dict(schema='opengpu-owner-executor-v1',kind='native-sample',profile=q.SAMPLE_PROFILE,
            source_root=str(q.ROOT),ownerResourceVersion=2,ownerResourceStage='geometry-compute-sample',
            fullResourceQualification=False,executorBuildVerified=True,sourceQualificationRequired=True,
            gpuExecution=False,clockQualified=False,fpsQualified=False,
            source_sha256={n:q.sha256(q.ROOT/n) for n in ('scripts/qualify_native_sample_owner.py',
                'scripts/native_sample_compiler_inputs.py','scripts/native_sample_owner_controller.py')})
        self.args.executor_qualification.write_text(json.dumps(record));return record

    def test_missing_qualification_has_zero_build_archive_or_container_actions(self):
        argv=['qualify_native_sample_owner.py','--executor',str(self.args.executor),
            '--executor-qualification',str(self.args.executor_qualification),
            '--executor-checker',str(self.args.executor_checker),'--executor-checker-python',sys.executable]
        with patch.object(sys,'argv',argv),patch.object(q.subprocess,'run') as run, \
             patch.object(q.subprocess,'Popen') as start,patch.object(q.subprocess,'check_output') as output, \
             patch.object(q,'snapshot') as snapshot,patch.object(q.tempfile,'mkdtemp') as temporary:
            with self.assertRaisesRegex(ValueError,'qualification is required'):q.main()
            for action in (run,start,output,snapshot,temporary):action.assert_not_called()

    def test_wrong_factory_scope_checkout_and_unbound_provenance_fail_before_build(self):
        original=self.qualification()
        with patch.object(q.subprocess,'run') as run,patch.object(q,'run_build') as build:
            for key,value in (('kind','compute'),('profile','two-cu-vga60-owner-v1'),
                    ('profile','two-cu-vga60-owner-v2'),('ownerResourceVersion',True),
                    ('ownerResourceStage','geometry-compute'),('source_root','/copied/archive'),
                    ('fullResourceQualification',True),('source_sha256',{})):
                bad=dict(original);bad[key]=value;self.args.executor_qualification.write_text(json.dumps(bad))
                with self.subTest(key=key,value=value),self.assertRaises(ValueError):q.checked_inputs(self.args)
            build.assert_not_called()
        self.args.executor_qualification.write_text(json.dumps(original))
        fake=self.out/'prepare_owner_executor.py';fake.write_bytes(self.args.executor_checker.read_bytes())
        self.args.executor_checker=fake
        with patch.object(q.subprocess,'run') as run:
            with self.assertRaisesRegex(ValueError,'original sample source checker'):q.checked_inputs(self.args)
            run.assert_not_called()

    def test_archive_requires_actual_qualified_bytes_not_program_names(self):
        name='userspace/tests/native_sample_essl/sample.frag'
        source=self.out/'archive';path=source/name;path.parent.mkdir(parents=True)
        path.write_bytes((q.ROOT/name).read_bytes());expected={name:q.sha256(q.ROOT/name)}
        q.check_archive(source,expected)
        path.write_bytes(b'not the qualified original ESSL')
        with self.assertRaisesRegex(ValueError,'differs from its qualified'):q.check_archive(source,expected)
        with self.assertRaises(ValueError):q.check_archive(source,{'../outside':'bad'})
        cpu=self.out/'cpu';binary=cpu/'live/native-sample-live-producer';binary.parent.mkdir(parents=True)
        binary.write_bytes(b'identity fixture');binary.chmod(0o755)
        programs=cpu/'programs';programs.mkdir();(programs/'sample-abi6.ognsf').write_bytes(b'fake by filename')
        with self.assertRaises(ValueError):q.compiled_outputs(cpu)

    def test_single_build_and_matrix_argv_keep_controller_in_original_checkout(self):
        matrix=q.case_matrix('all','all');self.assertEqual(len(matrix),21)
        self.assertEqual(len(set(matrix)),21)
        self.assertEqual(q.case_matrix('mask-depth-keep','4'),(('mask-depth-keep',4),))
        self.assertEqual(q.BUILD_SOURCE.count('scripts/test_native_sample_essl.py'),1)
        self.assertIn('build_bridge(out/"live",sanitize=True,live=True)',q.BUILD_SOURCE)
        gpu=q.ROOT;source=self.out/'archive';cpu=self.out/'cpu';image='sha256:'+'1'*64
        for case,samples in matrix:
            command=q.producer_command(self.args,gpu=gpu,source=source,cpu=cpu,image=image,
                name='owned-sample',case=case,samples=samples)
            self.assertEqual(command[command.index('--memory')+1],'512m')
            self.assertEqual(command[command.index('--memory-swap')+1],'512m')
            self.assertEqual(command[command.index('--cpus')+1],'1')
            self.assertIn(f'{gpu}:{gpu}:ro',command);self.assertIn('{ipc}:{ipc}:rw',command)
            self.assertIn('--network=none',command);self.assertIn('--read-only',command)
            self.assertIn(image,command);self.assertEqual(command[-2:],[str(samples),case])
            self.assertIn(str(cpu/'programs'/f'{case}-abi6.ognsf'),command)
            self.assertNotIn('python3',command);self.assertNotIn('--capture-only',command)
        process=Mock();process.wait.return_value=1;events=[]
        with patch.object(q.subprocess,'Popen',return_value=process) as start, \
             patch.object(q,'stop_group',side_effect=lambda p:events.append('group')), \
             patch.object(q,'remove_container',side_effect=lambda n:events.append('container')):
            with self.assertRaisesRegex(ValueError,'sample case failed'):
                q.run_case(self.args,gpu=gpu,source=source,cpu=cpu,temporary=self.out,
                           image=image,case='sample',samples=4)
            argv=start.call_args.args[0]
            self.assertEqual(argv[1],q.ROOT/'scripts/native_sample_owner_controller.py')
            self.assertNotIn(source/'scripts/native_sample_owner_controller.py',argv)
            self.assertIn('--bridge-container-name',argv)
            self.assertEqual(argv[argv.index('--case')+1],'sample')
            self.assertEqual(argv[argv.index('--program')+1],cpu/'programs/sample-abi6.ognsf')
        self.assertEqual(events,['group','container'])

    def test_build_failure_always_checks_container_after_group(self):
        process=Mock();process.wait.side_effect=subprocess.TimeoutExpired('cpu-build',30)
        events=[]
        with patch.object(q.subprocess,'Popen',return_value=process) as start, \
             patch.object(q,'stop_group',side_effect=lambda p:events.append('group')), \
             patch.object(q,'remove_container',side_effect=lambda n:events.append('container')):
            with self.assertRaises(subprocess.TimeoutExpired):
                q.run_build(self.args,gpu=q.ROOT,source=self.out/'source',temporary=self.out,
                            image='sha256:'+'1'*64)
            command=start.call_args.args[0]
            self.assertIn(f'{self.out}/cpu:{self.out}/cpu:rw',command)
            self.assertNotIn(f'{self.out}:{self.out}:rw',command)
        self.assertEqual(events,['group','container'])

    def test_unknown_exit_preserves_managed_inputs_and_known_failure_cleans(self):
        # All peers below are mocks. Exercise only actual finally policy; no
        # model qualification, compilation, receipt or GPU success is supplied.
        for error in (ValueError('CPU build refused'),q.CleanupIncomplete('active consumer unknown')):
            managed=self.out/('unknown' if isinstance(error,q.CleanupIncomplete) else 'known');managed.mkdir()
            for path in (self.args.mesa,self.args.mesa_build,self.args.library_directory):path.mkdir(exist_ok=True)
            archive=Mock(stdout=io.BytesIO());archive.wait.return_value=0
            def build(*args,**kwargs):
                (managed/'input').write_bytes(b'consumer input');raise error
            argv=['q','--executor','/unused','--executor-qualification','/unused','--executor-checker','/unused',
                '--executor-checker-python',sys.executable,'--mesa',str(self.args.mesa),
                '--mesa-build',str(self.args.mesa_build),'--library-directory',str(self.args.library_directory)]
            with patch.object(sys,'argv',argv),patch.object(q,'checked_inputs',return_value=(('q','exe'),{})), \
                 patch.object(q,'revision',return_value='fixed'),patch.object(q,'snapshot',return_value={}), \
                 patch.object(q.subprocess,'check_output',return_value='sha256:'+'1'*64), \
                 patch.object(q.tempfile,'mkdtemp',return_value=str(managed)), \
                 patch.object(q.subprocess,'Popen',return_value=archive),patch.object(q.subprocess,'run'), \
                 patch.object(q,'stop_group'),patch.object(q,'check_archive'),patch.object(q,'run_build',side_effect=build), \
                 redirect_stderr(io.StringIO()),redirect_stdout(io.StringIO()):
                with self.assertRaises(type(error)):q.main()
            if isinstance(error,q.CleanupIncomplete):
                self.assertEqual((managed/'input').read_bytes(),b'consumer input');shutil.rmtree(managed)
            else:self.assertFalse(managed.exists())

    def compiler_fixture(self):
        mesa,build,lib=self.args.mesa,self.args.mesa_build,self.args.library_directory
        for path in (mesa/'src',mesa/'include',build/'src',build/'include',lib):path.mkdir(parents=True,exist_ok=True)
        (mesa/'VERSION').write_text('22.3.6\n');(build/'compile_commands.json').write_text('[]')
        (mesa/'src/a.h').write_text('/* CPU header fixture */')
        for name in inputs.MESA_LIBRARIES:
            p=build/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'CPU archive identity fixture')
        (lib/'libexpat.so.1.6.7').write_bytes(b'CPU runtime identity fixture')
        return inputs.snapshot(mesa,build,lib,self.out)

    def test_original_compiler_inputs_and_actual_dependency_omissions_reject(self):
        bound=self.compiler_fixture();self.assertEqual(len(inputs.MESA_LIBRARIES),8)
        source=self.out/'source';source.mkdir();cpu=self.out/'cpu';cpu.mkdir()
        header=self.args.mesa/'src/a.h'
        for i in range(len(inputs.MESA_UNITS)+6):(cpu/f'{i}.d').write_text(f'{cpu}/{i}.o: {header}\n')
        inputs.check_dependencies(cpu,source,bound)
        archive_header=source/'fixture.h';archive_header.write_text('/* private archive fixture */')
        (cpu/'0.d').write_text(f'{cpu}/0.o: {archive_header}\n')
        with self.assertRaisesRegex(ValueError,'archive compiler input is not qualified'):
            inputs.check_dependencies(cpu,source,bound,{})
        inputs.check_dependencies(cpu,source,bound,{'fixture.h':inputs.sha256(archive_header)})
        (cpu/'0.d').write_text(f'{cpu}/0.o: {header}\n')
        header.write_text('/* changed original header */')
        with self.assertRaisesRegex(ValueError,'unbound/changed actual compiler'):inputs.check_dependencies(cpu,source,bound)
        self.assertNotEqual(bound,inputs.snapshot(self.args.mesa,self.args.mesa_build,self.args.library_directory,self.out))
        (cpu/'0.d').unlink()
        with self.assertRaisesRegex(ValueError,'incomplete actual compiler'):inputs.check_dependencies(cpu,source,bound)
        (self.args.mesa_build/inputs.MESA_LIBRARIES[0]).unlink()
        with self.assertRaises(FileNotFoundError):inputs.snapshot(self.args.mesa,self.args.mesa_build,self.args.library_directory,self.out)

    def test_live_build_declares_three_real_producer_dependency_units_without_running_them(self):
        out=self.out/'live';out.mkdir()
        with patch.object(bridge.subprocess,'run') as run, \
             patch('test_native_mmio_capture.write_bodies'):
            bridge.build_bridge(out,sanitize=True,live=True)
        commands=[call.args[0] for call in run.call_args_list]
        self.assertEqual(len(commands),4)
        for command in commands[:3]:
            self.assertIn('-MMD',command);self.assertIn('-MF',command);self.assertIn('-c',command)
        self.assertEqual(len({command[command.index('-MF')+1] for command in commands[:3]}),3)
        self.assertTrue(any(str(command[command.index('-c')+1]).endswith('userspace/opengpu_native_sample_graphics.c') for command in commands[:3]))
        self.assertNotIn('-c',commands[-1]);self.assertNotIn('-MMD',commands[-1])


if __name__ == '__main__':
    unittest.main()
