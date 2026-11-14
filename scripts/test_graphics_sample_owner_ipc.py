#!/usr/bin/env python3
"""Independent synthetic ordinary112 receipt wires; no Mesa, owner or GPU."""
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest
from owner_resource_contract import SAMPLE_WORDS, V1_WORDS, FULL_WORDS

ROOT=Path(__file__).resolve().parents[1]


def build_codec(directory):
    """Build the external-file C test reader; callers own this private output."""
    directory=Path(directory)
    flags=['-std=c11','-O1','-Wall','-Wextra','-Werror','-D_POSIX_C_SOURCE=200809L']
    if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
    sources=[ROOT/'userspace/tests'/name for name in ('graphics_sample_owner_ipc.c','graphics_sample_owner_ipc_test.c')]
    sources += [ROOT/'userspace/opengpu_native_sample_graphics.c',ROOT/'driver/opengpu_native_frame.c']
    binary=directory/'graphics-sample-owner-codec'
    subprocess.run([*shlex.split(os.environ.get('CC','cc')),*flags,'-I'+str(ROOT/'userspace'),
        '-I'+str(ROOT/'driver/tests/compute_submit_stubs'),*map(str,sources),'-o',str(binary)],check=True)
    return binary


def payload(record,retired=False,command=None,append=None,status=0x23):
    command=12+record if command is None else command
    append=(1<<32)+record*64 if append is None else append
    gnx=[0x474e5801,0,0x89abcdef,0x12345678,0,0,17,11,13,0,0,0]
    result=struct.pack('<15IQ4I',record,command,0x8700|command,*gnx,append,0,status,3,1)
    return result+(struct.pack('<4I',1,1,0,1) if retired else b'')


def wire(kind,record=0):
    if kind=='cleanup':return b'OGNGB001'+struct.pack('<I',2)
    if kind=='final':return b'OGNGF001'+struct.pack('<I',2)+payload(0,True)+payload(1,True)
    return (b'OGNGH001' if kind=='held' else b'OGNGR001')+struct.pack('<I',1)+payload(record,kind=='retired')


def profile(words=SAMPLE_WORDS,magic=b'OGNP0002'):
    auxiliary=[(0,0x47550002),(0x60,sum(1<<b for b in (6,22,23,25,29))|128<<8),
               (0x68,0x160),(0x320,1048576),(0x158,0x02020810)]
    return magic+struct.pack('<I',len(words))+b''.join(struct.pack('<II',w['address'],w['value']) for w in words)+\
        struct.pack('<I',5)+b''.join(struct.pack('<II',*p) for p in auxiliary)


