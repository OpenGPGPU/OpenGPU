#version 310 es
#extension GL_OES_sample_variables : require
precision highp float;
precision highp int;
layout(location=15) in vec4 v;
layout(location=3) out vec4 color;
void main() {
    color=v+vec4(gl_SamplePosition.xy,0.0,0.0);
}
