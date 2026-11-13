#!/usr/bin/env python3
"""Actual linked graphics UBO compiler/publication development fixture."""
def write_sources(out):
    for stage, suffix in ((0, "vert"), (1, "frag")):
        source = "#version 310 es\nprecision highp float;\nprecision highp int;\n"
        source += "uniform uvec4 defaults%d[256];\n" % stage
        for i in range(12):
            source += "layout(std140,binding=%d) uniform Block%d_%d {uvec4 values[1024];} block%d;\n" % (i + stage * 24, stage, i, i)
        source += ("layout(location=0) in vec4 position;\nflat out uvec4 varyingValue;\n" if not stage else
                   "flat in uvec4 varyingValue;\nlayout(location=0) out uvec4 color;\n")
        source += "void main(){uint sum=defaults%d[0].x ^ defaults%d[255].w;\n" % (stage, stage)
        for i in range(12):
            source += "sum=(sum*33u) ^ block%d.values[0].x ^ block%d.values[1023].w;\n" % (i, i)
        source += ("gl_Position=position;varyingValue=uvec4(sum);}\n" if not stage else
                   "color=varyingValue ^ uvec4(sum);}\n")
        (out / ("full." + suffix)).write_text(source)

if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(graphics_uniforms=True)
