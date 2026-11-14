#version 310 es
#extension GL_OES_sample_variables : require
precision highp float;
precision highp int;
layout(location=0) out vec4 color;
void main() {
    uint v=uint(gl_SampleMaskIn[0])^0x80000000u;
    color=vec4(float(v),float(int(v)),float(uint(gl_SampleID)),1.0);
}
