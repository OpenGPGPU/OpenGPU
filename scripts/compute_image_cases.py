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
IMAGE_LOAD_STORE_CASES = tuple('image-' + target + '-' + fmt for target in TARGETS for fmt in FORMATS) + (
    'image-single-array-r32ui', 'image-single-3d-r32ui',
    'image-plane-array-r32ui', 'image-plane-cube-r32ui', 'image-plane-3d-r32ui')
IMAGE_INTEGER_ATOMIC_CASES = tuple('image-atomic-'+target+'-'+fmt for target in TARGETS for fmt in ('r32ui','r32i'))
IMAGE_FLOAT_ATOMIC_CASES = tuple('image-atomic-'+target+'-r32f' for target in TARGETS)
IMAGE_ATOMIC_CASES = IMAGE_INTEGER_ATOMIC_CASES + IMAGE_FLOAT_ATOMIC_CASES
IMAGE_BOUNDARY_LOAD_STORE_CASES = tuple('image-bounds-'+target+'-r32ui' for target in TARGETS)
IMAGE_BOUNDARY_ATOMIC_CASES = tuple('image-atomic-bounds-'+target+'-'+fmt
    for target in TARGETS for fmt in ('r32ui','r32f'))
IMAGE_BOUNDARY_CASES = IMAGE_BOUNDARY_LOAD_STORE_CASES + IMAGE_BOUNDARY_ATOMIC_CASES
IMAGE_FLOAT_EXCHANGE_CASES = IMAGE_FLOAT_ATOMIC_CASES + tuple(
    case for case in IMAGE_BOUNDARY_ATOMIC_CASES if case.endswith('-r32f'))
IMAGE_CASES = IMAGE_LOAD_STORE_CASES + IMAGE_ATOMIC_CASES + IMAGE_BOUNDARY_CASES


def parameters(case):
    if case not in IMAGE_CASES:
        raise ValueError('unknown original typed-image source')
    fields = case.split('-')
    mode = 'atomic-bounds' if fields[1:3] == ['atomic','bounds'] else fields[1] if len(fields) == 4 else 'layered'
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
        if p['mode'] in ('bounds','atomic-bounds'):
            _write_boundary_source(out,p,extension,prefix,vector)
            continue
        if p['mode']=='atomic':
            scalar='uint' if prefix=='u' else 'int'
            floating=p['format']=='r32f'
            steps=((('Exchange','(g&1u)<<31u'),('Exchange','0x3f400000u+g'),
                ('Exchange','0xbf800000u+g'),('Exchange','0x7f800000u|((g&1u)<<31u)'),
                ('Exchange','0x40000000u+(g<<20u)')) if floating else
                (('Exchange','0x80000000u+g*17u'),('Add','0x7ffffff0u'),
                ('Xor','0x5555aaaau'),('Or','0x0000000fu'),('And','0xfffffff3u'),
                ('Min','0x7fffff00u'),('Max','0x80000020u'),('Exchange','0x12345670u+g'),
                ('CompSwap','0x12345670u+g','0xdeadb000u+g'),
                ('CompSwap','0x87654321u','0xa5a5a5a5u')))
            body='void main(){uint g=gl_GlobalInvocationID.x;uint o=g*%du;'%len(steps)
            for index,(operation,*values) in enumerate(steps):
                conversion='floatBitsToUint' if floating else 'uint'
                argument='uintBitsToFloat' if floating else scalar
                body+='observed.data[o+%du]=%s(imageAtomic%s(original,%s,%s));' % (
                    index,conversion,operation,coordinate(False),','.join(argument+'('+value+')' for value in values))
            body+='memoryBarrierImage();highp %s value=imageLoad(original,%s);imageStore(destination,%s,value);}\n' % (
                vector,coordinate(False),coordinate(True))
            (out/p['source']).write_text('#version 310 es\n#extension GL_OES_shader_image_atomic : require\n'+extension+
                'precision highp int;\n'+('precision highp float;\n' if floating else '')+'layout(local_size_x=4) in;\n'+
                'layout(%s,binding=3) coherent uniform highp %simage%s original;\n' % (p['format'],prefix,p['suffix'])+
                'layout(%s,binding=1) writeonly uniform highp %simage%s destination;\n' % (p['format'],prefix,p['suffix'])+
                'layout(std430,binding=2) buffer Output {uint data[];} observed;\n'+body)
            continue
        (out/p['source']).write_text('#version 310 es\n'+extension+
            'precision highp int;\nlayout(local_size_x=4) in;\n'+
            'layout(%s,binding=3) readonly uniform highp %simage%s original;\n' % (p['format'],prefix,p['suffix'])+
            'layout(%s,binding=1) writeonly uniform highp %simage%s destination;\n' % (p['format'],prefix,p['suffix'])+
            'layout(std430,binding=2) buffer Output {uvec4 data[];} observed;\n'+
            'void main(){highp %s value=imageLoad(original,%s);' % (vector,coordinate(False))+
            'observed.data[gl_GlobalInvocationID.x]=%s;' % bits+
            'imageStore(destination,%s,value);}\n' % coordinate(True))


def _write_boundary_source(out,p,extension,prefix,vector):
    """Original accesses to first/last texels and each negative/exact boundary.

    Only two invocations touch valid texels, once each. No ordering assumption
    between lanes or workgroups supplies an expected value.
    """
    target=p['shader'];atomic=p['mode']=='atomic-bounds';floating=p['format']=='r32f'
    coordinate_type='int' if target=='buffer' else 'ivec2' if target=='2d' else 'ivec3'
    upper=('int(g*13u)' if target=='buffer' else
        'ivec2(int((g&1u)*13u),int((g>>1u)*5u))' if target=='2d' else
        'ivec3(int((g&1u)*13u),int(((g>>1u)&1u)*%du),int((g>>2u)*%du))' % (
            13 if target=='cube' else 5,dict(array=3,cube=6,**{'3d':2})[target]))
    body='void main(){uint g=gl_GlobalInvocationID.x;uint o=g*2u;%s upper=%s;%s lower=upper-%s(1);' % (
        coordinate_type,upper,coordinate_type,coordinate_type)
    if atomic:
        for index,coordinate in enumerate(('upper','lower')):
            operand='0x%08xu+g*17u+%du' % (0x3f400000 if floating else 0x87650000,index)
            body+='observed.data[o+%du]=%s(imageAtomicExchange(original,%s,%s));' % (
                index,'floatBitsToUint' if floating else 'uint',coordinate,
                'uintBitsToFloat('+operand+')' if floating else operand)
        body+='memoryBarrierImage();'
    for index,coordinate in enumerate(('upper','lower')):
        body+='highp %s value%u=imageLoad(original,%s);' % (vector,index,coordinate)
        if not atomic:body+='observed.data[o+%du]=uvec4(value%u);' % (index,index)
        body+='imageStore(destination,%s,value%u);' % (coordinate,index)
    body+='}\n'
    (out/p['source']).write_text('#version 310 es\n'+
        ('#extension GL_OES_shader_image_atomic : require\n' if atomic else '')+extension+
        'precision highp int;\n'+('precision highp float;\n' if floating else '')+'layout(local_size_x=4) in;\n'+
        'layout(%s,binding=3) %s uniform highp %simage%s original;\n' % (
            p['format'],'coherent' if atomic else 'readonly',prefix,p['suffix'])+
        'layout(%s,binding=1) writeonly uniform highp %simage%s destination;\n' % (p['format'],prefix,p['suffix'])+
        'layout(std430,binding=2) buffer Output {%s data[];} observed;\n' % ('uint' if atomic else 'uvec4')+body)
