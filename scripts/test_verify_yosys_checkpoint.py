#!/usr/bin/env python3
"""Bounded negative controls for saved Yosys checkpoint provenance."""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import verify_yosys_checkpoint as verify


class CheckpointProvenanceTest(unittest.TestCase):
    def profile(self):
        image = 'sha256:' + 'a' * 64
        base = 'sha256:' + 'b' * 64
        profile = dict(image_id=image, base_image_id=base, orfs_environment=dict(verify.ENVIRONMENT),
                       tool_filesystem_layers_identical=True, abstracted_dut_modules=[],
                       memory_mocks=False, retiming=False)
        images = [dict(Id=image, RootFS={'Layers': ['same']},
                       Config={'Env': [k + '=' + v for k, v in verify.ENVIRONMENT.items()]}),
                  dict(Id=base, RootFS={'Layers': ['same']}, Config={'Env': []})]
        return profile, images

    def test_image_profile_rejects_mocks_changed_layers_and_unpinned_images(self):
        profile, images = self.profile()
        inspect = lambda command, text: json.dumps(images)
        self.assertEqual(verify.verify_image(profile, inspect), profile['image_id'])
        for mutation in ('mock', 'retime', 'blackbox', 'layers', 'environment', 'duplicate', 'checkpoint', 'abc', 'unpinned'):
            with self.subTest(mutation=mutation):
                p, i = copy.deepcopy(profile), copy.deepcopy(images)
                if mutation == 'mock': p['memory_mocks'] = True
                if mutation == 'retime': p['retiming'] = True
                if mutation == 'blackbox': p['abstracted_dut_modules'] = ['Child']
                if mutation == 'layers': i[0]['RootFS']['Layers'] = ['different']
                if mutation == 'environment': i[0]['Config']['Env'].append('SYNTH_GUT=1')
                if mutation == 'duplicate': i[0]['Config']['Env'].append('SYNTH_GUT=0')
                if mutation == 'checkpoint': i[0]['Config']['Env'].append('SYNTH_CHECKPOINT=/tmp/foreign.rtlil')
                if mutation == 'abc': i[0]['Config']['Env'].append('ABC_CLOCK_PERIOD_IN_PS=1')
                if mutation == 'unpinned': p['image_id'] = 'latest'
                with self.assertRaises(ValueError):
                    verify.verify_image(p, lambda command, text: json.dumps(i))

    def test_canonical_log_binds_bytes_and_rejects_unexpected_warnings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkpoint = root / 'input.rtlil'
            log = root / 'canonical.log'
            checkpoint.write_bytes(b'canonical checkpoint')
            prefix = hashlib.sha1(checkpoint.read_bytes()).hexdigest()[:20]
            row = f'1_1_yosys_canonicalize    .rtlil   2  100  {prefix}\n'
            good = ("Warning: Ignoring module Leaf because it contains processes (run 'proc' command first).\n"
                    'End of script.\n' + row)
            log.write_text(good)
            verify.verify_canonical_log(log, checkpoint)
            for invalid in (good.replace('End of script.', 'incomplete'), good + row,
                            good + 'Warning: unresolved cell\n', good + 'error: failed cell\n'):
                log.write_text(invalid)
                with self.assertRaises(ValueError):
                    verify.verify_canonical_log(log, checkpoint)
            log.write_text(good)
            checkpoint.write_bytes(b'changed checkpoint')
            with self.assertRaisesRegex(ValueError, 'differs'):
                verify.verify_canonical_log(log, checkpoint)

    def inputs(self):
        inputs = dict(reg_code='', module_name='Top', clock_port='clock', clock_period=1000.,
                      io_delay_percent=.25, io_false_path_ports=None, cell_vt='SLVT', corner='TC',
                      synthesis_engine='yosys', synthesis_only=True, sv_frontend='native',
                      enable_retiming=False, generate_gds=False, core_utilization=25, place_density=.6,
                      setup_slack_margin=0., abc_clock_period_ps=None, timing_effort='explore',
                      cache=False, clean=False)
        params = {k: v for k, v in inputs.items()
                  if k not in {'reg_code', 'module_name', 'synthesis_only', 'cache', 'clean'}}
        params.update(clock_port_auto=False, swap_arithmetic_operators=False, max_fanout=None,
                      high_fanout_nets=None, post_floorplan_tcl=None)
        return {'scope': 'synthesis estimate', 'dirty': False, 'inputs': inputs}, {'module_name': 'Top', 'parameters': params}

    def test_false_paths_retiming_and_changed_constraints_are_rejected(self):
        manifest, flow = self.inputs()
        verify.check_inputs(manifest, flow)
        for mutation in ('dirty', 'falsepath', 'flowfalsepath', 'retime', 'arithmetic', 'cache',
                         'clock', 'period', 'delay', 'module', 'gds'):
            with self.subTest(mutation=mutation):
                m, f = copy.deepcopy(manifest), copy.deepcopy(flow)
                if mutation == 'dirty': m['dirty'] = True
                if mutation == 'falsepath': m['inputs']['io_false_path_ports'] = ['data']
                if mutation == 'flowfalsepath': f['parameters']['io_false_path_ports'] = ['data']
                if mutation == 'retime': m['inputs']['enable_retiming'] = True
                if mutation == 'arithmetic': f['parameters']['swap_arithmetic_operators'] = True
                if mutation == 'cache': m['inputs']['cache'] = True
                if mutation == 'clock': f['parameters']['clock_port'] = 'other'
                if mutation == 'period': m['inputs']['clock_period'] = float('nan')
                if mutation == 'delay': m['inputs']['io_delay_percent'] = .75
                if mutation == 'module': m['inputs']['module_name'] = '../other'
                if mutation == 'gds': f['parameters']['generate_gds'] = True
                with self.assertRaises(ValueError):
                    verify.check_inputs(m, f)

    def test_source_inventory_and_clean_checkout_are_bound_to_git(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', '-c', 'init.defaultBranch=fixture-test', 'init', '-q', str(root)], check=True)
            names = ['src/main/A.scala', 'build.sbt', 'scripts/qualify_ppa.py',
                     'scripts/extract_emitted_rtl.py', 'scripts/report_ppa_sram_inventory.py',
                     'timing/asap7/ram.tcl']
            for name in names:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name + '\n')
            subprocess.run(['git', 'add', *names], cwd=root, check=True)
            subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.com',
                            'commit', '-q', '-m', 'fixture',
                            '-m', 'Co-authored-by: Codex (GPT-6) <codex@openai.com>'], cwd=root, check=True)
            head = verify.git(root, 'rev-parse', 'HEAD')
            hashes = {name: verify.sha(root / name) for name in names}
            self.assertEqual(verify.clean_checkout(root, head), root.resolve())
            verify.verify_sources(root, hashes)
            (root / names[0]).write_text('changed\n')
            with self.assertRaises(ValueError): verify.clean_checkout(root, head)
            with self.assertRaises(ValueError): verify.verify_sources(root, hashes)
            subprocess.run(['git', 'checkout', '--', names[0]], cwd=root, check=True)
            (root / 'src/main/extra.scala').write_text('unpublished\n')
            with self.assertRaisesRegex(ValueError, 'inventory'): verify.verify_sources(root, hashes)
            with self.assertRaises(ValueError): verify.within(root, '../outside')

    def test_extra_hdl_and_macro_collateral_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'Top.sv').write_text('module Top(); endmodule\n')
            (root / 'notes.txt').write_text('ignored by the HDL wildcard\n')
            verify.exact_files(root, {'Top.sv'}, {'.v', '.sv'})
            (root / 'Extra.v').write_text('module Extra(); endmodule\n')
            with self.assertRaisesRegex(ValueError, 'Unexpected input files'):
                verify.exact_files(root, {'Top.sv'}, {'.v', '.sv'})
            (root / 'Extra.v').unlink()
            (root / 'macro.lib').write_text('cell data\n')
            with self.assertRaisesRegex(ValueError, 'Unexpected input files'):
                verify.exact_files(root, {'Top.sv'})
            (root / 'macro.lib').unlink()
            (root / 'link.sv').symlink_to(root / 'Top.sv')
            with self.assertRaisesRegex(ValueError, 'Unexpected input files'):
                verify.exact_files(root, {'Top.sv', 'link.sv'}, {'.v', '.sv'})
            (root / 'missing-dir').symlink_to(root / 'absent', target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'missing'):
                verify.exact_files(root / 'missing-dir', set())

    def test_recorded_macro_libraries_and_copies_must_match(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, design = root / 'source', root / 'design'
            (source / 'generated').mkdir(parents=True)
            (design / 'macros').mkdir(parents=True)
            names = ['generated/ram.lib', 'generated/ram.lef']
            for name in names:
                (source / name).write_text('original ' + name + '\n')
                shutil.copy2(source / name, design / 'macros' / Path(name).name)
            hashes = {name: verify.sha(source / name) for name in names}
            inputs = {'macro_libs': ['/old/ram.lib'], 'macro_lefs': ['/old/ram.lef']}
            libs, lefs = verify.verify_macro_collateral(source, design, hashes, inputs)
            self.assertEqual(([p.name for p in libs], [p.name for p in lefs]), (['ram.lib'], ['ram.lef']))
            for path in (source / names[0], design / 'macros/ram.lef'):
                original = path.read_text()
                path.write_text('changed\n')
                with self.assertRaisesRegex(ValueError, 'Macro collateral differs'):
                    verify.verify_macro_collateral(source, design, hashes, inputs)
                path.write_text(original)
            with self.assertRaisesRegex(ValueError, 'Requested macro collateral differs'):
                verify.verify_macro_collateral(source, design, hashes,
                                              {'macro_libs': ['/old/other.lib'], 'macro_lefs': ['/old/ram.lef']})
            duplicate = dict(hashes)
            duplicate['other/ram.lib'] = hashes['generated/ram.lib']
            with self.assertRaisesRegex(ValueError, 'Duplicate macro collateral name'):
                verify.verify_macro_collateral(source, design, duplicate, inputs)


if __name__ == '__main__':
    unittest.main()
