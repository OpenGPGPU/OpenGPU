#!/usr/bin/env python3
"""CPU negative tests of fixed driver entry points and source hash boundaries.

The host stub never renders or supplies GPU success. Malformed command inputs
must fail before memory loading; synthetic build records test hash guards only.
"""
import json
import os
from pathlib import Path
import shlex
import shutil
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build_native_flashsim as builder
import qualify_native_layered_product as full
import qualify_native_sample_layered_product as sample
from owner_resource_contract import FULL_WORDS, SAMPLE_WORDS

ROOT = Path(__file__).resolve().parents[1]
SHARED = 'scripts/native_layered_product_execution.hpp'


def commands(magic, words):
    data = bytearray(struct.pack('<8sIIIIQI', magic.encode('ascii'), 640, 480, 1, 1,
                                 1000000000, len(words)))
    for word in words:
        data += struct.pack('<II', word['address'], word['value'])
    writes = [(0xc, 1), (0xc4, 1), (0xc8, 7), (0xf4, 0x1000),
              (0xf8, 0x1200), (0xfc, 512), (0x100, 0), (0x11c, 1)]
    data += struct.pack('<I', len(writes))
    for address, value in writes:
        data += struct.pack('<II', address, value)
    data += struct.pack('<IIII', 13, 0x124, 0xffff, 0x8701)
    for i in range(12):
        data += struct.pack('<III', 0x180 + 4*i, 0xffffffff if i in (0, 1, 11) else 0,
                            0x474e5801 if i == 0 else 0)
    data += struct.pack('<IIIII', 2, 0x130, 1, 0xc, 3)
    return data


