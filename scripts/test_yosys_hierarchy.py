#!/usr/bin/env python3
"""CPU-only configuration controls; real mapping is a separate qualification."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('qualification', Path(__file__).with_name('qualify_ppa.py'))
qualify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualify)


class HierarchyImageTest(unittest.TestCase):
    base = 'sha256:' + 'a' * 64
    derived = 'sha256:' + 'b' * 64
    env = dict(SYNTH_KEEP_MODULES='*', SYNTH_HIERARCHICAL='0', SYNTH_ARGS='-noshare',
               SYNTH_MOCK_LARGE_MEMORIES='0', SYNTH_KEEP_MOCKED_MEMORIES='0',
               SYNTH_GUT='0', SYNTH_BLACKBOXES='', SYNTH_RETIME_MODULES='')

    def build(self, directory, env=None, layers=None):
        env = self.env if env is None else env
        layers = ['["same-layer"]', '["same-layer"]'] if layers is None else layers
        def run(command, **kwargs):
            if command[:2] == ['docker', 'build']:
                Path(command[command.index('--iidfile') + 1]).write_text(self.derived)
        replies = [self.base, json.dumps([k + '=' + v for k, v in env.items()]), '\n'.join(layers)]
        with patch.object(qualify.subprocess, 'check_output', side_effect=replies), \
             patch.object(qualify.subprocess, 'run', side_effect=run) as calls:
            result = qualify.prepare_hierarchy_image('local-base', Path(directory))
            build = next(c.args[0] for c in calls.call_args_list if c.args[0][:2] == ['docker', 'build'])
            self.assertIn('BASE_IMAGE=opengpgpu-ppa-base:' + 'a' * 64, build)
            return result

    def test_profile_is_pinned_and_cannot_replace_the_tool_filesystem(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.build(directory)
            self.assertEqual(result['base_image_id'], self.base)
            self.assertEqual(result['image_id'], self.derived)
            self.assertTrue(result['tool_filesystem_layers_identical'])
            self.assertEqual(result['abstracted_dut_modules'], [])

    def test_abstracted_logic_memory_mocks_and_retiming_cannot_enter_the_profile(self):
        for key, value in [('SYNTH_BLACKBOXES', 'GpuComputeUnit'), ('SYNTH_MOCK_LARGE_MEMORIES', '1'),
                           ('SYNTH_GUT', '1'), ('SYNTH_RETIME_MODULES', 'GpuComputeUnit')]:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, 'declared hierarchy profile'):
                    self.build(directory, dict(self.env, **{key: value}))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'tool filesystem'):
                self.build(directory, layers=['["base"]', '["modified"]'])

    def test_unpinned_base_cannot_start_an_image_build(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(qualify.subprocess, 'check_output', return_value='mutable-tag'), \
             patch.object(qualify.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'pinned base'):
                qualify.prepare_hierarchy_image('local-base', Path(directory))
            run.assert_not_called()

    def test_incompatible_engine_and_route_are_rejected_before_output_creation(self):
        for options in ([], ['--synthesis-engine', 'yosys', '--stage', 'route']):
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'never-created'
                argv = ['qualify_ppa.py', 'unused', 'Top', '--output', str(output),
                        '--yosys-preserve-hierarchy', *options]
                with patch.object(qualify.sys, 'argv', argv), \
                     patch.object(qualify.sys, 'stderr', io.StringIO()), \
                     self.assertRaises(SystemExit) as error:
                    qualify.main()
                self.assertEqual(error.exception.code, 2)
                self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
