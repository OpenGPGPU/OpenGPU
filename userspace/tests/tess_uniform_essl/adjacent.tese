#version 320 es
precision highp float;precision highp int;
layout(isolines,equal_spacing) in;
in mat2x4 firstValue[];in mat2x4 secondValue[];
patch in uvec4 firstPatch[2];patch in uvec4 secondPatch[2];
uniform uvec4 indices;flat out uvec4 color;
void main(){
 gl_Position=gl_in[indices.x].gl_Position;
 color=floatBitsToUint(firstValue[indices.x][indices.y])^floatBitsToUint(secondValue[indices.x][indices.y])^firstPatch[indices.z]^secondPatch[indices.z];
}
