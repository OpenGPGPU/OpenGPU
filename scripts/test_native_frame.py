#!/usr/bin/env python3
"""CPU native frame layout and resource ABI checks; no RTL executes."""
import os
import json
from pathlib import Path
import shlex
import subprocess
import struct
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
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
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

    def test_encoder_and_published_wire_layout(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='native-frame-', dir=parent) as temporary:
            env = dict(os.environ, TMPDIR=temporary)
            compiler = shlex.split(os.environ.get('CC', 'cc'))
            flags = ['-std=c11', '-Wall', '-Wextra', '-Werror', '-pedantic', '-I', str(ROOT / 'driver')]
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            binary = Path(temporary) / 'encoder'
            subprocess.run([*compiler, *flags, str(ROOT / 'driver/opengpu_native_frame.c'),
                            str(ROOT / 'driver/tests/opengpu_native_frame_encoder_test.c'), '-o', str(binary)],
                           env=env, check=True)
            result = subprocess.run([str(binary)], env=env, check=True, text=True, capture_output=True)
            self.assertEqual(result.stdout, 'native frame encoder: PASS\n')
            wire = subprocess.run([str(binary), '--emit'], env=env, check=True, capture_output=True).stdout
            self.assertEqual(len(wire), 2048)
            words = struct.unpack('<512I', wire)
            self.assertEqual(words[:8], (0x474e4601, 2048, 1, 16, 16, 16, 4, 4))
            # Decode independently through the published Scala-generated JSON,
            # including line-contained nonuniform entries and every reserved word.
            abi = json.loads((ROOT / 'docs/abi/native-binned-graphics-v1.json').read_text())
            frame = abi['frame']
            fields = {field['name']: field for field in frame['fields']}
            def value(name):
                field = fields[name]
                count = (field['bits'] + 31) // 32
                return sum(words[field['word'] + i] << (32 * i) for i in range(count))
            self.assertEqual(value('frame.version'), 2)
            self.assertEqual(value('frame.bins.bins.tileCount'), 6)
            self.assertEqual(value('frame.bins.bins.pool.spillCount'), 8)
            self.assertEqual(value('frame.image.pixels.colors.sampleMode'), 2)
            self.assertEqual(value('frame.image.pixels.colors.reservedBytes'), 768)
            self.assertEqual(value('frame.image.pixels.colors.tileLog2'), 3)
            for prefix in ('frame.bins.producer.arena.identity.', 'frame.bins.bins.pool.identity.',
                           'frame.states.identity.', 'frame.image.pixels.colors.identity.'):
                self.assertEqual(tuple(value(prefix + key) for key in ('context', 'pass', 'generation')), (7, 8, 9))
            for i, (pixel_bytes, fmt) in enumerate(zip((4, 4, 6, 16, 2, 1), (3, 13, 32, 26, 1, 6))):
                if i < 4:
                    self.assertEqual(value(f'frame.image.pixels.colors.colors[{i}].format'), fmt)
                    for component in range(4):
                        expected = (0x10203040, 0x50607080, 0x90a0b0c0, 0xd0e0f000)[component] + i
                        self.assertEqual(value(f'frame.image.pixels.colors.colors[{i}].clear[{component}]'), expected)
                for prefix, samples, pitch_extra, read in ((f'frame.image.views[{i}].', 4, 13, 1),
                        (f'frame.image.resolves[{i}].destination.', 1, 7, 0)):
                    self.assertEqual(tuple(value(prefix + key) for key in ('imageWidth', 'imageHeight', 'rowPitch',
                                                                         'readable', 'writable', 'store')),
                                     (17, 9, 17 * samples * pixel_bytes + pitch_extra, read, 1, 1))
                self.assertEqual(value(f'frame.image.resolves[{i}].enable'), 1)
            used = {0, 1, 2}
            address_count = 0
            for field in frame['fields']:
                count = (field['bits'] + 31) // 32
                used.update(range(field['word'], field['word'] + count))
                if field['name'].endswith(('.base', '.limit', '.allocationBase', '.allocationLimit',
                                            '.directoryBase', '.directoryLimit', '.initialLimit')):
                    self.assertEqual(value(field['name']), 0, field['name'])
                    address_count += 1
                self.assertLess(value(field['name']), 1 << field['bits'], field['name'])
            self.assertGreater(address_count, 70)
            self.assertTrue(all(word == 0 for i, word in enumerate(words) if i not in used))


if __name__ == '__main__':
    unittest.main()
