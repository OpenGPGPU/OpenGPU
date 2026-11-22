"""P12 texture-side source: one launch on the complete float DUT, 128 local
threads, 16 usamplerBuffer textures, 4 SSBO and 4 buffer images. UBOs, atomic
counters and shared memory are absent: those are covered by the existing
p12-counter-coexist and p12-shared-coexist cases. The texture shader must stay
within the 512-word compiler profile."""

from pathlib import Path

CASES = ('p12-texture-coexist',)
LOCAL = 128
TEXTURE_COUNT = 16
TEXTURE_API_BASE = 16
OUTPUT_BYTES = 8192
IMAGE_VIEW_BYTES = 512
IMAGE_BACKING_BYTES = 4096
GUARD = 0xdeadbeef


def require(ok, message):
    if not ok:
        raise ValueError('p12 texture coexist cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown P12 texture coexist case')
    return dict(case=case, groups=1, local=LOCAL, viewBytes=OUTPUT_BYTES)


def shader():
    glsl = ('#version 310 es\n'
            '#extension GL_EXT_texture_buffer : require\n'
            'precision highp int;\n'
            'layout(local_size_x=128,local_size_y=1,local_size_z=1) in;\n')
    for i in range(TEXTURE_COUNT):
        glsl += 'layout(binding=%d) uniform highp usamplerBuffer t%d;\n' % (TEXTURE_API_BASE + i, i)
    for i in range(4):
        glsl += 'layout(std430,binding=%d) buffer Output%d {uint v[];} out%d;\n' % (i, i, i)
    for i in range(4):
        glsl += 'layout(r32ui,binding=%d) writeonly uniform highp uimageBuffer image%d;\n' % (i, i)
    glsl += ('void main(){\n'
             'uint id=gl_GlobalInvocationID.x;\n'
             'uint t=0u;\n')
    for i in range(TEXTURE_COUNT):
        glsl += 't^=texelFetch(t%d,int(2047u^id)).x;\n' % i
    for i in range(4):
        glsl += 'out%d.v[1920u+id]=t;\n' % i
    for i in range(4):
        glsl += 'imageStore(image%d,int(id),uvec4(t));\n' % i
    glsl += 'memoryBarrierBuffer();memoryBarrierImage();\n}\n'
    return glsl


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'p12-texture-coexist.comp').write_text(shader())
