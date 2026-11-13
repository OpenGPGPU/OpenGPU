#!/usr/bin/env python3
"""Explicit real ESSL310/Mesa memory-barrier compiler acceptance, CPU only."""


def write_sources(out):
    prefix = ("#version 310 es\nprecision highp int;\n"
              "layout(local_size_x=1) in;\n"
              "layout(std430,binding=0) buffer Data {uint v[];} data;\n")
    for name, call in (("buffer", "memoryBarrierBuffer"), ("image", "memoryBarrierImage"),
                       ("counter", "memoryBarrierAtomicCounter"), ("shared", "memoryBarrierShared"),
                       ("group", "groupMemoryBarrier"), ("all", "memoryBarrier")):
        (out / (name + ".comp")).write_text(prefix + "void main(){data.v[0]=7u;" +
            call + "();data.v[1]=data.v[2]+3u;}\n")
    (out / "branch.comp").write_text(prefix.replace("local_size_x=1", "local_size_x=4") +
        "void main(){uint id=gl_LocalInvocationID.x;if((id&1u)!=0u){"
        "data.v[id]=7u;memoryBarrierBuffer();data.v[id+4u]=data.v[id+8u]+3u;}}\n")
    for epoch in (1, 2):
        (out / ("publication-%d.comp" % epoch)).write_text(prefix +
            "void main(){data.v[1]=%du;memoryBarrierBuffer();data.v[17]=%du;}\n" % (epoch * 7, epoch))


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(memory_barriers=True)
