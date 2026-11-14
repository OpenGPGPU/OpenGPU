#version 310 es
#extension GL_OES_sample_variables : require
precision highp float;
precision highp int;
layout(location=0) out vec4 color0;
layout(location=1) out vec4 color1;
layout(location=2) out vec4 color2;
layout(location=3) out vec4 color3;
void main() {
    color0=vec4(gl_SamplePosition.xy,float(uint(gl_SampleID)+0u),1.0);
    color1=vec4(gl_SamplePosition.xy,float(uint(gl_SampleID)+1u),1.0);
    color2=vec4(gl_SamplePosition.xy,float(uint(gl_SampleID)+2u),1.0);
    color3=vec4(gl_SamplePosition.xy,float(uint(gl_SampleID)+3u),1.0);
}
