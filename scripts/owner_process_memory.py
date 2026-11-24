"""Sample memory for caller-owned processes, including compressed/swapped pages.

Ownership, deadlines, termination and proof of exit remain the caller's job.
This guard never signals a PID or authorizes removal of a process's inputs.
"""
import ctypes
from dataclasses import dataclass
import errno
import os
from pathlib import Path
import subprocess
import sys


class MemoryObservationError(RuntimeError):
    """A required memory observation is unavailable or its identity changed."""


class MemoryBudgetExceeded(RuntimeError):
    pass


@dataclass(frozen=True)
class ProcessMemory:
    birth: int
    resident_bytes: int
    charged_bytes: int


class _RusageInfoV0(ctypes.Structure):
    # Public RUSAGE_INFO_V0 ABI in Darwin's sys/resource.h. Do not pass a
    # newer flavor with this smaller buffer.
    _fields_ = [('ri_uuid', ctypes.c_uint8 * 16)] + [
        (name, ctypes.c_uint64) for name in (
            'ri_user_time', 'ri_system_time', 'ri_pkg_idle_wkups',
            'ri_interrupt_wkups', 'ri_pageins', 'ri_wired_size',
            'ri_resident_size', 'ri_phys_footprint', 'ri_proc_start_abstime',
            'ri_proc_exit_abstime')]


def _darwin_exit_confirmed(pid):
    # A short-lived child can lose its task port before the next rusage
    # sample. A missing PID or a zombie is independent evidence of exit.
    try:
        state = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'stat='],
            capture_output=True, text=True, timeout=1)
        fields = state.stdout.split()
        if (state.returncode == 0 and not state.stderr.strip() and
                len(fields) == 1 and fields[0].startswith(('Z', 'X'))):
            return True
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return True
    except OSError:
        return False
    return False


def _darwin_usage(pid):
    libproc = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
    read = libproc.proc_pid_rusage
    read.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
    read.restype = ctypes.c_int
    usage = _RusageInfoV0()
    if read(pid, 0, ctypes.byref(usage)) != 0:
        error = ctypes.get_errno()
        if error == errno.ESRCH or (error == errno.EPERM and _darwin_exit_confirmed(pid)):
            return None
        raise OSError(error, 'proc_pid_rusage failed', str(pid))
    if usage.ri_proc_exit_abstime:
        return None
    if not usage.ri_proc_start_abstime:
        raise ValueError('missing process birth identity')
    return usage


def _darwin_memory(pid):
    usage = _darwin_usage(pid)
    if usage is None:
        return None
    # phys_footprint includes the task's compressed-memory ledger. RSS can
    # exceed it for shared/file-backed pages; retain the larger value rather
    # than counting those pages twice or reverting to an RSS-only ceiling.
    return ProcessMemory(usage.ri_proc_start_abstime, usage.ri_resident_size,
                         max(usage.ri_resident_size, usage.ri_phys_footprint))


def _linux_stat(path):
    # comm may itself contain spaces or parentheses.
    fields = (path / 'stat').read_text().rsplit(')', 1)[1].split()
    return fields[0], int(fields[19])


def _linux_memory(pid, proc=Path('/proc')):
    path = proc / str(pid)
    try:
        state, birth = _linux_stat(path)
        if state in {'Z', 'X'}:
            return None
        values = {}
        for line in (path / 'status').read_text().splitlines():
            name, _, value = line.partition(':')
            if name in {'VmRSS', 'VmSwap'}:
                amount, unit = value.split()
                if unit != 'kB' or int(amount) < 0 or name in values:
                    raise ValueError('invalid memory field: ' + line)
                values[name] = int(amount) * 1024
        if not values:
            # exit_mm can release the address space before the task becomes
            # a zombie. Linux proc_pid_statm reports seven zeroes for a task
            # without an mm. This is positive zero-memory evidence, not an
            # RSS fallback or proof that the process itself has exited.
            if (path / 'statm').read_text().split() != ['0'] * 7:
                raise ValueError('missing memory counters without zero address-space evidence')
            values = {'VmRSS': 0, 'VmSwap': 0}
        after_state, after_birth = _linux_stat(path)
        if after_birth != birth:
            raise ValueError('PID reused during memory observation')
        if after_state in {'Z', 'X'}:
            return None
        return ProcessMemory(birth, values['VmRSS'], values['VmRSS'] + values['VmSwap'])
    except FileNotFoundError:
        # A departed process contributes no live memory. Other read failures,
        # missing counters and PID reuse must never silently contribute zero.
        if path.exists():
            raise
        return None


