#version 320 es
precision highp float;
precision highp int;
layout(location=15) flat in uvec4 values;
layout(location=0) out uvec4 a;
layout(location=1) out uvec4 b;
layout(location=2) out uvec4 c;
layout(location=3) out uvec4 d;
void main(){a=values;b=values+uvec4(uint(gl_PrimitiveID));c=values^uvec4(7u);d=values;}
