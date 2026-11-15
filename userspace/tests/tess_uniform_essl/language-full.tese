#version 320 es
precision highp float; precision highp int;
layout(isolines,equal_spacing) in;
layout(std140,binding=1) uniform EvalBlock0 {uvec4 words[1024];} block0;
layout(std140,binding=62) uniform EvalBlock1 {uvec4 words[1024];} block1;
layout(std140,binding=33) uniform EvalBlock2 {uvec4 words[1024];} block2;
layout(std140,binding=30) uniform EvalBlock3 {uvec4 words[1024];} block3;
layout(std140,binding=2) uniform EvalBlock4 {uvec4 words[1024];} block4;
layout(std140,binding=19) uniform EvalBlock5 {uvec4 words[1024];} block5;
layout(std140,binding=8) uniform EvalBlock6 {uvec4 words[1024];} block6;
layout(std140,binding=42) uniform EvalBlock7 {uvec4 words[1024];} block7;
layout(std140,binding=56) uniform EvalBlock8 {uvec4 words[1024];} block8;
layout(std140,binding=13) uniform EvalBlock9 {uvec4 words[1024];} block9;
layout(std140,binding=70) uniform EvalBlock10 {uvec4 words[1024];} block10;
layout(std140,binding=24) uniform EvalBlock11 {uvec4 words[1024];} block11;
uniform uvec4 defaults[256];
in uvec4 payload[]; flat out uvec4 color;
void main(){
  gl_Position=gl_in[0].gl_Position + vec4(gl_TessCoord.x,0.0,0.0,0.0);
  color=payload[0] ^ uvec4(block0.words[1023].w ^ block1.words[1023].w ^ block2.words[1023].w ^ block3.words[1023].w ^ block4.words[1023].w ^ block5.words[1023].w ^ block6.words[1023].w ^ block7.words[1023].w ^ block8.words[1023].w ^ block9.words[1023].w ^ block10.words[1023].w ^ block11.words[1023].w ^ defaults[255].w) ^ uvec4(uint(gl_PrimitiveID));
}
