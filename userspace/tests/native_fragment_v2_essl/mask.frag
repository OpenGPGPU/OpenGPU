#version 320 es
precision highp float;
precision highp int;
layout(location=0) out uvec4 color;
void main(){color=uvec4(uint(gl_PrimitiveID),uint(gl_SampleMaskIn[0]),0u,1u);gl_SampleMask[0]=int(uint(gl_SampleMaskIn[0])&3u);}
