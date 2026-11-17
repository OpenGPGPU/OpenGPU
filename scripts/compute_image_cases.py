"""Original typed-image ESSL recipes; no shader execution or GPU results."""
FORMATS = {
    'rgba32f': (35, 16, 32, 'float', 4), 'rgba16f': (34, 8, 16, 'float', 4),
    'r32f': (29, 4, 32, 'float', 1), 'rgba32ui': (26, 16, 32, 'uint', 4),
    'rgba16ui': (24, 8, 16, 'uint', 4), 'rgba8ui': (22, 4, 8, 'uint', 4),
    'r32ui': (14, 4, 32, 'uint', 1), 'rgba32i': (25, 16, 32, 'sint', 4),
    'rgba16i': (23, 8, 16, 'sint', 4), 'rgba8i': (21, 4, 8, 'sint', 4),
    'r32i': (13, 4, 32, 'sint', 1), 'rgba8': (3, 4, 8, 'unorm', 4),
    'rgba8_snorm': (39, 4, 8, 'snorm', 4),
}
TARGETS = ('buffer', '2d', 'array', 'cube', '3d')
IMAGE_CASES = tuple('image-' + target + '-' + fmt for target in TARGETS for fmt in FORMATS) + (
    'image-single-array-r32ui', 'image-single-3d-r32ui',
    'image-plane-array-r32ui', 'image-plane-cube-r32ui', 'image-plane-3d-r32ui')


def parameters(case):
    if case not in IMAGE_CASES:
        raise ValueError('unknown original typed-image source')
    fields = case.split('-')
    mode = fields[1] if len(fields) == 4 else 'layered'
    target, fmt = fields[-2:]
    native, size, bits, kind, components = FORMATS[fmt]
    shader = '2d' if mode == 'plane' else target
    suffix = dict(buffer='Buffer', **{'2d': '2D', 'array': '2DArray', 'cube': 'Cube', '3d': '3D'})[shader]
    return dict(case=case, source=case+'.comp', mode=mode, target=target, shader=shader,
        format=fmt, native=native, bytes=size, bits=bits, kind=kind, components=components,
        suffix=suffix, groups=2, local=4)


def coordinates(p, destination=False):
    x = 3 if destination else 1
    if p['shader'] == 'buffer':
        return x, 0, 0
    y = 3 if destination else 1
    if p['shader'] == '2d' or p['mode'] == 'single':
        return x, y, 0
    z = (4 if destination else 2) if p['shader'] == 'cube' else (
        2 if destination else 0) if p['shader'] == '3d' else 1
    return x, y, z


def write_sources(out):
    for case in IMAGE_CASES:
        p = parameters(case)
        prefix = 'u' if p['kind'] == 'uint' else 'i' if p['kind'] == 'sint' else ''
        vector = prefix+'vec4'
        def coordinate(destination):
            x, y, z = coordinates(p, destination)
            x = 'int(gl_GlobalInvocationID.x)+%d' % x
            return x if p['shader'] == 'buffer' else ('ivec2(%s,%d)' % (x, y)
                if p['shader'] == '2d' else 'ivec3(%s,%d,%d)' % (x, y, z))
        bits = 'uvec4(value)' if prefix else 'floatBitsToUint(value)'
        extension = '#extension GL_EXT_texture_buffer : require\n' if p['shader'] == 'buffer' else ''
        (out/p['source']).write_text('#version 310 es\n'+extension+
            'precision highp int;\nlayout(local_size_x=4) in;\n'+
            'layout(%s,binding=3) readonly uniform highp %simage%s original;\n' % (p['format'],prefix,p['suffix'])+
            'layout(%s,binding=1) writeonly uniform highp %simage%s destination;\n' % (p['format'],prefix,p['suffix'])+
            'layout(std430,binding=2) buffer Output {uvec4 data[];} observed;\n'+
            'void main(){highp %s value=imageLoad(original,%s);' % (vector,coordinate(False))+
            'observed.data[gl_GlobalInvocationID.x]=%s;' % bits+
            'imageStore(destination,%s,value);}\n' % coordinate(True))
