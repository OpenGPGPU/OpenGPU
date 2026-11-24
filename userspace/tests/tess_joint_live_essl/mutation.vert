#version 320 es
precision highp float;
precision highp int;
layout(location=0) in vec4 position;
layout(location=1) in uvec4 instanceSeed;
layout(location=1) out uvec4 seed;
uniform uvec4 sourceColor;

void main() {
    gl_Position = position;
    seed = sourceColor ^ instanceSeed ^ uvec4(5u, 9u, 17u, 33u);
}
