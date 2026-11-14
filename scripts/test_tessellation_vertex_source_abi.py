#!/usr/bin/env python3
"""GVS1 and inherited GNT7/8/9 source ABI checks without elaboration."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from generate_tessellation_vertex_source_abi import ROOT, OUTPUT, generate, rules


class SourceAbi(unittest.TestCase):
    def test_generated_and_exact_layout(self):
        self.assertEqual(OUTPUT.read_text(), generate())
        source = json.loads((ROOT / 'docs/abi/tessellation-vertex-source-v1.json').read_text())
        fields = {f['name']: f for f in source['fields']}
        expected = {
            'identity.context': (16, 32), 'identity.pass': (17, 32),
            'identity.generation': (18, 32), 'sequenceIndex': (19, 32),
            'drawIndex': (20, 32), 'taskIdBase': (21, 32), 'patchCount': (22, 32),
            'draw.base': (24, 32), 'draw.limit': (25, 33),
            'code.base': (27, 32), 'code.limit': (28, 33), 'code.entry': (30, 32),
            'slice.version': (32, 8), 'slice.firstOrdinal': (33, 32),
            'slice.patchesPerInstance': (34, 32), 'slice.firstInstance': (35, 32),
            'slice.instanceCount': (36, 32), 'layout.inputVertices': (40, 6),
            'layout.inputComponents': (41, 7), 'layout.outputVertices': (42, 6),
            'layout.outputComponents': (43, 7), 'layout.patchComponents': (44, 7),
            'vertexAbi': (45, 8),
        }
        for name, value in expected.items():
            self.assertEqual((fields[name]['word'], fields[name]['bits']), value)
        self.assertEqual([fields[f'components[{i}]']['word'] for i in range(64)], list(range(64, 128)))
        self.assertTrue(all(fields[f'components[{i}]']['bits'] == 9 for i in range(64)))
        widths = rules(source['fields'], 128)
        self.assertEqual({i for i, width in enumerate(widths) if not width},
                         set(range(11, 16)) | {23, 31, 37, 38, 39} | set(range(46, 64)))
        self.assertEqual([fields[f'header.{name}']['constant'] for name in (
            'magic', 'bytes', 'version', 'attributes', 'bindings', 'varyings',
            'mapCapacity', 'patchVertices', 'lanes', 'warps', 'computeUnits')],
            [0x47565301, 512, 1, 16, 16, 16, 64, 32, 16, 8, 2])

    def test_inheritance_does_not_consume_old_words(self):
        root = ROOT / 'docs/abi'
        old = json.loads((root / 'tessellation-uniform-sequence.json').read_text())
        new = json.loads((root / 'tessellation-vertex-sequence-v1.json').read_text())
        self.assertEqual(new['fields'], [
            dict(name='vertexSource.base', word=509, bits=32, signed=False),
            dict(name='vertexSource.limit', word=510, bits=33, signed=False)])
        for index, (parent, child) in enumerate(zip(old['records'], new['records'])):
            self.assertEqual(parent['domain'], child['domain'])
            self.assertEqual((child['magic'], child['version'], child['uniformVersion']),
                             (hex(0x474e5407 + index), 7 + index, 4 + index))
            base = json.loads((root / parent['baseSchema']).read_text())
            before = rules(base['fields'] + old['fields'], 512)
            after = rules(base['fields'] + old['fields'] + new['fields'], 512)
            self.assertEqual(before[:509], after[:509])
            self.assertEqual(before[509:], [0, 0, 0])
            self.assertEqual(after[509:], [32, 32, 1])

    def test_bad_fields(self):
        valid = dict(name='a', word=0, bits=32, signed=False)
        mutations = [dict(word=True), dict(bits=True), dict(signed=0), dict(bits=0),
                     dict(bits=513), dict(word=-1), dict(word=128),
                     dict(word=15, bits=33), dict(constant=2**32)]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                rules([dict(valid, **mutation)], 128)
        with self.assertRaises(ValueError):
            rules([valid, dict(valid, name='b')], 128)
        with self.assertRaises(ValueError):
            rules([valid, dict(valid, word=1)], 128)
        self.assertEqual(rules([dict(valid, bits=33, signed=True)], 2), [32, 129])

    def test_mutated_catalog_rejected(self):
        root = ROOT / 'docs/abi'
        names = ['tessellation-vertex-source-v1.json', 'tessellation-vertex-sequence-v1.json',
                 'tessellation-uniform-sequence.json', 'native-binned-graphics-v1.json']
        old = json.loads((root / names[2]).read_text())
        names += [r['baseSchema'] for r in old['records']]
        catalogs = {name: json.loads((root / name).read_text()) for name in names}
        changes = [
            (names[0], lambda d: d.update(bytes=2048)),
            (names[0], lambda d: d['componentEncoding'].update(componentShift=3)),
            (names[0], lambda d: d['fields'].pop()),
            (names[1], lambda d: d['records'][0].update(version=4)),
            (names[1], lambda d: d['records'][1].update(magic='0x474e5407')),
            (names[1], lambda d: d['fields'][0].update(word=508)),
            (names[2], lambda d: d['records'][2].update(version=5)),
            (names[3], lambda d: d['draw'].update(bytes=512)),
        ]
        with tempfile.TemporaryDirectory(dir=ROOT / '.artifacts') as temporary:
            directory = Path(temporary)
            for name, change in changes:
                modified = copy.deepcopy(catalogs)
                change(modified[name])
                for filename, data in modified.items():
                    (directory / filename).write_text(json.dumps(data))
                with self.subTest(name=name), self.assertRaises(ValueError):
                    generate(directory)


if __name__ == '__main__':
    unittest.main()
