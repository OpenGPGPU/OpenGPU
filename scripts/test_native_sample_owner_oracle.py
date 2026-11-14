#!/usr/bin/env python3
"""Actual Linux live capture plus synthetic returned-byte rejection tests.

The CEASE packets and hand-filled memory below are CPU unit inputs. No ESSL
compiler, shader DUT, genuine completion or successful owner cleanup executes.
"""
import copy
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import unittest

import native_sample_owner_oracle as oracle
from native_sample_owner_protocol import profile_bytes
from test_native_sample_owner import profile
from test_native_sample_submit_bridge import build_bridge
from test_native_sample_gny2_oracle import fixture as completion_fixture

ROOT = Path(__file__).resolve().parents[1]


class SampleByteOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT / '.artifacts').mkdir(exist_ok=True)
        cls.tmp = tempfile.TemporaryDirectory(prefix='sample-byte-unit-', dir=ROOT / '.artifacts')
        cls.out = Path(cls.tmp.name)
        cls.binary = build_bridge(cls.out, os.environ.get('OPENGPU_TEST_SANITIZERS') == '1', live=True)
        cls.observed = profile()
        cls.wire = cls.out / 'profile.bin'; cls.wire.write_bytes(profile_bytes(cls.observed))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def fixture(self, case, samples):
        directory = Path(tempfile.mkdtemp(prefix=case, dir=self.out))
        packet = b'OGNSF001' + struct.pack('<16I', 1, 6, 75, 512, 93 * 512,
            *oracle.CASES[case], 1, 0, 128, 16, 4, 1, 0x30500073)
        path = directory / 'unit.ognsf'; path.write_bytes(packet)
        process = subprocess.Popen([self.binary, path, self.wire, directory, str(samples), case],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=dict(os.environ, OPENGPU_NATIVE_HOST_TIMEOUT='15'))
        try:
            deadline = time.monotonic() + 15
            while not (directory / 'READY').exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue((directory / 'READY').exists(), process.communicate(timeout=1) if process.poll() is not None else '')
            description = json.loads((directory / 'sample-inputs.json').read_text())
            captured = (directory / 'record-0.capture').read_bytes()
            with oracle.SparseMemory(directory / 'union.memory') as initial:
                oracle.verify(initial, None, description, packet, captured, self.observed)
            # End the pending owner by a foreign receipt. Never invent actual
            # held/retired/ACK success to finish this CPU fixture.
            (directory / 'record-0.held.response').write_bytes(b'OGCMH001')
            stdout, stderr = process.communicate(timeout=5)
            self.assertNotEqual(process.returncode, 0)
            self.assertIn(b'wrong private wire magic', stderr)
            self.assertNotIn(b'completed', stdout)
            self.assertFalse((directory / 'cleanup.done').exists())
            return directory, description, packet, captured
        finally:
            if process.poll() is None:
                process.kill(); process.communicate()

    def test_live_capture_preflight_all_case_sample_pairs(self):
        for case in oracle.CASES:
            for samples in (1, 2, 4):
                with self.subTest(case=case, samples=samples):
                    directory, *_ = self.fixture(case, samples)
                    shutil.rmtree(directory)

    def test_known_source_arithmetic_and_exact_raw_samples(self):
        # Independent fixed examples from the committed ESSL, not a native
        # interpreter. All sample locations are subpixel positions.
        cases = [
            ('sample', 0, 0, 1, (0, .5, .5, 1)),
            ('sample', 0, 1, 2, (1, .75, .75, 2)),
            ('sample', 0, 2, 4, (2, .25, .75, 4)),
            ('sample', 0, 3, 4, (3, .75, .75, 8)),
            ('mrt', 3, 2, 4, (.25, .75, 5, 1)),
            ('varying', 3, 3, 4, (4.5625, 4.625, 3.9375, 4)),
            ('varying', 0, 0, 1, (.25,) * 4),
            ('mask-depth', 0, 1, 4, (.25,) * 4),
            ('mask-depth', 4, 3, 4, (1,)),
            ('mask-depth-keep', 4, 1, 2, (.5,)),
            ('mask-depth-keep', 0, 1, 2, (.75, .75, .25, 1)),
            ('truncation', 0, 1, 2, (2, 3, .75, .75)),
            ('signedness', 0, 3, 4, (2147483656, -2147483640, 3, 1)),
        ]
        for case, slot, sample, samples, expected in cases:
            self.assertEqual(oracle.sample_value(case, slot, sample, samples), expected)
        unsigned, signed, *_ = oracle.sample_value('signedness', 0, 3, 4)
        self.assertEqual(struct.unpack('<2I', struct.pack('<2f', unsigned, signed)), (0x4f000000, 0xcf000000))

    def write_physical(self, source, destination, changes):
        shutil.copyfile(source, destination)
        with oracle.SparseMemory(source) as memory, destination.open('r+b') as output:
            for physical, data in changes:
                offset = 0
                while offset < len(data):
                    at = physical + offset; page = at & ~4095
                    size = min(len(data) - offset, 4096 - (at & 4095))
                    output.seek(memory.pages[page] + (at & 4095))
                    output.write(data[offset:offset + size]); offset += size

    def test_returned_images_completion_and_every_other_physical_byte(self):
        directory, description, packet, captured = self.fixture('sample', 4)
        try:
            source, returned = directory / 'union.memory', directory / 'synthetic.memory'
            command, _, _ = oracle.capture(captured, self.observed)
            # Hand-fill the four known sample tuples, independently of the
            # production sample_value implementation. Other MRTs retain clear.
            tuples = ((0, .25, .25, 1), (1, .75, .25, 2), (2, .25, .75, 4), (3, .75, .75, 8))
            expected = b''.join(struct.pack('<4f', *t) for t in tuples) * 16
            changes = []
            for image in description['images']:
                slot = image['slot']; size = image['viewBytes']
                raw = expected if slot == 0 else struct.pack('<f', .25) * (size // 4)
                changes.append((image['allocationPhysical'] + image['offset'], raw))
            completed = description['completionOriginal']
            changes.append((completed['allocationPhysical'] + completed['offset'], completion_fixture(command, 4)))
            self.write_physical(source, returned, changes)
            with oracle.SparseMemory(source) as before, oracle.SparseMemory(returned) as after:
                oracle.verify(before, after, description, packet, captured, self.observed)
                with self.assertRaises(ValueError):
                    oracle.verify(before, before, description, packet, captured, self.observed)
            # Sample lane swap, depth, inactive MRT, original RX, PTE,
            # descriptor and page-rounded image tail must each fail closed.
            addresses = [image['allocationPhysical'] + image['offset'] for image in description['images']]
            addresses += [description['images'][0]['allocationPhysical'] + description['images'][0]['viewBytes'],
                description['pteRanges'][0]['first']]
            addresses += [s['physical'] for s in description['privateSegments']]
            for index, address in enumerate(addresses):
                bad = directory / 'bad.memory'
                with oracle.SparseMemory(returned) as m:
                    byte = bytes([m.read(address, 1)[0] ^ 1])
                self.write_physical(returned, bad, [(address, byte)])
                with oracle.SparseMemory(source) as before, oracle.SparseMemory(bad) as after:
                    with self.assertRaises(ValueError, msg=str(index)):
                        oracle.verify(before, after, description, packet, captured, self.observed)
                bad.unlink()
            for key, value in [('caseId', 'mrt'), ('samples', 2), ('fragmentPC', 0), ('vertexWords', 1), ('gpuExecuted', True)]:
                bad = copy.deepcopy(description); bad[key] = value
                with oracle.SparseMemory(source) as before, oracle.SparseMemory(returned) as after:
                    with self.assertRaises(ValueError):
                        oracle.verify(before, after, bad, packet, captured, self.observed)
        finally:
            shutil.rmtree(directory)


class SampleControllerGuardTests(unittest.TestCase):
    def test_missing_model_qualification_and_copied_checker_never_probe(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='sample-controller-unit-', dir=parent) as temp:
            directory = Path(temp)
            launched = directory / 'unexpected-launch'
            fake = directory / 'unqualified-executor'
            fake.write_text('#!' + sys.executable + '\nfrom pathlib import Path\nPath(' +
                            repr(str(launched)) + ').write_text("unqualified probe")\n')
            fake.chmod(0o755)
            qualification = directory / 'qualification.json'; qualification.write_text('{}\n')
            bridge = directory / 'bridge.json'
            bridge.write_text(json.dumps([sys.executable, '-c', 'raise RuntimeError("unqualified bridge")', '{ipc}']))
            copied = directory / 'copied-checker.py'; copied.write_text('raise SystemExit(0)\n')
            before = set(parent.iterdir())
            for checker in (ROOT / 'scripts/prepare_owner_executor.py', copied):
                result = subprocess.run([sys.executable, ROOT / 'scripts/native_sample_owner_controller.py',
                    '--executor', fake, '--executor-qualification', qualification,
                    '--executor-checker', checker, '--executor-checker-python', sys.executable,
                    '--bridge-command', bridge, '--program', directory / 'absent.ognsf', '--case', 'sample'],
                    capture_output=True, text=True, timeout=30, env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(launched.exists(), result.stdout + result.stderr)
                self.assertEqual(set(parent.iterdir()), before)
            for command in (["docker", "run", "--name", "owned", "--name", "other", "image", "{ipc}"],
                            ["docker", "run", "--name=owned", "image", "{ipc}"],
                            ["docker", "exec", "--name", "owned", "image", "{ipc}"]):
                bridge.write_text(json.dumps(command))
                result = subprocess.run([sys.executable, ROOT / 'scripts/native_sample_owner_controller.py',
                    '--executor', fake, '--executor-qualification', qualification,
                    '--executor-checker', ROOT / 'scripts/prepare_owner_executor.py',
                    '--executor-checker-python', sys.executable, '--bridge-command', bridge,
                    '--bridge-container-name', 'owned', '--program', directory / 'absent.ognsf', '--case', 'sample'],
                    capture_output=True, text=True, timeout=30, env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('one exact owned container name', result.stderr)
                self.assertEqual(set(parent.iterdir()), before)


if __name__ == '__main__':
    unittest.main()
