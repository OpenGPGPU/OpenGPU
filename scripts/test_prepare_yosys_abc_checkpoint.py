#!/usr/bin/env python3
"""Small controls for the exact pre-ABC script transformation."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_yosys_abc_checkpoint as prepare


def fixture():
    return ('source preamble\n' + prepare.SETUNDEF + '\n' + prepare.ABC +
            '}\ncheck -assert -mapped\nwrite_verilog result.v\n').encode()


class PrepareAbcCheckpointTest(unittest.TestCase):
    def derive(self, source):
        with patch.object(prepare, 'SUPPORTED_SHA256', hashlib.sha256(source).hexdigest()):
            return prepare.derive(source)

    def test_preserves_stock_commands_and_suffix(self):
        original = fixture()
        checkpoint, resume = self.derive(original)
        self.assertEqual(checkpoint.replace(prepare.SAVE.encode(), b'', 1), original)
        self.assertEqual(resume, (prepare.RESUME + prepare.ABC +
                                  original.decode().split(prepare.ABC, 1)[1]).encode())
        self.assertIn(b'check -assert -mapped\n', resume)
        self.assertIn(b'write_verilog result.v\n', resume)
        for text in (checkpoint, resume):
            self.assertIn(b'pre-ABC checkpoints require the standard ABC flow', text)
        self.assertIn(b'$checkpoint_file.sha256.tmp', checkpoint)
        self.assertIn(b'pre-ABC checkpoint checksum mismatch', resume)

    def test_unknown_or_ambiguous_script_fails(self):
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            prepare.derive(fixture())
        for source in (fixture().replace(b'setundef -zero\n', b''),
                       fixture().replace(prepare.ABC.encode(), prepare.ABC.encode() * 2),
                       fixture().replace(b'setundef -zero\n', b'setundef -zero\nopt\n'),
                       fixture().replace(b'check -assert -mapped\n', b'')):
            with self.subTest(source=source), self.assertRaises(ValueError):
                self.derive(source)

    def test_output_is_fresh_and_reports_script_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / 'synth.tcl', root / 'scripts'
            source.write_bytes(fixture())
            with patch.object(prepare, 'SUPPORTED_SHA256', hashlib.sha256(fixture()).hexdigest()):
                result = prepare.prepare(source, output)
                with self.assertRaises(FileExistsError):
                    prepare.prepare(source, output)
            self.assertEqual(json.loads((output / 'manifest.json').read_text()), result)
            self.assertEqual(result['resume_script_sha256'],
                             hashlib.sha256((output / 'resume-synth.tcl').read_bytes()).hexdigest())
            self.assertFalse(result['physical_qualified'])


if __name__ == '__main__':
    unittest.main()
