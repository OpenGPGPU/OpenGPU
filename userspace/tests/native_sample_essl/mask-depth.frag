#version 310 es
#extension GL_OES_sample_variables : require
precision highp float;
precision highp int;
layout(location=0) out vec4 color;
void main() {
    color=vec4(gl_SamplePosition.xy,0.25,1.0);
    gl_FragDepth=0.5;
    gl_SampleMask[0]=int((uint(gl_SampleMaskIn[0])&15u)^(1u<<uint(gl_SampleID)));
}
