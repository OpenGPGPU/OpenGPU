#!/usr/bin/env python3
"""CPU checks of the public ABI catalog; does not execute Scala or RTL."""
from pathlib import Path
import json
import os
import shlex
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TessellationUniformAbiTests(unittest.TestCase):
    def test_new_stage_widths_preserve_private_capacity(self):
        schema = json.loads((ROOT / 'docs/abi/tessellation-uniform-v3.json').read_text())
        self.assertEqual((schema['version'], schema['stages'], schema['stageMask']), (3, [4, 5], 48))
        self.assertEqual([schema[k] for k in ('blocksPerStage', 'bindings', 'blockBytes', 'defaultComponents')],
                         [12, 24, 16384, 1024])
        layouts = {layout['name']: layout for layout in schema['layouts']}
        for name in ('root', 'binding'):
            self.assertEqual((layouts[name]['words'], layouts[name]['bytes']), (16, 64))
        self.assertEqual(next(f['bits'] for f in layouts['root']['fields'] if f['name'] == 'stage'), 3)
        self.assertEqual(next(f['bits'] for f in layouts['binding']['fields'] if f['name'] == 'stageMask'), 6)
        self.assertEqual(next(f['bits'] for f in layouts['binding']['fields'] if f['name'] == 'bindingIndex'), 5)

    def test_append_only_three_domain_catalog(self):
        extension = json.loads((ROOT / 'docs/abi/tessellation-uniform-sequence.json').read_text())
        self.assertEqual((extension['words'], extension['bytes'], extension['extensionFirstWord']), (512, 2048, 496))
        extra = set()
        for f in extension['fields']:
            words = set(range(f['word'], f['word'] + (f['bits'] + 31) // 32))
            self.assertFalse(extra & words)
            extra |= words
        self.assertEqual(extra, set(range(496, 509)))
        for old_version, record in enumerate(extension['records'], 1):
            base = json.loads((ROOT / 'docs/abi' / record['baseSchema']).read_text())
            self.assertEqual((base['magic'], base['version']), (f'0x474e540{old_version}', old_version))
            self.assertEqual((record['magic'], record['version']), (f'0x474e540{old_version+3}', old_version+3))
            old = {w for f in base['fields'] for w in range(f['word'], f['word'] + (f['bits'] + 31) // 32)}
            self.assertFalse(old & extra)
            self.assertLess(max(old), 496)
            self.assertEqual(base['words'], extension['words'])

    def test_old_and_new_public_c_types_coexist(self):
        source = '''
#include "opengpu_uniform_abi.h"
#include "opengpu_geometry_uniform_abi.h"
#include "opengpu_tessellation_uniform_abi.h"
#include "opengpu_tessellation_uniform_sequence_abi.h"
_Static_assert(OPENGPU_UNIFORM_VERSION==1, "GUR1 frozen");
_Static_assert(OPENGPU_GEOMETRY_UNIFORM_VERSION==2, "GUR2 frozen");
_Static_assert(OPENGPU_TESSELLATION_UNIFORM_VERSION==3, "GUR3 distinct");
_Static_assert(sizeof(struct opengpu_tessellation_uniform_root)==64, "root line");
_Static_assert(sizeof(struct opengpu_tessellation_uniform_binding)==64, "binding line");
_Static_assert(OPENGPU_UNIFORM_ROOT_STAGE_BITS==1, "old root padding");
_Static_assert(OPENGPU_GEOMETRY_UNIFORM_ROOT_STAGE_BITS==2, "GS root padding");
_Static_assert(OPENGPU_TESSELLATION_UNIFORM_ROOT_STAGE_BITS==3, "TCS/TES root");
_Static_assert(OPENGPU_TESSELLATION_UNIFORM_BINDING_STAGEMASK_BITS==6, "stage mask");
_Static_assert(OPENGPU_TESSELLATION_UNIFORM_SEQUENCE_BYTES==2048, "extent");
_Static_assert(OPENGPU_TESSELLATION_UNIFORM_SEQUENCE_CONTROL_PRIVATELIMIT_WORD==502, "TCS range");
_Static_assert(OPENGPU_TESSELLATION_UNIFORM_SEQUENCE_EVALUATION_PRIVATELIMIT_WORD==507, "TES range");
'''
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + ['-std=c11', '-Werror', '-fsyntax-only',
                       '-I', str(ROOT / 'driver'), '-x', 'c', '-'], input=source, text=True, check=True)


if __name__ == '__main__':
    unittest.main()
