"""P12 shared-side source: one launch on the complete float DUT, 128 local
threads, 16 KiB shared memory, 12 UBO, 4 SSBO and 4 buffer images. Atomic
counters are absent: the compiler profile cannot lower counters and shared
memory in one shader (see docs/P12_RESOURCE_COEXISTENCE.md)."""

from pathlib import Path

CASES = ('p12-shared-coexist',)
LOCAL = 128
SHARED_BYTES = 16384
SHARED_WORDS = 4096
UBO_COUNT = 12
OUTPUT_BYTES = 8192


def require(ok, message):
    if not ok:
        raise ValueError('p12 shared coexist cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown P12 shared coexist case')
    return dict(case=case, groups=1, local=LOCAL, shared_bytes=SHARED_BYTES)


def shader():
    glsl = ('#version 310 es\n'
            '#extension GL_EXT_texture_buffer : require\n'
            'precision highp int;\n'
            'layout(local_size_x=128,local_size_y=1,local_size_z=1) in;\n'
            'shared uint data[4096];\n')
    for i in range(UBO_COUNT):
        glsl += 'layout(std140,binding=%d) uniform Block%d {uvec4 v[1024];} b%d;\n' % (24 + i, i, i)
    for i in range(4):
        glsl += 'layout(std430,binding=%d) buffer Output%d {uint v[];} out%d;\n' % (i, i, i)
    for i in range(4):
        glsl += 'layout(r32ui,binding=%d) writeonly uniform highp uimageBuffer image%d;\n' % (i, i)
    glsl += ('void main(){\n'
             'uint id=gl_LocalInvocationID.x;\n'
             'uint seed=0u;\n')
    for i in range(UBO_COUNT):
        glsl += 'seed=seed*33u^b%d.v[0].x^b%d.v[1023].w;\n' % (i, i)
    glsl += ('data[id]=seed^(id<<8u);data[3968u+id]=id;'
             'memoryBarrierShared();barrier();'
             'uint peer=data[(id^16u)]^data[3968u+id];\n')
    for i in range(4):
        glsl += 'out%d.v[id]=peer^%du;\n' % (i, i * 0x10203)
    for i in range(4):
        glsl += 'imageStore(image%d,int(id),uvec4(peer^%du));\n' % (i, i * 0x20406)
    glsl += 'memoryBarrierBuffer();memoryBarrierImage();\n}\n'
    return glsl


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'p12-shared-coexist.comp').write_text(shader())
