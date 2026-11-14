#!/usr/bin/env python3
"""Canonical triangle inputs through real Linux snapshots; synthetic code only."""
import copy
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import unittest

from compute_owner_oracle import SparseMemory
import test_native_sample_graphics_source as source_tests
from native_sample_graphics_source_oracle import Space, descriptor_schemas, verify as verify_sources
from native_sample_graphics_recipe import records, verify, verify_records

ROOT = Path(__file__).resolve().parents[1]

# Independent original C input, following opengpu_native_draw_encode_checked's
# fixed ordinary request. No Python expected record is fed to the kernel.
# Code words remain explicit placeholder CEASE, not compiler/GPU evidence.
CANONICAL_INPUT = '''
        memset(r.draw,0,sizeof(r.draw));memset(r.state,0,sizeof(r.state));
        const u32 header[]={0x474e4401,2048,1,16,16,16,4,4};memcpy(r.draw,header,sizeof(header));
        r.draw[17]=1;r.draw[18]=r.draw[19]=0x3f800000;r.draw[26]=r.draw[27]=4;r.draw[28]=1;
        r.draw[30]=0x12345678;r.draw[31]=1;r.draw[32]=15;r.draw[34]=4;r.draw[35]=1;
        r.draw[37]=4;r.draw[42]=1;r.draw[189]=16;r.draw[311]=3;r.draw[314]=17;
        r.draw[315]=13;r.draw[316]=11;r.draw[325]=1;r.draw[327]=2;r.draw[343]=15;
        r.draw[345]=1;r.draw[346]=1;r.draw[347]=r.draw[348]=4;r.draw[349]=0x3f800000;r.draw[351]=1;
        const u32 fs_header[]={0x47465301,512,1,4,17,11,13,0};memcpy(r.state,fs_header,sizeof(fs_header));
        r.state[27]=1;r.state[28]=4;r.state[30]=r.state[31]=r.state[32]=1;
        r.state[36]=17;r.state[37]=13;r.state[38]=11;r.state[55]=r.state[56]=1;
        r.state[93]=r.state[94]=1;
'''


class RecipeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Reuse only the bounded source snapshot fixture/build. Its original
        # descriptors are deliberately a noncanonical negative for this test.
        source_tests.SourceProofTests.setUpClass()
        try:
            cls.fixture = source_tests.SourceProofTests
            cls.out = cls.fixture.out / 'canonical'; cls.out.mkdir()
            text = (ROOT / 'userspace/tests/graphics_sample_source_test.c').read_text()
            first = '        r.draw[34]=4;'
            last = '        struct opengpu_native_sample_frame_request req='
            start, end = text.index(first), text.index(last)
            source = cls.fixture.out / 'canonical-input.c'
            source.write_text(text[:start] + CANONICAL_INPUT + text[end:])
            flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror', '-Wno-unused-function',
                     '-Wno-unused-parameter', '-D_POSIX_C_SOURCE=200809L']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            includes = [cls.fixture.out, ROOT/'driver', ROOT/'driver/tests',
                ROOT/'driver/tests/private_snapshot_stubs', ROOT/'driver/tests/mmu_stubs',
                ROOT/'driver/tests/compute_submit_stubs', ROOT/'userspace', ROOT/'userspace/tests']
            compiler = shlex.split(os.environ.get('CC', 'cc'))
            obj = cls.fixture.out / 'canonical-input.o'
            subprocess.run([*compiler, *flags, *['-I'+str(p) for p in includes], '-c', str(source), '-o', str(obj)], check=True)
            binary = cls.fixture.out / 'canonical-fixture'
            objects = [obj, *(cls.fixture.out/(stem+'.o') for stem in
                ('native_graphics_uniform_source', 'opengpu_native_sample_graphics', 'opengpu_native_frame'))]
            subprocess.run([*compiler, *flags, *map(str, objects), '-o', str(binary)], check=True)
            subprocess.run([str(binary), str(cls.out)], check=True, timeout=30)
            cls.descriptions = [json.loads((cls.out/('record-%d.source-plan.json'%i)).read_text()) for i in range(2)]
            cls.programs = [(cls.out/('compiler-%s.words'%stage)).read_bytes() for stage in ('vs', 'fs')]
            cls.reflection = cls.fixture.reflection
        except BaseException:
            source_tests.SourceProofTests.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        source_tests.SourceProofTests.tearDownClass()

    def memory(self):
        return SparseMemory(self.out/'union.memory')

    def test_two_original_owner_snapshots_match_all_canonical_records_and_sources(self):
        with self.memory() as memory:
            for d in self.descriptions:
                verify(memory, d, self.programs, self.reflection, returned=memory)
            for name in ('job', 'frame', 'draw', 'state'):
                self.assertNotEqual(self.descriptions[0][name+'VA'], self.descriptions[1][name+'VA'])
            expected = [records(d) for d in self.descriptions]
            self.assertEqual([sum(len(b) for _, b in e.values()) for e in expected], [5120, 5120])

    def test_narrow_source_proof_does_not_imply_canonical_triangle_descriptors(self):
        with SparseMemory(self.fixture.out/'union.memory') as memory:
            for d in self.fixture.descriptions:
                verify_sources(memory, d, self.programs, self.reflection)
                with self.assertRaisesRegex(ValueError, 'draw word'):
                    verify(memory, d, self.programs, self.reflection)

    def test_each_descriptor_word_low_and_high_bit_mutation_including_reserved_and_padding(self):
        with self.memory() as memory:
            mutants = 0
            for d in self.descriptions:
                space = Space(memory, d)
                for name, (va, expected) in records(d).items():
                    physical = space.physical(va, len(expected))
                    for word in range(len(expected)//4):
                        for bit in (0, 31):
                            at = physical + word*4
                            value, = struct.unpack('<I', memory.read(at, 4))
                            bad = source_tests.Overlay(memory, [(at, struct.pack('<I', value ^ (1<<bit)))])
                            changed = copy.copy(space); changed.memory = bad
                            with self.assertRaises(ValueError, msg='%s word=%d bit=%d' % (name, word, bit)):
                                verify_records(changed, d)
                            mutants += 1
            self.assertEqual(mutants, 5120)

    def test_private_descriptor_allocation_tails_and_high_33_bit_limits(self):
        with self.memory() as memory:
            for d in self.descriptions:
                space = Space(memory, d)
                for name, (va, expected) in records(d).items():
                    segment = next(s for s in d['privateSegments'] if s['va'] == va)
                    for offset in (len(expected), 4095):
                        bad = source_tests.Overlay(memory, [(segment['physical']+offset, b'\x01')])
                        changed = copy.copy(space); changed.memory = bad
                        with self.assertRaisesRegex(ValueError, 'allocation tail'):
                            verify_records(changed, d)
                    for field in descriptor_schemas()[name]['fields']:
                        if field['bits'] == 33:
                            at = segment['physical'] + field['word']*4 + 4
                            bad = source_tests.Overlay(memory, [(at, b'\x01\x00\x00\x00')])
                            changed = copy.copy(space); changed.memory = bad
                            with self.assertRaises(ValueError):verify_records(changed, d)

    def test_resource_bounds_aliases_and_version_isolation_are_not_descriptor_authority(self):
        with self.memory() as memory:
            d = self.descriptions[0]
            for key, value in [('schema', 'opengpu-native-owner-source-v1'),
                               ('recipe', 'fixed-sample-point-v1'), ('shaderABI', 6), ('record', 2)]:
                changed = copy.deepcopy(d); changed[key] = value
                with self.assertRaises(ValueError):verify(memory, changed, self.programs, self.reflection)
            for field in ('bytes', 'offset', 'allocationPhysical', 'allocationBytes', 'viewVA'):
                changed = copy.deepcopy(d); changed['resources'][0][field] ^= 64
                with self.assertRaises(ValueError):verify(memory, changed, self.programs, self.reflection)
            for key in ('vertexInput', 'vertexScratch', 'completion'):
                for field in ('bytes', 'offset', 'viewVA'):
                    changed = copy.deepcopy(d); changed[key][field] += 4
                    with self.assertRaises(ValueError):verify(memory, changed, self.programs, self.reflection)
            for mutation in ('duplicate', 'missing', 'alias'):
                changed = copy.deepcopy(d)
                if mutation == 'duplicate':changed['resources'].append(changed['resources'][0])
                elif mutation == 'missing':changed['resources'].pop()
                else:changed['completion'] = dict(changed['vertexScratch'])
                with self.assertRaises(ValueError):verify(memory, changed, self.programs, self.reflection)
            changed = copy.deepcopy(d)
            changed['resources'][0]['handle'] = changed['completion']['handle']
            with self.assertRaises(ValueError):verify(memory, changed, self.programs, self.reflection)

    def test_cross_owner_and_unreachable_private_code_root_and_descriptor_links(self):
        with self.memory() as memory:
            d, other = self.descriptions
            space = Space(memory, d)
            for name, (va, expected) in records(d).items():
                physical = space.physical(va, len(expected))
                at_other = Space(memory, other).physical(other[name+'VA'], len(expected))
                # Same descriptor kind and same context still cannot borrow
                # the second owner's private source/resource addresses.
                bad = source_tests.Overlay(memory, [(physical, memory.read(at_other, len(expected)))])
                changed = copy.copy(space); changed.memory = bad
                with self.assertRaises(ValueError):verify_records(changed, d)
                changed = copy.deepcopy(d); changed[name+'VA'] = other[name+'VA']
                with self.assertRaises(ValueError):verify(memory, changed, self.programs, self.reflection)
            for group, field in [('programs', 'privateVA'), ('uniforms', 'rootVA')]:
                changed = copy.deepcopy(d); changed[group][0][field] = other[group][0][field]
                with self.assertRaises(ValueError):verify(memory, changed, self.programs, self.reflection)


if __name__ == '__main__':
    unittest.main()
