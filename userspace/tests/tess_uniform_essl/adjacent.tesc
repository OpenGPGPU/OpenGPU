#version 320 es
precision highp float; precision highp int;
layout(vertices=2) out;
out mat2x4 firstValue[];out mat2x4 secondValue[];
patch out uvec4 firstPatch[2];patch out uvec4 secondPatch[2];
void main(){
 gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;
 firstValue[gl_InvocationID]=mat2x4(vec4(1,2,3,4),vec4(5,6,7,8));
 secondValue[gl_InvocationID]=mat2x4(vec4(9,10,11,12),vec4(13,14,15,16));
 firstPatch[0]=uvec4(17u);firstPatch[1]=uvec4(18u);
 secondPatch[0]=uvec4(19u);secondPatch[1]=uvec4(20u);
 gl_TessLevelOuter[gl_InvocationID]=1.0;barrier();
}
