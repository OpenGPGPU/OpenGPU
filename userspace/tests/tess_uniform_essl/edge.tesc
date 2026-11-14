#version 320 es
precision highp float;precision highp int;
layout(vertices=32) out;
out mat3x4 a[];out mat4 b[];out mat4 c[];out mat4 d[];

void main(){
 gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;
 a[gl_InvocationID][0]=vec4(1,2,3,4);
 b[gl_InvocationID][0]=vec4(5,6,7,8);
 c[gl_InvocationID][0]=vec4(9,10,11,12);
 d[gl_InvocationID][0]=vec4(13,14,15,16);

 gl_TessLevelOuter[gl_InvocationID&1]=1.0;barrier();
}
