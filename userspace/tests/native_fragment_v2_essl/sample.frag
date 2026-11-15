#version 320 es
precision highp float;
precision highp int;
layout(location=0) out vec4 color;
void main(){color=vec4(float(gl_PrimitiveID),float(gl_SampleID),gl_SamplePosition);}
