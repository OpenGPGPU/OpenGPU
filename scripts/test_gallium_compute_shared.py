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
    for name, offset in (("own", 0), ("own-end", 3968)):
        (out / (name + ".comp")).write_text(prefix +
            "data[id+%du]=0xa1000000u+(group<<16u)+id;memoryBarrierShared();\n" % offset +
            "result.v[group*128u+id]=data[id+%du];}\n" % offset)
    (out / "multi-own.comp").write_text(prefix + stores + "memoryBarrierShared();\n" +
        reads.replace("(id^16u)", "id") + "result.v[group*128u+id]=sum;}\n")
    (out / "own-shifted.comp").write_text(prefix +
        "data[id]=id;memoryBarrierShared();result.v[group*128u+id]=data[id+1u];}\n")

    functions = ('atomicExchange', 'atomicAdd', 'atomicXor', 'atomicOr', 'atomicAnd',
                 'atomicMin', 'atomicMax', 'atomicMin', 'atomicMax', 'atomicCompSwap')
    for op, function in enumerate(functions):
        signed = op in (5, 6)
        typ, seed, operand = ('int', 'int(0x80000000u)', '-1') if signed else ('uint', '0x80000000u', '0xffffffffu')
        atomic_prefix = prefix.replace('shared uint', 'shared ' + typ)
        initialize = ''.join('data[id+%du]=%s;\n' % (r*128, seed) for r in range(32))
        def call(index):
            arguments = 'data[%du],' % index + (seed + ',' if op == 9 else '') + operand
            return 'uint(%s(%s))' % (function, arguments)
        (out / ('shared-atomic-%d.comp' % op)).write_text(atomic_prefix + initialize +
            'memoryBarrierShared();barrier();\nuint first=' + call(0) + ';uint last=' + call(4095) +
            ';result.v[group*256u+id]=first;result.v[group*256u+128u+id]=last;}\n')


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(shared_memory=True)
