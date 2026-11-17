#!/usr/bin/env python3
"""Real Mesa image-atomic compiler checks; no shader executes."""
TARGETS = ('buffer','2d','array','cube','3d')
OPERATIONS = ('Exchange','Add','Xor','Or','And','Min','Max','CompSwap')


def write_sources(out):
    for target in TARGETS:
        suffix=dict(buffer='Buffer',**{'2d':'2D','array':'2DArray','cube':'Cube','3d':'3D'})[target]
        coordinate='3' if target=='buffer' else 'ivec2(3,7)' if target=='2d' else 'ivec3(3,7,2)'
        extension='#extension GL_EXT_texture_buffer : require\n' if target=='buffer' else ''
        for signed in (False,True):
            fmt='r32i' if signed else 'r32ui'
            kind='int' if signed else 'uint'
            for operation in OPERATIONS:
                value='int(0x87654321u)' if signed else '0x87654321u'
                replacement='int(0x12345678u)' if signed else '0x12345678u'
                arguments=value+','+replacement if operation=='CompSwap' else value
                (out/(target+'-'+fmt+'-'+operation+'.comp')).write_text(
                    '#version 310 es\n#extension GL_OES_shader_image_atomic : require\n'+extension+
                    'precision highp int;\nlayout(local_size_x=4) in;\n'+
                    'layout(%s,binding=3) coherent uniform highp %simage%s original;\n' % (fmt,'i' if signed else 'u',suffix)+
                    'layout(std430,binding=2) buffer Output{uint data[];} observed;\n'+
                    'void main(){highp %s previous=imageAtomic%s(original,%s,%s);' % (kind,operation,coordinate,arguments)+
                    'observed.data[gl_GlobalInvocationID.x]=uint(previous);}\n')
        (out/(target+'-r32f-Exchange.comp')).write_text(
            '#version 310 es\n#extension GL_OES_shader_image_atomic : require\n'+extension+
            'precision highp int;\nprecision highp float;\nlayout(local_size_x=4) in;\n'+
            'layout(r32f,binding=3) coherent uniform highp image%s original;\n' % suffix+
            'layout(std430,binding=2) buffer Output{uint data[];} observed;\n'+
            'void main(){highp float previous=imageAtomicExchange(original,%s,uintBitsToFloat(0x3f400000u));' % coordinate+
            'observed.data[gl_GlobalInvocationID.x]=floatBitsToUint(previous);}\n')


if __name__=='__main__':
    from test_gallium_compute_uniforms import main
    main(image_atomics=True)
