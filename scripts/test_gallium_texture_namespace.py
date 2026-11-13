#!/usr/bin/env python3
"""Actual Mesa global texture units mapped to retained native compute views."""


def selections():
    return {
        "low16": list(range(16)),
        "middle16": list(range(16, 32)),
        "high16": list(range(32, 48)),
        "mixed16": [(17 * i) % 48 for i in range(16)],
        "full32": list(range(16, 48)),
        "rgba": [47],
    }


def write_sources(out):
    for name, units in selections().items():
        text = ("#version 310 es\n#extension GL_EXT_texture_buffer : require\n"
                "precision highp int;\nlayout(local_size_x=4) in;\n")
        text += "".join("layout(binding=%d) uniform highp usamplerBuffer src%d;\n" % (unit, i)
                        for i, unit in enumerate(units))
        text += "".join("layout(std430,binding=%d) buffer Output%d {uint v[];} out%d;\n" % (i, i, i)
                        for i in range(4))
        text += "void main(){uint id=gl_GlobalInvocationID.x;uint value=0x713a2b09u;\n"
        if name == "rgba":
            text = text[:text.index("void main()")]
            text += "void main(){uint id=gl_GlobalInvocationID.x;uvec4 value=texelFetch(src0,int(id));\n"
            text += "".join("out%d.v[id]=value.%s;\n" % (i, "xyzw"[i]) for i in range(4)) + "}\n"
            (out / (name + ".comp")).write_text(text)
            continue
        if name == "full32":
            text += "".join("value=value ^ (uint(textureSize(src%d)) << %du);\n" % (i, 1+i%7 if i%3==0 else 0)
                            for i in range(len(units)))
            text += "value=value*33u ^ texelFetch(src31,int(id)).x;\n"
        else:
            text += "".join("value=value*33u ^ texelFetch(src%d,int(id)).x;\n" % i for i in range(len(units)))
        text += "".join("out%d.v[id]=value ^ %du;\n" % (i, i * 0x10203) for i in range(4)) + "}\n"
        (out / (name + ".comp")).write_text(text)
    high = (out / "high16.comp").read_text()
    (out / "invalid48.comp").write_text(high.replace("binding=47", "binding=48"))
    full = (out / "full32.comp").read_text()
    (out / "too-many.comp").write_text(full.replace("void main()",
        "layout(binding=0) uniform highp usamplerBuffer extra;\nvoid main()").replace(
            "uint value=", "uint value=texelFetch(extra,int(id)).x ^ "))


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(buffer_resources=True, texture_namespaces=True)
