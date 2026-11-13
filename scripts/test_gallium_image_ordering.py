#!/usr/bin/env python3
"""Actual Mesa image/barrier programs and retained alias publication inputs."""


def write_sources(out):
    prefix = ("#version 310 es\n#extension GL_EXT_texture_buffer : require\n"
              "precision highp int;\nlayout(local_size_x=1) in;\n")
    image = "layout(r32ui,binding=0) coherent uniform highp uimageBuffer image0;\n"
    for epoch in (1, 2):
        (out / ("producer-%d.comp" % epoch)).write_text(prefix + image +
            "void main(){imageStore(image0,1,uvec4(%du));memoryBarrierImage();"
            "imageStore(image0,17,uvec4(%du));}\n" % (epoch * 7, epoch))
    output = "layout(std430,binding=0) buffer Output {uint v[];} result;\n"
    for kind in ("image", "texture"):
        resource = image if kind == "image" else "layout(binding=0) uniform highp usamplerBuffer texture0;\n"
        fetch = "imageLoad(image0,%s).x" if kind == "image" else "texelFetch(texture0,%s).x"
        # The mutable checked index prevents CSE of the second sampler fetch.
        # It remains one in the actual resource input; no compiler words or
        # descriptors are edited to create the repeated access.
        (out / (kind + "-consumer.comp")).write_text(prefix + resource + output +
            "void main(){result.v[0]=" + fetch % "1" + ";memoryBarrier();"
            "uint coordinate=result.v[2];result.v[1]=" + fetch % "int(coordinate)" + ";}\n")


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(buffer_resources=True, image_ordering=True)
