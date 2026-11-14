#!/usr/bin/env python3
"""CPU OGLAY002/112 parser checks; no sample builtin or GPU qualification."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

from owner_resource_contract import SAMPLE_WORDS

ROOT = Path(__file__).resolve().parents[1]


class SampleLayeredProductTests(unittest.TestCase):
    def test_fixed_sample_parser_rejects_old_and_malformed_contracts(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='sample-layer-parser-', dir=parent) as temporary:
            compiler = shlex.split(os.environ.get('CXX', 'c++'))
            flags = ['-std=c++17', '-Wall', '-Wextra', '-Werror', '-pedantic']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            binary = Path(temporary) / 'parser'
            environment = dict(os.environ, TMPDIR=temporary)
            subprocess.run([*compiler, *flags, str(ROOT / 'scripts/test_native_sample_layered_product.cpp'),
                            '-o', str(binary)], env=environment, check=True)
            result = subprocess.check_output([str(binary), str(Path(temporary) / 'case.commands')],
                                             env=environment, text=True)
            self.assertEqual(result, 'native sample layered product parser and profile isolation: PASS\n')
            actual = json.loads(subprocess.check_output([str(binary), '--profile'], env=environment, text=True))
            self.assertEqual(actual, SAMPLE_WORDS)
            if os.environ.get('OPENGPU_SAMPLE_LAYERED_FIXTURES'):
                result = subprocess.check_output([str(binary), '--fixtures',
                    os.environ['OPENGPU_SAMPLE_LAYERED_FIXTURES']], env=environment, text=True)
                self.assertEqual(result, 'native sample layered producer containers: 6 accepted (CPU only)\n')
            subprocess.run([*compiler, *flags, '-fsyntax-only', '-I',
                            str(ROOT / 'scripts/test_stubs/native_host'),
                            str(ROOT / 'scripts/native_sample_layered_product_driver.cpp')],
                           env=environment, check=True)


if __name__ == '__main__':
    unittest.main()
