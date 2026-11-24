#version 320 es
precision highp float;
precision highp int;
layout(location=0) flat in uvec4 shade;
layout(location=0) out uvec4 color;

void main() {
    color = shade;
}
