#version 320 es
precision highp float; precision highp int;
layout(std140,binding=0) uniform GeometryBlock0 {uvec4 words[1024];} b0;
layout(std140,binding=1) uniform GeometryBlock1 {uvec4 words[1024];} b1;
layout(std140,binding=2) uniform GeometryBlock2 {uvec4 words[1024];} b2;
layout(std140,binding=3) uniform GeometryBlock3 {uvec4 words[1024];} b3;
layout(std140,binding=4) uniform GeometryBlock4 {uvec4 words[1024];} b4;
layout(std140,binding=5) uniform GeometryBlock5 {uvec4 words[1024];} b5;
layout(std140,binding=6) uniform GeometryBlock6 {uvec4 words[1024];} b6;
layout(std140,binding=7) uniform GeometryBlock7 {uvec4 words[1024];} b7;
layout(std140,binding=8) uniform GeometryBlock8 {uvec4 words[1024];} b8;
layout(std140,binding=9) uniform GeometryBlock9 {uvec4 words[1024];} b9;
layout(std140,binding=10) uniform GeometryBlock10 {uvec4 words[1024];} b10;
layout(std140,binding=71) uniform GeometryBlock11 {uvec4 words[1024];} b11;
layout(lines) in; layout(line_strip,max_vertices=2) out;
uniform uvec4 defaults[256];
flat in uvec4 color[];flat out uvec4 geometryColor;
void main(){
  if(defaults[0].x!=0u){gl_Position=gl_in[0].gl_Position;geometryColor=color[0];}
  else {gl_Position=gl_in[1].gl_Position;geometryColor=color[1];}
  geometryColor^=uvec4(b0.words[1023].w ^ b1.words[1023].w ^ b2.words[1023].w ^ b3.words[1023].w ^ b4.words[1023].w ^ b5.words[1023].w ^ b6.words[1023].w ^ b7.words[1023].w ^ b8.words[1023].w ^ b9.words[1023].w ^ b10.words[1023].w ^ b11.words[1023].w ^ defaults[255].w);
  EmitVertex();EndPrimitive();
}
