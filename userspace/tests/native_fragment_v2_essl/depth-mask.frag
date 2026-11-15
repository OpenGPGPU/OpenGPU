#version 320 es
precision highp float;
precision highp int;
layout(location=0) out vec4 color;
void main(){color=vec4(float(gl_PrimitiveID),0.25,0.5,1.0);gl_FragDepth=0.5;gl_SampleMask[0]=int(1u<<uint(gl_SampleID));}
