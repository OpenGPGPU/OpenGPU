#version 320 es
precision highp float; precision highp int;
layout(vertices=2) out;
layout(std140,binding=71) uniform ControlBlock0 {uvec4 words[1024];} block0;
layout(std140,binding=63) uniform ControlBlock1 {uvec4 words[1024];} block1;
layout(std140,binding=32) uniform ControlBlock2 {uvec4 words[1024];} block2;
layout(std140,binding=31) uniform ControlBlock3 {uvec4 words[1024];} block3;
layout(std140,binding=0) uniform ControlBlock4 {uvec4 words[1024];} block4;
layout(std140,binding=18) uniform ControlBlock5 {uvec4 words[1024];} block5;
layout(std140,binding=7) uniform ControlBlock6 {uvec4 words[1024];} block6;
layout(std140,binding=41) uniform ControlBlock7 {uvec4 words[1024];} block7;
layout(std140,binding=55) uniform ControlBlock8 {uvec4 words[1024];} block8;
layout(std140,binding=12) uniform ControlBlock9 {uvec4 words[1024];} block9;
layout(std140,binding=69) uniform ControlBlock10 {uvec4 words[1024];} block10;
layout(std140,binding=23) uniform ControlBlock11 {uvec4 words[1024];} block11;
uniform uvec4 defaults[256];
out uvec4 payload[];
void main(){
  gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;
  payload[gl_InvocationID]=uvec4(block0.words[1023].w ^ block1.words[1023].w ^ block2.words[1023].w ^ block3.words[1023].w ^ block4.words[1023].w ^ block5.words[1023].w ^ block6.words[1023].w ^ block7.words[1023].w ^ block8.words[1023].w ^ block9.words[1023].w ^ block10.words[1023].w ^ block11.words[1023].w ^ defaults[255].w);
  gl_TessLevelOuter[gl_InvocationID]=1.0;
  barrier();
}
