#version 320 es
precision highp float;
precision highp int;
layout(vertices=32) out;
layout(location=1) in uvec4 seed[];
out mat3x4 a[];
out mat4 b[];
out mat4 c[];
out mat4 d[];

void main() {
    gl_out[gl_InvocationID].gl_Position = gl_in[0].gl_Position;
    a[gl_InvocationID][0].w = 1.0;
    a[gl_InvocationID][2].w = 2.0;
    b[gl_InvocationID][0].w = 4.0;
    b[gl_InvocationID][3].w = 8.0;
    c[gl_InvocationID][0].w = 16.0;
    c[gl_InvocationID][3].w = 32.0;
    d[gl_InvocationID][0].w = uintBitsToFloat(0x3f800004u + uint(gl_InvocationID));
    d[gl_InvocationID][3].w = uintBitsToFloat(0x3f800100u + seed[0].w + uint(gl_InvocationID));
    if (gl_InvocationID == 0) {
        gl_TessLevelOuter[0] = 1.0;
        gl_TessLevelOuter[1] = 1.0;
        gl_TessLevelOuter[2] = 1.0;
        gl_TessLevelOuter[3] = 1.0;
        gl_TessLevelInner[0] = 1.0;
        gl_TessLevelInner[1] = 2.0;
    }
    barrier();
}
