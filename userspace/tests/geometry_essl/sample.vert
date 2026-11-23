#version 320 es
precision highp float;
layout(location=0) in vec4 position;
layout(location=1) in vec3 seed;
layout(location=0) out vec3 fromVertex[15];
layout(location=15) out vec3 flatVertex;

vec3 varyingAt(int slot) {
    float base = float(slot * 8 + 1);
    return seed + vec3(base, base + 2.0, base + 4.0);
}

void main() {
    gl_Position = position;
    fromVertex[0] = varyingAt(0);
    fromVertex[1] = varyingAt(1);
    fromVertex[2] = varyingAt(2);
    fromVertex[3] = varyingAt(3);
    fromVertex[4] = varyingAt(4);
    fromVertex[5] = varyingAt(5);
    fromVertex[6] = varyingAt(6);
    fromVertex[7] = varyingAt(7);
    fromVertex[8] = varyingAt(8);
    fromVertex[9] = varyingAt(9);
    fromVertex[10] = varyingAt(10);
    fromVertex[11] = varyingAt(11);
    fromVertex[12] = varyingAt(12);
    fromVertex[13] = varyingAt(13);
    fromVertex[14] = varyingAt(14);
    flatVertex = varyingAt(15);
}
