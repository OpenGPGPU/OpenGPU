#version 320 es
precision highp float; precision highp int;
layout(std140,binding=0) uniform FragmentBlock0 {uvec4 words[1024];} b0;
layout(std140,binding=1) uniform FragmentBlock1 {uvec4 words[1024];} b1;
layout(std140,binding=2) uniform FragmentBlock2 {uvec4 words[1024];} b2;
layout(std140,binding=3) uniform FragmentBlock3 {uvec4 words[1024];} b3;
layout(std140,binding=4) uniform FragmentBlock4 {uvec4 words[1024];} b4;
layout(std140,binding=5) uniform FragmentBlock5 {uvec4 words[1024];} b5;
layout(std140,binding=6) uniform FragmentBlock6 {uvec4 words[1024];} b6;
layout(std140,binding=7) uniform FragmentBlock7 {uvec4 words[1024];} b7;
layout(std140,binding=8) uniform FragmentBlock8 {uvec4 words[1024];} b8;
layout(std140,binding=9) uniform FragmentBlock9 {uvec4 words[1024];} b9;
layout(std140,binding=10) uniform FragmentBlock10 {uvec4 words[1024];} b10;
layout(std140,binding=71) uniform FragmentBlock11 {uvec4 words[1024];} b11;
flat in uvec4 geometryColor;layout(location=0) out uvec4 result;
void main(){result=geometryColor^uvec4(b0.words[1023].w ^ b1.words[1023].w ^ b2.words[1023].w ^ b3.words[1023].w ^ b4.words[1023].w ^ b5.words[1023].w ^ b6.words[1023].w ^ b7.words[1023].w ^ b8.words[1023].w ^ b9.words[1023].w ^ b10.words[1023].w ^ b11.words[1023].w);}
