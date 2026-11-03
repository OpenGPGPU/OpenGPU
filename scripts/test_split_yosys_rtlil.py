#!/usr/bin/env python3
"""Bounded controls for module-sized slices of a canonical Yosys design."""
from pathlib import Path
import json
import os
import tempfile
import unittest
from unittest.mock import patch

import split_yosys_rtlil as split
from extract_emitted_rtl import inventory

SRAM = 'srambank_64x4x32_6t122'


def block(name, children=(), attrs=()):
    result = ''.join('attribute \\' + key + ' ' + value + '\n' for key, value in attrs)
    result += 'module \\' + name + '\n'
    for index, child in enumerate(children):
        result += '  cell \\' + child + ' \\i' + str(index) + '\n  end\n'
    return result + 'end\n'


class PartitionPreparationTest(unittest.TestCase):
    def fixture(self, root):
        rtl = root / 'rtl'
        rtl.mkdir()
        (rtl / 'Top.sv').write_text('module Top(input clock);\n Child a(.clock(clock));\n Child b(.clock(clock));\nendmodule\n')
        (rtl / 'Child.sv').write_text(f'module Child(input clock);\n {SRAM} ram(.clk(clock));\nendmodule\n')
        (rtl / 'Unrelated.sv').write_text('module Unrelated(); endmodule\n')
        (rtl / 'filelist.f').write_text('Child.sv\nTop.sv\nUnrelated.sv\nverification/assert/unused.sv\n')
        parts = {
            'library': block('LibraryCell', attrs=[('liberty_cell', '1'), ('whitebox', '1')]),
            'sram': block(SRAM, attrs=[('liberty_cell', '1'), ('blackbox', '1')]),
            'child': block('Child', [SRAM]),
            'top': block('Top', ['Child', 'Child'], [('top', '1')]),
        }
        checkpoint = root / 'design.rtlil'
        checkpoint.write_text('# generated\nautoidx 9\n' + ''.join(parts.values()))
        return rtl, checkpoint, parts

    def test_complete_root_and_child_slices_preserve_shared_instances_and_real_sram(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rtl, checkpoint, parts = self.fixture(root)
            manifest = split.prepare(rtl, checkpoint, 'Top', 'Top', root / 'top')
            self.assertEqual(manifest['direct_dut_children_to_blackbox'], ['Child'])
            self.assertEqual(manifest['direct_child_instances'], {'Child': 2})
            self.assertEqual(manifest['expanded_sram_instances'], {SRAM: 2})
            self.assertEqual((manifest['reachable_definitions'], manifest['library_definitions']), (2, 2))
            self.assertFalse(manifest['physical_qualified'])
            self.assertIn('provenance', manifest['scope'])
            output = (root / 'top/partition.rtlil').read_text()
            for item in parts.values():
                self.assertIn(item, output)
            self.assertNotIn('module \\Unrelated', output)
            self.assertEqual(json.loads((root / 'top/manifest.json').read_text()), manifest)
            child = split.prepare(rtl, checkpoint, 'Top', 'Child', root / 'child')
            self.assertEqual(child['direct_dut_children_to_blackbox'], [])
            self.assertNotIn(parts['top'], (root / 'child/partition.rtlil').read_text())
            with self.assertRaises(FileExistsError):
                split.prepare(rtl, checkpoint, 'Top', 'Top', root / 'top')

    def test_changed_child_graph_missing_sram_and_unreachable_module_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rtl, checkpoint, parts = self.fixture(root)
            for modified in (
                checkpoint.read_text().replace(parts['top'], block('Top', ['Child'])),
                checkpoint.read_text().replace(parts['child'], ''),
                checkpoint.read_text().replace(parts['sram'], ''),
            ):
                checkpoint.write_text(modified)
                with self.assertRaises(ValueError):
                    split.prepare(rtl, checkpoint, 'Top', 'Top', root / 'never')
                self.assertFalse((root / 'never').exists())
                checkpoint.write_text('# generated\nautoidx 9\n' + ''.join(parts.values()))
            with self.assertRaisesRegex(ValueError, 'not reachable'):
                split.prepare(rtl, checkpoint, 'Top', 'Unrelated', root / 'never')

    def test_abstracted_dut_unknown_library_and_invalid_filelist_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rtl, checkpoint, parts = self.fixture(root)
            original = checkpoint.read_text()
            for modified in (
                original.replace(parts['child'], block('Child', [SRAM], [('blackbox', '1')])),
                original.replace(parts['library'], block('LibraryCell')),
                original.replace(parts['sram'], block(SRAM, attrs=[('liberty_cell', '1')])),
            ):
                checkpoint.write_text(modified)
                with self.assertRaises(ValueError):
                    split.prepare(rtl, checkpoint, 'Top', 'Top', root / 'never')
                checkpoint.write_text(original)
            (rtl / 'filelist.f').write_text('Child.sv\nTop.sv\nTop.sv\n')
            with self.assertRaisesRegex(ValueError, 'filelist'):
                split.prepare(rtl, checkpoint, 'Top', 'Top', root / 'never')

    def test_matching_hierarchy_is_not_a_source_or_timing_qualification(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rtl, checkpoint, _ = self.fixture(root)
            child = rtl / 'Child.sv'
            child.write_text(child.read_text().replace('endmodule', "wire changed = 1'b1;\nendmodule"))
            result = split.prepare(rtl, checkpoint, 'Top', 'Top', root / 'out')
            self.assertFalse(result['physical_qualified'])
            self.assertIn('checkpoint provenance', result['scope'])
            self.assertFalse((root / 'out/qualification.json').exists())

    def test_truncated_duplicate_and_unrecognized_checkpoint_syntax_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, checkpoint, parts = self.fixture(root)
            original = checkpoint.read_text()
            for altered in (original[:-4], original + parts['top'],
                            original.replace('autoidx 9\n', 'autoidx 9\nunknown command\n')):
                checkpoint.write_text(altered)
                with self.assertRaises(ValueError):
                    split.index_checkpoint(checkpoint)

    def test_changed_checkpoint_and_changed_emission_cannot_produce_a_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rtl, checkpoint, _ = self.fixture(root)
            indexed = split.index_checkpoint(checkpoint)
            modules = inventory(rtl)
            reachable, libraries, _ = split.validate_hierarchy(indexed, modules, 'Top')
            stat = checkpoint.stat()
            with checkpoint.open('r+b') as stream:
                stream.seek(indexed.blocks['Top'].start)
                first = stream.read(1)
                stream.seek(indexed.blocks['Top'].start)
                stream.write(b'X' if first != b'X' else b'Y')
            os.utime(checkpoint, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            with self.assertRaisesRegex(ValueError, 'module changed'):
                split.write_partition(indexed, 'Top', modules, reachable, libraries, root / 'stale.rtlil')

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rtl, checkpoint, _ = self.fixture(root)
            real = split.write_partition
            def modify_after_writing(*args, **kwargs):
                result = real(*args, **kwargs)
                (rtl / 'Child.sv').write_text('module Child(); endmodule\n')
                return result
            with patch.object(split, 'write_partition', side_effect=modify_after_writing):
                with self.assertRaisesRegex(ValueError, 'Emitted RTL changed'):
                    split.prepare(rtl, checkpoint, 'Top', 'Top', root / 'out')
            self.assertFalse((root / 'out/manifest.json').exists())

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rtl, checkpoint, _ = self.fixture(root)
            real = split.write_partition
            def modify_filelist_after_writing(*args, **kwargs):
                result = real(*args, **kwargs)
                (rtl / 'filelist.f').write_text('Child.sv\nTop.sv\n')
                return result
            with patch.object(split, 'write_partition', side_effect=modify_filelist_after_writing):
                with self.assertRaisesRegex(ValueError, 'filelist changed'):
                    split.prepare(rtl, checkpoint, 'Top', 'Top', root / 'out')
            self.assertFalse((root / 'out/manifest.json').exists())


if __name__ == '__main__':
    unittest.main()
