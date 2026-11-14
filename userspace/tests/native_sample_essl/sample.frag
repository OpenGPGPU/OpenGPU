#version 310 es
#extension GL_OES_sample_variables : require
precision highp float;
precision highp int;
layout(location=0) out vec4 color;
void main() {
    color=vec4(float(uint(gl_SampleID)),gl_SamplePosition.xy,float(uint(gl_SampleMaskIn[0])));
}
