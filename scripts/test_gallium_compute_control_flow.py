#!/usr/bin/env python3
"""Explicit CPU-only real ESSL/Mesa structured compute control-flow acceptance.

Uses the same real standalone/state-tracker/static-library builder as the
uniform fixture. Captures can also be consumed by the native CU replay.
"""


def write_sources(out):
    def prefix(local=(8, 4, 4)):
        return ("#version 310 es\nprecision highp int;\n"
                "layout(local_size_x=%d,local_size_y=%d,local_size_z=%d) in;\n" % local)

    buffers = "".join("layout(std430,binding=%d) buffer Output%d {uint v[];} o%d;\n" %
                      (i, i, i) for i in range(4))
    simple = {
        "store-if": "if((id&1u)!=0u)o0.v[id]=id*3u;else o0.v[id]=id+7u;",
        "if-only": "if((id&1u)!=0u)o0.v[id]=id*3u;",
        "nested": "if((id&8u)!=0u){if((id&4u)!=0u){if((id&2u)!=0u){if((id&1u)!=0u)o0.v[id]=1u;else o0.v[id]=2u;}else o0.v[id]=3u;}else o0.v[id]=4u;}else o0.v[id]=5u;",
        "early-return": "if(id<64u){o0.v[id]=3u;return;}o0.v[id]=5u;",
        "constant-loop": "uint a=id;for(uint i=0u;i<5u;i++)a=a*3u+i;o0.v[id]=a;",
        "dynamic-loop": "uint a=id;for(uint i=0u;i<(defaults[0].x&7u);i++)a=a*3u+i;o0.v[id]=a;",
        "loop-break": "uint a=id;for(uint i=0u;i<7u;i++){if(i==(id&3u))break;a=a*3u+i;}o0.v[id]=a;",
        "nested-loop": "uint a=id;for(uint i=0u;i<3u;i++){for(uint j=0u;j<(id&3u)+1u;j++)a=a*3u+i+j;}o0.v[id]=a;",
        "storage-loop": "for(uint i=0u;i<3u;i++)o0.v[id+i*128u]=o1.v[id]^i;",
        "continue-loop": "uint a=id;for(uint i=0u;i<6u;i++){if((i&1u)==(id&1u))continue;a=a*3u+i;}o0.v[id]=a;",
        "masked-storage-loop": "for(uint i=0u;i<4u;i++){if((i&1u)==(id&1u))continue;o0.v[id+i*128u]=o1.v[id]^i;}",
        "indexed-uniform-loop": "uint a=id;for(uint i=0u;i<3u;i++){if((i&1u)!=(id&1u))a=a*3u+defaults[(id+i*127u)&255u].w;}o0.v[id]=a;",
        "too-deep": "if((id&16u)!=0u){if((id&8u)!=0u){if((id&4u)!=0u){if((id&2u)!=0u){if((id&1u)!=0u)o0.v[id]=1u;else o0.v[id]=2u;}else o0.v[id]=3u;}else o0.v[id]=4u;}else o0.v[id]=5u;}else o0.v[id]=6u;",
    }
    for name, body in simple.items():
        uniforms = "uniform uvec4 defaults[256];\n" if name in ("dynamic-loop", "indexed-uniform-loop") else ""
        (out / (name + ".comp")).write_text(prefix() + buffers + uniforms +
            "void main(){uint id=gl_LocalInvocationIndex;" + body + "}\n")
    (out / "shared-barrier-mask.comp").write_text(prefix((128, 1, 1)) +
        "layout(std430,binding=0) buffer Output {uint v[];} result;\n"
        "shared uint published[128];\n"
        "void main() {\n"
        "  uint lane = gl_LocalInvocationID.x;\n"
        "  published[lane] = 0xa1000000u + lane;\n"
        "  memoryBarrierShared();\n"
        "  barrier();\n"
        "  uint peer = atomicAdd(published[lane ^ 16u], 3u);\n"
        "  if ((lane & 1u) != 0u) {\n"
        "    result.v[lane] = peer + 1u;\n"
        "  } else {\n"
        "    result.v[128u + lane] = peer ^ 7u;\n"
        "  }\n"
        "  memoryBarrierShared();\n"
        "  barrier();\n"
        "  result.v[512u + lane] = published[lane];\n"
        "}\n")
    # The invalid default-array indices belong exclusively to inactive lanes.
    # Dynamic CONST changes packed v0, independently from the outer warp mask.
    (out / "masked-uniform.comp").write_text(prefix() + buffers +
        "uniform uvec4 defaults[256];\nvoid main(){uint id=gl_LocalInvocationIndex;"
        "if((id&1u)!=0u){uint a=defaults[o0.v[id]].y;"
        "o1.v[id]=(a<0x80000000u)?a+3u:a^7u;}else o1.v[id]=id+11u;"
        "o2.v[id]=(id<31u)?0x12345678u:0x89abcdefu;}\n")
    for name, local in (("full128", (8, 4, 4)), ("full125", (5, 5, 5))):
        text = prefix(local) + buffers + "uniform uvec4 defaults[256];\n"
        text += "".join("layout(std140,binding=%d) uniform Block%d {uvec4 v[1024];} b%d;\n" %
                        (24+i, i, i) for i in range(12))
        text += "void main(){uint a=defaults[0].z^defaults[255].w;"
        text += "".join("a=a*33u^b%d.v[0].x^b%d.v[1023].w;" % (i, i) for i in range(12))
        text += "uint id=gl_WorkGroupID.x*%du+gl_LocalInvocationIndex;" % (local[0]*local[1]*local[2])
        text += "if((id&1u)!=0u){o0.v[id]=a^id;a=a+17u;}else{o0.v[id]=a+id;a=a^0x77u;}"
        text += "".join("o%d.v[id]=a^id^%du;" % (i, i*0x10203) for i in range(1, 4)) + "}\n"
        (out / (name + ".comp")).write_text(text)


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(control_flow=True)
