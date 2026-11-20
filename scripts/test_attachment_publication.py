#!/usr/bin/env python3
"""CPU checks for color-attachment publication cases and packet construction."""
import struct
import tempfile
import unittest
from pathlib import Path

from attachment_publication_cases import (CASES, parameters, BASE, DATA_OFFSET, FLAG_OFFSET,
    producer_source_pattern)
from attachment_publication_packets import build_packets
from framebuffer_blit_fixture import BlitFixture


class AttachmentPublicationCases(unittest.TestCase):
    def test_roles_and_shared_base(self):
        self.assertEqual([parameters(case)['role'] for case in CASES],
                         ['warm', 'producer', 'reload'])
        self.assertEqual(parameters(CASES[0])['expected_data'], 0)
        self.assertNotEqual(parameters(CASES[1])['expected_data'], 0)
        self.assertEqual(parameters(CASES[1])['expected_flag'],
                         parameters(CASES[2])['expected_flag'])
        self.assertEqual(BASE, 0x01000000)


class AttachmentPublicationPackets(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[1]
        marker = root / '.artifacts' / 'blit-caller-dir.txt'
        if not marker.is_file():
            raise unittest.SkipTest('framebuffer blit caller capture is required')
        cls.inputs = Path(marker.read_text().strip()) / 'inputs'

    def test_build_packets_keep_retained_attachment_handle_and_pixels(self):
        with tempfile.TemporaryDirectory(prefix='attachment-publication-') as temporary:
            out = build_packets(self.inputs / 'copy-region-0.blit',
                                self.inputs / 'same-gem.blit', temporary)
            warm = BlitFixture.read((out / (CASES[0] + '.blit')).read_bytes())
            producer = BlitFixture.read((out / (CASES[1] + '.blit')).read_bytes())
            reload = BlitFixture.read((out / (CASES[2] + '.blit')).read_bytes())
            self.assertEqual(warm.views[0].handle, producer.views[1].handle)
            self.assertEqual(reload.views[0].handle, producer.views[1].handle)
            expected = parameters(CASES[1])
            self.assertEqual(struct.unpack_from('<I', producer.expected, DATA_OFFSET)[0],
                             expected['expected_data'])
            self.assertEqual(struct.unpack_from('<I', producer.expected, FLAG_OFFSET)[0],
                             expected['expected_flag'])
            self.assertEqual(struct.unpack_from('<I', reload.source, DATA_OFFSET)[0],
                             expected['expected_data'])
            plan = (out / 'attachment-publication.plan').read_bytes()
            self.assertEqual(struct.unpack('<8sIIQ', plan), (b'OGAPPM01', 3, 1, BASE))
            pattern = producer_source_pattern(producer.views[0].allocation)
            self.assertEqual(producer.source, pattern)


if __name__ == '__main__':
    unittest.main()
