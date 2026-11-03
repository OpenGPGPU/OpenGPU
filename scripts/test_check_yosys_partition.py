#!/usr/bin/env python3
"""Bounded structural and negative controls for mapped partitions."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import check_yosys_partition as check


RTLIL = r'''autoidx 2
attribute \liberty_cell 1
attribute \blackbox 1
module \Macro
  wire input 1 \clock
  wire width 4 input 2 \data
end
module \Child
  wire input 1 \clock
  wire width 4 input 2 \data
  wire width 4 output 3 \value
  wire output 4 \unused
end
attribute \top 1
module \Top
  wire input 1 \clock
  wire width 4 input 2 \data
  wire width 4 output 3 \value
  cell \Child \u0
    connect \clock \clock
    connect \data \data
    connect \value \value
    connect \unused { }
  end
  cell \Macro \ram
    connect \clock \clock
    connect \data \data
  end
end
'''
VERILOG = '''module Top(clock, data, value);
  input clock;
  input [3:0] data;
  output [3:0] value;
  Child u0 (
    .clock(clock),
    .data(data),
    .value(value)
  );
  Macro ram (
    .clock(clock),
    .data(data)
  );
endmodule
'''


class PartitionStructureTest(unittest.TestCase):
    def fixture(self, root, rtlil=RTLIL, mapped=VERILOG):
        partition, manifest, netlist = root / 'input.rtlil', root / 'manifest.json', root / 'mapped.v'
        partition.write_text(rtlil)
        manifest.write_text(json.dumps(dict(module='Top', partition_sha256=hashlib.sha256(partition.read_bytes()).hexdigest(),
                                            direct_child_instances={'Child': 1, 'Macro': 1})))
        netlist.write_text(mapped)
        return partition, manifest, netlist

    def test_preserves_ports_children_and_intentionally_unconnected_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = self.fixture(Path(directory))
            result = check.check(*paths)
            self.assertEqual((result['port_count'], result['direct_instances'], result['omitted_empty_outputs']), (3, 2, 1))
            self.assertFalse(result['physical_qualified'])

    def test_rejects_changed_ports_missing_children_and_connections(self):
        mutations = (
            VERILOG.replace('input [3:0] data;', 'input [2:0] data;'),
            VERILOG.replace('output [3:0] value;', 'input [3:0] value;'),
            VERILOG.replace('Top(clock, data, value)', 'Top(data, clock, value)'),
            VERILOG.replace('Child u0 (', 'Child different ('),
            VERILOG.replace('    .data(data),\n', '', 1),
            VERILOG.replace('    .data(data),\n', '    .data(),\n', 1),
            VERILOG.replace('    .value(value)\n', '    .unexpected(value)\n'),
            '(* blackbox = 1 *)\n' + VERILOG,
            '(*\n  whitebox = 1\n*)\n' + VERILOG,
            VERILOG + 'module Another();\nendmodule\n',
        )
        for mapped in mutations:
            with self.subTest(mapped=mapped), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError): check.check(*self.fixture(Path(directory), mapped=mapped))
        with tempfile.TemporaryDirectory() as directory:
            connected_output = RTLIL.replace('connect \\unused { }', 'connect \\unused \\live')
            with self.assertRaisesRegex(ValueError, 'lost a connected port'):
                check.check(*self.fixture(Path(directory), rtlil=connected_output))
        with tempfile.TemporaryDirectory() as directory:
            malformed = RTLIL.replace('cell \\Child \\u0', 'cell \\Child u0')
            missing = VERILOG.replace('  Child u0 (\n    .clock(clock),\n    .data(data),\n    .value(value)\n  );\n', '')
            with self.assertRaisesRegex(ValueError, 'Unsupported canonical child'):
                check.check(*self.fixture(Path(directory), rtlil=malformed, mapped=missing))

    def test_rejects_stale_partition_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            partition, manifest, mapped = self.fixture(Path(directory))
            partition.write_text(partition.read_text() + '\n')
            with self.assertRaisesRegex(ValueError, 'Partition hash differs'):
                check.check(partition, manifest, mapped)

    def test_preserves_signed_offset_and_ascending_ranges(self):
        self.assertEqual(check.il_port('  wire width 4 offset 3 upto signed input 1 \\data\n'),
                         ('data', ('input', True, 3, 6), 1))
        self.assertEqual(check.header_names('clock, \\a.b , data'), ['clock', 'a.b', 'data'])
        rtlil = RTLIL.replace('module \\Top\n  wire input 1 \\clock\n  wire width 4 input 2 \\data',
                              'module \\Top\n  wire input 1 \\clock\n  wire width 4 offset 3 upto signed input 2 \\data')
        mapped = VERILOG.replace('input [3:0] data;', 'input signed [3:6] data;')
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(check.check(*self.fixture(Path(directory), rtlil=rtlil, mapped=mapped))['port_count'], 3)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'port signatures'):
                check.check(*self.fixture(Path(directory), rtlil=rtlil, mapped=mapped.replace('[3:6]', '[6:3]')))


if __name__ == '__main__':
    unittest.main()
