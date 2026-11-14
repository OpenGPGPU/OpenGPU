#version 320 es
precision highp float; precision highp int;
layout(isolines,equal_spacing) in;
in mat2x4 payload[];patch in uvec4 sharedWords[2];
uniform uvec4 indices;
flat out uvec4 color;
void main(){
 gl_Position=gl_in[indices.x].gl_Position+vec4(gl_TessCoord.x,0.0,0.0,0.0);
 color=floatBitsToUint(payload[indices.x][indices.y])^sharedWords[indices.z];
}
