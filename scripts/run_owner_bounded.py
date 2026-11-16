#!/usr/bin/env python3
"""Run an owner qualification command with explicit memory and time budgets."""
import argparse
from dataclasses import dataclass
import math
import os
import signal
import subprocess
import sys
import time

from owner_process_lifetime import CleanupIncomplete
from owner_process_memory import ProcessMemoryBudget, process_birth


@dataclass(frozen=True)
class ProcessRow:
    parent: int
    group: int
    state: str
    birth: str

    @property
    def live(self):
        return not self.state.startswith(('Z', 'X'))


def process_rows():
    """Include unreaped leaders so short commands retain their PID identity."""
    try:
        result = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,stat=,lstart='],
            env=dict(os.environ, LC_ALL='C'), text=True, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, check=True, timeout=2)
        rows = {}
        for line in result.stdout.splitlines():
            fields = line.split()
            if len(fields) != 9:
                raise ValueError('unknown process-table row')
            pid, parent, group = map(int, fields[:3])
            if pid <= 0 or parent < 0 or group < 0 or not fields[3][0].isalpha():
                raise ValueError('invalid process identity')
            rows[pid] = ProcessRow(parent, group, fields[3], ' '.join(fields[4:]))
        if not rows:
            raise ValueError('empty process observation')
        return rows
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise CleanupIncomplete('cannot enumerate owned processes; preserve command inputs') from error


class OwnedProcesses:
    def __init__(self, process):
        self.process = process
        self.births = {}
        self.native_births = {}
        self.groups = {process.pid}

    def observe(self):
        rows = process_rows()
        leader = rows.get(self.process.pid)
        if leader is None:
            raise CleanupIncomplete('unreaped command leader is missing; preserve command inputs')
        if self.process.pid not in self.births:
            if leader.parent != os.getpid() or leader.group != self.process.pid:
                raise CleanupIncomplete('command did not retain its new process group')
            self.births[self.process.pid] = leader.birth
        if self.births[self.process.pid] != leader.birth:
            raise CleanupIncomplete('command leader identity changed')
        # Do not poll()/wait() before cleanup: the unreaped Popen leader pins
        # its PID and original group against reuse, including quick exits.
        owned = set()
        pending = set(rows)
        changed = True
        while changed:
            changed = False
            for pid in tuple(pending):
                row = rows[pid]
                known = self.births.get(pid) == row.birth
                root_group = row.group == self.process.pid
                if not row.live or not (known or root_group or row.parent == self.process.pid or row.parent in owned):
                    continue
                if root_group:
                    try:
                        if os.getsid(pid) != self.process.pid:
                            raise CleanupIncomplete('foreign process in the pinned command group')
                    except ProcessLookupError:
                        rows[pid] = ProcessRow(row.parent, row.group, 'X', row.birth)
                        pending.remove(pid)
                        continue
                birth = process_birth(pid)
                if birth is None:
                    # A positive native exit observation supersedes the older
                    # ps row. It must not turn into a false foreign live member
                    # when cleanup compares group membership with `owned`.
                    rows[pid] = ProcessRow(row.parent, row.group, 'X', row.birth)
                    pending.remove(pid)
                    continue
                if row.group <= 0:
                    raise CleanupIncomplete('owned process has no valid signal group')
                if known and pid in self.native_births and self.native_births[pid] != birth:
                    raise CleanupIncomplete('native process birth changed; preserve command inputs')
                self.births[pid] = row.birth
                self.native_births[pid] = birth
                self.groups.add(row.group)
                owned.add(pid)
                pending.remove(pid)
                changed = True
        return rows, owned, not rows[self.process.pid].live

    def _signal_groups(self, sig):
        rows, owned, _ = self.observe()
        for group in sorted(self.groups):
            # Refresh before each signal, not just once before a long cleanup.
            rows, owned, _ = self.observe()
            members = {pid for pid, row in rows.items() if row.live and row.group == group}
            if not members:
                continue
            if group == os.getpgrp() or not members <= owned:
                raise CleanupIncomplete('unowned/reused process group; preserve command inputs')
            try:
                os.killpg(group, sig)
            except ProcessLookupError:
                pass

    def finish(self):
        """Join the leader only after every observed group/descendant is dead."""
        try:
            for sig in (signal.SIGTERM, signal.SIGKILL):
                self._signal_groups(sig)
                deadline = time.monotonic() + 3
                while True:
                    rows, owned, _ = self.observe()
                    group_live = any(row.live and row.group in self.groups for row in rows.values())
                    if not owned and not group_live:
                        break
                    if time.monotonic() >= deadline:
                        break
                    time.sleep(.05)
                if not owned and not group_live:
                    break
            if owned or group_live:
                raise CleanupIncomplete('owned consumers remain after SIGKILL; preserve command inputs')
            code = self.process.wait(timeout=3)
            # No signals after reaping: a new unrelated process may reuse PID/PGID.
            rows = process_rows()
            if any(row.live and row.group in self.groups for row in rows.values()):
                raise CleanupIncomplete('final process-group exit is unknown; preserve command inputs')
            for pid, birth in self.births.items():
                row = rows.get(pid)
                if row and row.live and row.birth == birth:
                    raise CleanupIncomplete('an observed descendant remains; preserve command inputs')
            return code
        except BaseException as error:
            if self.process.returncode is None:
                # The original unreaped leader still pins this one group.
                # Do not signal an uncertain detached/reused group.
                try:
                    os.killpg(self.process.pid, signal.SIGKILL)
                except OSError:
                    pass
                try:
                    self.process.wait(timeout=3)
                except (OSError, subprocess.TimeoutExpired):
                    pass
            raise CleanupIncomplete('consumer exit not proven; preserve command inputs') from error


def report(value):
    try:
        print(value, file=sys.stderr, flush=True)
    except (OSError, ValueError):
        pass  # Reporting must not replace a stage or cleanup failure.


def run_bounded(command, memory_mib, timeout_seconds):
    budget = ProcessMemoryBudget(memory_mib)
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise ValueError('timeout_seconds must be finite and positive')
    if not command:
        raise ValueError('a command is required')
    started = time.monotonic()
    process = subprocess.Popen(command, start_new_session=True)
    tree = OwnedProcesses(process)
    clean = False
    try:
        try:
            while True:
                _, owned, exited = tree.observe()
                budget.check({pid: tree.births[pid] for pid in owned})
                if time.monotonic() - started > timeout_seconds:
                    raise TimeoutError('owner command deadline exceeded')
                if exited:
                    break
                time.sleep(.2)
        finally:
            code = tree.finish()
            clean = True
        return code
    finally:
        report(f'owner-bounded: exit={process.returncode} seconds={time.monotonic()-started:.3f} '
               f'peak_resident_bytes={budget.peak_resident_bytes} '
               f'peak_charged_bytes={budget.peak_charged_bytes} cleanup_proven={str(clean).lower()}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--memory-mib', type=int, required=True)
    parser.add_argument('--timeout-seconds', type=float, required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if args.memory_mib <= 0 or not math.isfinite(args.timeout_seconds) or args.timeout_seconds <= 0 or not command:
        parser.error('positive memory/deadline budgets and a command after -- are required')
    def interrupted(signum, _frame):
        raise InterruptedError(f'owner guard interrupted by signal {signum}')
    previous = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        code = run_bounded(command, args.memory_mib, args.timeout_seconds)
        return 128 - code if code < 0 else code
    except (OSError, RuntimeError, ValueError, TimeoutError) as error:
        report(str(error))
        return 1
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    raise SystemExit(main())
