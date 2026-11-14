#!/usr/bin/env python3
"""Private112 direct source-encoder CPU checks; no Mesa build, model or GPU."""
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tempfile
import unittest
from owner_resource_contract import SAMPLE_WORDS

ROOT = Path(__file__).resolve().parents[1]


class PrivateGraphicsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix='sample-graphics-',dir=ROOT/'.artifacts')
        cls.out = Path(cls.temp.name)
        aux = [(0,0x47550002),(0x60,sum(1<<b for b in (6,22,23,25,29))|128<<8),
               (0x68,0x160),(0x320,1048576),(0x158,0x02020810)]
        values = [(w['address'],w['value']) for w in SAMPLE_WORDS]+aux
        (cls.out/'sample_profile.inc').write_text(
            'static const struct opengpu_native_sample_word profile[117]={\n'+
            ''.join('{%#xu,%#xu},\n'%p for p in values)+'};\n')
        flags = ['-std=c11','-O1','-Wall','-Wextra','-Werror']
        if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined','-fno-omit-frame-pointer']
        includes = [cls.out,ROOT/'userspace',ROOT/'driver',ROOT/'driver/tests/compute_submit_stubs']
        compiler = shlex.split(os.environ.get('CC','cc'))
        cls.env = dict(os.environ,TMPDIR=str(cls.out))
        cls.binary = cls.out/'private112'
        subprocess.run([*compiler,*flags,*['-I'+str(p) for p in includes],
            str(ROOT/'userspace/tests/native_sample_graphics_test.c'),
            str(ROOT/'userspace/opengpu_native_sample_graphics.c'),
            str(ROOT/'driver/opengpu_native_frame.c'),'-o',str(cls.binary)],check=True,env=cls.env)
        cls.compiler,cls.flags,cls.includes=compiler,flags,includes

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_discovery_read_failure_capacity_job_and_original_gur(self):
        subprocess.run([str(self.binary)],check=True,timeout=30,env=self.env)

    def test_full_gnf3_words_reserved_bits_and_logical_views(self):
        data=subprocess.check_output([str(self.binary),'--dump'],timeout=30,env=self.env)
        self.assertEqual(len(data),3*2048)
        definitions=(ROOT/'driver/opengpu_native_layered_abi.h').read_text()
        words={name:int(word) for name,word in re.findall(
            r'#define OPENGPU_NATIVE_LAYERED_FRAME_(\w+)_WORD (\d+)u',definitions)}
        for index,samples in enumerate((1,2,4)):
            expected=[0]*512
            expected[:8]=[0x474e4603,2048,3,16,16,16,4,4]
            def put(name,value):
                expected[words[name]]=value
            edge=16 if 256*samples*68<=32768 else 8
            texels=edge*edge*samples
            fields=dict(FRAME_VERSION=3,FRAME_BINS_PRODUCER_VERSION=1,
                FRAME_BINS_PRODUCER_ARENA_VERSION=1,FRAME_BINS_PRODUCER_SCREENWIDTH=4,
                FRAME_BINS_PRODUCER_SCREENHEIGHT=4,FRAME_BINS_PRODUCER_TILELOG2=4 if edge==16 else 3,
                FRAME_BINS_BINS_VERSION=3,FRAME_BINS_BINS_TILECOUNT=1,
                FRAME_BINS_BINS_POOL_VERSION=1,FRAME_BINS_BINS_POOL_PAGESIZELOG2=12,
                FRAME_STATES_VERSION=1,FRAME_SCRATCHSTRIDE=49152,FRAME_IMAGE_VERSION=1,
                FRAME_IMAGE_PIXELS_VERSION=1,FRAME_IMAGE_PIXELS_COLORS_VERSION=1,
                FRAME_IMAGE_PIXELS_COLORS_TILELOG2=4 if edge==16 else 3,
                FRAME_IMAGE_PIXELS_COLORS_TILEWIDTH=edge,FRAME_IMAGE_PIXELS_COLORS_TILEHEIGHT=edge,
                FRAME_IMAGE_PIXELS_COLORS_SAMPLEMODE=index,
                FRAME_IMAGE_PIXELS_COLORS_RESERVEDBYTES=4*texels,
                FRAME_IMAGE_PIXELS_DEPTH_FORMAT=3,FRAME_IMAGE_PIXELS_DEPTH_LOADOP=1,
                FRAME_IMAGE_PIXELS_DEPTH_CLEARDEPTH=0x3f800000,LAYERED_VERSION=1,
                LAYERED_LAYERS=1,LAYERED_SOURCEPITCH0=4096)
            for prefix in ('FRAME_BINS_PRODUCER_ARENA_IDENTITY_','FRAME_BINS_BINS_POOL_IDENTITY_',
                           'FRAME_STATES_IDENTITY_','FRAME_IMAGE_PIXELS_COLORS_IDENTITY_'):
                fields.update({prefix+'CONTEXT':7,prefix+'PASS':11,prefix+'GENERATION':13})
            for color in range(4):
                prefix='FRAME_IMAGE_PIXELS_COLORS_COLORS%d_'%color
                fields.update({prefix+'ENABLED':1,prefix+'FORMAT':35,prefix+'LOADOP':1,
                    prefix+'CHANNELMASK':15,prefix+'OFFSET':(4+16*color)*texels,
                    **{prefix+'CLEAR%d'%c:0x3e800000 for c in range(4)}})
            for image in range(5):
                prefix='FRAME_IMAGE_VIEWS%d_'%image
                fields.update({prefix+'IMAGEWIDTH':4,prefix+'IMAGEHEIGHT':4,
                    prefix+'ROWPITCH':4*samples*(16 if image<4 else 4),prefix+'WRITABLE':1,prefix+'STORE':1})
            for name,value in fields.items():put(name,value)
            actual=struct.unpack_from('<512I',data,index*2048)
            self.assertEqual(list(actual),expected,'complete GNF3 descriptor at %dx'%samples)

    def test_frozen_gnc2_gnc3_planner_regression(self):
        binary=self.out/'old-frame'
        subprocess.run([*self.compiler,*self.flags,*['-I'+str(p) for p in self.includes],
            str(ROOT/'driver/tests/opengpu_native_frame_test.c'),
            str(ROOT/'driver/opengpu_native_frame.c'),'-o',str(binary)],check=True,env=self.env)
        subprocess.run([str(binary)],check=True,timeout=30,env=self.env)


if __name__ == '__main__':
    unittest.main()
