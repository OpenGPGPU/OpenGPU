#version 320 es
precision highp float;
precision highp int;
layout(location=0) in vec3 toFragment[15];
layout(location=15) flat in vec3 flatFragment;
// Keep the multiply and add rounding of each weighted sum explicit.
layout(location=0) precise out vec4 color0;
layout(location=1) precise out vec4 color1;
layout(location=2) precise out vec4 color2;
layout(location=3) precise out vec4 color3;

void main() {
    color0 = vec4(toFragment[0] + toFragment[1] * 2.0 +
                  toFragment[2] * 3.0 + toFragment[3] * 4.0,
                  float(gl_PrimitiveID));
    color1 = vec4(toFragment[4] + toFragment[5] * 2.0 +
                  toFragment[6] * 3.0 + toFragment[7] * 4.0, float(gl_Layer));
    color2 = vec4(toFragment[8] + toFragment[9] * 2.0 +
                  toFragment[10] * 3.0 + toFragment[11] * 4.0, float(gl_SampleID));
    color3 = vec4(toFragment[12] + toFragment[13] * 2.0 +
                  toFragment[14] * 3.0 + flatFragment * 4.0, 1.0);
    gl_SampleMask[0] = gl_SampleMaskIn[0] & 5;
}
