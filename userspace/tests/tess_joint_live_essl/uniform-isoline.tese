#version 320 es
precision highp float;
precision highp int;
layout(isolines, equal_spacing) in;
layout(location=7) in uvec4 bridge[];
layout(location=0) flat out uvec4 shade;
layout(std140, binding=1) uniform EvaluationBlock0 { uvec4 words[1024]; } block0;
layout(std140, binding=62) uniform EvaluationBlock1 { uvec4 words[1024]; } block1;
layout(std140, binding=33) uniform EvaluationBlock2 { uvec4 words[1024]; } block2;
layout(std140, binding=30) uniform EvaluationBlock3 { uvec4 words[1024]; } block3;
layout(std140, binding=2) uniform EvaluationBlock4 { uvec4 words[1024]; } block4;
layout(std140, binding=19) uniform EvaluationBlock5 { uvec4 words[1024]; } block5;
layout(std140, binding=8) uniform EvaluationBlock6 { uvec4 words[1024]; } block6;
layout(std140, binding=42) uniform EvaluationBlock7 { uvec4 words[1024]; } block7;
layout(std140, binding=56) uniform EvaluationBlock8 { uvec4 words[1024]; } block8;
layout(std140, binding=13) uniform EvaluationBlock9 { uvec4 words[1024]; } block9;
layout(std140, binding=70) uniform EvaluationBlock10 { uvec4 words[1024]; } block10;
layout(std140, binding=24) uniform EvaluationBlock11 { uvec4 words[1024]; } block11;
uniform uvec4 evaluationDefaults[256];

void main() {
    gl_Position = gl_in[0].gl_Position + vec4(1.75 * gl_TessCoord.x, 0.0, 0.0, 0.0);
    uint blockWords = block0.words[1023].w ^ block1.words[1023].w ^ block2.words[1023].w ^
                      block3.words[1023].w ^ block4.words[1023].w ^ block5.words[1023].w ^
                      block6.words[1023].w ^ block7.words[1023].w ^ block8.words[1023].w ^
                      block9.words[1023].w ^ block10.words[1023].w ^ block11.words[1023].w;
    uvec4 control = bridge[0];
    shade = uvec4(control.xy, control.z ^ evaluationDefaults[255].w, control.w ^ blockWords);
}
