#!/usr/bin/env python3
"""Mutation checks for the Verilator executor boundary, separate from RTL qualification."""
import copy
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import prepare_owner_executor as owner
import prepare_owner_verilator_executor as verilator


class VerilatorExecutorInputsTest(unittest.TestCase):
    def setUp(self):
        original = owner.ROOT
        names = set(verilator.CHECKERS)
        for kind in verilator.KINDS:
            names.update(owner.driver_files(kind, owner.SAMPLE_PROFILE))
            names.update(owner.checker_files(kind))
        names.update(verilator.DEPENDENCIES)
        self.temporary = tempfile.TemporaryDirectory(prefix='verilator-input-test-', dir=original / '.artifacts')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in names:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original / name, target)
        self.out = self.root / '.artifacts/owner'
        self.model = self.out / 'verilator-dut/model'
        self.model.mkdir(parents=True)
        self.kit = self.root / 'compiler-kit'
        (self.kit / 'include/vltstd').mkdir(parents=True)
        (self.kit / 'include/verilated.h').write_text('// Fixture runtime header\n')
        (self.model / 'VGpuHostSystemAxi.h').write_text('// Fixture model header\n')
        for name in ('preparation.json', 'owner-manifest.json',
                     'verilator-dut/generation.json', 'verilator-dut/build.json'):
            (self.out / name).write_text('{}\n')
        compiler = verilator.native.tool('c++')
        built = dict(context=dict(compiler=compiler, sdk=None), runtime_objects=['verilated.o'])
        generated = dict(context=dict(verilator_root=str(self.kit)))
        # These mocks isolate record mutations. They do not qualify this tiny
        # fixture as a GPU model or substitute for the explicit full RTL run.
        def patched(patcher):
            value = patcher.start()
            self.addCleanup(patcher.stop)
            return value
        self.native_check = patched(patch.object(verilator.native, 'checked_build', return_value=built))
        patched(patch.object(verilator.generator, 'checked_generation', return_value=generated))
        patched(patch.object(owner, 'ROOT', self.root))
        patched(patch.object(verilator, 'ROOT', self.root))

    def fixture(self, kind='native-sample'):
        bound = verilator.context(self.out, kind)
        target = self.out / 'executors' / kind
        target.mkdir(parents=True, exist_ok=True)
        subprocess.run(['c++', '-x', 'c++', '-', '-o', str(target / 'executor')],
                       input='int main() { return 0; }\n', text=True, check=True)
        self.header = self.root / 'compiler-kit/include/consumed-system-header.h'
        self.header.write_text('// Actual dependency fixture\n')
        dependencies = {str(path.resolve()): owner.common.sha(path) for path in
            (Path(bound['driver']), self.model / 'VGpuHostSystemAxi.h',
             self.kit / 'include/verilated.h', self.header)}
        compile_command, link_command = verilator.commands(bound, target)
        built = dict(schema='opengpu-owner-verilator-executor-build-v1', context=bound,
            compile_command=compile_command, link_command=link_command, dependencies=dependencies,
            executable_sha256=owner.common.sha(target / 'executor'))
        record = dict(schema='opengpu-owner-executor-v1', version=1, backend='verilator', kind=kind,
            **owner.profile_contract(owner.SAMPLE_PROFILE, kind), executorBuildVerified=True,
            sourceQualificationRequired=True, gpuExecution=False, clockQualified=False, fpsQualified=False,
            source_root=str(self.root), owner_output=str(self.out), source_sha256=bound['source_sha256'],
            model=bound['model'], compiler=bound['compiler'], executable_sha256=built['executable_sha256'])
        self.path = target / 'qualification.json'
        self.save(record, built)
        owner.verify_qualification(self.path, kind=kind)
        return record, built

    def save(self, record, built):
        owner.common.write_json(self.path.parent / 'builder.json', built)
        record['builder_sha256'] = owner.common.sha(self.path.parent / 'builder.json')
        owner.common.write_json(self.path, record)

    def test_original_four_kinds_keep_their_distinct_wrapper_and_model(self):
        for kind in verilator.KINDS:
            with self.subTest(kind=kind):
                record, built = self.fixture(kind)
                self.assertEqual(built['context']['driver'], str(self.root / owner.driver_files(kind, owner.SAMPLE_PROFILE)[0]))
                self.assertFalse(any('flashsim_native' in name for name in record['source_sha256']))
                for other in verilator.KINDS:
                    if other != kind:
                        with self.assertRaises(RuntimeError):
                            owner.verify_qualification(self.path, kind=other)

    def test_relabelled_scope_unknown_backend_and_stale_model_are_rejected(self):
        record, built = self.fixture()
        for key, value in (('backend', 'unknown'), ('profile', owner.owner.PROFILE),
                           ('fullResourceQualification', True), ('gpuExecution', True)):
            bad = copy.deepcopy(record); bad[key] = value
            self.save(bad, built)
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                owner.verify_qualification(self.path, kind='native-sample')
        self.save(record, built)
        (self.out / 'verilator-dut/build.json').write_text('{"changed":true}\n')
        with self.assertRaises(RuntimeError):
            owner.verify_qualification(self.path, kind='native-sample')

    def test_rehashed_builder_cannot_change_compile_link_or_omit_model_header(self):
        record, built = self.fixture()
        for field in ('compile_command', 'link_command'):
            bad = copy.deepcopy(built); bad[field].append('-DUNQUALIFIED=1')
            self.save(record, bad)
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                owner.verify_qualification(self.path, kind='native-sample')
        bad = copy.deepcopy(built)
        del bad['dependencies'][str(self.model / 'VGpuHostSystemAxi.h')]
        self.save(record, bad)
        with self.assertRaises(RuntimeError):
            owner.verify_qualification(self.path, kind='native-sample')

    def test_changed_original_checker_binary_and_consumed_system_header_are_rejected(self):
        record, built = self.fixture()
        for path in (self.root / 'scripts/native_sample_owner_oracle.py', self.header,
                     self.path.parent / 'executor'):
            previous = path.read_bytes()
            path.write_bytes(previous + b'\n// Changed input\n')
            with self.subTest(path=path.name), self.assertRaises(RuntimeError):
                owner.verify_qualification(self.path, kind='native-sample')
            path.write_bytes(previous)
        self.native_check.side_effect = RuntimeError('Original native model is no longer valid')
        with self.assertRaisesRegex(RuntimeError, 'native model'):
            owner.verify_qualification(self.path, kind='native-sample')

    def test_undeclared_include_macro_adapter_and_standard_shadow_fail_before_compilation(self):
        source = self.root / 'scripts/native_sample_owner_driver.cpp'
        original = source.read_bytes()
        extra = source.parent / 'unregistered-header.hpp'
        extra.write_text('// Unregistered GPU dependency\n')
        adapter = source.parent / 'flashsim_native/VGpuHostSystemAxi.h'
        adapter.parent.mkdir()
        adapter.write_text('// Adapter substitution must be rejected even when present\n')
        for directive in ('#include "unregistered-header.hpp"', '#include UNKNOWN_INCLUDE',
                          '#include "flashsim_native/VGpuHostSystemAxi.h"'):
            source.write_bytes(original + ('\n' + directive + '\n').encode())
            with self.subTest(directive=directive), self.assertRaises(RuntimeError):
                verilator.context(self.out, 'native-sample')
        source.write_bytes(original)
        (self.model / 'cstdint').write_text('// Shadow of a standard header\n')
        with self.assertRaises(RuntimeError):
            verilator.context(self.out, 'native-sample')

    def test_duplicate_qualification_keys_and_aliased_sources_are_rejected(self):
        record, built = self.fixture()
        previous = self.path.read_text()
        self.path.write_text('{"backend":"verilator",' + previous.lstrip()[1:])
        with self.assertRaises(ValueError):
            owner.verify_qualification(self.path, kind='native-sample')
        self.save(record, built)
        source = self.root / 'scripts/native_sample_owner_runtime.hpp'
        alias = self.root / 'aliased-runtime.hpp'
        alias.write_bytes(source.read_bytes())
        source.unlink(); source.symlink_to(alias)
        with self.assertRaises(RuntimeError):
            owner.verify_qualification(self.path, kind='native-sample')


if __name__ == '__main__':
    unittest.main()
