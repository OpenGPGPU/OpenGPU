#version 320 es
precision highp float;
precision highp int;
layout(location=0) out uvec4 color;
void main(){color=uvec4(uint(gl_PrimitiveID),0u,0u,1u);if(false)color.y=uint(gl_SampleID);}
