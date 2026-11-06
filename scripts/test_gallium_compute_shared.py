#!/usr/bin/env python3
"""Explicit CPU-only real ESSL shared uint32/stripe/barrier acceptance."""


def write_sources(out):
    prefix = ("#version 310 es\nprecision highp int;\n"
              "layout(local_size_x=128,local_size_y=1,local_size_z=1) in;\n"
              "layout(std430,binding=0) buffer Output {uint v[];} result;\n"
              "shared uint data[4096];\nvoid main(){uint id=gl_LocalInvocationID.x;uint group=gl_WorkGroupID.x;\n")
    stores = "".join("data[id+%du]=0xa1000000u+(group<<16u)+id+%du;\n" % (r*128, r*128) for r in range(32))
    reads = "uint sum=0u;\n" + "".join("sum+=data[(id^16u)+%du];\n" % (r*128) for r in range(32))
    full = prefix + stores + "memoryBarrierShared();barrier();\n" + reads + "barrier();result.v[group*128u+id]=sum;}\n"
    (out / "full.comp").write_text(full)
    (out / "epoch-b.comp").write_text(full.replace("0xa1000000u", "0xa2000000u"))
    (out / "uninitialized.comp").write_text(prefix + reads + "barrier();result.v[group*128u+id]=sum;}\n")
    (out / "no-barrier.comp").write_text(full.replace("memoryBarrierShared();barrier();", "memoryBarrierShared();", 1))
    (out / "divergent.comp").write_text(full.replace("memoryBarrierShared();barrier();", "if(id<64u){barrier();}", 1))
    (out / "peer-store.comp").write_text(full.replace("data[id+", "data[(id^16u)+"))


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(shared_memory=True)
