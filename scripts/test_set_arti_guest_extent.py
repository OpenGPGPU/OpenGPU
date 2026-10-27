#!/usr/bin/env python3
"""Check that a private viewport repair preserves unrelated device metadata."""
import unittest

from set_arti_guest_extent import patch_extent


TREE = '''/dts-v1/;

/ {
\tother {
\t\topengpu,width = <0x123>;
\t};
\tarti-rtl@b000000 {
\t\tcompatible = "riscv-simt,opengpu", "arti,rtl";
\t\treg = <0x00 0xb000000 0x00 0x1000>;
\t\tinterrupts = <0x00 0xb4 0x04>;
\t\topengpu,width = <0x280>;
\t\topengpu,height = <0x1e0>;
\t\topengpu,stride = <0xa00>;
\t};
};
'''


class ExtentTests(unittest.TestCase):
    def test_only_selected_gpu_cells_change(self):
        actual, changes = patch_extent(TREE, 64, 64, 256)
        expected = TREE.replace("<0x280>", "<0x40>").replace("<0x1e0>", "<0x40>").replace("<0xa00>", "<0x100>")
        self.assertEqual(actual, expected)
        self.assertEqual(changes["opengpu,width"], dict(before=640, after=64))
        self.assertIn("opengpu,width = <0x123>", actual)

    def test_rejects_missing_duplicate_or_non_cell_properties(self):
        for tree in (TREE.replace("opengpu,height", "unrelated-height"),
                     TREE.replace("\t\topengpu,height", "\t\topengpu,width"),
                     TREE.replace("<0x280>", "<0x00 0x280>"),
                     TREE.replace("arti-rtl@b000000", "unrelated@b000000")):
            with self.assertRaises(ValueError):
                patch_extent(tree, 64, 64, 256)

    def test_rejects_invalid_numeric_contract(self):
        for values in ((0, 64, 256), (64, 0, 256), (64, 64, 252),
                       (64, 64, 1 << 32), (-1, 64, 256)):
            with self.assertRaises(ValueError):
                patch_extent(TREE, *values)


if __name__ == "__main__":
    unittest.main()
