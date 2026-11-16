#!/usr/bin/env python3
"""Bounded memory accounting and cleanup of real caller-owned processes."""
import ctypes
import errno
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import owner_process_memory as memory
from owner_process_lifetime import group_dead, stop_group

MIB = 1024 * 1024


class MemoryTests(unittest.TestCase):
    def darwin_reader(self, resident, footprint, *, error=None, exited=0, birth=123):
        def read(pid, flavor, buffer):
            self.assertEqual((pid, flavor), (42, 0))
            if error is not None:
                ctypes.set_errno(error)
                return -1
            info = ctypes.cast(buffer, ctypes.POINTER(memory._RusageInfoV0)).contents
            info.ri_resident_size = resident
            info.ri_phys_footprint = footprint
            info.ri_proc_start_abstime = birth
            info.ri_proc_exit_abstime = exited
            return 0
        # A ctypes function permits argtypes/restype attributes.
        class Lib:
            proc_pid_rusage = staticmethod(read)
        return patch.object(memory.ctypes, 'CDLL', return_value=Lib())

    def test_compressed_footprint_trips_ceiling_while_rss_fits(self):
        with patch.object(memory.sys, 'platform', 'darwin'), self.darwin_reader(MIB // 4, 2 * MIB):
            guard = memory.ProcessMemoryBudget(1)
            with self.assertRaisesRegex(memory.MemoryBudgetExceeded, 'charged=2097152 resident=262144'):
                guard.check({42: 'owned birth'})
            self.assertEqual(guard.peak_resident_bytes, MIB // 4)
            self.assertEqual(guard.peak_charged_bytes, 2 * MIB)

    def test_resident_coverage_is_preserved_without_double_counting(self):
        for resident, footprint in ((2 * MIB, MIB), (MIB, 2 * MIB)):
            with self.subTest(resident=resident), self.darwin_reader(resident, footprint):
                self.assertEqual(memory._darwin_memory(42), memory.ProcessMemory(123, resident, 2 * MIB))

    def test_failed_native_observation_never_falls_back_to_rss(self):
        with patch.object(memory.sys, 'platform', 'darwin'):
            for error in (errno.EPERM, errno.EACCES, errno.EINVAL):
                with self.subTest(error=error), self.darwin_reader(0, 0, error=error):
                    with self.assertRaises(memory.MemoryObservationError):
                        memory.process_memory(42)
            with self.darwin_reader(0, 0, error=errno.ESRCH):
                self.assertIsNone(memory.process_memory(42))
            with self.darwin_reader(0, 0, exited=99):
                self.assertIsNone(memory.process_memory(42))
            with self.darwin_reader(0, 0, birth=0):
                with self.assertRaises(memory.MemoryObservationError):
                    memory.process_memory(42)

    def test_aggregate_limit_and_peaks_cover_all_owned_processes(self):
        guard = memory.ProcessMemoryBudget(3)
        with patch.object(memory, 'process_memory', side_effect=[
                memory.ProcessMemory(10, MIB, MIB), memory.ProcessMemory(20, MIB, 2 * MIB),
                memory.ProcessMemory(10, MIB, 2 * MIB), memory.ProcessMemory(20, MIB, 2 * MIB)]):
            self.assertEqual(guard.check({1: 'a', 2: 'b'}), (2 * MIB, 3 * MIB))
            with self.assertRaises(memory.MemoryBudgetExceeded):
                guard.check({1: 'a', 2: 'b'})
        self.assertEqual((guard.peak_resident_bytes, guard.peak_charged_bytes), (2 * MIB, 4 * MIB))

    def test_pid_reuse_and_caller_identity_change_fail(self):
        for native_birth, caller_birth in ((11, 'same'), (10, 'different')):
            guard = memory.ProcessMemoryBudget(1)
            with patch.object(memory, 'process_memory', side_effect=[
                    memory.ProcessMemory(10, 1, 2), memory.ProcessMemory(native_birth, 1, 2)]):
                guard.check({42: 'same'})
                with self.assertRaisesRegex(memory.MemoryObservationError, 'reused'):
                    guard.check({42: caller_birth})

    def test_gone_pid_does_not_contribute_live_memory(self):
        guard = memory.ProcessMemoryBudget(1)
        with patch.object(memory, 'process_memory', return_value=None):
            self.assertEqual(guard.check({42: 'owned'}), (0, 0))

    def test_invalid_budget_pid_and_platform_are_errors(self):
        for value in (0, -1, True, 1.5, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                memory.ProcessMemoryBudget(value)
        for pid in (0, -1, True, 1.5, '42'):
            with self.subTest(pid=pid), self.assertRaises(memory.MemoryObservationError):
                memory.process_memory(pid)
        with patch.object(memory.sys, 'platform', 'unsupported'):
            with self.assertRaises(memory.MemoryObservationError):
                memory.process_memory(42)

    def test_linux_swap_and_unusual_process_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            process = root / '42'
            process.mkdir()
            fields = ['S'] + ['0'] * 18 + ['123']
            (process / 'stat').write_text('42 (worker with ) parens) ' + ' '.join(fields))
            (process / 'status').write_text('Name:\tworker\nVmRSS:\t8 kB\nVmSwap:\t32 kB\n')
            self.assertEqual(memory._linux_memory(42, root), memory.ProcessMemory(123, 8192, 40960))
            for status in ('VmRSS: 8 kB\n', 'VmRSS: -1 kB\nVmSwap: 0 kB\n',
                           'VmRSS: 8 MB\nVmSwap: 0 kB\n', 'VmRSS: 8 kB\nVmRSS: 1 kB\nVmSwap: 0 kB\n'):
                with self.subTest(status=status):
                    (process / 'status').write_text(status)
                    with self.assertRaises((ValueError, KeyError)):
                        memory._linux_memory(42, root)
            (process / 'status').unlink()
            with self.assertRaises(FileNotFoundError):
                memory._linux_memory(42, root)
            self.assertIsNone(memory._linux_memory(99999, root))

    def test_linux_reuse_during_read_and_zombie(self):
        with patch.object(memory, '_linux_stat', side_effect=[('S', 1), ('S', 2)]), \
             patch.object(Path, 'read_text', return_value='VmRSS: 8 kB\nVmSwap: 0 kB\n'):
            with self.assertRaisesRegex(ValueError, 'reused'):
                memory._linux_memory(42)
        with patch.object(memory, '_linux_stat', return_value=('Z', 1)):
            self.assertIsNone(memory._linux_memory(42))

    def test_real_owned_allocation_and_failure_cleanup(self):
        # Keep the parent and child alive throughout the samples. The child
        # touches 32 MiB; this never creates deliberate system memory pressure.
        code = 'import sys; data=bytearray(32*1024*1024); print("ready",flush=True); sys.stdin.read(1)'
        process = subprocess.Popen([sys.executable, '-B', '-c', code], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, text=True, start_new_session=True)
        try:
            self.assertEqual(process.stdout.readline().strip(), 'ready')
            child = memory.process_memory(process.pid)
            self.assertIsNotNone(child)
            self.assertGreaterEqual(child.charged_bytes, 32 * MIB)
            self.assertEqual(memory.process_memory(process.pid).birth, child.birth)
            guard = memory.ProcessMemoryBudget(1)
            with self.assertRaises(memory.MemoryBudgetExceeded):
                guard.check({process.pid: 'caller-owned child'})
        finally:
            stop_group(process)
            process.stdin.close()
            process.stdout.close()
        self.assertTrue(group_dead(process.pid))

    @unittest.skipUnless(sys.platform == 'darwin', 'Darwin public ABI')
    def test_darwin_layout_matches_installed_c_headers(self):
        # Independent ABI check against the C compiler and installed SDK,
        # rather than duplicating Python's struct layout in a fake reader.
        source = '''#include <stddef.h>
#include <stdio.h>
#include <sys/resource.h>
int main(void) {
  printf("%zu %zu %zu %zu %zu\\n", sizeof(struct rusage_info_v0),
    offsetof(struct rusage_info_v0, ri_resident_size),
    offsetof(struct rusage_info_v0, ri_phys_footprint),
    offsetof(struct rusage_info_v0, ri_proc_start_abstime),
    offsetof(struct rusage_info_v0, ri_proc_exit_abstime));
}
'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'abi.c').write_text(source)
            subprocess.run(['cc', '-Wall', '-Werror', str(path / 'abi.c'), '-o', str(path / 'abi')],
                           check=True, timeout=30)
            actual = list(map(int, subprocess.check_output([str(path / 'abi')], text=True, timeout=5).split()))
        expected = [ctypes.sizeof(memory._RusageInfoV0)] + [
            getattr(memory._RusageInfoV0, name).offset for name in (
                'ri_resident_size', 'ri_phys_footprint', 'ri_proc_start_abstime', 'ri_proc_exit_abstime')]
        self.assertEqual(actual, expected)


if __name__ == '__main__':
    unittest.main()
