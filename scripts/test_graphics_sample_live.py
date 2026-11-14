#!/usr/bin/env python3
"""Real Linux lifetime glue, external synthetic file peer; never a GPU run."""
import hashlib
import json
import os
from pathlib import Path
import runpy
import shlex
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from owner_resource_contract import SAMPLE_WORDS

ROOT=Path(__file__).resolve().parents[1]
READY=dict(schema='opengpu-native-sample-graphics-ready-v1',ownerProfile='two-cu-vga60-owner-v2-sample-stage',
    recordCount=2,memory='union.memory',captures=['record-0.capture','record-1.capture'],
    sourcePlans=['record-0.source-plan.json','record-1.source-plan.json'],reflection='compiler-reflection.json',
    programs=['native-real-vs.words','native-real-fs.words'])


class KernelLiveTransportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.temp=tempfile.TemporaryDirectory(prefix='ordinary112-live-',dir=ROOT/'.artifacts');cls.out=Path(cls.temp.name)
        runpy.run_path(str(ROOT/'scripts/test_native_mmio_capture.py'))['write_bodies'](cls.out)
        auxiliary=[(0,0x47550002),(0x60,sum(1<<b for b in (6,22,23,25,29))|128<<8),
                   (0x68,0x160),(0x320,1048576),(0x158,0x02020810)]
        (cls.out/'sample_profile.inc').write_text('static const struct opengpu_native_sample_word profile[117]={'+
            ','.join('{%du,%du}'%(w['address'],w['value']) for w in SAMPLE_WORDS)+','+
            ','.join('{%du,%du}'%p for p in auxiliary)+'};\n')
        cls.compiler=shlex.split(os.environ.get('CC','cc'))
        cls.flags=['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-function','-Wno-unused-parameter','-D_POSIX_C_SOURCE=200809L']
        if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':cls.flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
        paths=[cls.out,ROOT/'driver',ROOT/'driver/tests',ROOT/'driver/tests/private_snapshot_stubs',
               ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs',ROOT/'userspace',ROOT/'userspace/tests']
        cls.includes=['-I'+str(p) for p in paths];objects=[]
        for relative in ('userspace/tests/graphics_sample_live_test.c','userspace/tests/native_graphics_uniform_source.c',
                         'userspace/tests/graphics_sample_owner_ipc.c','userspace/opengpu_native_sample_graphics.c','driver/opengpu_native_frame.c'):
            obj=cls.out/(Path(relative).stem+'.o');objects.append(obj)
            subprocess.run([*cls.compiler,*cls.flags,*cls.includes,'-MMD','-MF',str(obj.with_suffix('.d')),
                            '-c',str(ROOT/relative),'-o',str(obj)],check=True)
        cls.binary=cls.out/'live-fixture'
        subprocess.run([*cls.compiler,*cls.flags,*map(str,objects),'-o',str(cls.binary)],check=True)
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def wait(self,path,process,seconds=5):
        deadline=time.monotonic()+seconds
        while not path.is_file():
            if process.poll() is not None:
                out,err=process.communicate();self.fail('CPU fixture exited before '+path.name+': '+out+err)
            if time.monotonic()>deadline:self.fail('CPU file-peer timed out: '+path.name)
            time.sleep(.005)
        return path.read_bytes()
    def atomic(self,path,data):
        temporary=path.with_suffix(path.suffix+'.new');temporary.write_bytes(data);temporary.rename(path)
    def case(self,mode):
        directory=self.out/mode;directory.mkdir()
        process=subprocess.Popen([self.binary,directory,mode],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,
            env=dict(os.environ,OPENGPU_NATIVE_HOST_TIMEOUT='1',
                     ASAN_OPTIONS='detect_leaks=%u:abort_on_error=1'%int(sys.platform.startswith('linux'))))
        try:
            if mode in ('bad-profile','bad-rx','unretained','bad-word-file','missing-input'):
                out,err=process.communicate(timeout=5);self.assertEqual(process.returncode,0,out+err)
                self.assertFalse((directory/'union.memory').exists());self.assertFalse((directory/'ready.json').exists());return
            ready=self.wait(directory/'ready.json',process);self.assertEqual(json.loads(ready),READY)
            memory=directory/'union.memory';identity=hashlib.sha256(memory.read_bytes()).digest()
            held=[];retired=[]
            for record in range(2):
                capture=self.wait(directory/('record-%u.capture'%record),process)
                self.assertEqual(capture[:8],b'OGCAP002');self.assertEqual(struct.unpack_from('<I',capture,24)[0],117)
                command,=struct.unpack_from('<I',capture,20)
                gnx=[0x474e5801,0,0x12345678,0x1234,0,0,17,11,13,0,0,0]
                payload=struct.pack('<15IQ4I',record,command,0x8700|command,*gnx,(1<<32)+64,0,0x23,3,1)
                if not record and mode=='held-bad':
                    self.atomic(directory/'record-0.held.bin',b'OGNSH001'+payload);break
                if not record and mode=='held-unbound':
                    bad=bytearray(payload);struct.pack_into('<I',bad,12+6*4,18)
                    self.atomic(directory/'record-0.held.bin',b'OGNGH001'+struct.pack('<I',1)+bad);break
                held.append(payload)
                self.atomic(directory/('record-%u.held.bin'%record),b'OGNGH001'+struct.pack('<I',1)+payload)
                trace=self.wait(directory/('record-%u.retire'%record),process)
                self.assertEqual(trace[:8],b'OGRTR001')
                if not record:
                    time.sleep(.02)
                    self.assertFalse((directory/'record-1.capture').exists(),'second capture before observed retirement')
                body=payload+struct.pack('<4I',1,1,0,1)
                if not record and mode=='retired-bad':
                    body=bytearray(body);struct.pack_into('<Q',body,60,(1<<32)+128)
                retired.append(bytes(body))
                self.atomic(directory/('record-%u.response.bin'%record),b'OGNGR001'+struct.pack('<I',1)+body)
                if not record and mode=='retired-bad':break
            if len(retired)==2:
                self.assertEqual(hashlib.sha256(memory.read_bytes()).digest(),identity,'union RAM was reloaded/re-exported')
                if mode!='cleanup-denied':
                    self.atomic(directory/'cleanup.authorize.bin',b'OGNGB001'+struct.pack('<I',2))
                    self.assertEqual(self.wait(directory/'cleanup.begin',process),b'OGCLB001'+struct.pack('<I',2))
                    sequence=0;clears=flushes=0
                    while not (directory/'cleanup.done').exists():
                        trace=directory/('cleanup-%u.trace'%sequence)
                        if not trace.is_file():
                            if process.poll() is not None:break
                            time.sleep(.005);continue
                        raw=trace.read_bytes();self.assertEqual(raw[:8],b'OGCLN001')
                        count,=struct.unpack_from('<I',raw,8);self.assertEqual(len(raw),12+16*count)
                        for kind,address,value,mask in struct.iter_unpack('<4I',raw[12:]):
                            if kind==4:self.assertEqual(value,0);self.assertTrue(mask&1);clears+=1
                            elif kind in (1,2,3):
                                self.assertEqual(address&3,0);self.assertLess(address,0x1000)
                                if kind==1:
                                    self.assertEqual(mask,0)
                                    if address==0x154:
                                        self.assertIn(value&7,(1,2))
                                        self.assertEqual(value&~(0xffa if value&2 else 1),0);flushes+=1
                                else:
                                    self.assertNotEqual(mask,0);self.assertEqual(value&~mask,0)
                                    if kind==2:
                                        self.assertEqual(address,0x120)
                                        self.assertEqual(mask,0x2a if value&2 else 0x3f)
                                    else:
                                        self.assertNotEqual(address,0x120);self.assertEqual(mask,0xffffffff)
                            else:self.fail('unexpected CPU cleanup action: '+str((kind,address,value,mask)))
                        self.assertIsNone(process.poll(),'free completed before external cleanup ACK')
                        if mode=='cleanup-missing':break
                        ack=sequence+1 if mode=='cleanup-bad' else sequence
                        self.atomic(directory/('cleanup-%u.observed'%sequence),b'OGCLA001'+struct.pack('<5I',ack,1,1,0,1))
                        sequence+=1
                        if mode=='cleanup-bad':break
                    if mode not in ('cleanup-bad','cleanup-missing'):
                        done=self.wait(directory/'cleanup.done',process)
                        self.assertEqual(done,b'OGCLD001'+struct.pack('<I',sequence));self.assertGreater(clears,0);self.assertGreater(flushes,0)
                        final=b'OGNGF001'+struct.pack('<I',2)+b''.join(retired)
                        if mode=='final-bad':
                            final=bytearray(final);word,=struct.unpack_from('<I',final,12+100+12+2*4)
                            struct.pack_into('<I',final,12+100+12+2*4,word^1)
                        self.atomic(directory/'response.bin',final)
            out,err=process.communicate(timeout=5);self.assertEqual(process.returncode,0,out+err)
            if mode!='ok':self.assertIn('GPU=false',out)
            self.assertEqual(hashlib.sha256(memory.read_bytes()).digest(),identity)
        finally:
            if process.poll() is None:process.kill()
            process.communicate()
    def test_guard_excludes_all_default_cpu_peers_and_declares_live_dependencies(self):
        source=ROOT/'userspace/tests/graphics_sample_kernel.c'
        plain=[*self.compiler,*self.flags,*self.includes,'-E','-P',str(source)]
        default=subprocess.check_output(plain,text=True)
        live=subprocess.check_output(plain[:-1]+['-DOPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=1',str(source)],text=True)
        for symbol in ('sample_cpu_held','sample_cpu_cleanup','graphics_sample_cpu_capture'):
            self.assertIn(symbol,default);self.assertNotIn(symbol,live)
        self.assertIn('graphics_sample_graphics_host_execute',live)
        dependencies=(self.out/'graphics_sample_live_test.d').read_text()
        self.assertIn('graphics_sample_live.inc',dependencies);self.assertIn('graphics_sample_owner_ipc.h',dependencies)
        bad=subprocess.run(plain[:-1]+['-DOPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=1','-DOPENGPU_NATIVE_MMIO_CLEANUP_TEST_PEER=forbidden',str(source)],capture_output=True,text=True)
        self.assertNotEqual(bad.returncode,0);self.assertIn('cannot compile a cleanup test peer',bad.stderr)
    def test_pre_ready_bad_profile_private_rx_original_reference_and_source_inputs(self):
        for mode in ('bad-profile','bad-rx','unretained','bad-word-file','missing-input'):self.case(mode)
    def test_actual_irq_drain_rejects_foreign_unbound_or_changed_retirement(self):
        for mode in ('held-bad','held-unbound','retired-bad'):self.case(mode)
    def test_both_owners_survive_missing_authorization_and_bad_or_missing_cleanup_ack(self):
        for mode in ('cleanup-denied','cleanup-bad','cleanup-missing'):self.case(mode)
    def test_all_external_ack_phases_then_exact_final_observation_matching(self):
        for mode in ('ok','closed-inputs','final-bad'):self.case(mode)


if __name__=='__main__':unittest.main()
