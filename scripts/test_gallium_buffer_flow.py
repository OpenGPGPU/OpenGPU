#!/usr/bin/env python3
"""Explicit CPU-only real Mesa buffer shader lowering/submission acceptance."""


def write_sources(out):
    (out / "buffers.comp").write_text("""#version 310 es
#extension GL_EXT_texture_buffer : require
precision highp int;
layout(local_size_x=4) in;
layout(binding=0) uniform highp usamplerBuffer src[32];
layout(r32ui,binding=0) uniform highp uimageBuffer images[4];
layout(std430,binding=0) buffer Result0 { uvec4 result[]; } out0;
layout(std430,binding=1) buffer Result1 { uvec4 result[]; } out1;
layout(std430,binding=2) buffer Result2 { uvec4 result[]; } out2;
layout(std430,binding=3) buffer Result3 { uvec4 result[]; } out3;
void main() {
    int i=int(gl_LocalInvocationID.x);
    uvec4 a=texelFetch(src[31],i);
    uvec4 b=imageLoad(images[0],i);
    uint n=uint(textureSize(src[31]));
    uint m=uint(imageSize(images[3]));
    imageStore(images[3],i,uvec4(a.x,0u,0u,1u));
    uvec4 value=uvec4(a.x,b.x,n,m);
    out0.result[i]=value;
    out1.result[i]=value ^ uvec4(0x10203u);
    out2.result[i]=value ^ uvec4(0x20406u);
    out3.result[i]=value ^ uvec4(0x30609u);
}
""")
    base = (out / "buffers.comp").read_text()
    signed = base.replace("usamplerBuffer", "isamplerBuffer").replace("uimageBuffer", "iimageBuffer").replace("r32ui", "r32i")
    signed = signed.replace("uvec4 a=", "ivec4 a=").replace("uvec4 b=", "ivec4 b=")
    signed = signed.replace("imageStore(images[3],i,uvec4(a.x,0u,0u,1u))", "imageStore(images[3],i,ivec4(a.x,0,0,1))")
    signed = signed.replace("uvec4(a.x,b.x,n,m)", "uvec4(uint(a.x),uint(b.x),n,m)")
    (out / "buffers-signed.comp").write_text(signed)
    floating = base.replace("usamplerBuffer", "samplerBuffer").replace("uimageBuffer", "imageBuffer").replace("r32ui", "r32f")
    floating = floating.replace("uvec4 a=", "vec4 a=").replace("uvec4 b=", "vec4 b=")
    floating = floating.replace("imageStore(images[3],i,uvec4(a.x,0u,0u,1u))", "imageStore(images[3],i,vec4(a.x,0.0,0.0,1.0))")
    floating = floating.replace("uvec4(a.x,b.x,n,m)", "uvec4(floatBitsToUint(a.x),floatBitsToUint(b.x),n,m)")
    (out / "buffers-float.comp").write_text(floating)
    # A conditional read can legally be hoisted to TXF+UCMP by Mesa. A store
    # has an observable side effect and retains the genuine UIF arm whose
    # image/texture opcodes the native SIMT admission does not yet support.
    conditional = base.replace("imageStore(images[3],i,uvec4(a.x,0u,0u,1u));",
        "if((gl_LocalInvocationID.x&1u)!=0u)imageStore(images[3],i,uvec4(a.x,0u,0u,1u));")
    (out / "buffers-branch.comp").write_text(conditional)
    uniforms = base.replace("void main()", "uniform uvec4 defaults;\n" + "".join(
        "layout(std140,binding=%d) uniform Block%d {uvec4 value;} block%d;\n" % (24 + i, i, i)
        for i in range(12)) + "void main()")
    uniforms = uniforms.replace("    out0.result[i]=value;", "    value^=uvec4(defaults.x" +
        "".join(" ^ block%d.value.x" % i for i in range(12)) + ");\n    out0.result[i]=value;")
    (out / "buffers-uniforms.comp").write_text(uniforms)


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(buffer_resources=True)