class Ordinary112CodecTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.temp=tempfile.TemporaryDirectory(prefix='ordinary112-ipc-',dir=ROOT/'.artifacts')
        cls.out=Path(cls.temp.name);cls.binary=build_codec(cls.out)
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def decode(self,kind,data,record=0,valid=True):
        names=dict(held='record-%u.held.bin'%record,retired='record-%u.response.bin'%record,
                   final='response.bin',cleanup='cleanup.authorize.bin',profile='profile.bin')
        (self.out/names[kind]).write_bytes(data)
        result=subprocess.run([self.binary,kind,self.out,str(record),'ok' if valid else 'bad'],
            check=True,capture_output=True,text=True,timeout=10)
        return [json.loads(line) for line in result.stdout.splitlines()]
    def test_valid_little_endian_raw_counters_boundaries_and_two_records(self):
        for record in (0,1):
            for command,append,status in ((1,0,2),(255,1<<33,0x22),(12+record,(1<<32)+64,3)):
                raw=b'OGNGH001'+struct.pack('<I',1)+payload(record,command=command,append=append,status=status)
                value=self.decode('held',raw,record)[0]
                self.assertEqual((value['record'],value['commandId'],value['completion']),(record,command,0x8700|command))
                self.assertEqual(value['appendReadBytes'],append)
                self.assertEqual(value['gnx'][2:4],[0x89abcdef,0x12345678])
                self.assertEqual((value['heldStatus'],value['retiredStatus'],value['externalDrained']),(status,0,0))
            self.assertEqual(self.decode('retired',wire('retired',record),record)[0]['retiredIrq'],1)
        self.assertEqual([v['record'] for v in self.decode('final',wire('final'))],[0,1])
        self.decode('cleanup',wire('cleanup'))
    def test_held_each_malformed_field_boolean_count_old_wire_and_eof(self):
        good=wire('held')
        wrong={0:[1,2,0xffffffff],4:[0,256,0xffffffff],8:[0,0x8700,0x870d],12:[0,0x474e5802],
               68:[1,0xffffffff],72:[0,1,4,6,0xffffffff],76:[0,1,2,4],80:[0,2,0xffffffff]}
        wrong.update({12+4*i:[1,0xffffffff] for i in (1,4,5,9,10,11)})
        for offset,values in wrong.items():
            for value in values:
                bad=bytearray(good);struct.pack_into('<I',bad,12+offset,value)
                self.decode('held',bad,valid=False)
        for append in (1,63,(1<<33)+64,1<<63):
            bad=bytearray(good);struct.pack_into('<Q',bad,12+60,append);self.decode('held',bad,valid=False)
        for status in range(256):
            bad=bytearray(good);struct.pack_into('<I',bad,12+72,status)
            self.decode('held',bad,valid=bool(status&2 and not status&~0x23))
        for n in range(len(good)):self.decode('held',good[:n],valid=False)
        for bad in (good+b'x',good[:8]+struct.pack('<I',0)+good[12:],good[:8]+struct.pack('<I',2)+good[12:],
                    b'OGHLD001'+good[8:],b'OGNSH001'+good[8:],b'OGNSH001'+good[12:]):
            self.decode('held',bad,valid=False)
    def test_retired_final_cleanup_phase_order_and_atomic_error_outputs(self):
        good=wire('retired')
        for offset,values in {84:[0,3],88:[0,3],92:[1,2],96:[0,2]}.items():
            for value in values:
                bad=bytearray(good);struct.pack_into('<I',bad,12+offset,value);self.decode('retired',bad,valid=False)
        for kind in ('retired','final','cleanup'):
            raw=wire(kind)
            for n in range(len(raw)):self.decode(kind,raw[:n],valid=False)
            for bad in (raw+b'x',raw[:8]+struct.pack('<I',3)+raw[12:],b'OGRSP001'+raw[8:],
                        b'OGNSR001'+raw[8:],b'OGNSBA01'+raw[8:],b'OGNSOK01'+raw[8:]):
                self.decode(kind,bad,valid=False)
        final=wire('final')
        self.decode('final',final[:12]+final[112:]+final[12:112],valid=False)
        bad=bytearray(final);struct.pack_into('<I',bad,12+100+96,0);self.decode('final',bad,valid=False)
        for kind in ('held','retired'):
            for other in ('held','retired','final','cleanup'):
                if other!=kind:self.decode(kind,wire(other),valid=False)
        for count in (0,1,3,0xffffffff):self.decode('cleanup',b'OGNGB001'+struct.pack('<I',count),valid=False)
    def test_strict_112_profile_isolated_from_old_96_104_and_bad_auxiliary(self):
        good=profile();self.assertEqual(self.decode('profile',good),[dict(profileWords=112,registers=5)])
        for words in (V1_WORDS,FULL_WORDS):self.decode('profile',profile(words),valid=False)
        for i in range(112):
            bad=bytearray(good);bad[12+i*8+4]^=1;self.decode('profile',bad,valid=False)
        for index in range(5):
            bad=bytearray(good);struct.pack_into('<I',bad,12+112*8+4+index*8+4,0);self.decode('profile',bad,valid=False)
        for bad in (good[:-1],good+b'x',profile(magic=b'OGPF0001'),good[:8]+struct.pack('<I',104)+good[12:]):
            self.decode('profile',bad,valid=False)
        bad=bytearray(good);bad[12:20],bad[20:28]=good[20:28],good[12:20];self.decode('profile',bad,valid=False)
        bad=bytearray(good);offset=12+112*8+4;bad[offset:offset+8],bad[offset+8:offset+16]=good[offset+8:offset+16],good[offset:offset+8]
        self.decode('profile',bad,valid=False)
    def test_argument_error_cancellation_and_bounded_missing_file(self):
        subprocess.run([self.binary,'arguments',self.out,'0','ok'],check=True,timeout=5)
        empty=self.out/'empty';empty.mkdir()
        subprocess.run([self.binary,'held',empty,'0','bad'],check=True,timeout=3)
        (empty/'error').write_text('explicit CPU cancellation')
        for kind in ('held','retired','final','cleanup'):
            subprocess.run([self.binary,kind,empty,'0','bad'],check=True,timeout=2)


if __name__=='__main__':unittest.main()