class SampleLayeredDriverGuardTests(unittest.TestCase):
    def temporary(self):
        parent = ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        return tempfile.TemporaryDirectory(prefix='sample-driver-guards-', dir=parent)

    def test_actual_fixed_wrapper_binaries_reject_opposite_and_mixed_containers(self):
        with self.temporary() as temporary:
            directory = Path(temporary)
            compiler = shlex.split(os.environ.get('CXX', 'c++'))
            flags = ['-std=c++17', '-Wall', '-Wextra', '-Werror', '-pedantic']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            environment = dict(os.environ, TMPDIR=temporary)
            binaries = {}
            for name, module in (('full', full), ('sample', sample)):
                runner = module.Runner(directory, False, directory, api_capacity=True, two_cu_vga=True)
                binary = directory / name
                subprocess.run([*compiler, *flags, '-I', str(ROOT / 'scripts/test_stubs/native_host'),
                                str(runner.driver_path()), '-o', str(binary)], check=True, env=environment)
                binaries[name] = binary
            cases = [('full', 'OGLAY002', SAMPLE_WORDS, 'unknown native fixture format'),
                     ('sample', 'OGLAY001', FULL_WORDS, 'unknown native fixture format'),
                     ('full', 'OGLAY001', SAMPLE_WORDS, 'exact complete discovery table'),
                     ('sample', 'OGLAY002', FULL_WORDS, 'exact complete discovery table')]
            for index, (name, magic, words, message) in enumerate(cases):
                fixtures = directory / f'fixtures-{index}'
                fixtures.mkdir()
                (fixtures / 'suite.txt').write_text('\n'.join(full.common.SCENES) + '\n')
                (fixtures / 'desktop-composition.commands').write_bytes(commands(magic, words))
                # No .memory exists: a wrong fixed wrapper would pass the
                # parser and fail later with a different memory-load error.
                result = subprocess.run([str(binaries[name]), str(fixtures), str(directory / f'out-{index}')],
                    capture_output=True, text=True, env=environment, timeout=10)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn(message, result.stderr)
                self.assertNotIn('cannot open sparse memory', result.stderr)
                self.assertEqual(result.stdout, '')

    def test_complete_local_include_graph_and_fixture_hash_cover_shared_header(self):
        with self.temporary() as temporary:
            directory = Path(temporary)
            dut = directory / 'dut'
            dut.mkdir()
            header = dut / 'dut.h'
            header.write_text('// CPU include-graph sentinel; not a DUT\n')
            mirror = directory / 'source'
            for module in (full, sample):
                runner = module.Runner(directory, False, directory, api_capacity=True, two_cu_vga=True)
                dependencies = [runner.driver_path(), builder.ADAPTER / 'VGpuHostSystemAxi.h',
                    builder.ADAPTER / 'verilated.h', *[ROOT / ('scripts/' + name) for name in
                    ('native_host_simulation.hpp', 'native_execution_progress.hpp',
                     'native_frame_axi.hpp', 'native_frame_fixture.hpp')], *runner.extra_driver_dependencies()]
                builder.validate_driver_includes(dependencies, header, dut)
                self.assertIn(SHARED, runner.driver_sources())
                self.assertIn(ROOT / SHARED, runner.extra_driver_dependencies())
                with self.assertRaisesRegex(ValueError, 'unsupported or shadowed driver include'):
                    builder.validate_driver_includes([p for p in dependencies if p != ROOT / SHARED], header, dut)
                for name in runner.driver_sources():
                    target = mirror / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / name, target)
                fixtures = directory / 'fixtures'
                fixtures.mkdir(exist_ok=True)
                (fixtures / 'suite.txt').write_text('\n'.join(full.common.SCENES) + '\n')
                for scene in full.common.SCENES:
                    for suffix in ('.commands', '.memory'):
                        (fixtures / (scene + suffix)).write_bytes(b'CPU hash sentinel only')
                (directory / runner.contract.fixture_manifest).write_text('CPU hash sentinel only')
                with patch.object(full, 'ROOT', mirror):
                    before = runner.fixture_inputs()
                    full.common.write_json(directory / runner.contract.fixture_record, before)
                    shared = mirror / SHARED
                    shared.write_bytes(shared.read_bytes() + b'\n// CPU hash mutation\n')
                    after = runner.fixture_inputs()
                    with patch.object(runner, 'full_owner', return_value={}), \
                            patch.object(runner, 'fixture_manifest'):
                        with self.assertRaisesRegex(RuntimeError, 'fixtures or driver changed'):
                            runner.prepared()
                self.assertNotEqual(before['driver_sha256'][SHARED], after['driver_sha256'][SHARED])
                self.assertEqual(before['fixture_sha256'], after['fixture_sha256'])

    def test_build_guards_reject_other_wrapper_and_mutated_shared_header(self):
        with self.temporary() as temporary:
            directory = Path(temporary)
            mirror = directory / 'source'
            runners = [module.Runner(directory, False, directory, api_capacity=True, two_cu_vga=True)
                       for module in (full, sample)]
            for runner in runners:
                runner.driver_root = mirror
                runner.hierarchy_profile = 'flat'  # CPU hash record has no hierarchy model.
                for name in runner.driver_sources():
                    target = mirror / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / name, target)
            (directory / 'native_product_driver').write_bytes(b'CPU build guard sentinel, not an executable')
            generation = directory / 'flashsim-dut/generation.json'
            builder_record = directory / 'flashsim-build/native-flashsim-build.json'
            for path in (generation, builder_record):
                path.parent.mkdir(exist_ok=True)
                path.write_text('{"cpuGuardOnly":true}')
            prepared = dict(rtl_sha256={name + '.sv': 'cpu-hash-identity-only'
                            for name in ('GpuHostSystemAxi', 'GpuComputeUnit', 'Rgba8BlendFmaEngine')},
                            manifest_sha256='cpu-manifest-identity')
            generated, generator = {'cpuGeneratedOnly': True}, {'cpuGeneratorOnly': True}
            for selected, other in ((runners[0], runners[1]), (runners[1], runners[0])):
                hashes = {name: full.common.sha(mirror / name) for name in selected.driver_sources()}
                context = dict(backend='flashsim', shards=selected.shards,
                    precompiled_header=selected.precompiled_header, shard_opt=selected.shard_opt,
                    conservative_alias=selected.conservative_alias, rtl_sha256=prepared['rtl_sha256'],
                    profile_manifest_sha256=prepared['manifest_sha256'],
                    assertion_layers=full.common.assertion_layer_hashes(prepared['rtl_sha256']),
                    hierarchy=full.common.hierarchy_plan(prepared['rtl_sha256'], selected.hierarchy_profile),
                    driver_sha256=hashes, generator=generator, generated=generated, reshard=None)
                record = dict(exit_code=0, context=context,
                    executable_sha256=full.common.sha(directory / 'native_product_driver'),
                    generation_sha256=full.common.sha(generation), builder_sha256=full.common.sha(builder_record))
                full.common.write_json(directory / 'build.json', record)

                def compiled(runner):
                    return full.common.guard_build(directory, prepared, runner.hierarchy_profile,
                        runner.driver_sources(), runner.executable_name, driver_root=mirror)

                with patch.object(full.flash, 'generator_inputs', return_value=generator), \
                        patch.object(full.flash, 'generated_sources', return_value=generated), \
                        patch.object(selected, 'checked_reshard', return_value=None), \
                        patch.object(other, 'checked_reshard', return_value=None):
                    compiled(selected)
                    selected.checked_build(prepared)
                    with self.assertRaises(RuntimeError):
                        compiled(other)
                    with self.assertRaises(RuntimeError):
                        other.checked_build(prepared)
                    shared = mirror / SHARED
                    original = shared.read_bytes()
                    shared.write_bytes(original + b'\n// CPU hash mutation\n')
                    with self.assertRaises(RuntimeError):
                        compiled(selected)
                    with self.assertRaises(RuntimeError):
                        selected.checked_build(prepared)
                    shared.write_bytes(original)


if __name__ == '__main__':
    unittest.main()
