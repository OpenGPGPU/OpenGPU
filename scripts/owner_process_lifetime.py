"""Shared native/compute process and container exit proof before input deletion."""
import os
import signal
import subprocess
import time


class CleanupIncomplete(RuntimeError):
    """An active consumer cannot be excluded; preserve its temporary inputs."""


def group_dead(group):
    """A joined leader alone says nothing about its remaining descendants."""
    try:
        result = subprocess.run(['ps', '-axo', 'pgid=,stat='], stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, timeout=2, check=False)
        if result.returncode or not result.stdout.strip():
            raise CleanupIncomplete('cannot enumerate the owned process group')
        for line in result.stdout.splitlines():
            fields = line.split()
            if not fields or not fields[0].lstrip('-').isdigit():
                raise CleanupIncomplete('unknown process-group exit observation')
            if int(fields[0]) != group:
                continue
            if len(fields) != 2 or not fields[1][0].isalpha():
                raise CleanupIncomplete('unknown owned process-group state')
            if not fields[1].startswith('Z'):
                return False
        return True
    except (OSError, subprocess.TimeoutExpired) as error:
        raise CleanupIncomplete('cannot verify owned process-group exit') from error


def stop_group(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    except OSError:
        # macOS can return EPERM for a group containing only orphan zombies.
        # The independent group observation below must still prove it dead.
        pass
    try:
        process.wait(timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except OSError:
        pass
    try:
        process.wait(timeout=10)
    except (OSError, subprocess.TimeoutExpired) as error:
        # A termination request cannot authorize deletion of live inputs.
        raise CleanupIncomplete('cannot prove owned process leader exit after SIGKILL') from error
    deadline = time.monotonic() + 10
    while True:
        try:
            if group_dead(process.pid):
                break
        except CleanupIncomplete:
            # ps can race a departing process. Retry only within the bound;
            # an unknown final observation never authorizes input deletion.
            pass
        if time.monotonic() >= deadline:
            raise CleanupIncomplete('owned process group still has active members or unknown state after SIGKILL')
        time.sleep(0.05)


def remove_container(name):
    try:
        removed = subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, timeout=30, check=False)
        if removed.returncode:
            remaining = subprocess.run(['docker', 'ps', '-aq', '--filter', 'name=^/' + name + '$'],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False, text=True)
            if remaining.returncode or remaining.stdout.strip():
                raise CleanupIncomplete('cannot exclude a live task container: ' + name)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise CleanupIncomplete('cannot verify task container exit: ' + name) from error
