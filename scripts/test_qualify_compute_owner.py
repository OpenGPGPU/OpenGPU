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
                        if (case in qualifier.IMAGE_FLOAT_EXCHANGE_CASES or '-r32f-' in case or
                                case in qualifier.MIXED_ATOMIC_CASES) and profile==qualifier.SAMPLE_PROFILE:
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
            drm_include=gpu / 'depends/include', mesa_link_libs='-lz -lexpat', library_directory=gpu / 'depends/lib', timeout=3600,
            counter_inputs=gpu / '.artifacts/counter-inputs.json',global_inputs=gpu/'.artifacts/global-inputs.json')
        for case in qualifier.CASES:
            command = qualifier.producer_command(args, gpu=gpu, source=gpu / '.artifacts/source',
                ntt=gpu / '.artifacts/ntt.c', image='sha256:' + '1' * 64, name='unique-owned-container', case=case)
            self.assertIn(f'{gpu}:{gpu}:ro', command)
            self.assertIn('{ipc}:{ipc}:rw', command)
            self.assertIn('TMPDIR={ipc}', command)
            self.assertIn('--memory-swap', command)
            self.assertIn('--network=none', command)
            self.assertIn('--read-only', command)
            raw=case in qualifier.GLOBAL_ATOMIC_CASES
            strict=raw or case in (*qualifier.COUNTER_CASES,*qualifier.IMAGE_CASES,*qualifier.STORAGE_ATOMIC_CASES)
            expected_memory='512m' if strict else '2g'
            self.assertEqual(command[command.index('--memory')+1],expected_memory)
            self.assertEqual(command[command.index('--memory-swap')+1],expected_memory)
            self.assertEqual(command[command.index('--cpus')+1],'1' if strict else '2')
            self.assertIn('OPENGPU_COMPUTE_HOST_TIMEOUT=3600', command)
            self.assertEqual(command[command.index('--case')+1],case)
            self.assertNotIn('--capture-only', command)
            self.assertNotIn('OPENGPU_COMPUTE_HOST_TEST_MAIN', ' '.join(command))
            self.assertIn('{ipc}/profile.bin', command)
            self.assertIn('scripts/compute_global_host_producer.py' if raw else
                          'scripts/compute_owner_inputs.py' if strict else 'scripts/test_gallium_compute_host.py', command)
            if raw:
                self.assertIn('--bound-inputs',command)
                self.assertIn('OPENGPU_COMPILER_IMAGE=sha256:'+'1'*64,command)
                self.assertFalse(any('OPENGPU_MESA' in value or 'LD_LIBRARY_PATH=' in value for value in command))
            self.assertIn('--sanitize', command)
            self.assertNotIn('HOME={ipc}',command)

    def test_storage_matrix_keeps_one_producer_alive_until_every_actual_cleanup(self):
        self.storage_matrix_peer(False)

    def test_contended_images_keep_one_original_producer_and_check_all_fifty_five_cleanups(self):
        self.storage_matrix_peer(False,contended=True)

    def test_mixed_atomics_keep_one_original_producer_for_all_sixty_six_owners(self):
        self.storage_matrix_peer(False,mixed=True)

    def test_bad_mixed_old_value_prevents_cleanup_and_the_next_source(self):
        self.storage_matrix_peer(True,mixed=True)

    def test_mixed_matrix_uses_its_exact_original_alias_case_and_distinct_mode(self):
        with tempfile.TemporaryDirectory(prefix='mixed-atomic-command-') as temporary:
            root=Path(temporary);q=root/'qualification.json';q.write_text(json.dumps(dict(source_sha256={})))
            args=argparse.Namespace(mesa=root,mesa_build=root,drm_include=root,library_directory=root,
                mesa_link_libs='-lfixture',executor_qualification=q,executor=root/'executor',
                executor_checker=root/'checker',executor_checker_python=sys.executable,timeout=60,
                storage_matrix=False,storage_recovery_matrix=False,contended_image_matrix=False,mixed_atomic_matrix=True)
            with patch('compute_owner_inputs.snapshot',return_value={}),\
                 patch.object(qualifier.subprocess,'Popen') as popen,\
                 patch.object(qualifier,'stop_group'),patch.object(qualifier,'remove_container'):
                popen.return_value.wait.return_value=0
                qualifier.run_case(args,gpu=ROOT,source=ROOT,temporary=root,ntt=root/'ntt',
                    image='immutable-image',case=qualifier.MIXED_ATOMIC_CASES[0])
                self.assertIn('--mixed-atomic-matrix',popen.call_args.args[0])
                producer=json.loads((root/(qualifier.MIXED_ATOMIC_CASES[0]+'.argv.json')).read_text())
                self.assertIn('--mixed-atomic-matrix',producer)
                self.assertNotIn('--contended-image-matrix',producer)
                self.assertNotIn('--capture-only',producer)
            keyword=dict(gpu=ROOT,source=ROOT,ntt=root/'ntt',image='immutable-image',name='negative')
            for case in ('counter-increment',qualifier.MIXED_ATOMIC_CASES[1],qualifier.CONTENDED_IMAGE_CASES[0]):
                with self.subTest(case=case),self.assertRaisesRegex(ValueError,'independent original alias owners'):
                    qualifier.producer_command(args,case=case,**keyword)

    def test_bad_contended_image_old_value_prevents_cleanup_and_the_next_source(self):
        self.storage_matrix_peer(True,contended=True)

    def test_contended_image_matrix_binds_its_exact_sources_and_full_float_owner(self):
        with tempfile.TemporaryDirectory(prefix='contended-image-command-') as temporary:
            root=Path(temporary);q=root/'qualification.json';executable=root/'executor'
            executable.write_bytes(b'explicit CPU command fixture')
            args=argparse.Namespace(mesa=root,mesa_build=root,drm_include=root,library_directory=root,
                mesa_link_libs='-lfixture',executor_qualification=q,executor=executable,
                executor_checker=root/'checker',executor_checker_python=sys.executable,timeout=60,
                storage_matrix=False,storage_recovery_matrix=False,contended_image_matrix=True,
                case='image-atomics-contended')
            for profile in (qualifier.SAMPLE_PROFILE,IMAGE_FLOAT_PROFILE):
                q.write_text(json.dumps(dict(schema='opengpu-owner-executor-v1',kind='compute',
                    profile=profile,source_sha256={})))
                with patch.object(qualifier.subprocess,'run'):
                    if profile==IMAGE_FLOAT_PROFILE:qualifier.checked_inputs(args)
                    else:
                        with self.assertRaisesRegex(ValueError,'qualified float owner'):qualifier.checked_inputs(args)
            with patch('compute_owner_inputs.snapshot',return_value={}),\
                 patch.object(qualifier.subprocess,'Popen') as popen,\
                 patch.object(qualifier,'stop_group'),patch.object(qualifier,'remove_container'):
                popen.return_value.wait.return_value=0
                qualifier.run_case(args,gpu=ROOT,source=ROOT,temporary=root,ntt=root/'ntt',
                    image='immutable-image',case=qualifier.CONTENDED_IMAGE_CASES[0])
                self.assertIn('--contended-image-matrix',popen.call_args.args[0])
                producer=json.loads((root/(qualifier.CONTENDED_IMAGE_CASES[0]+'.argv.json')).read_text())
                self.assertIn('--contended-image-matrix',producer)
                self.assertNotIn('--storage-matrix',producer)
                self.assertNotIn('--capture-only',producer)
            keyword=dict(gpu=ROOT,source=ROOT,ntt=root/'ntt',image='immutable-image',name='negative')
            for case in ('counter-increment',qualifier.CONTENDED_IMAGE_CASES[1],qualifier.STORAGE_ATOMIC_CASES[0]):
                with self.subTest(case=case),self.assertRaisesRegex(ValueError,'independent original owners'):
                    qualifier.producer_command(args,case=case,**keyword)

    def test_continuous_storage_recovery_keeps_its_distinct_original_sequence(self):
        with tempfile.TemporaryDirectory(prefix='storage-recovery-command-') as temporary:
            root=Path(temporary);q=root/'qualification.json'
            q.write_text(json.dumps(dict(source_sha256={})))
            args=argparse.Namespace(mesa=root,mesa_build=root,drm_include=root,library_directory=root,
                mesa_link_libs='-lfixture',executor_qualification=q,executor=root/'executor',
                executor_checker=root/'checker',executor_checker_python=sys.executable,timeout=60,
                storage_matrix=False,storage_delayed_ack=False,storage_recovery_matrix=True)
            with patch('compute_owner_inputs.snapshot',return_value={}),\
                 patch.object(qualifier.subprocess,'Popen') as popen,\
                 patch.object(qualifier,'stop_group'),patch.object(qualifier,'remove_container'):
                popen.return_value.wait.return_value=0
                qualifier.run_case(args,gpu=ROOT,source=ROOT,temporary=root,ntt=root/'ntt',
                    image='immutable-image',case=qualifier.STORAGE_ATOMIC_CASES[0])
                self.assertIn('--storage-recovery-matrix',popen.call_args.args[0])
                producer=json.loads((root/(qualifier.STORAGE_ATOMIC_CASES[0]+'.argv.json')).read_text())
                self.assertIn('--storage-recovery-matrix',producer)
                self.assertNotIn('--storage-matrix',producer)
                self.assertNotIn('--capture-only',producer)
                popen.return_value.wait.assert_called_once_with(timeout=180)
            keyword=dict(gpu=ROOT,source=ROOT,ntt=root/'ntt',image='immutable-image',name='negative')
            for case in ('shared','counter-increment',qualifier.STORAGE_ATOMIC_CASES[1]):
                with self.subTest(case=case),self.assertRaisesRegex(ValueError,'continuous original receipts'):
                    qualifier.producer_command(args,case=case,**keyword)
            args.storage_matrix=True
            with self.assertRaises(ValueError):
                qualifier.producer_command(args,case=qualifier.STORAGE_ATOMIC_CASES[0],**keyword)

    def test_delayed_ack_uses_same_original_matrix_and_guarded_source_command(self):
        with tempfile.TemporaryDirectory(prefix='storage-ack-command-') as temporary:
            root=Path(temporary);q=root/'qualification.json'
            q.write_text(json.dumps(dict(source_sha256={})))
            args=argparse.Namespace(mesa=root,mesa_build=root,drm_include=root,library_directory=root,
                mesa_link_libs='-lfixture',executor_qualification=q,executor=root/'executor',
                executor_checker=root/'checker',executor_checker_python=sys.executable,timeout=60,
                storage_matrix=True,storage_delayed_ack=True)
            with patch('compute_owner_inputs.snapshot',return_value={}),\
                 patch.object(qualifier.subprocess,'Popen') as popen,\
                 patch.object(qualifier,'stop_group'),patch.object(qualifier,'remove_container'):
                popen.return_value.wait.return_value=0
                qualifier.run_case(args,gpu=ROOT,source=ROOT,temporary=root,ntt=root/'ntt',
                    image='immutable-image',case=qualifier.STORAGE_ATOMIC_CASES[0])
                argv=popen.call_args.args[0]
                self.assertIn('--storage-matrix',argv)
                self.assertIn('--storage-delayed-ack',argv)
                producer=json.loads((root/(qualifier.STORAGE_ATOMIC_CASES[0]+'.argv.json')).read_text())
                self.assertIn('--storage-matrix',producer)
                self.assertNotIn('--storage-delayed-ack',producer)
                self.assertEqual(producer[producer.index('--memory')+1],'512m')
                self.assertNotIn('--capture-only',producer)
                popen.return_value.wait.assert_called_once_with(timeout=180)
            args.storage_matrix=False
            with self.assertRaisesRegex(ValueError,'original complete source matrix'):
                qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=root/'ntt',
                    image='immutable-image',name='negative',case=qualifier.STORAGE_ATOMIC_CASES[0])

    def test_storage_matrix_command_cannot_substitute_another_original_source(self):
        args=argparse.Namespace(mesa=ROOT/'depends/mesa',mesa_build=ROOT/'depends/mesa-build',
            drm_include=ROOT/'depends/include',mesa_link_libs='-lz -lexpat',library_directory=ROOT/'depends/lib',
            timeout=60,counter_inputs=ROOT/'.artifacts/inputs.json',storage_matrix=True)
        keyword=dict(gpu=ROOT,source=ROOT,ntt=ROOT/'ntt.c',image='immutable-image',name='owned')
        command=qualifier.producer_command(args,case=qualifier.STORAGE_ATOMIC_CASES[0],**keyword)
        self.assertEqual(command[-1],'--storage-matrix')
        self.assertIn('CXX=c++',command)
        self.assertNotIn('--capture-only',command)
        for case in ('shared','counter-increment',qualifier.STORAGE_ATOMIC_CASES[1]):
            with self.subTest(case=case),self.assertRaisesRegex(ValueError,'exact first source'):
                qualifier.producer_command(args,case=case,**keyword)

    def test_storage_matrix_byte_failure_never_authorizes_failed_owner_cleanup(self):
        self.storage_matrix_peer(True)

    def test_delayed_ack_matrix_checks_every_actual_ack_before_owner_cleanup(self):
        self.storage_matrix_peer(False, delayed=True)

    def test_invalid_actual_ack_never_authorizes_bytes_or_cleanup(self):
        self.storage_matrix_peer(False, delayed=True, bad_ack=True)

    def test_global_matrix_keeps_all_original_scalar_callers_and_cleanup_receipts(self):
        self.storage_matrix_peer(False,global_raw=True)

    def test_global_bad_old_word_never_authorizes_failed_owner_cleanup(self):
        self.storage_matrix_peer(True,global_raw=True)

    def test_global_delayed_ack_keeps_old_word_and_cleanup_gates(self):
        self.storage_matrix_peer(False,global_raw=True,global_delayed=True)

    def test_global_wrong_ack_does_not_authorize_returned_bytes_or_cleanup(self):
        self.storage_matrix_peer(False,global_raw=True,global_delayed=True,bad_ack=True)

    def test_global_ack_entry_requires_its_exact_matrix(self):
        args=argparse.Namespace(timeout=60,global_inputs=ROOT/'.artifacts/global-inputs.json',
            global_atomic_matrix=False,global_delayed_ack=True)
        with self.assertRaisesRegex(ValueError,'original complete source matrix'):
            qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=None,image='sha256:'+'1'*64,name='raw',
                case=qualifier.GLOBAL_ATOMIC_CASES[0])
        args.global_atomic_matrix=True
        command=qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=None,image='sha256:'+'1'*64,name='raw',
            case=qualifier.GLOBAL_ATOMIC_CASES[0])
        self.assertIn('--global-atomic-matrix',command)
        self.assertIn('--bound-inputs',command)

    def test_global_source_and_matrix_bind_their_exact_original_case(self):
        args=argparse.Namespace(timeout=60,global_inputs=ROOT/'.artifacts/global-inputs.json',global_atomic_matrix=True)
        for case in qualifier.GLOBAL_ATOMIC_CASES:
            if case!=qualifier.GLOBAL_ATOMIC_CASES[0]:
                with self.assertRaisesRegex(ValueError,'exact first'):
                    qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=None,image='sha256:'+'1'*64,name='raw',case=case)
        command=qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=None,image='sha256:'+'1'*64,name='raw',
            case=qualifier.GLOBAL_ATOMIC_CASES[0])
        self.assertIn('--global-atomic-matrix',command)
        args.global_atomic_matrix=False
        del args.global_inputs
        with self.assertRaisesRegex(ValueError,'bound compiler'):
            qualifier.producer_command(args,gpu=ROOT,source=ROOT,ntt=None,image='sha256:'+'1'*64,name='raw',
                case=qualifier.GLOBAL_ATOMIC_CASES[0])

    def storage_matrix_peer(self, fail_second, delayed=False, bad_ack=False,contended=False,mixed=False,global_raw=False,global_delayed=False):
        from compute_storage_atomic_controller import run_matrix
        from compute_storage_atomic_cases import CASES
        if contended:CASES=qualifier.CONTENDED_IMAGE_CASES
        if mixed:CASES=qualifier.MIXED_ATOMIC_CASES
        if global_raw:CASES=qualifier.GLOBAL_ATOMIC_CASES
        from unittest.mock import Mock
        class Process:
            def __init__(self):self.returncode=None;self.terminated=False
            def poll(self):return self.returncode
            def wait(self,timeout=None):
                if self.returncode is None:self.returncode=0
                return self.returncode
            def terminate(self):self.terminated=True;self.returncode=-15
            def kill(self):self.returncode=-9
        processes=[];events=[];commands=[]
        def start(*_args,**_kwargs):
            commands.append(_args[0])
            process=Process();processes.append(process);return process
        def atomic(path,data):
            events.append(('response',path.parent.name,path.name))
            if path.name=='record-0.ack-delay-plan':
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        def check(*_args):
            events.append(('bytes',))
            if fail_second and len([e for e in events if e[0]=='bytes'])==2:
                raise ValueError('deliberately invalid actual atomic old word')
        qualified=Mock()
        ack_check=Mock(side_effect=ValueError('invalid actual ACK') if bad_ack else None)
        with tempfile.TemporaryDirectory(prefix='atomic-matrix-peer-',dir=ROOT/'.artifacts') as temporary:
            directory=Path(temporary)
            args=argparse.Namespace(timeout=60,storage_delayed_ack=delayed,contended_image_matrix=contended,
                                   mixed_atomic_matrix=mixed,global_atomic_matrix=global_raw,global_delayed_ack=global_delayed)
            def record(path):
                if path.name=='profile.json':return {'actual-profile-cpu-peer':True}
                return {'actual-receipt-cpu-peer':True,'commandId':42,'physicalReadFaults':0,'injectedReadFaults':0,'physicalWriteFaults':0}
            patches=(
                patch('compute_storage_atomic_controller.plan_bytes',return_value=b'original-physical-line-plan'),
                patch('compute_storage_atomic_controller.check_report',ack_check),
                patch('compute_storage_atomic_controller.subprocess.Popen',side_effect=start),
                patch('compute_storage_atomic_controller.strict_json',side_effect=record),
                patch('compute_storage_atomic_controller.held_bytes',return_value=b'held'),
                patch('compute_storage_atomic_controller.retired_bytes',return_value=b'retired'),
                patch('compute_storage_atomic_controller.failed_held_bytes',return_value=b'failed-held'),
                patch('compute_storage_atomic_controller.failed_retired_bytes',return_value=b'failed-retired'),
                patch('compute_storage_atomic_controller.checked_cleanup'),
                patch('compute_storage_atomic_controller.SparseMemory'),
                patch('compute_storage_atomic_controller.verify',side_effect=check),
                patch('compute_owner_controller.wait_file'),
                patch('compute_owner_controller.ready_inputs',return_value=({'source':'explicit-cpu-peer'},{})),
                patch('compute_owner_controller.unchanged_inputs'),
                patch('compute_owner_controller.atomic',side_effect=atomic))
            from contextlib import ExitStack
            with ExitStack() as stack, redirect_stdout(io.StringIO()):
                for active in patches:stack.enter_context(active)
                if fail_second or bad_ack:
                    with self.assertRaisesRegex(ValueError,'invalid actual ACK' if bad_ack else 'invalid actual atomic'):
                        run_matrix(args,['original-producer','{ipc}'],directory,Path('actual-executor'),{},qualified,IMAGE_FLOAT_PROFILE)
                else:run_matrix(args,['original-producer','{ipc}'],directory,Path('actual-executor'),{},qualified,IMAGE_FLOAT_PROFILE)
        cleanups=[e for e in events if e[0]=='response' and e[2]=='BEGIN_CLEANUP']
        self.assertEqual(len(cleanups),0 if bad_ack else 1 if fail_second else len(CASES))
        if fail_second or bad_ack:
            self.assertTrue(processes[0].terminated)
            self.assertTrue(processes[-1].terminated)
            qualified.assert_not_called()
            self.assertTrue(any(e[0]=='response' and e[1]==CASES[0 if bad_ack else 1] and e[2]=='error' for e in events))
            if bad_ack:
                self.assertFalse(any(e[0]=='bytes' for e in events))
                self.assertFalse(any(e[0]=='response' and e[2]=='record-0.held.response' for e in events))
        else:
            self.assertEqual(len(processes),len(CASES)+1)
            self.assertTrue(all(p.returncode==0 and not p.terminated for p in processes))
            self.assertEqual([e[1] for e in cleanups],list(CASES))
            qualified.assert_called_once()
            self.assertEqual(ack_check.call_count,len(CASES) if global_delayed else 15 if delayed else 0)
            self.assertEqual(sum('--active-storage-delayed-ack' in command for command in commands),15 if delayed else 0)
            self.assertEqual(sum('--active-global-delayed-ack' in command for command in commands),len(CASES) if global_delayed else 0)

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
