#version 320 es
precision highp float;
precision highp int;
layout(isolines, equal_spacing) in;
layout(location=7) in uvec4 bridge[];
layout(location=0) flat out uvec4 shade;

void main() {
    gl_Position = gl_in[0].gl_Position + vec4(1.75 * gl_TessCoord.x, 0.0, 0.0, 0.0);
    shade = bridge[0];
}
