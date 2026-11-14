#version 320 es
precision highp float; precision highp int;
layout(std140,binding=0) uniform VertexBlock0 {uvec4 words[1024];} b0;
layout(std140,binding=1) uniform VertexBlock1 {uvec4 words[1024];} b1;
layout(std140,binding=2) uniform VertexBlock2 {uvec4 words[1024];} b2;
layout(std140,binding=3) uniform VertexBlock3 {uvec4 words[1024];} b3;
layout(std140,binding=4) uniform VertexBlock4 {uvec4 words[1024];} b4;
layout(std140,binding=5) uniform VertexBlock5 {uvec4 words[1024];} b5;
layout(std140,binding=6) uniform VertexBlock6 {uvec4 words[1024];} b6;
layout(std140,binding=7) uniform VertexBlock7 {uvec4 words[1024];} b7;
layout(std140,binding=8) uniform VertexBlock8 {uvec4 words[1024];} b8;
layout(std140,binding=9) uniform VertexBlock9 {uvec4 words[1024];} b9;
layout(std140,binding=10) uniform VertexBlock10 {uvec4 words[1024];} b10;
layout(std140,binding=71) uniform VertexBlock11 {uvec4 words[1024];} b11;
layout(location=0) in vec4 position;
void main(){gl_Position=position+vec4(float(b0.words[1023].w ^ b1.words[1023].w ^ b2.words[1023].w ^ b3.words[1023].w ^ b4.words[1023].w ^ b5.words[1023].w ^ b6.words[1023].w ^ b7.words[1023].w ^ b8.words[1023].w ^ b9.words[1023].w ^ b10.words[1023].w ^ b11.words[1023].w),0,0,0);}
