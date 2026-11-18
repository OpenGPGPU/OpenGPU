#!/usr/bin/env python3
"""CPU command/lifetime guards, never a substitute for actual GPU receipts."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import qualify_compute_owner as qualifier
from compute_owner_cases import ADDRESS_CASES
from owner_resource_contract import IMAGE_FLOAT_PROFILE
from compute_owner_protocol import RECOVERY_CASE,RECOVERY_BASES,RECOVERY_COMMAND_SCHEMA
from compute_owner_controller import recovery_commands
import owner_process_lifetime as lifetime

ROOT = Path(__file__).resolve().parents[1]


class Entry(unittest.TestCase):
    def test_reset_commands_bind_one_live_cancel_then_one_original_healthy_source(self):
        args=argparse.Namespace(mesa=ROOT/'mesa',mesa_build=ROOT/'mesa-build',
            drm_include=ROOT/'drm',library_directory=ROOT/'lib',mesa_link_libs='-lfixture',
            counter_inputs=ROOT/'inputs',timeout=30,expect_image_source_fault=False,image_source_recovery=False)
        for channel in ('read','write'):
            args.image_reset_recovery=channel
            commands=[qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
                image='sha256:fixture',name='reset-'+str(i),case=RECOVERY_CASE,
                source_fault=False,physical_base=base,reset=channel if i==0 else None)
                for i,base in enumerate(RECOVERY_BASES)]
            recovery_commands(dict(schema=RECOVERY_COMMAND_SCHEMA,rounds=commands),reset=channel)
            self.assertNotIn('--expect-image-source-fault',commands[0])
            self.assertNotIn('--expect-image-reset',commands[1])
            for case in ('image-atomic-2d-r32f','counter-increment','both'):
                args.case=case
                with patch.object(qualifier.subprocess,'run') as run:
                    with self.assertRaises(ValueError):qualifier.checked_inputs(args)
                    run.assert_not_called()
        for options in (dict(reset=True),dict(reset='rw'),dict(reset='read',source_fault=True),
                        dict(reset='read',permission='write')):
            with self.assertRaises(ValueError):qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
                image='sha256:fixture',name='reset-negative',case=RECOVERY_CASE,**options)

    def test_permission_recovery_commands_bind_actual_drm_request_variants(self):
        args=argparse.Namespace(mesa=ROOT/'mesa',mesa_build=ROOT/'mesa-build',
            drm_include=ROOT/'drm',library_directory=ROOT/'lib',mesa_link_libs='-lfixture',
            counter_inputs=ROOT/'inputs',timeout=30,expect_image_source_fault=False,image_source_recovery=False)
        for permission in ('read','write'):
            args.image_permission_recovery=permission
            commands=[qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
                image='sha256:fixture',name='permission-'+str(i),case=RECOVERY_CASE,
                source_fault=False,physical_base=base,permission=permission if i==0 else None)
                for i,base in enumerate(RECOVERY_BASES)]
            recovery_commands(dict(schema=RECOVERY_COMMAND_SCHEMA,rounds=commands),permission=permission)
            self.assertNotIn('--expect-image-source-fault',commands[0])
            for case in ('image-atomic-2d-r32f','counter-increment','both'):
                args.case=case
                with self.subTest(permission=permission,case=case),patch.object(qualifier.subprocess,'run') as run:
                    with self.assertRaises(ValueError):qualifier.checked_inputs(args)
                    run.assert_not_called()
        for permission in (True,'rw',''):
            with self.assertRaises(ValueError):qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
                image='sha256:fixture',name='permission-negative',case=RECOVERY_CASE,permission=permission)
        with self.assertRaises(ValueError):qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
            image='sha256:fixture',name='permission-negative',case=RECOVERY_CASE,source_fault=True,permission='read')

    def test_recovery_commands_bind_each_original_physical_namespace(self):
        args=argparse.Namespace(mesa=ROOT/'mesa',mesa_build=ROOT/'mesa-build',
            drm_include=ROOT/'drm',library_directory=ROOT/'lib',mesa_link_libs='-lfixture',
            counter_inputs=ROOT/'inputs',timeout=30,expect_image_source_fault=False,image_source_recovery=True)
        commands=[qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
            image='sha256:fixture',name='recovery-test-'+str(i),case=RECOVERY_CASE,
            source_fault=i==0,physical_base=base) for i,base in enumerate(RECOVERY_BASES)]
        recovery_commands(dict(schema=RECOVERY_COMMAND_SCHEMA,rounds=commands))
        for base in (0,1,True,0x100000000):
            with self.subTest(base=base),self.assertRaises(ValueError):
                qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
                    image='sha256:fixture',name='recovery-test',case=RECOVERY_CASE,physical_base=base)
        for case in ('counter-increment','image-atomic-2d-r32f','both'):
            args.case=case
            with self.subTest(case=case),patch.object(qualifier.subprocess,'run') as run:
                with self.assertRaisesRegex(ValueError,'source fault requires'):qualifier.checked_inputs(args)
                run.assert_not_called()

    def test_recovery_attempts_both_container_cleanups_when_one_fails(self):
        # Process/container handles are CPU mocks; no execution is qualified.
        import compute_owner_inputs
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='recovery-lifetime-',dir=parent) as d:
            root=Path(d);q=root/'q.json';q.write_text(json.dumps(dict(source_sha256={})))
            args=argparse.Namespace(mesa=root,mesa_build=root,drm_include=root,library_directory=root,
                mesa_link_libs='-lfixture',executor_qualification=q,executor=root/'executor',
                executor_checker=root/'checker',executor_checker_python=sys.executable,
                timeout=30,image_source_recovery=True,expect_image_source_fault=False)
            with patch.object(compute_owner_inputs,'snapshot',return_value={}),\
                 patch.object(qualifier.subprocess,'Popen') as popen,\
                 patch.object(qualifier,'stop_group'),\
                 patch.object(qualifier,'remove_container',side_effect=[lifetime.CleanupIncomplete('blocked cleanup'),None]) as remove:
                popen.return_value.wait.return_value=1
                with self.assertRaises(lifetime.CleanupIncomplete):
                    qualifier.run_case(args,gpu=ROOT,source=ROOT,temporary=root,ntt=root/'ntt',
                        image='sha256:fixture',case=RECOVERY_CASE)
                self.assertEqual(len(remove.call_args_list),2)
                self.assertTrue(remove.call_args_list[0].args[0].endswith('-round-0'))
                self.assertTrue(remove.call_args_list[1].args[0].endswith('-round-1'))

    def test_source_fault_mode_is_explicit_and_refuses_other_original_sources(self):
        for case in ('counter-increment','image-buffer-r32ui','image-atomic-2d-r32f','both'):
            args=argparse.Namespace(case=case,expect_image_source_fault=True)
            with self.subTest(case=case),patch.object(qualifier.subprocess,'run') as run:
                with self.assertRaisesRegex(ValueError,'source fault requires'):
                    qualifier.checked_inputs(args)
                run.assert_not_called()
        args=argparse.Namespace(mesa=ROOT/'mesa',mesa_build=ROOT/'mesa-build',
            drm_include=ROOT/'drm',library_directory=ROOT/'lib',mesa_link_libs='-lfixture',
            counter_inputs=ROOT/'inputs',timeout=30,expect_image_source_fault=True)
        command=qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
            image='sha256:fixture',name='source-fault-test',case='image-atomic-buffer-r32f')
        self.assertEqual(command[-3:],['--case','image-atomic-buffer-r32f','--expect-image-source-fault'])
        self.assertIn('scripts/compute_owner_inputs.py',command)
        self.assertNotIn('--capture-only',command)
        with self.assertRaisesRegex(ValueError,'source fault requires'):
            qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=ROOT/'ntt',
                image='sha256:fixture',name='source-fault-test',case='counter-increment')

    def test_absent_qualification_refuses_before_any_command(self):
        args = argparse.Namespace(executor_qualification=ROOT / '.artifacts/no-such-qualification',
                                  executor=ROOT / '.artifacts/no-such-executor')
        with patch.object(qualifier.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'qualification is required'):
                qualifier.checked_inputs(args)
            run.assert_not_called()

    def test_atomic_sources_require_the_new_factory_before_starting_a_producer(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-stage-identity-',dir=parent) as d:
            directory=Path(d);executable=directory/'executor';executable.write_bytes(b'CPU fixture')
            qualification=directory/'qualification.json'
            args=argparse.Namespace(executor=executable,executor_qualification=qualification,
                executor_checker_python=sys.executable,executor_checker=directory/'checker',case='mandatory-compute')
            for case in ('mandatory-compute', *ADDRESS_CASES):
                args.case = case
                for profile in ('two-cu-vga60-owner-v1', 'unknown', qualifier.COMPUTE_STAGE,
                                *qualifier.FULL_COMPUTE_CONTRACTS):
                    qualification.write_text(json.dumps(dict(schema='opengpu-owner-executor-v1',kind='compute',profile=profile)))
                    with patch.object(qualifier.subprocess,'run'):
                        if profile==qualifier.COMPUTE_STAGE or profile in qualifier.FULL_COMPUTE_CONTRACTS:
                            qualifier.checked_inputs(args)
                        else:
                            with self.assertRaisesRegex(ValueError,'distinct shared-enabled'):
                                qualifier.checked_inputs(args)

    def test_counter_requires_exact_sample_factory_and_old_matrix_is_unchanged(self):
        self.assertEqual(len(qualifier.LEGACY_CASES),21)
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-factory-',dir=parent) as d:
            directory=Path(d);executable=directory/'executor';executable.write_bytes(b'CPU fixture')
            qualification=directory/'qualification.json'
            args=argparse.Namespace(executor=executable,executor_qualification=qualification,
                executor_checker_python=sys.executable,executor_checker=directory/'checker',case=qualifier.COUNTER_CASE)
            for profile in ('two-cu-vga60-owner-v1',qualifier.COMPUTE_STAGE,*qualifier.FULL_COMPUTE_CONTRACTS):
                qualification.write_text(json.dumps(dict(schema='opengpu-owner-executor-v1',kind='compute',profile=profile)))
                with patch.object(qualifier.subprocess,'run'):
                    if profile in (qualifier.SAMPLE_PROFILE,IMAGE_FLOAT_PROFILE):qualifier.checked_inputs(args)
                    else:
                        with self.assertRaisesRegex(ValueError,'exact final112'):qualifier.checked_inputs(args)

    def test_every_counter_recipe_requires_final112_and_matrix_mode_cannot_run_live(self):
        parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='counter-case-gates-',dir=parent) as d:
            directory=Path(d);executable=directory/'executor';executable.write_bytes(b'CPU fixture')
            qualification=directory/'qualification.json'
            args=argparse.Namespace(executor=executable,executor_qualification=qualification,
                executor_checker_python=sys.executable,executor_checker=directory/'checker')
            for case in (*qualifier.COUNTER_CASES,*qualifier.IMAGE_CASES):
                args.case=case
                for profile in ('two-cu-vga60-owner-v1',qualifier.COMPUTE_STAGE,*qualifier.FULL_COMPUTE_CONTRACTS):
                    qualification.write_text(json.dumps(dict(schema='opengpu-owner-executor-v1',kind='compute',profile=profile)))
                    with patch.object(qualifier.subprocess,'run'):
                        if case in qualifier.IMAGE_FLOAT_EXCHANGE_CASES and profile==qualifier.SAMPLE_PROFILE:
                            with self.assertRaisesRegex(ValueError,'qualified float owner'):qualifier.checked_inputs(args)
                        elif profile in (qualifier.SAMPLE_PROFILE,IMAGE_FLOAT_PROFILE):qualifier.checked_inputs(args)
                        else:
                            with self.assertRaisesRegex(ValueError,'exact final112'):qualifier.checked_inputs(args)
            for extra in ([],['--capture-only','--case','counter-read']):
                output=directory/'never-created'
                command=[sys.executable,str(ROOT/'scripts/test_gallium_compute_host.py'),
                    '--build',str(output),'--profile-wire',str(directory/'absent-profile'),
                    '--session',str(directory/'absent-session'),'--capture-counter-matrix',*extra]
                result=subprocess.run(command,capture_output=True,text=True,
                    env=dict(__import__('os').environ,PYTHONDONTWRITEBYTECODE='1'))
                self.assertEqual(result.returncode,2,result.stderr)
                self.assertIn('requires --case counter-increment --capture-only',result.stderr)
                self.assertFalse(output.exists())
            for extra in ([],['--capture-only','--case','image-2d-r32ui']):
                output=directory/'never-created-image'
                command=[sys.executable,str(ROOT/'scripts/test_gallium_compute_host.py'),
                    '--build',str(output),'--profile-wire',str(directory/'absent-profile'),
                    '--session',str(directory/'absent-session'),'--capture-image-matrix',*extra]
                result=subprocess.run(command,capture_output=True,text=True,
                    env=dict(__import__('os').environ,PYTHONDONTWRITEBYTECODE='1'))
                self.assertEqual(result.returncode,2,result.stderr)
                self.assertIn('requires the first image case and --capture-only',result.stderr)
                self.assertFalse(output.exists())

    def test_fixed_paths_real_producer_and_both_full_sources(self):
        gpu = ROOT
        args = argparse.Namespace(mesa=gpu / 'depends/mesa', mesa_build=gpu / 'depends/mesa-build',
            drm_include=gpu / 'depends/include', mesa_link_libs='-lz -lexpat', library_directory=gpu / 'depends/lib', timeout=3600, counter_inputs=gpu / '.artifacts/counter-inputs.json')
        for case in qualifier.CASES:
            command = qualifier.producer_command(args, gpu=gpu, source=gpu / '.artifacts/source',
                ntt=gpu / '.artifacts/ntt.c', image='sha256:' + '1' * 64, name='unique-owned-container', case=case)
            self.assertIn(f'{gpu}:{gpu}:ro', command)
            self.assertIn('{ipc}:{ipc}:rw', command)
            self.assertIn('TMPDIR={ipc}', command)
            self.assertIn('--memory-swap', command)
            self.assertIn('--network=none', command)
            self.assertIn('--read-only', command)
            strict=case in (*qualifier.COUNTER_CASES,*qualifier.IMAGE_CASES)
            expected_memory='512m' if strict else '2g'
            self.assertEqual(command[command.index('--memory')+1],expected_memory)
            self.assertEqual(command[command.index('--memory-swap')+1],expected_memory)
            self.assertEqual(command[command.index('--cpus')+1],'1' if strict else '2')
            self.assertIn('OPENGPU_COMPUTE_HOST_TIMEOUT=3600', command)
            self.assertEqual(command[-2:], ['--case', case])
            self.assertNotIn('--capture-only', command)
            self.assertNotIn('OPENGPU_COMPUTE_HOST_TEST_MAIN', ' '.join(command))
            self.assertIn('{ipc}/profile.bin', command)
            self.assertIn('scripts/compute_owner_inputs.py' if strict else 'scripts/test_gallium_compute_host.py', command)
            self.assertIn('--sanitize', command)

    def test_container_exit_must_be_proven_and_active_inputs_preserved(self):
        result = lambda code, output='': subprocess.CompletedProcess([], code, stdout=output, stderr='')
        with patch.object(qualifier.subprocess, 'run', side_effect=[result(1), result(0)]):
            qualifier.remove_container('owned-name')
        for reply in (result(1), result(0, 'still-running-id')):
            with patch.object(qualifier.subprocess, 'run', side_effect=[result(1), reply]):
                with self.assertRaises(qualifier.CleanupIncomplete):
                    qualifier.remove_container('owned-name')

    def test_controller_process_group_is_stopped_before_input_removal(self):
        process = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
        try:
            qualifier.stop_group(process)
            self.assertIsNotNone(process.poll())
        finally:
            if process.poll() is None:
                process.kill(); process.wait()

    def test_group_members_not_only_the_joined_leader_are_checked(self):
        def result(text, code=0):
            return subprocess.CompletedProcess([], code, stdout=text, stderr='')
        for text, expected in (('42 S\n43 S\n', False), ('42 Z\n43 S\n', True), ('43 S\n', True)):
            with patch.object(lifetime.subprocess, 'run', return_value=result(text)):
                self.assertEqual(lifetime.group_dead(42), expected)
        for reply in (result('unknown'), result('', 1), result('')):
            with patch.object(lifetime.subprocess, 'run', return_value=reply):
                with self.assertRaises(qualifier.CleanupIncomplete):
                    lifetime.group_dead(42)
        with patch.object(lifetime.os, 'killpg'), patch.object(lifetime, 'group_dead', return_value=False), \
             patch.object(lifetime.time, 'monotonic', side_effect=(0, 11)), \
             patch.object(lifetime.time, 'sleep'):
            with self.assertRaisesRegex(qualifier.CleanupIncomplete, 'still has active members'):
                lifetime.stop_group(unittest.mock.Mock(pid=42))

    def test_sigkill_leader_wait_is_bounded_and_failure_preserves_inputs(self):
        for first in (subprocess.TimeoutExpired('owned-leader', 10), OSError('initial wait unknown')):
            for failure in (subprocess.TimeoutExpired('owned-leader', 10), OSError('exit unknown')):
                process = unittest.mock.Mock(pid=42)
                process.wait.side_effect = [first, failure]
                with patch.object(lifetime.os, 'killpg'), patch.object(lifetime, 'group_dead') as dead:
                    with self.assertRaisesRegex(qualifier.CleanupIncomplete, 'leader exit after SIGKILL'):
                        lifetime.stop_group(process)
                self.assertEqual(process.wait.call_args_list,
                                 [unittest.mock.call(timeout=10), unittest.mock.call(timeout=10)])
                dead.assert_not_called()

    def exercise_managed_cleanup(self, process, *, preserve):
        """Exercise the formal entry's actual finally policy, without a DUT."""
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        temporary = Path(tempfile.mkdtemp(prefix='compute-cleanup-', dir=parent))
        sentinel = temporary / 'shared-session/original-input.bin'
        diagnostic = io.StringIO()
        archive = unittest.mock.Mock(stdout=io.BytesIO(), wait=unittest.mock.Mock(return_value=0))
        def run_case(*args, **kwargs):
            sentinel.parent.mkdir()
            sentinel.write_bytes(b'CPU fixture, never GPU output')
            lifetime.stop_group(process)
        def output(command, **kwargs):
            if command[0] == 'docker':
                return 'sha256:' + '1' * 64
            return '' if command[-1] == '--porcelain' else 'fixed-source-commit'
        argv = ['qualify_compute_owner.py', '--executor', '/unused/executor',
            '--executor-qualification', '/unused/qualification', '--executor-checker', '/unused/checker',
            '--executor-checker-python', sys.executable, '--case', 'shared']
        try:
            with patch.object(sys, 'argv', argv), patch.object(qualifier, 'checked_inputs', return_value=('q', 'exe')), \
                 patch.object(qualifier, 'inside', side_effect=lambda path, gpu: Path(path)), \
                 patch.object(qualifier.subprocess, 'check_output', side_effect=output), \
                 patch.object(qualifier.subprocess, 'Popen', return_value=archive), \
                 patch.object(qualifier.subprocess, 'run'), \
                 patch.object(qualifier.tempfile, 'mkdtemp', return_value=str(temporary)), \
                 patch.object(qualifier, 'run_case', side_effect=run_case), \
                 redirect_stderr(diagnostic), redirect_stdout(io.StringIO()):
                if preserve:
                    with self.assertRaises(qualifier.CleanupIncomplete):
                        qualifier.main()
                else:
                    qualifier.main()
            if preserve:
                self.assertEqual(sentinel.read_bytes(), b'CPU fixture, never GPU output')
                self.assertIn('preserved their inputs', diagnostic.getvalue())
            else:
                self.assertFalse(temporary.exists())
        finally:
            # All peers are mocks; no consumer uses the deliberately preserved
            # private CPU inputs after this assertion.
            if temporary.exists():
                shutil.rmtree(temporary)

    def test_unknown_exit_preserves_formal_managed_inputs(self):
        for first in (OSError('initial unknown'), subprocess.TimeoutExpired('leader', 10)):
            for last in (OSError('exit unknown'), subprocess.TimeoutExpired('leader', 10)):
                process = unittest.mock.Mock(pid=42)
                process.wait.side_effect = [first, last]
                with patch.object(lifetime.os, 'killpg'), patch.object(lifetime, 'group_dead') as dead:
                    self.exercise_managed_cleanup(process, preserve=True)
                dead.assert_not_called()

    def test_initial_wait_error_still_requires_group_proof_before_removal(self):
        for known_dead in (True, False):
            process = unittest.mock.Mock(pid=42)
            process.wait.side_effect = [OSError('initial unknown'), 0]
            with patch.object(lifetime.os, 'killpg') as terminate, \
                 patch.object(lifetime, 'group_dead', return_value=known_dead) as dead, \
                 patch.object(lifetime.time, 'monotonic', side_effect=(0, 11)), \
                 patch.object(lifetime.time, 'sleep'):
                self.exercise_managed_cleanup(process, preserve=not known_dead)
            self.assertEqual(process.wait.call_args_list,
                [unittest.mock.call(timeout=10), unittest.mock.call(timeout=10)])
            self.assertEqual(terminate.call_count, 2)
            dead.assert_called_once_with(42)

    def test_already_exited_leader_does_not_hide_a_live_descendant(self):
        code = ('import subprocess,sys; child=subprocess.Popen([sys.executable,"-c",'
                '"import time; time.sleep(60)"]); print(child.pid,flush=True)')
        process = subprocess.Popen([sys.executable, '-c', code], start_new_session=True,
                                   stdout=subprocess.PIPE, text=True)
        try:
            self.assertGreater(int(process.stdout.readline()), 0)
            self.assertEqual(process.wait(timeout=10), 0)
            qualifier.stop_group(process)
        finally:
            try:
                qualifier.stop_group(process)
            finally:
                process.stdout.close()

    def test_case_failure_still_removes_its_unique_container(self):
        args = argparse.Namespace(executor=Path('/unused/executor'), executor_qualification=Path('/unused/q'),
            executor_checker=Path('/unused/checker'), executor_checker_python=Path(sys.executable), timeout=1)
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-entry-', dir=parent) as temporary:
            with patch.object(qualifier, 'producer_command', return_value=['real-producer']), \
                 patch.object(qualifier.subprocess, 'Popen') as start, \
                 patch.object(qualifier, 'stop_group') as stop, \
                 patch.object(qualifier, 'remove_container') as remove:
                start.return_value.wait.return_value = 1
                with self.assertRaisesRegex(ValueError, 'case failed'):
                    qualifier.run_case(args, gpu=ROOT, source=ROOT, temporary=Path(temporary),
                                       ntt=ROOT, image='immutable-image', case='shared')
                stop.assert_called_once_with(start.return_value)
                remove.assert_called_once()
                self.assertTrue(remove.call_args.args[0].startswith('opengpu-compute-'))
                self.assertIn('--managed-session-directory', start.call_args.args[0])
                self.assertIn(Path(temporary) / 'shared-session', start.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
