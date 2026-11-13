"""Shared native/compute process and container exit proof before input deletion."""
import os
import signal
import subprocess


class CleanupIncomplete(RuntimeError):
    """An active consumer cannot be excluded; preserve its temporary inputs."""


def stop_group(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    except OSError as error:
        raise CleanupIncomplete('cannot stop the owned controller process group') from error
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except OSError as error:
        raise CleanupIncomplete('cannot exclude live controller/executor children') from error
    process.wait()


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


