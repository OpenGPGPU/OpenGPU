#!/usr/bin/env python3
"""Original kernel admission and dependency attribution, never DUT wait proof."""
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest
from compute_global_atomic_cases import CASES,bound_words,initial_kernarg
from owner_resource_contract import IMAGE_FLOAT_PROFILE
from test_compute_host import write_bodies

ROOT=Path(__file__).resolve().parents[1]


class ReservationAdmission(unittest.TestCase):
    def test_actual_linux_cross_context_gem_dependencies_and_independent_global_chain(self):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='reservation-admission-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);write_bodies(out,full_profile=IMAGE_FLOAT_PROFILE)
            for index,case in enumerate(CASES):
                code=bound_words(case)
                (out/('program-%u.bin'%index)).write_bytes(struct.pack('<%uI'%len(code),*code))
                (out/('initial-%u.bin'%index)).write_bytes(initial_kernarg(case))
            flags=['-std=c11','-D_POSIX_C_SOURCE=200809L','-O1','-Wall','-Wextra','-Werror',
                   '-Wno-unused-function','-fsanitize=address,undefined','-fno-omit-frame-pointer']
            includes=[out,ROOT/'driver',ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs']
            command=shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(path) for path in includes]
            built=subprocess.run([*command,str(ROOT/'driver/tests/opengpu_compute_reservation_admission_test.c'),
                                  '-o',str(out/'test')],capture_output=True,text=True)
            self.assertEqual(built.returncode,0,built.stderr)
            ran=subprocess.run([str(out/'test'),str(out)],capture_output=True,text=True,
                env=dict(os.environ,ASAN_OPTIONS='halt_on_error=1:abort_on_error=1',UBSAN_OPTIONS='halt_on_error=1'))
            self.assertEqual(ran.returncode,0,ran.stdout+ran.stderr)
            self.assertIn('96 cross-context pairs / 192 jobs',ran.stdout)
            self.assertIn('96 blocked/released-or-cancelled cases + 48 disjoint controls PASS',ran.stdout)


if __name__=='__main__':unittest.main()