def process_memory(pid):
    """Return a stable native birth ID and conservative bytes, or a dead PID.

    On macOS charge max(resident, physical footprint); on Linux charge RSS
    plus VmSwap. These are sampled per-process values, not a kernel hard limit
    or an atomic snapshot of the entire tree. Shared pages may be counted for
    more than one process.
    """
    try:
        if type(pid) is not int or pid <= 0:
            raise ValueError('a positive integer PID is required')
        if sys.platform == 'darwin':
            return _darwin_memory(pid)
        if sys.platform.startswith('linux'):
            return _linux_memory(pid)
        raise ValueError('unsupported memory-accounting platform: ' + sys.platform)
    except (OSError, ValueError, KeyError, IndexError) as error:
        raise MemoryObservationError(f'cannot account for owned PID {pid}: {error}') from error


def process_birth(pid):
    """Observe native identity independently of memory accounting for cleanup."""
    try:
        if type(pid) is not int or pid <= 0:
            raise ValueError('a positive integer PID is required')
        if sys.platform == 'darwin':
            usage = _darwin_usage(pid)
            return usage.ri_proc_start_abstime if usage is not None else None
        if sys.platform.startswith('linux'):
            path = Path('/proc') / str(pid)
            try:
                state, birth = _linux_stat(path)
            except FileNotFoundError:
                if path.exists():
                    raise
                return None
            if state in {'Z', 'X'}:
                return None
            if birth <= 0:
                raise ValueError('missing process birth identity')
            return birth
        raise ValueError('unsupported process-identity platform: ' + sys.platform)
    except (OSError, ValueError, KeyError, IndexError) as error:
        raise MemoryObservationError(f'cannot identify owned PID {pid}: {error}') from error


class ProcessMemoryBudget:
    """Enforce a sampled aggregate ceiling for an already-owned process tree.

    Call check with {pid: caller_birth_identity} after each ancestry/ownership
    observation. Native start IDs additionally detect reuse between polls.
    Any failure must enter the caller's normal process cleanup path; never
    call this guard as a prerequisite for cleanup itself.
    """
    def __init__(self, memory_mib):
        if type(memory_mib) is not int or memory_mib <= 0:
            raise ValueError('memory_mib must be a positive integer')
        self.limit_bytes = memory_mib * 1024 * 1024
        self.peak_resident_bytes = 0
        self.peak_charged_bytes = 0
        self._births = {}

    def check(self, owned):
        resident = charged = 0
        for pid, caller_birth in owned.items():
            sample = process_memory(pid)
            if sample is None:
                continue
            birth = (caller_birth, sample.birth)
            if pid in self._births and self._births[pid] != birth:
                raise MemoryObservationError(f'owned PID {pid} was reused')
            self._births[pid] = birth
            resident += sample.resident_bytes
            charged += sample.charged_bytes
        self.peak_resident_bytes = max(self.peak_resident_bytes, resident)
        self.peak_charged_bytes = max(self.peak_charged_bytes, charged)
        if charged > self.limit_bytes:
            raise MemoryBudgetExceeded(
                f'sampled aggregate memory ceiling exceeded: charged={charged} '
                f'resident={resident} limit={self.limit_bytes} bytes')
        return resident, charged
