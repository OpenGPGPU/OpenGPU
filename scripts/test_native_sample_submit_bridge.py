#!/usr/bin/env python3
"""Private raw nativeFS -> protected Linux ioctl/RX CPU bridge; no GPU peer."""
import argparse
import json
import time
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest

from owner_resource_contract import FULL_WORDS, SAMPLE_WORDS, V1_WORDS
from test_native_submit import write_bodies

ROOT = Path(__file__).resolve().parents[1]


def build_bridge(out: Path, sanitize: bool = False, live: bool = False) -> Path:
    if live:
        from test_native_mmio_capture import write_bodies as write_mmio_bodies
        write_mmio_bodies(out)
    else:
        write_bodies(out)
    # One source-fixed registry supplies every table word. This compilation
    # input is private qualification scaffolding, never observed DUT discovery.
    auxiliary = [(0, 0x47550002), (0x60, 'GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS|(128u<<GPU_CAP_FRAGMENT_BATCH_SHIFT)'),
                 (0x68, 0x160), (0x320, 1048576), (0x158, 0x02020810)]
    tables = []
    for name, source in (('sample', SAMPLE_WORDS), ('layered', FULL_WORDS), ('legacy', V1_WORDS)):
        words = [(w['address'], w['value']) for w in source] + auxiliary
        body = ',\n'.join('{%#xu,%s}' % (a, v if isinstance(v, str) else '%#xu' % v) for a, v in words)
        tables.append('static const struct native_kernel_profile_word %s_expected_profile[%d]={\n%s\n};\n'
                      % (name, len(words), body))
    (out / 'native_sample_expected_profile.inc').write_text(''.join(tables))
    flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
    if sanitize:
        flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
    includes = [out, ROOT/'driver', ROOT/'driver/tests', ROOT/'driver/tests/private_snapshot_stubs',
                ROOT/'driver/tests/mmu_stubs', ROOT/'driver/tests/compute_submit_stubs']
    binary = out / ('native-sample-live-producer' if live else 'native-sample-submit-producer')
    cc = shlex.split(os.environ.get('CC', 'cc'))
    objects = []
    for source in (ROOT/'scripts'/('native_sample_live_producer.c' if live else 'native_sample_submit_producer.c'),
                   ROOT/'driver/opengpu_native_frame.c'):
        obj = out/(source.stem+'.o')
        subprocess.run([*cc, *flags, *['-I'+str(p) for p in includes],
                        '-MMD', '-MF', str(obj.with_suffix('.d')), '-c', str(source), '-o', str(obj)], check=True)
        objects.append(str(obj))
    subprocess.run([*cc, *flags, *objects, '-o', str(binary)], check=True)
    return binary


def run_bridge(binary: Path, program: Path, samples=1, profile=112):
    return subprocess.run([str(binary), str(program), str(samples), str(profile)],
                          text=True, capture_output=True)


class SampleSubmitBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.tmp = tempfile.TemporaryDirectory(prefix='sample-submit-', dir=ROOT/'.artifacts')
        cls.out = Path(cls.tmp.name)
        cls.binary = build_bridge(cls.out, os.environ.get('OPENGPU_TEST_SANITIZERS') == '1')

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def fixture(self, **changes):
        # Parser/ownership unit input ONLY; CEASE is not an ESSL compilation or
        # rendered result. The separate --compiled-directory path consumes the
        # actual frontend artifacts without substituting this synthetic code.
        meta = [1, 6, 75, 512, 93*512, 0, 15, 0, 0, 1, 0, 128, 16, 4, 1]
        for key, value in changes.items():
            meta[int(key)] = value
        path = self.out / 'unit.ognsf'
        path.write_bytes(b'OGNSF001' + struct.pack('<15I', *meta) + struct.pack('<I', 0x30500073))
        return path

    def test_ordinary_full_capacity_ownership(self):
        for samples in (1, 2, 4):
            result = run_bridge(self.binary, self.fixture(), samples)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('CPU modeled final kick', result.stdout)
            self.assertIn('no GPU execution', result.stdout)

    def test_profile_and_layout_gates(self):
        for profile in (96, 104):
            result = run_bridge(self.binary, self.fixture(), profile=profile)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('profile gate rejected', result.stderr)
        for index, value in ((0, 2), (1, 1), (1, 7), (2, 77), (3, 256), (4, 95*512),
                             (6, 16), (7, 2), (8, 2), (9, 0), (10, 1), (11, 64),
                             (12, 15), (13, 3)):
            result = run_bridge(self.binary, self.fixture(**{str(index): value}))
            self.assertNotEqual(result.returncode, 0, (index, value))
            self.assertIn('invalid ordinary ABI6', result.stderr)

    def test_strict_container_and_actual_validator(self):
        source = self.fixture().read_bytes()
        for data in (source[:-1], source+b'\0', b'X'+source[1:], source[:64]+struct.pack('<I', 513)+source[68:]):
            path = self.out/'bad.ognsf';path.write_bytes(data)
            self.assertNotEqual(run_bridge(self.binary, path).returncode, 0)
        path = self.fixture()
        data = bytearray(path.read_bytes());struct.pack_into('<I', data, 68, 0xffffffff);path.write_bytes(data)
        result = run_bridge(self.binary, path)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('protected ioctl program rejected', result.stderr)


class SampleLiveProducerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        cls.tmp = tempfile.TemporaryDirectory(prefix='sample-live-', dir=ROOT/'.artifacts')
        cls.out = Path(cls.tmp.name)
        cls.binary = build_bridge(cls.out, os.environ.get('OPENGPU_TEST_SANITIZERS') == '1', live=True)
        source = cls.out/'actual-reader.cpp'
        source.write_text(r'''#include "native_sample_owner_capture.hpp"
#include <iostream>
int main(int argc,char **argv) {
  try {native_owner::require(argc==2,"missing live capture");
    auto capture=native_owner::SampleCapture::read(std::string(argv[1]),true);
    native_owner::require(capture.discovery.size()==117 && capture.checks.size()==13,"incomplete live capture");
    std::cout<<"actual Linux OGCAP002 -> fixed SampleCapture reader PASS (CPU only)\n";return 0;
  } catch(const std::exception &e){std::cerr<<e.what()<<"\n";return 2;}
}
''')
        cls.reader = cls.out/'actual-reader'
        flags = ['-std=c++17','-O1','-Wall','-Wextra','-Werror','-pedantic']
        if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined','-fno-omit-frame-pointer']
        subprocess.run([*shlex.split(os.environ.get('CXX','c++')),*flags,
                        '-I'+str(ROOT/'scripts'),'-I'+str(ROOT/'scripts/test_stubs/native_host'),
                        str(source),'-o',str(cls.reader)],check=True)
        cls.program = cls.out/'unit.ognsf'
        # CPU capture/IPC unit only. Never represents an actual ESSL compile.
        cls.program.write_bytes(b'OGNSF001'+struct.pack('<16I', 1,6,75,512,93*512,7,1,0,0,1,0,128,16,4,1,0x30500073))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def profile(self, name, words=SAMPLE_WORDS, magic=b'OGNP0002', batch=128):
        auxiliary = [(0,0x47550002),(0x60,(1<<6)|(1<<22)|(1<<23)|(1<<25)|(1<<29)|(batch<<8)),
                     (0x68,0x160),(0x320,1048576),(0x158,0x02020810)]
        data = magic+struct.pack('<I',len(words))
        data += b''.join(struct.pack('<II', w['address'],w['value']) for w in words)
        data += struct.pack('<I',5)+b''.join(struct.pack('<II',*w) for w in auxiliary)
        path = self.out/name;path.write_bytes(data);return path

    def test_capture_preserves_live_owner_and_rejects_compute_receipt(self):
        for samples in (1,2,4):
            with self.subTest(samples=samples):
                self.capture_preserves_live_owner(samples)

    def capture_preserves_live_owner(self,samples):
        profile = self.profile('sample.ognp')
        session = self.out/('capture-'+str(samples));session.mkdir()
        process = subprocess.Popen([str(self.binary),str(self.program),str(profile),str(session),str(samples),'sample'],
                                   stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,
                                   env=dict(os.environ,OPENGPU_NATIVE_HOST_TIMEOUT='15'))
        try:
            deadline = time.monotonic()+15
            while not (session/'READY').exists() and process.poll() is None and time.monotonic()<deadline:
                time.sleep(.01)
            self.assertIsNone(process.poll(), process.communicate() if process.poll() is not None else '')
            self.assertTrue((session/'READY').exists())
            inputs = json.loads((session/'sample-inputs.json').read_text())
            self.assertFalse(inputs['gpuExecuted'])
            self.assertEqual(inputs['fragmentWords'],[0x30500073])
            self.assertEqual(inputs['vertexWords'],420)
            self.assertEqual(len(inputs['images']),5)
            self.assertEqual((inputs['context'],inputs['pass'],inputs['generation']),(7,8,9))
            self.assertEqual(inputs['completionBytes'],16384)
            capture = (session/'record-0.capture').read_bytes()
            self.assertEqual(capture[:8],b'OGCAP002')
            self.assertEqual(struct.unpack_from('<I',capture,24)[0],117)
            self.assertEqual((session/'union.memory').read_bytes()[:8],b'OGMEM001')
            subprocess.run([str(self.reader),str(session/'record-0.capture')],check=True)
            from compute_owner_oracle import SparseMemory
            from native_sample_owner_oracle import verify
            from test_native_sample_owner import profile as observed
            actual_profile = observed();actual_profile['registers'][-1]['value']=0x02020810
            with SparseMemory(session/'union.memory') as memory:
                self.assertTrue(verify(memory,None,inputs,self.program.read_bytes(),capture,actual_profile))
            for name, offset, value in [('old-magic',0,None),('missing-batch',28+113*8+4,0)]:
                invalid = bytearray(capture)
                if value is None:
                    invalid[:8]=b'OGCAP001'
                else:
                    flags,=struct.unpack_from('<I',invalid,offset);struct.pack_into('<I',invalid,offset,flags&~(255<<8))
                path = session/(name+'.capture');path.write_bytes(invalid)
                self.assertNotEqual(subprocess.run([str(self.reader),str(path)],capture_output=True).returncode,0)
            self.assertFalse((session/'record-0.retire').exists())
            self.assertFalse((session/'cleanup.begin').exists())
            # Wrong private receipt fails closed; no synthesized completion,
            # drain, cleanup or owner-success publication is performed.
            (session/'record-0.held.response').write_bytes(b'OGCMH001'+bytes(100))
            stdout,stderr = process.communicate(timeout=5)
            self.assertNotEqual(process.returncode,0)
            self.assertIn('wrong private wire magic',stderr)
            self.assertFalse((session/'record-0.retire').exists())
            self.assertFalse((session/'cleanup.done').exists())
            self.assertNotIn('completed',stdout)
        finally:
            if process.poll() is None:
                process.kill();process.communicate()

    def test_missing_batch_rejected_before_ready(self):
        session = self.out/'missing-batch';session.mkdir()
        result = subprocess.run([str(self.binary),str(self.program),str(self.profile('missing-batch.ognp',batch=0)),
                                 str(session),'1','sample'],text=True,capture_output=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('actual profile cache validation',result.stderr)
        self.assertFalse((session/'READY').exists())
        self.assertFalse((session/'union.memory').exists())

    def test_rewritten_depth_policy_preflight(self):
        # Synthetic CEASE instruction tests transport/layout only; it is not
        # an ESSL compile or a claim that a depth shader ran on the DUT.
        program = self.out/'depth-unit.ognsf'
        program.write_bytes(b'OGNSF001'+struct.pack('<16I',1,6,75,512,93*512,7,1,1,1,1,0,128,16,4,1,0x30500073))
        session = self.out/'depth-capture';session.mkdir()
        process = subprocess.Popen([str(self.binary),str(program),str(self.profile('depth.ognp')),str(session),'4','mask-depth-keep'],
                                   stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,
                                   env=dict(os.environ,OPENGPU_NATIVE_HOST_TIMEOUT='15'))
        try:
            deadline=time.monotonic()+15
            while not (session/'READY').exists() and process.poll() is None and time.monotonic()<deadline:
                time.sleep(.01)
            self.assertIsNone(process.poll(),process.communicate() if process.poll() is not None else '')
            self.assertTrue((session/'READY').exists())
            subprocess.run([str(self.reader),str(session/'record-0.capture')],check=True)
            inputs=json.loads((session/'sample-inputs.json').read_text())
            capture=(session/'record-0.capture').read_bytes()
            from compute_owner_oracle import SparseMemory
            from native_sample_owner_oracle import AddressSpace, capture as read_capture, verify
            from test_native_sample_owner import profile as observed
            actual_profile=observed();actual_profile['registers'][-1]['value']=0x02020810
            with SparseMemory(session/'union.memory') as memory:
                self.assertTrue(verify(memory,None,inputs,program.read_bytes(),capture,actual_profile))
                _,registers,_=read_capture(capture,actual_profile)
                address=AddressSpace(memory,inputs,registers[0x14c]).physical(inputs['stateVA'],512)
                self.assertEqual(struct.unpack('<I',memory.read(address+29*4,4))[0],1)
                offset=memory.pages[(address+29*4)&~4095]+((address+29*4)&4095)
            broken=session/'depth-override-disabled.memory'
            data=bytearray((session/'union.memory').read_bytes());struct.pack_into('<I',data,offset,0);broken.write_bytes(data)
            with SparseMemory(broken) as memory:
                with self.assertRaisesRegex(ValueError,'sample recipe: GFS1 word 29 differs'):
                    verify(memory,None,inputs,program.read_bytes(),capture,actual_profile)
            (session/'record-0.held.response').write_bytes(b'OGCMH001')
            process.communicate(timeout=5)
            self.assertNotEqual(process.returncode,0)
            self.assertFalse((session/'record-0.retire').exists())
        finally:
            if process.poll() is None:
                process.kill();process.communicate()

    def test_fixed_profile_cross_entry_rejection(self):
        variants = [('v1',V1_WORDS,b'OGNP0002'),('full',FULL_WORDS,b'OGNP0002'),
                    ('oldmagic',SAMPLE_WORDS,b'OGNP0001')]
        corrupt = [dict(w) for w in SAMPLE_WORDS];corrupt[-1]['value']^=1
        variants.append(('bad-gss',corrupt,b'OGNP0002'))
        for name,words,magic in variants:
            session = self.out/name;session.mkdir()
            result = subprocess.run([str(self.binary),str(self.program),str(self.profile(name+'.ognp',words,magic)),
                                     str(session),'1','sample'],text=True,capture_output=True)
            self.assertNotEqual(result.returncode,0)
            self.assertFalse((session/'READY').exists())
            self.assertFalse((session/'union.memory').exists())


def compiled_directory(directory: Path):
    files = sorted(directory.glob('*.ognsf'))
    if not files:
        raise SystemExit('no actual compiled .ognsf files')
    (ROOT/'.artifacts').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='sample-submit-compiled-', dir=ROOT/'.artifacts') as temp:
        binary = build_bridge(Path(temp), os.environ.get('OPENGPU_TEST_SANITIZERS') == '1')
        live_binary = build_bridge(Path(temp), os.environ.get('OPENGPU_TEST_SANITIZERS') == '1', live=True)
        auxiliary = [(0,0x47550002),(0x60,(1<<6)|(1<<22)|(1<<23)|(1<<25)|(1<<29)|(128<<8)),
                     (0x68,0x160),(0x320,1048576),(0x158,0x02020810)]
        profile = Path(temp)/'sample.ognp'
        profile.write_bytes(b'OGNP0002'+struct.pack('<I',112)+b''.join(
            struct.pack('<II',w['address'],w['value']) for w in SAMPLE_WORDS)+struct.pack('<I',5)+
            b''.join(struct.pack('<II',*w) for w in auxiliary))
        ordinary = 0
        for program in files:
            data = program.read_bytes()
            abi = struct.unpack_from('<I', data, 12)[0]
            for samples in (1, 2, 4):
                result = run_bridge(binary, program, samples)
                if abi == 7:
                    if result.returncode == 0:
                        raise SystemExit('ABI7 accepted without real GS owner')
                elif result.returncode:
                    raise SystemExit(result.stderr)
                else:
                    ordinary += 1
                    print(program.name, result.stdout.strip())
                    session = Path(temp)/(program.stem+'-'+str(samples));session.mkdir()
                    process = subprocess.Popen([str(live_binary),str(program),str(profile),str(session),str(samples),program.stem.removesuffix('-abi6')],
                        stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=dict(os.environ,OPENGPU_NATIVE_HOST_TIMEOUT='15'))
                    try:
                        deadline = time.monotonic()+15
                        while not (session/'READY').exists() and process.poll() is None and time.monotonic()<deadline:
                            time.sleep(.01)
                        if process.poll() is not None or not (session/'READY').exists():
                            raise RuntimeError('compiled live capture failed: '+str(process.communicate(timeout=1)))
                        inputs = json.loads((session/'sample-inputs.json').read_text())
                        if inputs['fragmentWords'] != list(struct.unpack_from('<%dI' % ((len(data)-68)//4),data,68)):
                            raise RuntimeError('compiled original bytes differ in live owner')
                        if (session/'record-0.capture').read_bytes()[:8] != b'OGCAP002':
                            raise RuntimeError('wrong live capture schema')
                        from compute_owner_oracle import SparseMemory
                        from native_sample_owner_oracle import verify
                        from test_native_sample_owner import profile as observed
                        actual_profile=observed()
                        actual_profile['registers'][-1]['value']=0x02020810  # Match this explicit CPU wire fixture.
                        with SparseMemory(session/'union.memory') as memory:
                            verify(memory,None,inputs,program.read_bytes(),
                                   (session/'record-0.capture').read_bytes(),actual_profile)
                        # A negative control receipt ends this CPU-only test.
                        # No actual-DUT response, native retirement or cleanup
                        # success is synthesized to complete qualification.
                        (session/'record-0.held.response').write_bytes(b'OGCMH001')
                        stdout,stderr = process.communicate(timeout=5)
                        if process.returncode == 0 or 'wrong private wire magic' not in stderr or (session/'cleanup.done').exists():
                            raise RuntimeError('live producer accepted unrelated control receipt')
                        print(program.name, samples, 'live private capture + pending owner/foreign receipt rejection PASS (CPU only)')
                    finally:
                        if process.poll() is None:
                            process.kill();process.communicate()
        if not ordinary:
            raise SystemExit('no actual ABI6 ESSL program traversed RX')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiled-directory', type=Path)
    args, rest = parser.parse_known_args()
    if args.compiled_directory:
        compiled_directory(args.compiled_directory)
    else:
        unittest.main(argv=[__file__, *rest])
