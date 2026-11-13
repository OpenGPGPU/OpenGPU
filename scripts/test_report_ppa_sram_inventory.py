#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest

from report_ppa_sram_inventory import inventory
from qualify_ppa import sram_macro_gate

M32, M64 = 'srambank_64x4x32_6t122', 'srambank_64x4x64_6t122'


class SramInventoryTest(unittest.TestCase):
    def fixture(self, root):
        (root / 'Top.sv').write_text('module Top(); Child a(); Child b(); endmodule\n')
        (root / 'Child.sv').write_text(f'module Child(); {M32} memory(); endmodule\n')
        (root / 'Models.sv').write_text(f'module Models(); endmodule\n'
            f'module {M32}(); endmodule\nmodule {M64}(); endmodule\n')
        (root / 'verification').mkdir()
        (root / 'verification/ignore.sv').write_text('not synthesis source')
        (root / 'filelist.f').write_text('Top.sv\nChild.sv\nModels.sv\nverification/ignore.sv\n')

    def test_complete_shared_hierarchy_counts_instances_and_unused_views(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            self.assertEqual(inventory(root, 'Top'), {M32: 2, M64: 0})
            report = f'OPENGPU_SRAM_MACROS_BEGIN\nOPENGPU_SRAM_MACRO {M32} 2 2\nOPENGPU_SRAM_MACROS_END\n'
            self.assertTrue(sram_macro_gate(report, 0, dict(inventory(root, 'Top')))['passed'])
            self.assertFalse(sram_macro_gate(report, 0, {M32: 1, M64: 0})['passed'])

    def test_unlisted_sources_match_qualifier_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / 'filelist.f').unlink()
            self.assertEqual(inventory(root, 'Top'), {M32: 2, M64: 0})

    def test_comments_and_strings_do_not_create_cells(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            with (root / 'Child.sv').open('a') as stream:
                stream.write(f'// {M32} fake();\n/* module Wrong(); endmodule */\n')
            text=(root / 'Top.sv').read_text().replace('endmodule',
                'initial $display("Missing fake(); module Fake(); endmodule"); endmodule')
            (root / 'Top.sv').write_text(text)
            self.assertEqual(inventory(root, 'Top'), {M32: 2, M64: 0})

    def test_missing_repeated_external_or_recursive_sources_fail(self):
        for fault in ('missing', 'repeat', 'external', 'recursive', 'misnamed'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.fixture(root)
                if fault == 'missing': (root / 'Child.sv').unlink()
                elif fault == 'repeat':
                    with (root / 'filelist.f').open('a') as stream: stream.write('./Child.sv\n')
                elif fault == 'external':
                    (root / 'filelist.f').write_text('../outside.sv\n')
                elif fault == 'recursive': (root / 'Child.sv').write_text('module Child(); Top again(); endmodule\n')
                elif fault == 'misnamed': (root / 'Child.sv').write_text('module Other(); endmodule\n')
                with self.assertRaises(ValueError): inventory(root, 'Top')

    def test_uncertain_hierarchy_or_extra_dut_definition_is_rejected(self):
        for text in ('Child #(.N(2)) a();', 'Child a[1:0]();', 'generate Child a(); endgenerate',
                     'Child a(), b();', 'Missing fake();', 'srambank_fake_6t122 fake();'):
            with self.subTest(text=text), tempfile.TemporaryDirectory() as directory:
                root=Path(directory)
                self.fixture(root)
                (root / 'Top.sv').write_text('module Top(); ' + text + ' endmodule\n')
                with self.assertRaises(ValueError): inventory(root, 'Top')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            self.fixture(root)
            with (root / 'Models.sv').open('a') as stream: stream.write('module Extra(); endmodule\n')
            with self.assertRaises(ValueError): inventory(root, 'Top')


if __name__ == '__main__':
    unittest.main()
