#version 310 es
#extension GL_OES_sample_variables : require
precision highp float;
precision highp int;
layout(location=0) out vec4 color;
void main() {
    color=vec4(float(uint(gl_SamplePosition.x*3.0)),float(int(gl_SamplePosition.y*3.0)+gl_SampleID),gl_SamplePosition.xy);
}
