#!/usr/bin/env python3
"""Fail-closed component selection, proof receipts and owned-container cleanup."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_yosys_opt_equivalence as q

ROOT = Path(__file__).resolve().parents[1]


class OptEquivalenceTests(unittest.TestCase):
    def setUp(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix='opt-test-', dir=parent)
        self.addCleanup(self.directory.cleanup)
        self.rtl = Path(self.directory.name)

    def source(self, name, text):
        path = self.rtl / (name + '.sv')
        path.write_text(text)
        return path

    def test_reused_definition_retains_both_physical_instances(self):
        self.source('Top', 'module Top(input a, output y); Leaf a0(.a(a),.y()); Leaf a1(.a(a),.y(y)); endmodule')
        self.source('Leaf', 'module Leaf(input a, output y); assign y=a; endmodule')
        modules, counts = q.component_closure(self.rtl, 'Top')
        self.assertEqual(set(modules), {'Top', 'Leaf'})
        self.assertEqual(counts, {'Top': 1, 'Leaf': 2})

    def test_unrelated_simulation_macro_file_is_not_loaded(self):
        self.source('Top', 'module Top(input a, output y); assign y=a; endmodule')
        self.source('Models', 'module Models(); endmodule module srambank_1x1x1_6t122(); endmodule')
        self.assertEqual(q.component_closure(self.rtl, 'Top')[1], {'Top': 1})

    def test_reachable_macro_cannot_be_abstracted(self):
        self.source('Top', 'module Top(); srambank_64x4x32_6t122 mem(); endmodule')
        with self.assertRaisesRegex(ValueError, 'External macros'):
            q.component_closure(self.rtl, 'Top')

    def test_recursive_hierarchy_rejected(self):
        self.source('Top', 'module Top(); Top recursive(); endmodule')
        with self.assertRaisesRegex(ValueError, 'Recursive'):
            q.component_closure(self.rtl, 'Top')

    def test_missing_definition_rejected(self):
        self.source('Top', 'module Top(); Missing child(); endmodule')
        with self.assertRaises(FileNotFoundError):
            q.component_closure(self.rtl, 'Top')

    def test_parameterized_and_duplicate_definitions_rejected(self):
        for text in ('module Top #(parameter N=2)(); endmodule',
                     'module Top(); endmodule module Other(); endmodule'):
            self.source('Top', text)
            with self.assertRaisesRegex(ValueError, 'one unparameterized'):
                q.component_closure(self.rtl, 'Top')

    def test_identifier_cannot_inject_commands(self):
        with self.assertRaisesRegex(ValueError, 'Invalid module'):
            q.compare(self.rtl, 'Top; delete *', 'unused', self.rtl, 1)

    def test_timeout_still_removes_owned_container(self):
        def run(args, **kwargs):
            if args[:2] == ['docker', 'run']:
                raise subprocess.TimeoutExpired(args, 1)
            return subprocess.CompletedProcess(args, 0)
        with patch.object(q.subprocess, 'run', side_effect=run) as call:
            with self.assertRaises(subprocess.TimeoutExpired):
                q.run_yosys('image', self.rtl, self.rtl, 'help', 1)
        first, cleanup = [c.args[0] for c in call.call_args_list]
        self.assertEqual(cleanup, ['docker', 'rm', '--force', first[first.index('--name') + 1]])

    def test_failed_proof_cannot_return_success_and_cleans_container(self):
        def run(args, **kwargs):
            return subprocess.CompletedProcess(args, 1 if args[1] == 'run' else 0,
                                               stdout='ERROR: Unproven $equiv cells')
        with patch.object(q.subprocess, 'run', side_effect=run) as call:
            with self.assertRaisesRegex(RuntimeError, 'Unproven'):
                q.run_yosys('image', self.rtl, self.rtl, 'equiv_status -assert', 1)
        self.assertEqual(call.call_args_list[-1].args[0][1:3], ['rm', '--force'])

    def test_layer_mutation_retires_otherwise_successful_proof(self):
        self.source('Top', 'module Top(input a, output y); assign y=a; endmodule')
        layer = self.rtl / 'verification/assert/Top.sv'
        layer.parent.mkdir(parents=True)
        layer.write_text('// original assertions')
        def run(image, rtl, work, commands, timeout):
            for name in ('ordinary.il', 'fast.il'):
                (work / name).write_text('diagnostic mock')
            if 'equiv_status' in commands:
                layer.write_text('// changed assertion source')
                return 0, 'Equivalence successfully proven!'
            return 0, ''
        with patch.object(q.subprocess, 'check_output', return_value='sha256:' + '0' * 64), \
             patch.object(q, 'run_yosys', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, 'assertion layers changed'):
                q.compare(self.rtl, 'Top', 'image', self.rtl, 1)
        self.assertEqual(list(self.rtl.glob('opt-*')), [])


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--image')
    args, remaining = parser.parse_known_args()
    if args.image:
        class ActualOptProofTests(OptEquivalenceTests):
            # Select only these integration cases rather than rerunning the
            # inherited CPU guards when a pinned image is explicitly supplied.
            def test_actual_sequential_enable_reset_cancel_equivalence(self):
                self.source('Owner', '''module Owner(input clock, reset, cancel, enable,
                    input [15:0] data, output reg [15:0] value);
                    always @(posedge clock) if(reset || cancel) value<=0;
                    else if(enable) value<=data; endmodule''')
                result = q.compare(self.rtl, 'Owner', args.image, self.rtl, 60)
                self.assertTrue(result['equivalent'])

            def test_actual_changed_state_transition_rejected(self):
                self.source('Golden', '''module Owner(input clock, reset, enable,
                    input [15:0] data, output reg [15:0] value);
                    always @(posedge clock) if(reset) value<=0;
                    else if(enable) value<=data; endmodule''')
                self.source('Broken', (self.rtl / 'Golden.sv').read_text().replace(
                    'value<=data', 'value<=~data'))
                (self.rtl / 'tmp').mkdir(); (self.rtl / 'home').mkdir()
                q.run_yosys(args.image, self.rtl, self.rtl,
                    'read_verilog -formal /src/Golden.sv; proc; opt; '
                    'write_rtlil /work/ordinary.il; design -reset; '
                    'read_verilog -formal /src/Broken.sv; proc; opt; '
                    'write_rtlil /work/fast.il', 60)
                with self.assertRaisesRegex(RuntimeError, 'Unproven|unproven'):
                    q.run_yosys(args.image, self.rtl, self.rtl,
                                q.equivalence_commands(), 60)

        # Inherited tests have already run in their original class.
        for name in list(vars(OptEquivalenceTests)):
            if name.startswith('test_'):
                setattr(ActualOptProofTests, name, None)
    unittest.main(argv=[__file__, *remaining])
