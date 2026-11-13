#!/usr/bin/env python3
"""Actual Mesa HW_ATOMIC counter lowering and independent counter resources."""


def write_sources(out):
    prefix = ("#version 310 es\n#extension GL_EXT_texture_buffer : require\nprecision highp int;\nlayout(local_size_x=4) in;\n"
              "layout(binding=0,offset=0) uniform atomic_uint counters[8];\n")
    outputs = "".join("layout(std430,binding=%d) buffer Output%d {uint v[];} out%d;\n" % (i, i, i)
                      for i in range(4))
    for name, call in (("increment", "atomicCounterIncrement"), ("decrement", "atomicCounterDecrement"),
                       ("read", "atomicCounter")):
        for dynamic in (False, True):
            label = ("dynamic-" if dynamic else "") + name
            uniform = "layout(std140,binding=35) uniform Index {uvec4 index;} params;\n" if dynamic else ""
            index = "params.index.x+id" if dynamic else "7u"
            (out / (label + ".comp")).write_text(prefix + outputs + uniform +
                "void main(){uint id=gl_GlobalInvocationID.x;uint value=" + call + "(counters[" + index + "]);"
                "out0.v[id]=value;out1.v[id]=value;out2.v[id]=value;out3.v[id]=value;}\n")
    images = "".join("layout(r32ui,binding=%d) writeonly uniform highp uimageBuffer image%d;\n" % (i, i)
                     for i in range(4))
    (out / "coexist.comp").write_text(prefix + outputs + images +
        "void main(){uint id=gl_GlobalInvocationID.x;uint value=atomicCounterIncrement(counters[7]);"
        "out0.v[id]=value;out1.v[id]=value;out2.v[id]=value;out3.v[id]=value;" +
        "".join("imageStore(image%d,int(id),uvec4(value));" % i for i in range(4)) + "}\n")
    (out / "scalar.comp").write_text(prefix.replace("counters[8]", "counter") + outputs +
        "void main(){uint id=gl_GlobalInvocationID.x;uint value=atomicCounterIncrement(counter);"
        "out0.v[id]=value;out1.v[id]=value;out2.v[id]=value;out3.v[id]=value;}\n")



if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(buffer_resources=True, atomic_counters=True)
