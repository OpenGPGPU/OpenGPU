#!/usr/bin/env python3
"""CPU entry/lifetime checks; never supplies GPU observations or images."""
import argparse
from contextlib import redirect_stderr
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import qualify_native_graphics_owner as qualifier
import owner_process_lifetime as lifetime
import test_gallium_native_capture as capture

ROOT = Path(__file__).resolve().parents[1]


class Entry(unittest.TestCase):
    def test_absent_qualification_stops_before_any_command(self):
        args = argparse.Namespace(executor_qualification=ROOT / '.artifacts/no-native-qualification',
                                  executor=ROOT / '.artifacts/no-native-executor')
        with patch.object(qualifier.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'qualification is required'):
                qualifier.checked_inputs(args)
            run.assert_not_called()

    def test_original_mesa_producer_receives_live_profile_and_writable_session_only(self):
        args = argparse.Namespace(mesa=ROOT / 'depends/mesa', mesa_build=ROOT / 'depends/mesa-build',
            drm_include=ROOT / 'depends/include', mesa_link_libs='-lz -lexpat',
            library_directory=ROOT / 'depends/lib', timeout=3600)
        temporary = ROOT / '.artifacts/private'
        command = qualifier.producer_command(args, gpu=ROOT, temporary=temporary,
            source=temporary / 'source', kernel=temporary / 'kernel', lowering=temporary / 'nir.c',
            image='sha256:' + '1' * 64, name='owned-native-container')
        self.assertIn(f'{ROOT}:{ROOT}:ro', command)
        self.assertIn(f'{temporary}:{temporary}:rw', command)
        self.assertIn('OPENGPU_NATIVE_HOST_PROFILE={ipc}/profile.bin', command)
        self.assertIn('OPENGPU_NATIVE_HOST_TIMEOUT=3600', command)
        self.assertIn('scripts/test_gallium_graphics_uniforms.py', command)
        self.assertIn('--sanitize', command)
        self.assertNotIn('--capture-only', command)
        self.assertNotIn('test-profile', ' '.join(map(str, command)))

    def test_container_exit_must_be_proven(self):
        result = lambda code, output='': subprocess.CompletedProcess([], code, stdout=output, stderr='')
        with patch('owner_process_lifetime.subprocess.run', side_effect=[result(1), result(0)]):
            qualifier.remove_container('owned-native')
        for reply in (result(1), result(0, 'still-running-id')):
            with patch('owner_process_lifetime.subprocess.run', side_effect=[result(1), reply]):
                with self.assertRaises(qualifier.CleanupIncomplete):
                    qualifier.remove_container('owned-native')

    def test_real_controller_process_group_stops_before_input_removal(self):
        process = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
        try:
            qualifier.stop_group(process)
            self.assertIsNotNone(process.poll())
        finally:
            if process.poll() is None:
                process.kill(); process.wait()

    def test_unknown_leader_exit_preserves_managed_capture_inputs(self):
        for first in (OSError('wait unknown'), subprocess.TimeoutExpired('leader', 10)):
            for last in (OSError('exit unknown'), subprocess.TimeoutExpired('leader', 10)):
                process = unittest.mock.Mock(pid=42)
                process.wait.side_effect = [first, last]
                temporary = None
                diagnostic = io.StringIO()
                try:
                    with patch.object(lifetime.os, 'killpg'), patch.object(lifetime, 'group_dead') as dead:
                        with redirect_stderr(diagnostic), self.assertRaisesRegex(
                                qualifier.CleanupIncomplete, 'leader exit after SIGKILL'):
                            with capture.capture_workspace() as directory:
                                temporary = Path(directory)
                                (temporary / 'inputs.bin').write_bytes(b'CPU fixture')
                                lifetime.stop_group(process)
                    self.assertEqual(process.wait.call_args_list,
                        [unittest.mock.call(timeout=10), unittest.mock.call(timeout=10)])
                    dead.assert_not_called()
                    self.assertEqual((temporary / 'inputs.bin').read_bytes(), b'CPU fixture')
                    self.assertIn('preserved inputs', diagnostic.getvalue())
                finally:
                    # The peer is a mock; there is no process consuming these
                    # deliberately retained CPU fixture bytes after the check.
                    if temporary is not None:
                        shutil.rmtree(temporary)

    def test_initial_wait_error_still_requires_final_exit_and_group_proof(self):
        process = unittest.mock.Mock(pid=42)
        process.wait.side_effect = [OSError('initial wait unknown'), 0]
        with patch.object(lifetime.os, 'killpg') as terminate, \
             patch.object(lifetime, 'group_dead', return_value=True) as dead:
            with capture.capture_workspace() as directory:
                temporary = Path(directory)
                (temporary / 'inputs.bin').write_bytes(b'CPU fixture')
                lifetime.stop_group(process)
        self.assertEqual(process.wait.call_args_list,
            [unittest.mock.call(timeout=10), unittest.mock.call(timeout=10)])
        self.assertEqual(terminate.call_count, 2)
        dead.assert_called_once_with(42)
        self.assertFalse(temporary.exists())

    def test_failure_stops_process_and_checks_exact_owned_container(self):
        args = argparse.Namespace(executor=Path('/unused/executor'), executor_qualification=Path('/unused/q'),
            executor_checker=Path('/unused/checker'), executor_checker_python=Path(sys.executable), timeout=1)
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='native-entry-', dir=parent) as temporary:
            with patch.object(qualifier, 'producer_command', return_value=['actual-producer']), \
                 patch.object(qualifier.subprocess, 'Popen') as start, \
                 patch.object(qualifier, 'stop_group') as stop, \
                 patch.object(qualifier, 'remove_container') as remove:
                start.return_value.wait.return_value = 1
                with self.assertRaisesRegex(ValueError, 'native owner execution failed'):
                    qualifier.run_owner(args, gpu=ROOT, temporary=Path(temporary), source=ROOT,
                        kernel=ROOT, lowering=ROOT, image='immutable-image')
                stop.assert_called_once_with(start.return_value)
                remove.assert_called_once()
                self.assertTrue(remove.call_args.args[0].startswith('opengpu-native-'))
                self.assertIn('--managed-session-directory', start.call_args.args[0])
                self.assertIn(Path(temporary) / 'session', start.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
