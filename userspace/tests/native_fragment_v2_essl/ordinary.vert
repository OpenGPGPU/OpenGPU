#version 320 es
precision highp float;
precision highp int;
layout(location=0) in vec4 position;
layout(location=15) flat out uvec4 values;
void main(){gl_Position=position;values=uvec4(11u,13u,17u,19u);}
