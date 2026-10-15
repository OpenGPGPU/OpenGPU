#!/usr/bin/env python3
"""Exercise presentation checks against stale and partially rendered captures."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

PATH = Path(__file__).with_name('verify_gtk_scanout.py')
spec = importlib.util.spec_from_file_location('scanout', PATH)
scanout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanout)


def frame(width, height, rectangle=None, shape='rectangle', color=(242, 89, 46)):
    pixels = bytearray(width * height * 3)
    for y in range(height):
        for x in range(width):
            value = (x % 120, y % 120, (x + y) % 120)
            if rectangle:
                left, top, rw, rh = rectangle
                inside = left <= x < left + rw and top <= y < top + rh
                if shape == 'triangle':
                    inside = inside and (x - left) * rh <= (y - top) * rw
                if inside:
                    value = color
            offset = 3 * (y * width + x)
            pixels[offset:offset + 3] = bytes(value)
    return f'P6\n{width} {height}\n255\n'.encode() + pixels


class ScanoutTests(unittest.TestCase):
    def test_complete_compact_and_normal_rectangles(self):
        for size, client, rect in [((64, 64), (64, 64), (6, 13, 52, 38)),
                                   ((640, 480), (280, 124), (120, 180, 230, 74))]:
            result = scanout.verify(frame(*size, rect, color=(241, 88, 45)), size, client)
            self.assertTrue(result['passed'])
            self.assertEqual(result['client_size'], list(client))

    def test_stale_colourful_and_idle_frames(self):
        for data in [frame(64, 64), frame(64, 64, (6, 13, 52, 38), color=(51, 140, 46))]:
            with self.assertRaises(scanout.ScanoutError):
                scanout.verify(data, (64, 64), (64, 64))

    def test_partial_triangle_tile_and_stripe(self):
        for rect, shape in [((120, 180, 230, 74), 'triangle'),
                            ((120, 180, 16, 16), 'rectangle'),
                            ((0, 180, 640, 5), 'rectangle')]:
            with self.assertRaises(scanout.ScanoutError):
                scanout.verify(frame(640, 480, rect, shape), (640, 480), (280, 124))

    def test_hole_in_otherwise_complete_rectangle(self):
        data = bytearray(frame(64, 64, (6, 13, 52, 38)))
        offset = len(b'P6\n64 64\n255\n') + 3 * (32 * 64 + 32)
        data[offset:offset + 3] = b'\0\0\0'
        with self.assertRaises(scanout.ScanoutError):
            scanout.verify(data, (64, 64), (64, 64))

    def test_wrong_dimensions_and_incomplete_capture(self):
        data = frame(64, 64, (6, 13, 52, 38))
        for content, size in [(data, (640, 480)), (data[:-1], (64, 64))]:
            with self.assertRaises(scanout.ScanoutError):
                scanout.verify(content, size, (64, 64))

    def test_requires_active_readback_dimensions(self):
        with self.assertRaises(scanout.ScanoutError):
            scanout.active_size('OPENGPU GTK FRAME PASS: idle pixel=51,140,46,255 size=64x64')
        with self.assertRaises(scanout.ScanoutError):
            scanout.active_size('OPENGPU GTK FRAME PASS: active pixel=242,89,46,255 size=1x1')
        self.assertEqual(scanout.active_size('OPENGPU GTK FRAME PASS: active pixel=242,89,46,255 size=280x124'), (280, 124))

    def test_cli_freezes_exact_verified_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / 'input.ppm'; saved = root / 'saved.ppm'
            log = root / 'client.log'; result = root / 'result.json'
            data = frame(64, 64, (6, 13, 52, 38)); source.write_bytes(data)
            log.write_text('OPENGPU GTK FRAME PASS: active pixel=242,89,46,255 size=64x64\n')
            subprocess.run([sys.executable, str(PATH), str(source), '64', '64', str(log),
                            '--save-frame', str(saved), '--output', str(result)], check=True,
                           stdout=subprocess.DEVNULL)
            source.write_bytes(frame(64, 64))
            self.assertEqual(saved.read_bytes(), data)
            self.assertEqual(json.loads(result.read_text())['capture_sha256'], hashlib.sha256(data).hexdigest())


if __name__ == '__main__':
    unittest.main()
