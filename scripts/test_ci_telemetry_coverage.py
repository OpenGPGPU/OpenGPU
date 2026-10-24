#!/usr/bin/env python3
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

from ci_telemetry_coverage import (BINNED, TYPED, WITNESSES, GRAPHICS, TESTS,
                                   SYSTEM, OLD_COUNTER, NEW_COUNTER,
                                   counter_source_only, names_only, narrow,
                                   passive_forwarding)

BEFORE = 'class Pipeline {\n  val memory = service()\n}\n'
AFTER = ('class Pipeline {\n    val fragmentEvents=Output(new FragmentPerformanceEvents)\n'
         '  val memory = service()\n  io.fragmentEvents:=image.io.fragmentEvents\n}\n')
TEST = 'it should "render" in {\n  assert(image == expected)\n}\n'


class TelemetryCoverageTest(unittest.TestCase):
    def test_only_exact_output_additions_qualify(self):
        self.assertTrue(passive_forwarding(BEFORE, AFTER))
        for changed in (AFTER.replace('service()', 'otherService()'),
                        AFTER.replace('Output(', 'Input('),
                        AFTER + 'reset := io.cancel\n',
                        AFTER.replace('image.io.fragmentEvents', '0.U')):
            self.assertFalse(passive_forwarding(BEFORE, changed))
        self.assertFalse(passive_forwarding(AFTER, AFTER))

    def test_only_exact_counter_source_selection_qualifies(self):
        before, after = '  ' + OLD_COUNTER + '\n  count(events)\n', '  ' + NEW_COUNTER + '\n  count(events)\n'
        self.assertTrue(counter_source_only(before, after))
        self.assertFalse(counter_source_only(before, after.replace('count(events)', 'count(0.U)')))
        self.assertFalse(counter_source_only(before, after.replace('getOrElse(host.io.performance.fragment)', 'getOrElse(0.U)')))

    def test_names_cannot_hide_body_fixture_or_registration_changes(self):
        renamed = TEST.replace('"render"', 's"render local=$local oneSlot=false"')
        self.assertTrue(names_only(TEST, renamed))
        for changed in (renamed.replace('expected', 'other'), renamed + 'val fixture = 2\n', renamed + TEST):
            self.assertFalse(names_only(TEST, changed))

    def run_selection(self, sources, selection, catalog, exact, broad=()):
        def show(args, **kwargs):
            if args[1] == 'diff':
                return '\n'.join(key.split(':', 1)[1] for key in sources if key.startswith('HEAD:'))
            try:
                return sources[args[-1]]
            except KeyError:
                raise subprocess.CalledProcessError(128, args)
        with patch('ci_telemetry_coverage.subprocess.check_output', side_effect=show), \
             patch('ci_telemetry_coverage.changed_suites', return_value=broad):
            return narrow(selection, catalog, exact, Path('.'), 'base')

    def fixture(self, module='TypedTileImagePipeline'):
        suite = 'opengpu.graphics.' + module + 'Spec'
        sources = {f'base:{GRAPHICS}{module}.scala': BEFORE,
                   f'HEAD:{GRAPHICS}{module}.scala': AFTER,
                   f'base:{TESTS}{module}Spec.scala': TEST,
                   f'HEAD:{TESTS}{module}Spec.scala': TEST}
        catalog = {s: [prefix + local for local in ('false', 'true')]
                   for s, prefix in WITNESSES.items()}
        selection = [suite, TYPED, BINNED, 'opengpu.graphics.FragmentShaderStageSpec']
        return suite, sources, catalog, selection

    def test_substitution_retains_both_localities_and_fragment_suite(self):
        suite, sources, catalog, selection = self.fixture()
        self.assertEqual(self.run_selection(sources, selection, catalog, {TYPED, BINNED}), selection[1:])

    def test_missing_witness_exactness_or_locality_falls_back(self):
        suite, sources, catalog, selection = self.fixture()
        for exact in (set(), {TYPED}, {BINNED}):
            self.assertEqual(self.run_selection(sources, selection, catalog, exact), selection)
        catalog[TYPED].pop()
        self.assertEqual(self.run_selection(sources, selection, catalog, {TYPED, BINNED}), selection)

    def test_behavior_fixture_and_missing_source_fall_back(self):
        suite, sources, catalog, selection = self.fixture()
        for key, change in ((f'HEAD:{GRAPHICS}TypedTileImagePipeline.scala', AFTER.replace('service()', 'other()')),
                            (f'HEAD:{TESTS}TypedTileImagePipelineSpec.scala', TEST + 'val fixture = 2\n')):
            changed = dict(sources, **{key: change})
            self.assertEqual(self.run_selection(changed, selection, catalog, {TYPED, BINNED}), selection)
        sources.pop(f'base:{GRAPHICS}TypedTileImagePipeline.scala')
        self.assertEqual(self.run_selection(sources, selection, catalog, {TYPED, BINNED}), selection)

    def test_replay_names_only_but_not_body_edits_allow_substitution(self):
        suite, sources, catalog, selection = self.fixture('TypedReplayTileImagePipeline')
        key = f'HEAD:{TESTS}TypedReplayTileImagePipelineSpec.scala'
        sources[key] = TEST.replace('"render"', '"render local=true oneSlot=false"')
        self.assertEqual(self.run_selection(sources, selection, catalog, {BINNED}), selection[1:])
        sources[key] = sources[key].replace('expected', 'other')
        self.assertEqual(self.run_selection(sources, selection, catalog, {BINNED}), selection)

    def test_independent_shared_fixture_selection_is_preserved(self):
        suite, sources, catalog, selection = self.fixture()
        self.assertEqual(self.run_selection(sources, selection, catalog, {TYPED, BINNED}, broad=[suite]), selection)

    def test_counter_substitution_keeps_generic_fallback_and_fixture_guard(self):
        _, sources, catalog, selection = self.fixture()
        system = ['opengpu.system.GpuHostSystemAxiSpec', 'opengpu.system.BufferedCommandGpuHostSystemAxiSpec']
        sources.update({f'base:{SYSTEM}': OLD_COUNTER, f'HEAD:{SYSTEM}': NEW_COUNTER,
                        'base:src/test/scala/opengpu/system/GpuHostSystemAxiSpec.scala': TEST,
                        'HEAD:src/test/scala/opengpu/system/GpuHostSystemAxiSpec.scala': TEST})
        selection = system + [TYPED, BINNED]
        self.assertEqual(self.run_selection(sources, selection, catalog, {TYPED, BINNED}), selection[2:])
        sources['HEAD:src/test/scala/opengpu/system/GpuHostSystemAxiSpec.scala'] = TEST + 'val fixture = 2\n'
        self.assertEqual(self.run_selection(sources, selection, catalog, {TYPED, BINNED}), selection)


if __name__ == '__main__':
    unittest.main()
