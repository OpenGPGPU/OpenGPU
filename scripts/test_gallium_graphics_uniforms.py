#!/usr/bin/env python3
"""Actual linked graphics UBO compiler/publication development fixture."""
def validate_native_metadata(directory):
    """Decode actual encoder output using independent published wire schemas."""
    import json
    from pathlib import Path
    import struct
    root = Path(__file__).resolve().parents[1]
    abi = json.loads((root / 'docs/abi/native-binned-graphics-v1.json').read_text())
    fragment = json.loads((root / 'docs/abi/typed-fragment-state-v1.json').read_text())
    decoded = []
    for kind, schema in (('gnj', abi['job']), ('gnd', abi['draw']), ('gfs', fragment), ('gnf', abi['frame'])):
        data = (directory / ('native.' + kind)).read_bytes()
        assert len(data) == schema['bytes']
        words = struct.unpack('<%dI' % (len(data) // 4), data)
        assert words[:3] == (int(schema['magic'], 16), len(data), 1)
        assert words[3:8] == ((4, words[4], 11, 13, 0) if kind == 'gfs' else (16, 16, 16, 4, 4))
        assert kind != 'gfs' or words[4] != 0
        masks = [0] * len(words)
        for i in range(8):
            masks[i] = 0xffffffff
        fields = {}
        for field in schema['fields']:
            count = (field['bits'] + 31) // 32
            value = sum(words[field['word'] + i] << (32 * i) for i in range(count))
            assert value >> field['bits'] == 0, field['name']
            fields[field['name']] = value
            for i in range(count):
                masks[field['word'] + i] |= min((1 << max(0, field['bits'] - i * 32)) - 1, 0xffffffff)
            if field['name'].split('.')[-1] in {'base', 'limit', 'allocationBase', 'allocationLimit',
                    'shaderPc', 'address', 'frameBase', 'frameLimit', 'drawBase', 'drawLimit'}:
                assert value == 0, field['name']
        assert all(word & ~mask == 0 for word, mask in zip(words, masks))
        decoded.append(fields)
    job, draw, state, frame = decoded
    assert job['job.drawCount'] == 1 and job['job.drawStride'] == 2048
    assert job['job.identity.context'] == draw['draw.vertex.geometry.vertex.input.identity.context'] == state['fragment.identity.context']
    assert job['job.identity.pass'] == draw['draw.vertex.geometry.vertex.input.identity.pass'] == state['fragment.identity.pass'] == 11
    assert job['job.identity.generation'] == draw['draw.vertex.geometry.vertex.input.identity.generation'] == state['fragment.identity.generation'] == 13
    assert draw['draw.version'] == draw['draw.raster.version'] == draw['draw.vertex.viewport.version'] == 1
    assert draw['draw.vertex.viewport.extentX'] == draw['draw.vertex.viewport.extentY'] == 4
    assert draw['draw.vertex.geometry.vertex.input.version'] == 2
    assert draw['draw.vertex.geometry.vertex.input.count'] == 3 and draw['draw.vertex.geometry.vertex.input.instances'] == 1
    assert draw['draw.vertex.geometry.vertex.input.attributes[0].components'] == 4
    assert draw['draw.vertex.geometry.vertex.input.bindings[0].stride'] == 16
    assert draw['draw.vertex.geometry.vertex.varyingSlots'] == state['fragment.varyingSlots'] == 1
    assert draw['draw.vertex.geometry.vertex.varyingMask'] == draw['draw.vertex.geometry.flatMask'] == 15
    assert state['fragment.colorSlots'] == state['fragment.colorMask'] == state['fragment.colorTypes[0]'] == 1
    assert state['version'] == state['blend.version'] == state['tests.version'] == state['fragment.version'] == 1
    assert frame['frame.states.identity.context'] == job['job.identity.context']
    assert frame['frame.image.pixels.colors.identity.context'] == job['job.identity.context']
    assert frame['frame.version'] == 2 and frame['frame.bins.bins.tileCount'] == 1
    assert frame['frame.image.pixels.colors.colors[0].format'] == 26
    assert frame['frame.image.pixels.colors.colors[0].enabled'] == 1

def write_sources(out):
    for stage, suffix in ((0, "vert"), (1, "frag")):
        source = "#version 310 es\nprecision highp float;\nprecision highp int;\n"
        source += "uniform uvec4 defaults%d[256];\n" % stage
        for i in range(12):
            source += "layout(std140,binding=%d) uniform Block%d_%d {uvec4 values[1024];} block%d;\n" % (i + stage * 24, stage, i, i)
        source += ("layout(location=0) in vec4 position;\nflat out uvec4 varyingValue;\n" if not stage else
                   "flat in uvec4 varyingValue;\nlayout(location=0) out uvec4 color;\n")
        source += "void main(){uint sum=defaults%d[0].x ^ defaults%d[255].w;\n" % (stage, stage)
        for i in range(12):
            source += "sum=(sum*33u) ^ block%d.values[0].x ^ block%d.values[1023].w;\n" % (i, i)
        source += ("gl_Position=position;varyingValue=uvec4(sum);}\n" if not stage else
                   "color=varyingValue ^ uvec4(sum);}\n")
        (out / ("full." + suffix)).write_text(source)

if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(graphics_uniforms=True)
