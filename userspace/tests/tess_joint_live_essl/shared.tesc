#version 320 es
precision highp float;
precision highp int;
layout(vertices=2) out;
layout(location=1) in uvec4 seed[];
layout(location=7) out uvec4 bridge[];

void main() {
    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
    bridge[gl_InvocationID] = seed[gl_InvocationID];
    if (gl_InvocationID == 0) {
        gl_TessLevelOuter[0] = 1.0;
        gl_TessLevelOuter[1] = 1.0;
        gl_TessLevelOuter[2] = 1.0;
        gl_TessLevelOuter[3] = 1.0;
        gl_TessLevelInner[0] = 1.0;
        gl_TessLevelInner[1] = 1.0;
    }
    barrier();
}
