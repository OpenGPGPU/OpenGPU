#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest

from extract_emitted_rtl import extract


class ExtractEmittedRtlTest(unittest.TestCase):
    def tree(self, root, **modules):
        source = root / 'source'
        source.mkdir()
        for name, body in modules.items():
            (source / (name + '.sv')).write_text(body)
        return source

    def test_complete_repeated_hierarchy_preserves_bytes_and_external_macros(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = dict(Top='module Top(input clock);\n Child first(.clock(clock));\n Child second(.clock(clock));\nendmodule\n',
                Child='module Child(input clock); srambank_64x4x64_6t122 memory(.clk(clock));\nendmodule\n',
                Unrelated='module Unrelated();\nendmodule\n')
            source = self.tree(root, **sources)
            result = extract(source, 'Top', root / 'out')
            self.assertEqual(result['expanded_instances'], {'Top': 1, 'Child': 2, 'srambank_64x4x64_6t122': 2})
            self.assertEqual(set(result['files']), {'Top.sv', 'Child.sv'})
            for name in ('Top', 'Child'):
                self.assertEqual((root / 'out' / (name + '.sv')).read_bytes(), sources[name].encode())
            self.assertEqual((root / 'out/filelist.f').read_text(), 'Child.sv\nTop.sv\n')
            with self.assertRaises(FileExistsError):
                extract(source, 'Top', root / 'out')

    def test_comments_strings_nested_ports_and_multiline_instances(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.tree(root,
                Top='''// module Fake(); endmodule
module Top(input clock); Child inline(.clock(clock));
/* Missing fake(); */
Child
 first(.clock((clock)));
initial $display("module Wrong(); Missing fake(); endmodule // text");
endmodule
''', Child='module Child(input clock); endmodule\n')
            self.assertEqual(set(extract(source, 'Top', root / 'out')['files']), {'Top.sv', 'Child.sv'})

    def test_missing_or_fake_external_module_fails_before_output_creation(self):
        for kind in ('Missing', 'srambank_fake_6t122'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = self.tree(root, Top=f'module Top(); {kind} cell();\nendmodule\n')
                with self.assertRaisesRegex(ValueError, 'Missing DUT'):
                    extract(source, 'Top', root / 'out')
                self.assertFalse((root / 'out').exists())

    def test_unsupported_hierarchy_forms_and_duplicate_instances_are_rejected(self):
        forms = ['Child #(.N(2)) cell();', 'Child cell[1:0]();', 'Child \\cell [1:0]();',
                 '\\Unknown cell();', 'generate Child cell(); endgenerate',
                 'Child a(), b();', 'Child a();\n Child a();', '`include "missing.vh"',
                 'Child a();\n Child \\a ();', 'initial $readmemh("missing.hex", memory);']
        for form in forms:
            with self.subTest(form=form), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = self.tree(root, Top='module Top(); ' + form + '\nendmodule\n',
                    Child='module Child(); endmodule\n')
                with self.assertRaises(ValueError):
                    extract(source, 'Top', root / 'out')
                self.assertFalse((root / 'out').exists())

    def test_recursive_or_misnamed_definitions_are_rejected(self):
        for body in ('module Other(); endmodule\n', 'module Top();\n Top again();\nendmodule\n',
                     'module Top(); endmodule\nmodule Other(); endmodule\n'):
            with self.subTest(body=body), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = self.tree(root, Top=body)
                with self.assertRaises(ValueError):
                    extract(source, 'Top', root / 'out')


if __name__ == '__main__':
    unittest.main()
