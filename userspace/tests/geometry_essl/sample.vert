#version 320 es
precision highp float;
layout(location=0) in vec4 position;
layout(location=1) in vec3 seed;
layout(location=0) out vec3 fromVertex[16];

void main() {
    gl_Position = position;
    for (int slot = 0; slot < 16; ++slot) {
        float base = float(slot * 8 + 1);
        fromVertex[slot] = seed + vec3(base, base + 2.0, base + 4.0);
    }
}
