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
import owner_process_lifetime as lifetime

ROOT = Path(__file__).resolve().parents[1]


class Entry(unittest.TestCase):
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
            for profile in ('two-cu-vga60-owner-v1', 'unknown', qualifier.COMPUTE_STAGE, *qualifier.FULL_COMPUTE_CONTRACTS):
                qualification.write_text(json.dumps(dict(schema='opengpu-owner-executor-v1',kind='compute',profile=profile)))
                with patch.object(qualifier.subprocess,'run'):
                    if profile==qualifier.COMPUTE_STAGE or profile in qualifier.FULL_COMPUTE_CONTRACTS:
                        qualifier.checked_inputs(args)
                    else:
                        with self.assertRaisesRegex(ValueError,'distinct shared-enabled'):
                            qualifier.checked_inputs(args)

    def test_fixed_paths_real_producer_and_both_full_sources(self):
        gpu = ROOT
        args = argparse.Namespace(mesa=gpu / 'depends/mesa', mesa_build=gpu / 'depends/mesa-build',
            drm_include=gpu / 'depends/include', mesa_link_libs='-lz -lexpat', library_directory=gpu / 'depends/lib', timeout=3600)
        for case in qualifier.CASES:
            command = qualifier.producer_command(args, gpu=gpu, source=gpu / '.artifacts/source',
                ntt=gpu / '.artifacts/ntt.c', image='sha256:' + '1' * 64, name='unique-owned-container', case=case)
            self.assertIn(f'{gpu}:{gpu}:ro', command)
            self.assertIn('{ipc}:{ipc}:rw', command)
            self.assertIn('TMPDIR={ipc}', command)
            self.assertIn('--memory-swap', command)
            self.assertIn('--network=none', command)
            self.assertIn('--read-only', command)
            self.assertIn('OPENGPU_COMPUTE_HOST_TIMEOUT=3600', command)
            self.assertEqual(command[-2:], ['--case', case])
            self.assertNotIn('--capture-only', command)
            self.assertNotIn('OPENGPU_COMPUTE_HOST_TEST_MAIN', ' '.join(command))
            self.assertIn('{ipc}/profile.bin', command)
            self.assertIn('scripts/test_gallium_compute_host.py', command)
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
