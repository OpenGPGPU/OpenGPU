#version 320 es
precision highp float;precision highp int;
layout(isolines,equal_spacing) in;
in mat2x4 a[];in mat4 b[];in mat4 c[];in mat4 d[];
patch in uvec4 scratch[30];uniform uvec4 indices;flat out uvec4 color;
void main(){
 gl_Position=gl_in[indices.x].gl_Position;
 color=floatBitsToUint(a[indices.x][indices.y])^floatBitsToUint(b[indices.x][indices.y])^floatBitsToUint(c[indices.x][indices.y])^floatBitsToUint(d[indices.x][indices.y])^scratch[indices.z];
}
