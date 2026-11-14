#version 320 es
precision highp float; precision highp int;
layout(isolines,equal_spacing) in;
layout(std140,binding=32) uniform EvalBlock {uvec4 words[1024];} blockData;
uniform uvec4 defaults[256];
in uvec4 payload[]; flat out uvec4 color;
void main(){
  gl_Position=gl_in[0].gl_Position + vec4(gl_TessCoord.x,0.0,0.0,0.0);
  color=payload[0] ^ blockData.words[1023] ^ defaults[255];
}
