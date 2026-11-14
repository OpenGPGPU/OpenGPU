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
    cases = [(op, op in (5, 6), 'shared-atomic-%d.comp' % op) for op in range(10)]
    cases += [(op, True, 'shared-atomic-i-%d.comp' % op) for op in (0, 1, 2, 3, 4, 9)]
    for op, signed, name in cases:
        function = functions[op]
        typ, seed, operand = ('int', 'int(0x80000000u)', '-1') if signed else ('uint', '0x80000000u', '0xffffffffu')
        atomic_prefix = prefix.replace('shared uint', 'shared ' + typ)
        initialize = ''.join('data[id+%du]=%s;\n' % (r*128, seed) for r in range(32))
        def call(index):
            arguments = 'data[%du],' % index + (seed + ',' if op == 9 else '') + operand
            return 'uint(%s(%s))' % (function, arguments)
        (out / name).write_text(atomic_prefix + initialize +
            'memoryBarrierShared();barrier();\nuint first=' + call(0) + ';uint last=' + call(4095) +
            ';result.v[group*256u+id]=first;result.v[group*256u+128u+id]=last;}\n')
        def dynamic_call(index):
            arguments = 'data[' + index + '],' + (seed + ',' if op == 9 else '') + operand
            return 'uint(%s(%s))' % (function, arguments)
        dynamic = atomic_prefix + initialize + 'memoryBarrierShared();barrier();\n' + \
            'uint first=0xfeedfaceu;if((id&1u)==0u){first=' + dynamic_call('id^16u') + ';}\n' + \
            'uint last=' + dynamic_call('4080u+((id^group)&15u)') + ';barrier();\n' + \
            'result.v[group*512u+id]=first;result.v[group*512u+128u+id]=last;\n' + \
            'result.v[group*512u+256u+id]=uint(data[id]);\n' + \
            'result.v[group*512u+384u+id]=uint(data[3968u+id]);}\n'
        (out / name.replace('shared-atomic-', 'shared-dynamic-')).write_text(dynamic)
        if op == 1 and not signed:
            (out / 'dynamic-no-barrier.comp').write_text(dynamic.replace('memoryBarrierShared();barrier();', '', 1))
            (out / 'dynamic-uninitialized-tail.comp').write_text(dynamic.replace(initialize,
                'data[id]=0x80000000u;\n', 1))
            (out / 'dynamic-conditional-init.comp').write_text(dynamic.replace(initialize,
                'if(id<64u){' + initialize + '}\n', 1))

    # Different words and groups have different initial values. Per-address
    # old values now distinguish a lost group XOR or a permutation of atomic
    # targets even when every address still receives eight operations.
    from compute_owner_cases import ADDRESS_CASES
    for kind in ADDRESS_CASES:
        def address_call(address):
            if kind == 'shared-address-add':
                return 'atomicAdd(data[' + address + '],257u)'
            expected = '(0xa1000000u+(group<<16u)+' + address + ')'
            compare = expected + ('^1u' if kind.endswith('-miss') else '')
            return 'atomicCompSwap(data[' + address + '],' + compare + ',0xfedcba98u)'
        source = prefix + stores + 'memoryBarrierShared();barrier();\n' + \
            'uint first=0xfeedfaceu;if((id&1u)==0u){uint target=id^16u;first=' + address_call('target') + ';}\n' + \
            'uint target=4080u+((id^group)&15u);uint last=' + address_call('target') + ';barrier();\n' + \
            'result.v[group*512u+id]=first;result.v[group*512u+128u+id]=last;\n' + \
            'result.v[group*512u+256u+id]=data[id];\n' + \
            'result.v[group*512u+384u+id]=data[3968u+id];}\n'
        (out / (kind + '.comp')).write_text(source)


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(shared_memory=True)
