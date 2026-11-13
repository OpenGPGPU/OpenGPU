#!/usr/bin/env python3
"""CPU-only command fixture and corruption checks; no RTL model executes."""
import os
import copy
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import qualify_native_alignment_sequence as q
import test_prepare_native_owner_flashsim as owner_tests

ROOT = Path(__file__).resolve().parents[1]


class SequenceFixtureTests(unittest.TestCase):
    def test_long_macos_socket_path_is_rejected_before_starting_jvm(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='alignment-sequence-long-', dir=parent) as temporary:
            out = Path(temporary)
            runner = q.Runner(out, False, ROOT, api_capacity=True, two_cu_vga=True, alignment_v2=True)
            with patch.object(q, 'temporary_root', return_value=out), \
                    patch.object(q.sys, 'platform', 'darwin'), patch.object(q.subprocess, 'Popen') as start:
                with self.assertRaisesRegex(RuntimeError, 'Unix socket limit'):
                    runner.execute('test', ['sbt', 'about'], out / 'unused.log')
                start.assert_not_called()
            self.assertEqual(list(out.iterdir()), [])

    def test_child_temporary_files_stay_inside_gpu_and_are_removed_on_failure(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='alignment-sequence-child-', dir=parent) as temporary:
            out = Path(temporary)
            runner = q.Runner(out, False, ROOT, api_capacity=True, two_cu_vga=True, alignment_v2=True)
            previous = os.environ.get('TMPDIR')
            for code in (0, 1):
                log = out / 'child.log'
                command = [sys.executable, '-c', 'import tempfile,pathlib,sys; '
                           'p=pathlib.Path(tempfile.gettempdir()); '
                           '(p/"owned-input").write_text("temporary"); print(p); sys.exit(int(sys.argv[1]))', str(code)]
                if code:
                    with self.assertRaises(RuntimeError): runner.execute('test', command, log)
                else:
                    runner.execute('test', command, log)
                child = Path(log.read_text().strip())
                self.assertEqual(child.parent, q.temporary_root())
                self.assertFalse(child.exists())
                self.assertEqual(os.environ.get('TMPDIR'), previous)

    def test_complete_ordered_execution_gate(self):
        rows = [dict(scene=name, epoch=epoch, computeCycles=100, nativeCycles=200,
                     profile='two-cu-vga60-v2', resourceDiscoveryWords=72,
                     resetBetweenCommands=False, independentImagesRequired=True, fpsQualified=False)
                for name in q.common.SCENES for epoch in range(2)]
        q.validate_rows(rows)
        for changed in (rows[:-1], rows[::-1], rows + rows[:1]):
            with self.assertRaises(RuntimeError): q.validate_rows(changed)
        for key, value in (('computeCycles', 0), ('nativeCycles', True), ('epoch', False),
                           ('resetBetweenCommands', True), ('independentImagesRequired', False),
                           ('fpsQualified', True)):
            changed = copy.deepcopy(rows); changed[0][key] = value
            with self.assertRaises(RuntimeError): q.validate_rows(changed)
        owner_rows = copy.deepcopy(rows)
        for row in owner_rows:
            row.update(profile=q.owner.PROFILE, resourceDiscoveryWords=96)
        q.validate_rows(owner_rows, True)
        for selected, wrong in ((False, owner_rows), (True, rows)):
            with self.assertRaises(RuntimeError): q.validate_rows(wrong, selected)
        for key, value in (('resourceDiscoveryWords', True), ('resourceDiscoveryWords', 72),
                           ('profile', 'two-cu-vga60-v2')):
            changed=copy.deepcopy(owner_rows); changed[0][key]=value
            with self.assertRaises(RuntimeError): q.validate_rows(changed, True)

    def test_owner_fixtures_cannot_authorize_rtl_or_mix_changed_inputs(self):
        parent=ROOT/'.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='owner-sequence-inputs-',dir=parent) as directory:
            out=Path(directory); (out/'fixtures').mkdir()
            fixture=out/'fixtures/suite.txt'; fixture.write_text('\n'.join(q.common.SCENES)+'\n')
            for name in q.common.SCENES:
                for suffix in ('.memory','.commands'):
                    (out/'fixtures'/(name+suffix)).write_text('CPU guard placeholder; no GPU executes')
            manifest=owner_tests.OwnerInputTests().manifest()
            manifest.update(rtlEmitted=False,workloadProduct=False,workloadWidth=32,
                            workloadHeight=32,scenes=q.common.SCENES)
            path=out/'owner-fixture-manifest.json'; path.write_text(q.json.dumps(manifest))
            runner=q.OwnerRunner(out,False,ROOT,api_capacity=True,two_cu_vga=True,alignment_v2=True)
            with self.assertRaises(RuntimeError): q.owner.check_manifest(manifest)
            with patch.object(q.owner,'prepared',return_value={'real-owner-rtl':'unchanged'}) as guard:
                q.common.write_json(out/'sequence-fixtures.json',runner.fixture_inputs())
                self.assertEqual(runner.prepared(),guard.return_value)
                fixture.write_text('changed')
                with self.assertRaises(RuntimeError): runner.prepared()
                fixture.write_text('\n'.join(q.common.SCENES)+'\n')
                missing=out/'fixtures'/(q.common.SCENES[0]+'.commands')
                contents=missing.read_bytes(); missing.unlink()
                with self.assertRaises(RuntimeError): runner.prepared()
                missing.write_bytes(contents)
                for key,value in (('workloadProduct',True),('workloadWidth',640),
                                  ('scenes',q.common.SCENES[::-1]),('rtlEmitted',True)):
                    changed=copy.deepcopy(manifest); changed[key]=value; path.write_text(q.json.dumps(changed))
                    with self.assertRaises(RuntimeError): runner.prepared()
                path.write_text(q.json.dumps(manifest))
                with patch.object(runner,'driver_sources',return_value=[]):
                    with self.assertRaises(RuntimeError): runner.prepared()
                with patch.object(runner,'execute') as execute:
                    with self.assertRaises(RuntimeError): runner.prepare()
                    execute.assert_not_called()
            with patch.object(q.owner,'prepared',side_effect=RuntimeError('stale RTL')), \
                    patch.object(runner,'execute') as execute:
                with self.assertRaisesRegex(RuntimeError,'stale RTL'): runner.prepare()
                execute.assert_not_called()

    def test_independent_verification_requires_each_image_and_final_marker(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        lines = [f'NATIVE_ALIGNMENT_SEQUENCE_IMAGE scene={name} epoch={epoch} '
                 'exactImagesComputeAndReadOnlyGuards=true'
                 for name in q.common.SCENES for epoch in range(2)] + [q.MARKER]
        with tempfile.TemporaryDirectory(prefix='alignment-sequence-markers-', dir=parent) as temporary:
            log = Path(temporary) / 'verify.log'
            log.write_text('\n'.join(lines) + '\n'); q.verify_marker(log)
            for changed in (lines[:-1], lines[1:], lines[::-1], lines + [q.MARKER]):
                log.write_text('\n'.join(changed) + '\n')
                with self.assertRaises(RuntimeError): q.verify_marker(log)

    def test_compute_epochs_and_read_only_guards(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='alignment-sequence-cpu-', dir=parent) as temporary:
            binary = Path(temporary) / 'fixture'
            subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + [
                '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror',
                '-I', str(ROOT / 'scripts/test_stubs/native_host'), '-I', str(ROOT / 'scripts'),
                str(ROOT / 'scripts/test_native_alignment_sequence.cpp'), '-o', str(binary)], check=True)
            result = subprocess.run([str(binary)], check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout, 'alignment sequence CPU fixture/guard checks: PASS\n')


if __name__ == '__main__':
    unittest.main()
