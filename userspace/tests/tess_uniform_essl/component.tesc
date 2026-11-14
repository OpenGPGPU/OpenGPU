#version 320 es
precision highp float;precision highp int;
layout(vertices=2) out;
void main(){gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;gl_TessLevelOuter[gl_InvocationID]=1.0;barrier();}
