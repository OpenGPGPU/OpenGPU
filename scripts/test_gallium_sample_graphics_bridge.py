#!/usr/bin/env python3
"""Fast private112 graphics admission/entry tests; no Mesa build or GPU."""
import os
from pathlib import Path
import runpy
import shlex
import struct
import subprocess
import sys
import tempfile
import unittest
from owner_resource_contract import SAMPLE_WORDS,V1_WORDS,FULL_WORDS
ROOT=Path(__file__).resolve().parents[1]
AUX=[(0,0x47550002),(0x60,sum(1<<b for b in (6,22,23,25,29))|128<<8),(0x68,0x160),(0x320,1048576),(0x158,0x02020810)]

def profile(words=SAMPLE_WORDS,magic=b'OGNP0002'):
    return magic+struct.pack('<I',len(words))+b''.join(struct.pack('<II',w['address'],w['value']) for w in words)+struct.pack('<I',5)+b''.join(struct.pack('<II',*p) for p in AUX)

class PrivateMesaBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.tmp=tempfile.TemporaryDirectory(prefix='mesa112-guards-',dir=ROOT/'.artifacts');cls.out=Path(cls.tmp.name)
        runpy.run_path(str(ROOT/'scripts/test_native_mmio_capture.py'))['write_bodies'](cls.out)
        flags=['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-unused-function','-D_POSIX_C_SOURCE=200809L']
        if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
        inc=[cls.out,ROOT/'driver',ROOT/'driver/tests',ROOT/'driver/tests/private_snapshot_stubs',ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs',ROOT/'userspace',ROOT/'userspace/tests']
        cls.binary=cls.out/'profile-check'
        subprocess.run([*shlex.split(os.environ.get('CC','cc')),*flags,*['-I'+str(p) for p in inc],
            str(ROOT/'userspace/tests/graphics_sample_profile_test.c'),str(ROOT/'userspace/tests/native_graphics_uniform_source.c'),
            str(ROOT/'userspace/opengpu_native_sample_graphics.c'),str(ROOT/'driver/opengpu_native_frame.c'),'-o',str(cls.binary)],check=True)
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def test_actual_new_kernel_bootstrap_and_old_profile_isolation(self):
        path=self.out/'valid.ognp';path.write_bytes(profile())
        subprocess.run([str(self.binary),str(path),'valid'],check=True,timeout=15)
        four=bytearray(profile());struct.pack_into('<I',four,len(four)-4,0x02040810)
        path=self.out/'legal-four-banks.ognp';path.write_bytes(four)
        subprocess.run([str(self.binary),str(path),'valid'],check=True,timeout=15)
    def test_wrong_version_counts_raw_shader_config_and_canonical_eof(self):
        good=profile();variants=[profile(V1_WORDS),profile(FULL_WORDS),profile(magic=b'OGNP0001'),good+b'\0',good[:-1]]
        bad=bytearray(good);struct.pack_into('<I',bad,len(bad)-4,0x02030810);variants.append(bytes(bad))
        for i,value in enumerate(variants):
            path=self.out/('bad%d.ognp'%i);path.write_bytes(value)
            subprocess.run([str(self.binary),str(path),'invalid'],check=True,timeout=15)
    def test_cpu_entry_rejects_live_or_missing_private_kernel_before_build(self):
        build=self.out/'must-not-create';base=[sys.executable,str(ROOT/'scripts/test_gallium_sample_graphics_uniforms.py'),
            '--build',str(build),'--cpu-capture-only','--profile-wire',str(self.out/'no-profile')]
        for args,env in [(base,dict(os.environ)),(base+['--native-kernel-root',str(ROOT)],dict(os.environ,OPENGPU_NATIVE_HOST_DIRECTORY='forbidden'))]:
            result=subprocess.run(args,text=True,capture_output=True,env=env)
            self.assertNotEqual(result.returncode,0);self.assertIn('live owner protocol is not implemented',result.stderr);self.assertFalse(build.exists())

if __name__=='__main__':unittest.main()
