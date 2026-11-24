#!/usr/bin/env python3
"""Exercise real command budgets and exit proof without RTL/model generation."""
import contextlib
import io
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import run_owner_bounded as bounded
from owner_process_lifetime import CleanupIncomplete, group_dead
from owner_process_memory import MemoryBudgetExceeded, MemoryObservationError, process_memory

ROOT = Path(__file__).resolve().parents[1]


class BoundedTests(unittest.TestCase):
    def setUp(self):
        (ROOT / '.artifacts').mkdir(exist_ok=True)
        self.temp = Path(tempfile.mkdtemp(prefix='bounded-command-test-', dir=ROOT / '.artifacts'))
        self.roots = []
        self.stderr = io.StringIO()
        self.sentinel = self.temp / 'input'
        self.sentinel.write_text('keep original input')

    def tearDown(self):
        groups = set()
        for process in self.roots:
            groups.add(process.pid)
            if process.returncode is None:
                # Held Popen instances have not been reaped by these tests.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=3)
        for path in self.temp.glob('identity-*.json'):
            value = json.loads(path.read_text())
            sample = process_memory(value['pid'])
            groups.add(value['group'])
            if sample and sample.birth == value['birth']:
                self.assertEqual(os.getpgid(value['pid']), value['group'])
                os.killpg(value['group'], signal.SIGKILL)
        deadline = time.monotonic() + 3
        while groups and not all(group_dead(group) for group in groups):
            if time.monotonic() > deadline:
                raise CleanupIncomplete('test consumer exit unknown; preserved ' + str(self.temp))
            time.sleep(.05)
        shutil.rmtree(self.temp)

    def run_command(self, command, memory=256, seconds=5):
        popen = subprocess.Popen
        def capture(*args, **kwargs):
            process = popen(*args, **kwargs)
            if kwargs.get('start_new_session'):
                self.roots.append(process)
            return process
        with patch.object(bounded.subprocess, 'Popen', capture), contextlib.redirect_stderr(self.stderr):
            return bounded.run_bounded(command, memory, seconds)

    def python(self, code):
        return [sys.executable, '-B', '-c', code]

    def parent(self, *, detached, early_exit=False, allocate=0, ignore_term=False):
        identity = self.temp / 'identity-child.json'
        worker = self.temp / 'worker.py'
        worker.write_text(f'''import json,os,signal,sys,time
from pathlib import Path
sys.path.insert(0,{str(ROOT / 'scripts')!r})
from owner_process_memory import process_memory
if {ignore_term!r}:signal.signal(signal.SIGTERM,signal.SIG_IGN)
sample=process_memory(os.getpid())
path=Path({str(identity)!r});staging=path.with_suffix('.tmp')
staging.write_text(json.dumps(dict(pid=os.getpid(),group=os.getpgrp(),birth=sample.birth)))
staging.replace(path)
data=bytearray({allocate}*1024*1024)
for offset in range(0,len(data),4096):data[offset]=0x5a
time.sleep(30)
''')
        return self.python(f'''import subprocess,sys,time
from pathlib import Path
child=subprocess.Popen([sys.executable,'-B',{str(worker)!r}],start_new_session={detached!r})
while not Path({str(identity)!r}).is_file():time.sleep(.01)
if not {early_exit!r}:time.sleep(30)
''')

    def test_quick_command_and_nonzero_status_are_preserved(self):
        for code in (0, 7):
            self.assertEqual(self.run_command(self.python(f'raise SystemExit({code})')), code)
        self.assertIn('cleanup_proven=true', self.stderr.getvalue())

    def test_real_aggregate_memory_limit_stops_detached_child(self):
        with self.assertRaises(MemoryBudgetExceeded):
            self.run_command(self.parent(detached=True, allocate=96), memory=80)
        self.assertIn('cleanup_proven=true', self.stderr.getvalue())
        self.assertTrue((self.temp / 'identity-child.json').is_file())
        self.assertEqual(self.sentinel.read_text(), 'keep original input')

    def test_deadline_stops_observed_new_session_even_if_term_is_ignored(self):
        with self.assertRaises(TimeoutError):
            self.run_command(self.parent(detached=True, ignore_term=True), seconds=.8)
        self.assertIn('cleanup_proven=true', self.stderr.getvalue())
        child = json.loads((self.temp / 'identity-child.json').read_text())
        self.assertTrue(group_dead(child['group']))

    def test_exited_leader_does_not_leave_original_group_child(self):
        self.assertEqual(self.run_command(self.parent(detached=False, early_exit=True)), 0)
        child = json.loads((self.temp / 'identity-child.json').read_text())
        self.assertTrue(group_dead(child['group']))
        self.assertIn('cleanup_proven=true', self.stderr.getvalue())

    def test_memory_observation_failure_preserves_error_and_cleans_consumers(self):
        error = MemoryObservationError('required footprint unavailable')
        with patch('owner_process_memory.process_memory', side_effect=error), \
             patch.object(bounded, 'process_memory', side_effect=error, create=True):
            with self.assertRaises(MemoryObservationError) as caught:
                self.run_command(self.python('import time;time.sleep(30)'))
        self.assertIs(caught.exception, error)
        self.assertTrue(group_dead(self.roots[0].pid))

    def test_unknown_snapshot_never_reports_success_or_removes_inputs(self):
        with patch.object(bounded, 'process_rows', side_effect=CleanupIncomplete('unknown snapshot')):
            with self.assertRaises(CleanupIncomplete):
                self.run_command(self.python('import time;time.sleep(30)'))
        self.assertIn('cleanup_proven=false', self.stderr.getvalue())
        self.assertTrue(self.sentinel.is_file())
        self.assertTrue(group_dead(self.roots[0].pid))

    def test_invalid_budgets_fail_before_launch(self):
        with patch.object(bounded.subprocess, 'Popen') as launch:
            for memory, seconds, command in ((0, 1, ['x']), (True, 1, ['x']), (1, 0, ['x']),
                                             (1, math.inf, ['x']), (1, math.nan, ['x']), (1, 1, [])):
                with self.subTest(memory=memory, seconds=seconds), self.assertRaises(ValueError):
                    bounded.run_bounded(command, memory, seconds)
            launch.assert_not_called()

    def test_native_pid_reuse_is_not_adopted(self):
        process = Mock(pid=42)
        tree = bounded.OwnedProcesses(process)
        row = bounded.ProcessRow(os.getpid(), 42, 'S', 'same ps birth')
        with patch.object(bounded, 'process_rows', return_value={42: row}), \
             patch.object(bounded.os, 'getsid', return_value=42), \
             patch.object(bounded, 'process_birth', side_effect=[1, 2], create=True):
            tree.observe()
            with self.assertRaisesRegex(CleanupIncomplete, 'birth changed'):
                tree.observe()

    def test_foreign_detached_group_is_never_signaled(self):
        tree = bounded.OwnedProcesses(Mock(pid=42))
        tree.groups.add(77)
        rows = {99: bounded.ProcessRow(1, 77, 'S', 'foreign')}
        with patch.object(tree, 'observe', return_value=(rows, set(), True)), \
             patch.object(bounded.os, 'killpg') as kill:
            with self.assertRaisesRegex(CleanupIncomplete, 'unowned/reused'):
                tree._signal_groups(signal.SIGKILL)
            kill.assert_not_called()

    def test_child_starting_its_own_session_refreshes_process_rows(self):
        leader = bounded.ProcessRow(os.getpid(), 42, 'S', 'leader')
        initial = {42: leader, 77: bounded.ProcessRow(42, 42, 'S', 'child')}
        for parent, expected in ((42, {42, 77}), (1, {42})):
            birth = 'child' if parent == 42 else 'reused pid'
            current = {42: leader, 77: bounded.ProcessRow(parent, 77, 'S', birth)}
            tree = bounded.OwnedProcesses(Mock(pid=42))
            with self.subTest(parent=parent), \
                 patch.object(bounded, 'process_rows', side_effect=[initial.copy(), current]), \
                 patch.object(bounded.os, 'getsid', side_effect=lambda pid: pid), \
                 patch.object(bounded.os, 'getpgid', side_effect=lambda pid: pid), \
                 patch.object(bounded, 'process_birth', side_effect=lambda pid: pid):
                rows, owned, exited = tree.observe()
                self.assertEqual(rows[77], current[77])
                self.assertEqual(owned, expected)
                self.assertEqual(tree.groups, expected)
                self.assertFalse(exited)

    def test_inconsistent_session_in_pinned_group_is_rejected(self):
        tree = bounded.OwnedProcesses(Mock(pid=42))
        rows = {42: bounded.ProcessRow(os.getpid(), 42, 'S', 'leader'),
                77: bounded.ProcessRow(42, 42, 'S', 'child')}
        with patch.object(bounded, 'process_rows', return_value=rows), \
             patch.object(bounded.os, 'getsid', side_effect=lambda pid: pid), \
             patch.object(bounded.os, 'getpgid', return_value=42), \
             patch.object(bounded, 'process_birth', side_effect=lambda pid: pid), \
             patch.object(bounded.os, 'killpg') as kill:
            with self.assertRaisesRegex(CleanupIncomplete, 'foreign process'):
                tree._signal_groups(signal.SIGTERM)
            kill.assert_not_called()

    def test_departed_process_does_not_become_a_foreign_live_member(self):
        row = bounded.ProcessRow(os.getpid(), 42, 'S', 'earlier ps snapshot')
        for exit_at in ('session', 'footprint'):
            tree = bounded.OwnedProcesses(Mock(pid=42))
            with self.subTest(exit_at=exit_at), \
                 patch.object(bounded, 'process_rows', return_value={42: row}), \
                 patch.object(bounded.os, 'getsid', return_value=42,
                              side_effect=ProcessLookupError() if exit_at == 'session' else None), \
                 patch.object(bounded, 'process_birth', return_value=None, create=True), \
                 patch.object(bounded.os, 'killpg') as kill:
                tree._signal_groups(signal.SIGTERM)
                kill.assert_not_called()

    def test_process_table_allows_unrelated_kernel_group_zero_but_rejects_unknown_rows(self):
        good = '2 0 0 S Mon Nov 16 00:00:00 2026\n'
        with patch.object(bounded.subprocess, 'run', return_value=Mock(stdout=good)):
            self.assertEqual(bounded.process_rows()[2].group, 0)
        for bad in ('', 'unknown', '2 0 -1 S Mon Nov 16 00:00:00 2026\n',
                    '2 0 2 ! Mon Nov 16 00:00:00 2026\n'):
            with self.subTest(bad=bad), patch.object(bounded.subprocess, 'run', return_value=Mock(stdout=bad)):
                with self.assertRaises(CleanupIncomplete):
                    bounded.process_rows()

    def test_transient_unknown_state_is_observed_as_live(self):
        for state in ('?', '?E'):
            snapshot = f'42 {os.getpid()} 42 {state} Mon Jan 1 00:00:00 2024\n'
            with self.subTest(state=state), \
                 patch.object(bounded.subprocess, 'run', return_value=Mock(stdout=snapshot)):
                row = bounded.process_rows()[42]
                self.assertEqual(row.state, state)
                self.assertTrue(row.live)

    def test_transient_state_still_requires_native_identity_and_group_ownership(self):
        snapshot = f'42 {os.getpid()} 42 ?E Mon Jan 1 00:00:00 2024\n'
        with patch.object(bounded.subprocess, 'run', return_value=Mock(stdout=snapshot)), \
             patch.object(bounded.os, 'getsid', return_value=42) as session, \
             patch.object(bounded, 'process_birth', side_effect=[123, 124]) as birth:
            tree = bounded.OwnedProcesses(Mock(pid=42))
            _, owned, exited = tree.observe()
            self.assertEqual(owned, {42})
            self.assertFalse(exited)
            self.assertEqual(tree.native_births, {42: 123})
            with self.assertRaisesRegex(CleanupIncomplete, 'birth changed'):
                tree.observe()
            self.assertEqual(session.call_count, 2)
            self.assertEqual(birth.call_count, 2)

    def test_broken_reporting_stream_does_not_replace_failure(self):
        class Broken:
            def write(self, _):
                raise OSError('closed diagnostic stream')
        with patch.object(bounded.sys, 'stderr', Broken()):
            bounded.report('ignored diagnostic failure')

    def test_cli_interrupt_cleans_its_child(self):
        identity = self.temp / 'task-pid'
        command = self.python(f'import os,time;from pathlib import Path;p=Path({str(identity)!r});t=p.with_suffix(".tmp");t.write_text(str(os.getpid()));t.replace(p);time.sleep(30)')
        guard = subprocess.Popen([sys.executable, '-B', str(ROOT / 'scripts/run_owner_bounded.py'),
            '--memory-mib', '256', '--timeout-seconds', '10', '--', *command],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        self.roots.append(guard)
        try:
            deadline = time.monotonic() + 5
            while not identity.is_file():
                if time.monotonic() > deadline:
                    self.fail('guarded command did not start')
                time.sleep(.02)
            guard.send_signal(signal.SIGTERM)
            _, errors = guard.communicate(timeout=10)
            self.assertEqual(guard.returncode, 1)
            self.assertIn('cleanup_proven=true', errors)
            self.assertTrue(group_dead(int(identity.read_text())))
        finally:
            guard.stdout.close()
            guard.stderr.close()


if __name__ == '__main__':
    unittest.main()
