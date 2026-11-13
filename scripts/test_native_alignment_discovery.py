#!/usr/bin/env python3
"""CPU-only binary/MMIO profile checks; no GPU model is elaborated or run."""
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def discovery():
    blocks = [
        (0x360,[0x47435201,16,0x7fff,2,16,8,128,128,0xffffffff,16384,16,8,0x10000000,32,131072,4]),
        (0x160,[0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813d902]),
        (0x340,[0x47434901,8,0x7f,14,12,4,0xffffffff,128]),
        (0x240,[0x47515601,8,0x0b0a0908,0x47514f01,64,8,0x01001001,31]),
        (0x260,[0x47464301,8,0x1003010c,0x47544601,512,0x04400408,69,255]),
        (0x280,[0x47515001,8,0x0010040d,0x47504f01,64,0x00204008,0x8c88,63]),
        (0x3a0,[0x47554301,16,1,0x7f,3,12,24,16384]),
        (0x3c0,[1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31])]
    return [(base+4*n,value) for base,words in blocks for n,value in enumerate(words)]


def fixture(words, product=True, samples=1, identifier=1):
    def commands(values):
        return struct.pack('<I',len(values))+b''.join(struct.pack('<II',*r) for r in values)
    return (b'OGAPI001'+struct.pack('<IIIIQ',640 if product else 32,480 if product else 32,
        samples,identifier,1000000000 if product else 10000000)+commands(words)+
        commands([(0,0)]*8)+struct.pack('<I',13)+bytes(13*12)+commands([(0,0)]*2))


class AlignmentDiscoveryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='opengpu-alignment-discovery-')
        cls.root=Path(cls.temp.name)
        source=cls.root/'parser.cpp'
        source.write_text('''#include "native_binned_host_profile.hpp"
int main(int argc,char **argv) {
  try {
    const std::string mode(argv[1]);
    if(mode=="topology" || mode=="legacy-topology") {
      const auto f=native_frame::topology_fixture_contract(argv[2],mode=="legacy-topology",mode=="topology");
      std::map<uint32_t,uint32_t> words;
      for(const auto &r:f.resources)
        native_frame::require(words.emplace(r.address,r.value).second,"duplicate address");
      native_frame::check_topology_discovery(words,mode=="legacy-topology",mode=="topology");
    } else native_frame::ApiFixture::read(argv[2],mode!="smoke",mode!="legacy");
    return 0;
  } catch(const std::exception&) { return 1; }
}
''')
        cls.binary=cls.root/'parser'
        subprocess.run(shlex.split(os.environ.get('CXX','c++'))+
            ['-std=c++17','-Wall','-Wextra','-Werror','-Wno-unused-parameter',
             '-I',str(ROOT/'scripts'),str(source),'-o',str(cls.binary)],check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def accepted(self, words, mode, product=True, samples=1, identifier=1):
        path=self.root/'native-api-extent-1x.commands'
        path.write_bytes(fixture(words,product,samples,identifier))
        argument=self.root if mode.endswith('topology') else path
        return subprocess.run([str(self.binary),mode,str(argument)],capture_output=True).returncode==0

    def test_both_sample_counts_and_explicit_smoke_have_exact_v2_profile(self):
        words=discovery()
        self.assertEqual(len(words),72)
        for product,mode in [(True,'api'),(False,'smoke')]:
            for samples,identifier in [(1,1),(4,2)]:
                self.assertTrue(self.accepted(words,mode,product,samples,identifier))
        self.assertFalse(self.accepted(words,'legacy'))
        self.assertFalse(self.accepted(words,'api',False))
        self.assertTrue(self.accepted(words,'topology'))
        self.assertFalse(self.accepted(words,'topology',False))

    def test_every_address_and_word_missing_duplicate_and_old_compute_flags_are_rejected(self):
        words=discovery()
        for index,(address,value) in enumerate(words):
            for mutation in [(address^4,value),(address,value^1)]:
                changed=words.copy(); changed[index]=mutation
                for mode in ('api','topology'):
                    with self.subTest(index=index,mutation=mutation,mode=mode):
                        self.assertFalse(self.accepted(changed,mode))
        old=words.copy(); old[2]=(0x368,15)
        for changed in [words[:-1],words+[words[-1]],words[:24],old]:
            for mode in ('api','topology'):
                with self.subTest(length=len(changed),mode=mode):
                    self.assertFalse(self.accepted(changed,mode))

    def test_v1_topology_selector_cannot_silently_accept_v2_fixture(self):
        words=discovery()
        path=self.root/'desktop-composition.commands'
        path.write_bytes(b'OGNAT001'+fixture(words)[8:])
        self.assertFalse(self.accepted(words,'legacy-topology'))


if __name__=='__main__':
    unittest.main()
