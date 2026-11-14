"""Canonical private112 ordinary-Mesa triangle descriptors, not an executor.

The fixed original ABI1 recipe is separate from native96 and ABI6 sample-point
qualification. Expected records start at zero and use only the published ABI,
explicit original resource views and independently checked compiler/reflection
inputs. Initial/returned descriptor bytes never supply expected field values.
This module does not authorize hardware completion, GNY2, retirement or cleanup.
"""
import struct

from native_sample_graphics_source_oracle import (Space, descriptor_schemas,
    verify as verify_sources, uint)


def require(ok, message):
    if not ok:
        raise ValueError('ordinary112 recipe: ' + message)


class Record:
    def __init__(self, schema, header):
        self.schema = schema
        self.words = [0] * (schema['bytes'] // 4)
        self.words[:len(header)] = header
        self.fields = {f['name']: f for f in schema['fields']}
        require(len(self.fields) == len(schema['fields']), 'duplicate ABI field')

    def set(self, **values):
        for name, value in values.items():
            field = self.fields[name]
            value = uint(value, field['bits'])
            for part in range((field['bits'] + 31) // 32):
                self.words[field['word'] + part] = value >> (32 * part) & 0xffffffff
        return self

    def bytes(self):
        return struct.pack('<%dI' % len(self.words), *self.words)


def _view(view, size, offset=0):
    require(type(view) is dict and uint(view.get('handle')) > 0 and
            uint(view.get('bytes')) == size and uint(view.get('offset'), 64) == offset,
            'wrong original resource window')
    allocation, physical = uint(view.get('allocationBytes')), uint(view.get('allocationPhysical'))
    va = uint(view.get('viewVA'))
    require(physical % 4096 == 0 and allocation > 0 and allocation % 4096 == 0 and
            offset + size <= allocation and physical + allocation <= 1 << 32 and
            va % 4096 == offset % 4096 and va + size <= 1 << 32,
            'original resource alignment/bounds differ')
    return va


def records(description):
    """Build four complete expected records without reading any memory.

    Address observations must be bound to actual original GEM/PTE/source bytes
    by verify(), rather than using this lower-level constructor as provenance.
    """
    d = description
    require(d.get('schema') == 'opengpu-native-sample-graphics-source-v1' and
            d.get('recipe') == 'original-abi1-uniform-triangle-v1' and
            uint(d.get('shaderABI')) == 1 and uint(d.get('record')) in (0, 1) and
            d.get('gpuExecution') is False and
            tuple(uint(d.get(k)) for k in ('width', 'height', 'samples', 'pass', 'generation')) ==
            (4, 4, 1, 11, 13), 'wrong versioned ordinary triangle recipe')
    context = uint(d.get('context')); require(context > 0, 'missing original context')
    addresses = {name: uint(d.get(name + 'VA')) for name in ('job', 'frame', 'draw', 'state')}
    require(len(set(addresses.values())) == 4 and all(v % 4096 == 0 for v in addresses.values()),
            'aliased or unaligned private descriptor segments')
    sizes = {(1, 0): 256, (3, 0): 65536, (5, 0): 4096, (4, 0): 4096,
             **{(6, i): 8192 for i in range(4)}}
    resources = {}
    for view in d.get('resources', []):
        key = (uint(view.get('role')), uint(view.get('index')))
        require(key in sizes and key not in resources, 'duplicate/unknown frame resource role')
        resources[key] = _view(view, sizes[key], 64 if key == (1, 0) else 0)
    require(resources.keys() == sizes.keys(), 'missing frame resource role')
    scratch = _view(d.get('vertexScratch'), 7680)
    vertex = _view(d.get('vertexInput'), 48, 4)
    completion = _view(d.get('completion'), 16384)
    views = list(d['resources']) + [d['vertexScratch'], d['vertexInput'], d['completion']]
    require(len({v['handle'] for v in views}) == len(views), 'aliased original resource handles')
    intervals = sorted((v['allocationPhysical'], v['allocationPhysical'] + v['allocationBytes']) for v in views)
    require(all(a[1] <= b[0] for a, b in zip(intervals, intervals[1:])), 'overlapping original resources')
    programs = d.get('programs', [])
    require(len(programs) == 2 and [p.get('layout') for p in programs] ==
            [[0, 128, 1, 1, 0], [1, 128, 0, 1, 1]] and
            [p.get('stage') for p in programs] == [0, 1] and
            all(p.get('entryOffset') == 0 for p in programs), 'wrong original ABI1 layouts')
    pcs = [uint(p.get('privateVA')) for p in programs]
    uniforms = {(u['stage'], u['slot']): u for u in d.get('uniforms', [])}
    require(len(uniforms) == 26 and uniforms.keys() == {(s, i) for s in range(2) for i in range(13)},
            'missing original two-stage uniform inventory')
    roots = [uint(uniforms[s, 0].get('rootVA')) for s in range(2)]
    require(len(set([*addresses.values(), *pcs, *roots])) == 8 and
            all(v % 4096 == 0 for v in pcs) and roots[1] == roots[0] + 64,
            'aliased/unreachable shader or uniform roots')
    schemas = descriptor_schemas()
    require({name: s['bytes'] for name, s in schemas.items()} ==
            dict(job=512, frame=2048, draw=2048, state=512), 'unsupported descriptor sizes')
    header = [16, 16, 16, 4, 4]
    r = {name: Record(schema, [int(schema['magic'], 16), schema['bytes'], schema.get('version', 1),
             *([4, context, 11, 13, 0] if name == 'state' else header)])
         for name, schema in schemas.items()}
    r['job'].set(**{'job.drawBase': addresses['draw'], 'job.drawLimit': addresses['draw'] + 2048,
        'job.drawCount': 1, 'job.drawStride': 2048, 'job.frameBase': addresses['frame'],
        'job.frameLimit': addresses['frame'] + 2048, 'job.identity.context': context,
        'job.identity.pass': 11, 'job.identity.generation': 13, 'completion.base': completion,
        'completion.limit': completion + 16384, 'completion.allocationBase': completion,
        'completion.allocationLimit': completion + 16384, 'completion.writable': 1})
    frame = r['frame']
    for prefix in ('frame.bins.producer.arena.identity.', 'frame.bins.bins.pool.identity.',
                   'frame.states.identity.', 'frame.image.pixels.colors.identity.'):
        frame.set(**{prefix + 'context': context, prefix + 'pass': 11, prefix + 'generation': 13})
    frame.set(**{'frame.version': 3, 'frame.bins.producer.version': 1,
        'frame.bins.producer.arena.version': 1, 'frame.bins.producer.screenWidth': 4,
        'frame.bins.producer.screenHeight': 4, 'frame.bins.producer.tileLog2': 4,
        'frame.bins.bins.version': 3, 'frame.bins.bins.tileCount': 1,
        'frame.bins.bins.pool.version': 1, 'frame.bins.bins.pool.pageSizeLog2': 12,
        'frame.states.version': 1, 'frame.states.base': addresses['state'],
        'frame.states.limit': addresses['state'] + 512, 'frame.scratchStride': 8192,
        'frame.image.version': 1, 'frame.image.pixels.version': 1,
        'frame.image.pixels.colors.version': 1, 'frame.image.pixels.colors.tileLog2': 4,
        'frame.image.pixels.colors.tileWidth': 16, 'frame.image.pixels.colors.tileHeight': 16,
        'frame.image.pixels.colors.colors[0].enabled': 1, 'frame.image.pixels.colors.colors[0].format': 26,
        'frame.image.pixels.colors.colors[0].loadOp': 1, 'frame.image.pixels.colors.colors[0].channelMask': 15,
        'layered.version': 1, 'layered.layers': 1, 'layered.sourcePitch[0]': 256})
    for prefix, key in [('frame.bins.producer.arena.', (3, 0)),
                        ('frame.bins.bins.directory', (5, 0)), ('frame.bins.bins.pool.', (4, 0))]:
        if prefix.endswith('directory'):
            frame.set(**{prefix + 'Base': resources[key], prefix + 'Limit': resources[key] + sizes[key]})
        else:
            frame.set(**{prefix + 'base': resources[key], prefix + 'limit': resources[key] + sizes[key]})
    frame.set(**{'frame.bins.bins.pool.initialLimit': resources[4, 0] + 4096})
    image = resources[1, 0]
    frame.set(**{'frame.image.views[0].base': image, 'frame.image.views[0].limit': image + 256,
        'frame.image.views[0].allocationBase': image, 'frame.image.views[0].allocationLimit': image + 256,
        'frame.image.views[0].imageWidth': 4, 'frame.image.views[0].imageHeight': 4,
        'frame.image.views[0].rowPitch': 64, 'frame.image.views[0].writable': 1, 'frame.image.views[0].store': 1})
    for engine in range(4):
        frame.set(**{'frame.scratch[%d].base' % engine: resources[6, engine],
                     'frame.scratch[%d].limit' % engine: resources[6, engine] + 8192})
    draw = r['draw']
    draw.set(**{'draw.version': 1, 'draw.token': 0x12345678, 'draw.raster.version': 1,
        'draw.raster.frontFaceCCW': 1, 'draw.raster.lineWidth': 0x3f800000,
        'draw.raster.pointSize': 0x3f800000, 'draw.raster.screenW': 4, 'draw.raster.screenH': 4,
        'draw.vertex.geometry.topology': 4, 'draw.vertex.geometry.flatMask': 15,
        'draw.vertex.geometry.vertex.attributeSlots': 1, 'draw.vertex.geometry.vertex.varyingSlots': 1,
        'draw.vertex.geometry.vertex.varyingMask': 15, 'draw.vertex.geometry.vertex.version': 1,
        'draw.vertex.viewport.version': 1, 'draw.vertex.viewport.extentX': 4, 'draw.vertex.viewport.extentY': 4,
        'draw.vertex.viewport.farDepth': 0x3f800000, 'draw.vertex.geometry.vertex.input.version': 2,
        'draw.vertex.geometry.vertex.input.count': 3, 'draw.vertex.geometry.vertex.input.instances': 1,
        'draw.vertex.geometry.vertex.input.identity.context': context,
        'draw.vertex.geometry.vertex.input.identity.pass': 11,
        'draw.vertex.geometry.vertex.input.identity.generation': 13,
        'draw.vertex.geometry.vertex.input.attributes[0].enabled': 1,
        'draw.vertex.geometry.vertex.input.attributes[0].components': 4,
        'draw.vertex.geometry.vertex.input.bindings[0].base': vertex,
        'draw.vertex.geometry.vertex.input.bindings[0].limit': vertex + 48,
        'draw.vertex.geometry.vertex.input.bindings[0].allocationBase': vertex - 4,
        'draw.vertex.geometry.vertex.input.bindings[0].allocationLimit': vertex - 4 + 64,
        'draw.vertex.geometry.vertex.input.bindings[0].stride': 16,
        'draw.vertex.geometry.vertex.scratch.base': scratch,
        'draw.vertex.geometry.vertex.scratch.limit': scratch + 7680,
        'draw.vertex.geometry.vertex.scratch.allocationBase': scratch,
        'draw.vertex.geometry.vertex.scratch.allocationLimit': scratch + 7680,
        'draw.vertex.geometry.vertex.shaderPc': pcs[0],
        'draw.vertex.geometry.vertex.uniforms.version': 1, 'draw.vertex.geometry.vertex.uniforms.address': roots[0]})
    state = r['state']
    state.set(**{'version': 1, 'blend.version': 1, 'tests.version': 1, 'engineCount': 4,
        'fragment.version': 1, 'fragment.identity.context': context, 'fragment.identity.pass': 11,
        'fragment.identity.generation': 13, 'fragment.varyingSlots': 1, 'fragment.colorSlots': 1,
        'fragment.colorMask': 1, 'fragment.colorTypes[0]': 1, 'fragment.shaderPc': pcs[1],
        'fragment.uniforms.version': 1, 'fragment.uniforms.address': roots[1]})
    for engine, prefix in enumerate(('fragment.scratch.', 'scratch[0].', 'scratch[1].', 'scratch[2].')):
        base = resources[6, engine]
        state.set(**{prefix + 'base': base, prefix + 'limit': base + 8192,
                     prefix + 'allocationBase': base, prefix + 'allocationLimit': base + 8192})
    return {name: (addresses[name], record.bytes()) for name, record in r.items()}


def verify_records(space, description):
    """Check full descriptors/tails; source provenance still requires verify()."""
    for name, (va, expected) in records(description).items():
        segments = [s for s in description['privateSegments'] if s['va'] == va]
        require(len(segments) == 1 and segments[0]['bytes'] == len(expected) and
                segments[0]['allocationBytes'] == 4096 and not segments[0]['executable'],
                'wrong private descriptor allocation: ' + name)
        actual = space.read(va, len(expected))
        if actual != expected:
            for word, (got, want) in enumerate(zip(struct.unpack('<%dI' % (len(actual) // 4), actual),
                                                  struct.unpack('<%dI' % (len(expected) // 4), expected))):
                require(got == want, '%s word %d: %#x != %#x' % (name, word, got, want))
        segment = segments[0]
        require(space.memory.read(segment['physical'] + len(expected), 4096 - len(expected)) ==
                bytes(4096 - len(expected)), 'private descriptor allocation tail changed: ' + name)


def verify(initial, description, programs, reflection, returned=None):
    """Original source binding plus exact ordinary112 descriptors, no GPU claim."""
    verify_sources(initial, description, programs, reflection, returned=returned)
    verify_records(Space(initial, description), description)
    if returned is not None:
        verify_records(Space(returned, description), description)
