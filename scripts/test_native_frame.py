#!/usr/bin/env python3
"""CPU native frame layout and resource ABI checks; no RTL executes."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class NativeFrameTests(unittest.TestCase):
    def test_layout_and_resource_abi(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='native-frame-', dir=parent) as temporary:
            directory = Path(temporary)
            env = dict(os.environ, TMPDIR=temporary)
            compiler = shlex.split(os.environ.get('CC', 'cc'))
            flags = ['-std=c11', '-Wall', '-Wextra', '-Werror', '-pedantic', '-I', str(ROOT / 'driver')]
            binary = directory / 'layout'
            subprocess.run([*compiler, *flags, str(ROOT / 'driver/opengpu_native_frame.c'),
                            str(ROOT / 'driver/tests/opengpu_native_frame_test.c'), '-o', str(binary)],
                           env=env, check=True)
            result = subprocess.run([str(binary)], env=env, check=True, text=True, capture_output=True)
            self.assertEqual(result.stdout, 'native frame tile layout: PASS\n')
            source = directory / 'abi.c'
            source.write_text('#include "opengpu_native_frame_abi.h"\n'
                              '_Static_assert(sizeof(struct opengpu_native_frame_resource)==32,"size");\n'
                              '_Static_assert(__builtin_offsetof(struct opengpu_native_frame_resource,offset)==16,"offset");\n'
                              '_Static_assert(__builtin_offsetof(struct opengpu_native_frame_resource,bytes)==24,"bytes");\n')
            for mode in ([], ['-D__KERNEL__', '-nostdinc']):
                subprocess.run([*compiler, *flags, *mode, '-fsyntax-only', str(source)], env=env, check=True)


if __name__ == '__main__':
    unittest.main()
