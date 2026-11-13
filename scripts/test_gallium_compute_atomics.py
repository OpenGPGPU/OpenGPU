#!/usr/bin/env python3
"""Explicit real ESSL310/Mesa checked-storage word atomic acceptance."""


def write_sources(out):
    def source(kind, expression, index="0u"):
        return ("#version 310 es\nprecision highp int;\nlayout(local_size_x=4) in;\n"
                "layout(std430,binding=0) buffer Target {%s v[];} target;\n" % kind +
                "layout(std430,binding=1) buffer Output {uint v[];} outputData;\n"
                "void main(){uint id=gl_WorkGroupID.x*4u+gl_LocalInvocationID.x;"
                "outputData.v[id]=uint(" + expression.replace("INDEX", index) + ");}\n")
    cases = {
        "exchange": ("uint", "atomicExchange(target.v[INDEX],7u)"),
        "add": ("uint", "atomicAdd(target.v[INDEX],5u)"),
        "xor": ("uint", "atomicXor(target.v[INDEX],0x80000007u)"),
        "or": ("uint", "atomicOr(target.v[INDEX],0x80000007u)"),
        "and": ("uint", "atomicAnd(target.v[INDEX],0x80000007u)"),
        "signed-min": ("int", "atomicMin(target.v[INDEX],-7)"),
        "signed-max": ("int", "atomicMax(target.v[INDEX],7)"),
        "unsigned-min": ("uint", "atomicMin(target.v[INDEX],7u)"),
        "unsigned-max": ("uint", "atomicMax(target.v[INDEX],0x80000007u)"),
        "compare-match": ("uint", "atomicCompSwap(target.v[INDEX],10u,7u)"),
        "compare-miss": ("uint", "atomicCompSwap(target.v[INDEX],11u,7u)"),
    }
    for name, (kind, expression) in cases.items():
        (out / (name + ".comp")).write_text(source(kind, expression))
    kind, expression = cases["add"]
    (out / "endpoint.comp").write_text(source(kind, expression, "33554431u"))
    (out / "past-end.comp").write_text(source(kind, expression, "33554432u"))
    (out / "branch.comp").write_text(source(kind, expression).replace(
        "outputData.v[id]=", "if((id&1u)!=0u)outputData.v[id]="))
    (out / "compare-inputs.comp").write_text(source("uint",
        "atomicCompSwap(target.v[outputData.v[id]&3u],"
        "params.value[outputData.v[id]&3u].x,params.value[outputData.v[id]&3u].y)").replace(
            "void main()", "layout(std140,binding=0) uniform Parameters {uvec4 value[4];} params;\nvoid main()"))


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(storage_atomics=True)
