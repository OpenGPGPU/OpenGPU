#!/usr/bin/env python3
"""Original ESSL typed load/store compilation; CPU only, no image execution."""

FORMATS = ('rgba32f', 'rgba16f', 'r32f', 'rgba32ui', 'rgba16ui', 'rgba8ui',
           'r32ui', 'rgba32i', 'rgba16i', 'rgba8i', 'r32i', 'rgba8', 'rgba8_snorm')
TARGETS = ('buffer', '2d', 'array', 'cube', '3d')


def write_sources(out):
    for target in TARGETS:
        suffix = dict(buffer='Buffer', **{'2d': '2D', 'array': '2DArray', 'cube': 'Cube', '3d': '3D'})[target]
        coordinate = '3' if target == 'buffer' else 'ivec2(3,7)' if target == '2d' else 'ivec3(3,7,2)'
        for fmt in FORMATS:
            kind = 'u' if fmt.endswith('ui') else 'i' if fmt.endswith('i') else ''
            extension = '#extension GL_EXT_texture_buffer : require\n' if target == 'buffer' else ''
            (out / (target + '-' + fmt + '.comp')).write_text(
                '#version 310 es\n' + extension + 'precision highp int;\nlayout(local_size_x=4) in;\n' +
                'layout(%s,binding=3) readonly uniform highp %simage%s original;\n' % (fmt, kind, suffix) +
                'layout(%s,binding=1) writeonly uniform highp %simage%s destination;\n' % (fmt, kind, suffix) +
                'void main(){imageStore(destination,%s,imageLoad(original,%s));}\n' % (coordinate, coordinate))


if __name__ == '__main__':
    from test_gallium_compute_uniforms import main
    main(typed_images=True)
