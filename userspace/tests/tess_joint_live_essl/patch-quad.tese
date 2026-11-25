#version 320 es
precision highp float;
precision highp int;
layout(quads, equal_spacing, ccw) in;
in mat2x4 a[];
in mat4 b[];
in mat4 c[];
in mat4 d[];
patch in uvec4 patchWords[30];
uniform uvec4 indices;
layout(location=0) flat out uvec4 shade;

void main() {
    gl_Position = gl_in[indices.x].gl_Position + vec4(2.0 * gl_TessCoord.x, 2.0 * gl_TessCoord.y, 0.0, 0.0);
    shade.x = floatBitsToUint(a[indices.x][indices.y & 1u].w) ^
              floatBitsToUint(b[indices.x][indices.y].w) ^
              floatBitsToUint(c[indices.x][indices.y].w);
    shade.y = floatBitsToUint(d[indices.x][indices.y].w);
    shade.z = patchWords[indices.z][indices.w];
    shade.w = floatBitsToUint(gl_TessLevelOuter[0]) ^ floatBitsToUint(gl_TessLevelInner[1]);
}
