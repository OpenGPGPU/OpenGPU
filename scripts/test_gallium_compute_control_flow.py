#!/usr/bin/env python3
"""Explicit CPU-only real ESSL/Mesa forward compute if/else acceptance.

Uses the same real standalone/state-tracker/static-library builder as the
uniform fixture. No GL capability, hardware execution or loop support claim.
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
        "too-deep": "if((id&16u)!=0u){if((id&8u)!=0u){if((id&4u)!=0u){if((id&2u)!=0u){if((id&1u)!=0u)o0.v[id]=1u;else o0.v[id]=2u;}else o0.v[id]=3u;}else o0.v[id]=4u;}else o0.v[id]=5u;}else o0.v[id]=6u;",
    }
    for name, body in simple.items():
        uniforms = "uniform uvec4 defaults[256];\n" if name == "dynamic-loop" else ""
        (out / (name + ".comp")).write_text(prefix() + buffers + uniforms +
            "void main(){uint id=gl_LocalInvocationIndex;" + body + "}\n")
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
