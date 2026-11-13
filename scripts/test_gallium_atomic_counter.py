#!/usr/bin/env python3
"""Actual Mesa HW_ATOMIC counter lowering and independent counter resources."""


def write_sources(out):
    prefix = ("#version 310 es\nprecision highp int;\nlayout(local_size_x=4) in;\n"
              "layout(binding=0,offset=0) uniform atomic_uint counters[8];\n")
    outputs = "".join("layout(std430,binding=%d) buffer Output%d {uint v[];} out%d;\n" % (i, i, i)
                      for i in range(4))
    for name, call in (("increment", "atomicCounterIncrement"), ("decrement", "atomicCounterDecrement"),
                       ("read", "atomicCounter")):
        (out / (name + ".comp")).write_text(prefix + outputs +
            "void main(){uint id=gl_GlobalInvocationID.x;uint value=" + call + "(counters[7]);"
            "out0.v[id]=value;out1.v[id]=value;out2.v[id]=value;out3.v[id]=value;}\n")


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(buffer_resources=True, atomic_counters=True)
