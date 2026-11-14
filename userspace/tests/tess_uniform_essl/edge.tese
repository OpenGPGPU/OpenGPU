#version 320 es
precision highp float;precision highp int;
layout(isolines,equal_spacing) in;
in mat3x4 a[];in mat4 b[];in mat4 c[];in mat4 d[];
uniform uvec4 indices;flat out uvec4 color;
void main(){
 gl_Position=gl_in[indices.x].gl_Position;
 color=floatBitsToUint(a[indices.x][indices.y])^floatBitsToUint(b[indices.x][indices.y])^floatBitsToUint(c[indices.x][indices.y])^floatBitsToUint(d[indices.x][indices.y]);
}
