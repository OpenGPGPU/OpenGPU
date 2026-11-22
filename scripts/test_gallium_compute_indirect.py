#!/usr/bin/env python3
"""Real Mesa indirect callbacks and production driver bodies; CPU only."""


def write_sources(out):
    consumer = """#version 310 es
precision highp int;
layout(local_size_x=4,local_size_y=2,local_size_z=1) in;
layout(std430,binding=2) buffer Result { uvec4 value[]; } result;
void main() {
    uint group=gl_WorkGroupID.x+gl_NumWorkGroups.x*(gl_WorkGroupID.y+gl_NumWorkGroups.y*gl_WorkGroupID.z);
    uint index=group*8u+gl_LocalInvocationIndex;
    uint seed=0u;
    result.value[index]=uvec4(gl_NumWorkGroups,index^seed);
}
"""
    (out / "indirect-consumer.comp").write_text(consumer)
    from compute_indirect_sources import LOOP_CONSUMER
    (out / "indirect-loop.comp").write_text(LOOP_CONSUMER)
    uniform = consumer.replace("void main()", "uniform uint defaultSeed;\n"
        "layout(std140,binding=35) uniform Block { uvec4 value; } block;\nvoid main()")
    uniform = uniform.replace("uint seed=0u;", "uint seed=defaultSeed^block.value.x;")
    (out / "indirect-uniform.comp").write_text(uniform)
    counter = consumer.replace("void main()", "layout(binding=0,offset=0) uniform highp atomic_uint counter;\nvoid main()")
    counter = counter.replace("uint seed=0u;", "uint seed=0u; atomicCounterIncrement(counter);")
    (out / "indirect-counter.comp").write_text(counter)
    (out / "indirect-producer.comp").write_text("""#version 310 es
precision highp int;
layout(local_size_x=4) in;
layout(std430,binding=2) buffer Counts { uint value[]; } counts;
void main() {
    if(gl_LocalInvocationIndex==0u) {
        counts.value[1023]=2u; counts.value[1024]=2u; counts.value[1025]=2u;
    }
}
""")


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(indirect_compute=True)
