#!/usr/bin/env python3
"""CPU checks for original buffer-texture publication sources and continuous receipts."""
import struct
import tempfile
import unittest
from pathlib import Path
from compute_texture_publication_cases import CASES, parameters, write_sources, DATA_WORD, FLAG_WORD
from compute_texture_publication import (PHASES, BASE, matrix_plan, retained_extents,
    check_round, check_source_launch)


class TexturePublicationCases(unittest.TestCase):
    def test_roles_and_expected_words(self):
        self.assertEqual([parameters(case)['role'] for case in CASES],
                         ['warm', 'producer', 'reload'])
        self.assertEqual(parameters(CASES[0])['expected_data'], 0)
        self.assertEqual(parameters(CASES[1])['expected_data'], DATA_WORD)
        self.assertEqual(parameters(CASES[1])['expected_flag'], FLAG_WORD)
        self.assertEqual(parameters(CASES[2])['expected_flag'], FLAG_WORD)

    def test_write_sources_keep_barrier_publication_shape(self):
        with tempfile.TemporaryDirectory(prefix='texture-publication-sources-') as temporary:
            out = Path(temporary)
            write_sources(out)
            producer = (out / 'publication-1.comp').read_text()
            self.assertIn('memoryBarrierImage()', producer)
            self.assertIn('imageStore(image0,1,uvec4(%du))' % DATA_WORD, producer)
            self.assertIn('imageStore(image0,17,uvec4(%du))' % FLAG_WORD, producer)
            reader = (out / 'warm.comp').read_text()
            self.assertEqual(reader, (out / 'reload.comp').read_text())
            self.assertIn('texelFetch(texture0,1).x', reader)
            self.assertIn('uniform highp usamplerBuffer texture0', reader)
            self.assertIn('GL_EXT_texture_buffer', reader)


class TexturePublicationProtocol(unittest.TestCase):
    def test_shared_base_three_phase_plan(self):
        self.assertEqual(len(PHASES), 3)
        self.assertTrue(all(stage['base'] == BASE for stage in PHASES))
        self.assertEqual(struct.unpack('<8sIIQ', matrix_plan()), (b'OGCTPM01', 3, 1, BASE))
        self.assertEqual(retained_extents(BASE, BASE + 8192), struct.pack('<QQ', BASE, BASE + 8192))

    def test_check_round_keeps_one_reset_and_command_id(self):
        held = {'commandId': 7}
        first = check_round(dict(schema='opengpu-compute-texture-publication-round-v1',
            phase=0, role='warm', commandId=7, resetCount=1, ramReplaced=False,
            externalDrained=True, sourceQualificationRequired=True,
            independentBytesRequired=True, startCycle=1, endCycle=10), 0, held)
        second = check_round(dict(schema='opengpu-compute-texture-publication-round-v1',
            phase=1, role='producer', commandId=7, resetCount=1, ramReplaced=False,
            externalDrained=True, sourceQualificationRequired=True,
            independentBytesRequired=True, startCycle=10, endCycle=20), 1, held, first)
        self.assertEqual(second['commandId'], 7)
        with self.assertRaises(ValueError):
            check_round(dict(schema='opengpu-compute-texture-publication-round-v1',
                phase=1, role='producer', commandId=8, resetCount=1, ramReplaced=False,
                externalDrained=True, sourceQualificationRequired=True,
                independentBytesRequired=True, startCycle=10, endCycle=20), 1, {'commandId': 8}, first)

    def test_source_launch_shape(self):
        build = Path('/tmp/build')
        session = Path('/tmp/session')
        profile = Path('/tmp/profile.bin')
        command = [str(build / 'compute_host_frontend'), str(profile),
                   str(session / 'round-0'), str(build / 'glsl' / 'warm.comp'),
                   CASES[0], '--physical-base', str(BASE)]
        check_source_launch(command, 0, build, session, profile)
        with self.assertRaises(ValueError):
            check_source_launch(command + ['--extra'], 0, build, session, profile)


class TexturePublicationQualifyWiring(unittest.TestCase):
    def test_qualify_exposes_texture_publication_case(self):
        import qualify_compute_owner as qualify
        self.assertEqual(qualify.TEXTURE_PUBLICATION_CASES[0], 'texture-publication-warm')
        self.assertIn('texture-publication-reload', qualify.TEXTURE_PUBLICATION_CASES)
        self.assertTrue(set(qualify.TEXTURE_PUBLICATION_CASES) <= set(qualify.CASES))


if __name__ == '__main__':
    unittest.main()
