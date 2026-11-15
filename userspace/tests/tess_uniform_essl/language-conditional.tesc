#version 320 es
precision highp float; precision highp int;
layout(vertices=2) out;
layout(std140,binding=71) uniform ControlBlock {uvec4 words[1024];} blockData;
uniform uvec4 defaults[256];
out uvec4 payload[];
void main(){
  gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;
  if(gl_InvocationID==0) payload[gl_InvocationID]=blockData.words[1023] ^ defaults[255] ^ uvec4(uint(gl_PrimitiveID));
  else payload[gl_InvocationID]=blockData.words[1022] ^ defaults[254] ^ uvec4(uint(gl_PrimitiveID));
  gl_TessLevelOuter[gl_InvocationID]=1.0;
  barrier();
}
