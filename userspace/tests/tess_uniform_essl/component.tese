#version 320 es
precision highp float;precision highp int;
layout(isolines,equal_spacing) in;
uniform uvec4 indices;flat out uvec4 color;
void main(){gl_Position=gl_in[0].gl_Position;color=uvec4(floatBitsToUint(gl_in[indices.x].gl_Position[indices.w]));}
