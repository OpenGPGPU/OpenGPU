#version 320 es
precision highp float; precision highp int;
flat in uvec4 color; layout(location=0) out uvec4 result;
void main(){result=color;}
