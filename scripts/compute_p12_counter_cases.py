"""P12 counter-side source: 128 local threads, 12 UBO, 4 SSBO, 4 images,
8 atomic counters in one launch. Shared memory is intentionally absent: the
Mesa compiler profile cannot lower atomic counters and shared memory in the
same shader (see docs/P12_RESOURCE_COEXISTENCE.md)."""

from pathlib import Path

CASES = ('p12-counter-coexist',)
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


def shader():
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
    glsl += ('void main(){\n'
             'uint id=gl_GlobalInvocationID.x;\n'
             'uint seed=0u;\n')
    for i in range(12):
        glsl += 'seed=seed*33u^b%d.v[0].x^b%d.v[1023].w;\n' % (i, i)
    glsl += 'uint value=atomicCounterIncrement(counters[7])^seed;\n'
    for i in range(4):
        glsl += 'out%d.v[id]=value^%du;\n' % (i, i * 0x10203)
    for i in range(4):
        glsl += 'imageStore(image%d,int(id),uvec4(value^%du));\n' % (i, i * 0x20406)
    glsl += 'memoryBarrierBuffer();memoryBarrierImage();memoryBarrierAtomicCounter();\n}\n'
    return glsl


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'p12-counter-coexist.comp').write_text(shader())
