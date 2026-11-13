#!/usr/bin/env python3
"""CPU-only product observation checks; no DUT or rendered frame is qualified."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ProductObservationTests(unittest.TestCase):
    def test_complete_fault_identity_and_retirement_observations(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='product-observe-', dir=parent) as temporary:
            compiler = shlex.split(os.environ.get('CXX', 'c++'))
            flags = ['-std=c++17', '-Wall', '-Wextra', '-Werror', '-pedantic']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            output = Path(temporary) / 'observe'
            env = dict(os.environ, TMPDIR=temporary)
            subprocess.run([*compiler, *flags, str(ROOT / 'scripts/test_native_product_observation.cpp'),
                            '-o', str(output)], env=env, check=True)
            result = subprocess.check_output([str(output)], env=env, text=True)
            self.assertEqual(result, 'native product completion observations: PASS\n')
            subprocess.run([*compiler, *flags, '-fsyntax-only',
                            '-I', str(ROOT / 'scripts/test_stubs/native_host'),
                            str(ROOT / 'scripts/native_product_driver.cpp')], env=env, check=True)

    def test_identity_offsets_match_published_gnj_wire_contract(self):
        fields = {f['name']: f for f in json.loads(
            (ROOT / 'docs/abi/native-binned-graphics-v1.json').read_text())['job']['fields']}
        for name, word in [('context', 24), ('generation', 25), ('pass', 26)]:
            field = fields['job.identity.' + name]
            self.assertEqual((field['word'], field['bits'], field['signed']), (word, 32, False))


if __name__ == '__main__':
    unittest.main()
