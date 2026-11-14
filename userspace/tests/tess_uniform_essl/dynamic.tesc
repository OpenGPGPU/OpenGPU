#version 320 es
precision highp float; precision highp int;
layout(vertices=2) out;
out mat2x4 payload[];
patch out uvec4 sharedWords[2];
void main(){
 gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;
 payload[gl_InvocationID]=mat2x4(uintBitsToFloat(uvec4(uint(gl_InvocationID),2u,3u,4u)),vec4(5.0,6.0,7.0,8.0));
 sharedWords[0]=uvec4(9u,10u,11u,12u);sharedWords[1]=uvec4(13u,14u,15u,16u);
 gl_TessLevelOuter[gl_InvocationID]=1.0;barrier();
}
