"""Original Mesa P12 counter and combined-resource shaders."""

from pathlib import Path

COMBINED = 'p12-combined-coexist'
CASES = ('p12-counter-coexist', COMBINED)
DEFAULT_COMPONENTS = 1016
LOCAL = 128
SSBO_WORDS = 2048
COUNTER_BYTES = 32
COUNTER_OFFSET = 8192 - COUNTER_BYTES
KERNARG_BYTES = 4096
COUNTER_INDEX = 7
GUARD = 0xa5a5a5a5


def require(ok, message):
    if not ok:
        raise ValueError('p12 counter coexist cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown P12 counter coexist case')
    return dict(case=case, groups=1, local=LOCAL, counter_index=COUNTER_INDEX,
                counter_bytes=COUNTER_BYTES, ssbo_words=SSBO_WORDS)


def shader(case=CASES[0]):
    parameters(case)
    combined = case == COMBINED
    glsl = ('#version 310 es\n'
            '#extension GL_EXT_texture_buffer : require\n'
            'precision highp int;\n'
            'layout(local_size_x=128,local_size_y=1,local_size_z=1) in;\n'
            'layout(binding=0,offset=0) uniform atomic_uint counters[8];\n')
    for i in range(12):
        glsl += 'layout(std140,binding=%d) uniform Block%d {uvec4 v[1024];} b%d;\n' % (24 + i, i, i)
    for i in range(4):
        glsl += 'layout(std430,binding=%d) buffer Output%d {uint v[];} out%d;\n' % (i, i, i)
    for i in range(4):
        glsl += 'layout(r32ui,binding=%d) writeonly uniform highp uimageBuffer image%d;\n' % (i, i)
    if combined:
        glsl += 'uniform uvec4 defaults[%d];\nshared uint data[4096];\n' % (DEFAULT_COMPONENTS // 4)
        for i in range(16):
            glsl += 'layout(binding=%d) uniform highp usamplerBuffer t%d;\n' % (16 + i, i)
        glsl += ('void main(){\nuint id=gl_LocalInvocationID.x;\n'
                 'uint seed=defaults[0].x^defaults[%d].w;\n' % (DEFAULT_COMPONENTS // 4 - 1))
    else:
        glsl += 'void main(){\nuint id=gl_GlobalInvocationID.x;\nuint seed=0u;\n'
    for i in range(12):
        if combined:
            glsl += 'seed^=b%d.v[1023].w;\n' % i
        else:
            glsl += 'seed=seed*33u^b%d.v[0].x^b%d.v[1023].w;\n' % (i, i)
    glsl += 'uint value=atomicCounterIncrement(counters[id&7u])^seed;\n'
    if combined:
        glsl += ('data[id]=value;data[3968u+id]=id*3u;\n'
                 'memoryBarrierShared();barrier();\nvalue=data[id^16u]^data[3968u+id];\n')
        for i in range(16):
            glsl += 'value^=texelFetch(t%d,int(2047u^id)).x;\n' % i
    for i in range(4):
        glsl += ('out%d.v[1920u+id]=value;\n' % i if combined else
                 'out%d.v[id]=value^%du;\n' % (i, i * 0x10203))
    for i in range(4):
        glsl += ('imageStore(image%d,int(id),uvec4(value));\n' % i if combined else
                 'imageStore(image%d,int(id),uvec4(value^%du));\n' % (i, i * 0x20406))
    glsl += 'memoryBarrierBuffer();memoryBarrierImage();memoryBarrierAtomicCounter();\n}\n'
    return glsl


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    for case in CASES:
        (out / (case + '.comp')).write_text(shader(case))
