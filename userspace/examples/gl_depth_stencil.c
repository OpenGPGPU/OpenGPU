/* SPDX-License-Identifier: MIT */
/* GLES2 depth/stencil persistence and scissored clears through real GL calls.
 * The 8x8 FBO keeps this a routine rendering test, not a 720p qualification. */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <gbm.h>

#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "opengpu_gl_egl.h"

#define SIDE 8
#define INSIDE_X 3
#define INSIDE_Y 3
#define OUTSIDE_X 6
#define OUTSIDE_Y 5

static const char *vs_source =
    "attribute vec4 pos; attribute vec4 color; varying vec4 v_color;\n"
    "void main() { gl_Position = pos; v_color = color; }\n";
static const char *fs_source =
    "precision mediump float; varying vec4 v_color;\n"
    "void main() { gl_FragColor = v_color; }\n";

static void fail(const char *what)
{
    fprintf(stderr, "gl_depth_stencil: %s (GL 0x%x, EGL 0x%x)\n",
            what, glGetError(), eglGetError());
    exit(1);
}

static void check_gl(const char *what)
{
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        fprintf(stderr, "gl_depth_stencil: %s: GL error 0x%x\n", what, error);
        exit(1);
    }
}

static GLuint shader(GLenum type, const char *source)
{
    GLuint result = glCreateShader(type);
    GLint ok = 0;
    char log[1024];

    glShaderSource(result, 1, &source, NULL);
    glCompileShader(result);
    glGetShaderiv(result, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glGetShaderInfoLog(result, sizeof(log), NULL, log);
        fprintf(stderr, "gl_depth_stencil: shader: %s\n", log);
        exit(1);
    }
    return result;
}

/* GLES2 legacy base formats induce sampler-view component selection. */
static void sample_format_matrix(GLuint vbo, const char *label)
{
    static const char *sample_vs =
        "attribute vec4 pos; attribute vec2 uv; varying vec2 v_uv;\n"
        "void main() { gl_Position = pos; v_uv = uv; }\n";
    static const char *sample_fs =
        "precision mediump float; uniform sampler2D tex; varying vec2 v_uv;\n"
        "void main() { gl_FragColor = texture2D(tex, v_uv); }\n";
    static const float vertices[6][6] = {
        {-1,-1,0,1, .5,.5}, {1,-1,0,1, .5,.5}, {-1,1,0,1, .5,.5},
        {1,-1,0,1, .5,.5}, {1,1,0,1, .5,.5}, {-1,1,0,1, .5,.5},
    };
    static const struct {
        GLenum format;
        uint8_t input[4], expected[4];
    } cases[] = {
        {GL_LUMINANCE,       {72,0,0,0},     {72,72,72,255}},
        {GL_ALPHA,           {95,0,0,0},     {0,0,0,95}},
        {GL_LUMINANCE_ALPHA, {128,160,0,0},  {128,128,128,160}},
        {GL_RGB,             {12,34,56,0},   {12,34,56,255}},
        {GL_RGBA,            {21,43,65,87},  {21,43,65,87}},
    };
    GLuint vs = shader(GL_VERTEX_SHADER, sample_vs);
    GLuint fs = shader(GL_FRAGMENT_SHADER, sample_fs);
    GLuint program = glCreateProgram(), tex;
    GLint linked = 0, uniform, previous_program = 0;

    glGetIntegerv(GL_CURRENT_PROGRAM, &previous_program);
    glAttachShader(program, vs); glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos"); glBindAttribLocation(program, 1, "uv");
    glLinkProgram(program); glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) fail("link sampler shader");
    glUseProgram(program);
    uniform = glGetUniformLocation(program, "tex");
    if (uniform < 0) fail("sampler uniform missing");
    glUniform1i(uniform, 0);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)(4 * sizeof(float)));
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_DITHER);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glGenTextures(1, &tex); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    for (unsigned i = 0; i < sizeof(cases)/sizeof(cases[0]); i++) {
        uint8_t got[4] = {0};
        glTexImage2D(GL_TEXTURE_2D, 0, cases[i].format, 1, 1, 0,
                     cases[i].format, GL_UNSIGNED_BYTE, cases[i].input);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glFinish();
        glReadPixels(INSIDE_X, INSIDE_Y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, got);
        check_gl("legacy sampler base format");
        if (memcmp(got, cases[i].expected, sizeof(got))) {
            fprintf(stderr, "gl_depth_stencil: %s sampler format 0x%x "
                    "got %u,%u,%u,%u expected %u,%u,%u,%u\n", label, cases[i].format,
                    got[0],got[1],got[2],got[3],cases[i].expected[0],cases[i].expected[1],
                    cases[i].expected[2],cases[i].expected[3]);
            exit(1);
        }
    }
    glDeleteTextures(1, &tex);
    glUseProgram((GLuint)previous_program);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)(4 * sizeof(float)));
    glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    check_gl("destroy sampler matrix");
    printf("gl_depth_stencil: %s sampler base format matrix PASS\n", label);
}

static void sample_at(GLuint vbo, const char *label, const char *stage,
                      float s, float t, const uint8_t expected[4], unsigned tolerance)
{
    static const float xy[6][2] = {
        {-1,-1}, {1,-1}, {-1,1}, {1,-1}, {1,1}, {-1,1},
    };
    float vertices[6][6];
    uint8_t got[4] = {0};
    for (unsigned i = 0; i < 6; i++) {
        vertices[i][0] = xy[i][0]; vertices[i][1] = xy[i][1];
        vertices[i][2] = 0; vertices[i][3] = 1;
        vertices[i][4] = s; vertices[i][5] = t;
    }
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glFinish();
    glReadPixels(INSIDE_X, INSIDE_Y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, got);
    check_gl(stage);
    for (unsigned i = 0; i < 4; i++) {
        int difference = (int)got[i] - (int)expected[i];
        if (difference < -(int)tolerance || difference > (int)tolerance) {
            fprintf(stderr, "gl_depth_stencil: %s %s got %u,%u,%u,%u "
                    "expected %u,%u,%u,%u (+/-%u)\n", label, stage,
                    got[0], got[1], got[2], got[3], expected[0], expected[1],
                    expected[2], expected[3], tolerance);
            exit(1);
        }
    }
}

/* At pixel (3,3), perspective interpolation gives s=0.53 at the GLES
 * center, but approximately 0.481 at the integer corner. The two positions
 * therefore select different texels with nearest filtering. */
static void sample_perspective_center(GLuint vbo, const char *label,
                                      const uint8_t expected[4])
{
    static const float vertices[6][6] = {
        {-1,-1,0,1, .25f,.25f}, {2,-2,0,2, 1.25f,.25f}, {-1,1,0,1, .25f,.25f},
        {2,-2,0,2, 1.25f,.25f}, {2,2,0,2, 1.25f,.25f}, {-1,1,0,1, .25f,.25f},
    };
    uint8_t got[4] = {0};

    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glFinish();
    glReadPixels(INSIDE_X, INSIDE_Y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, got);
    check_gl("perspective pixel center");
    if (memcmp(got, expected, sizeof(got))) {
        fprintf(stderr, "gl_depth_stencil: %s perspective pixel center "
                "got %u,%u,%u,%u expected %u,%u,%u,%u\n", label,
                got[0], got[1], got[2], got[3],
                expected[0], expected[1], expected[2], expected[3]);
        exit(1);
    }
    printf("gl_depth_stencil: %s perspective pixel center PASS\n", label);
    fflush(stdout);
}

static void sample_repeat_gradient(GLuint vbo, const char *label,
                                   const uint8_t expected[4])
{
    /* At pixel (3,3), S is 0.1875. Independently wrapping the left and
     * right vertices would incorrectly make the entire triangle S=0.75. */
    static const float vertices[6][6] = {
        {-1,-1,0,1,-.25f,.25f}, {1,-1,0,1,.75f,.25f}, {-1,1,0,1,-.25f,.25f},
        {1,-1,0,1,.75f,.25f}, {1,1,0,1,.75f,.25f}, {-1,1,0,1,-.25f,.25f},
    };
    static const float perspective[6][6] = {
        {-1,-1,0,1,-.25f,.25f}, {2,-2,0,2,.75f,.25f}, {-1,1,0,1,-.25f,.25f},
        {2,-2,0,2,.75f,.25f}, {2,2,0,2,.75f,.25f}, {-1,1,0,1,-.25f,.25f},
    };
    const float (*cases[])[6] = {vertices, perspective};
    uint8_t got[4] = {0};
    for (unsigned i = 0; i < 2; i++) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), cases[i], GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glFinish();
        glReadPixels(INSIDE_X, INSIDE_Y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, got);
        check_gl("repeat interpolated coordinates");
        if (memcmp(got, expected, sizeof(got))) {
            fprintf(stderr, "gl_depth_stencil: %s repeat gradient %u got %u,%u,%u,%u "
                    "expected %u,%u,%u,%u\n", label, i, got[0], got[1], got[2], got[3],
                    expected[0], expected[1], expected[2], expected[3]);
            exit(1);
        }
    }
}

static void draw(GLuint vbo, float z, float r, float g, float b);

/* Paint a real draw plus three scissored updates in the current 8x8 source. */
static void paint_copy_source(GLuint vbo, GLuint color_program, GLuint sample_program)
{
    glUseProgram(color_program);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)0);
    glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)(4*sizeof(float)));
    draw(vbo,0,1,0,0);
    glEnable(GL_SCISSOR_TEST);
    glScissor(3,2,1,1); glClearColor(0,1,0,1); glClear(GL_COLOR_BUFFER_BIT);
    glScissor(2,3,1,1); glClearColor(0,0,1,1); glClear(GL_COLOR_BUFFER_BIT);
    glScissor(3,3,1,1); glClearColor(1,1,0,1); glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);
    glUseProgram(sample_program);
    glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,6*sizeof(float),(void *)0);
    glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,6*sizeof(float),(void *)(4*sizeof(float)));
}

/* Copy from the real render target into a different texture, including a
 * subrectangle with untouched neighbors and aligned CPU readback rows. */
static void texture_copy_matrix(GLuint vbo, GLuint tex, GLuint color_program,
                                GLuint sample_program, const char *label)
{
    static const uint8_t colors[4][4] = {
        {255,0,0,255}, {0,255,0,255}, {0,0,255,255}, {255,255,0,255}
    };
    static const uint8_t border[4] = {7,11,13,255};
    uint8_t image[4*4*4], readback[32], expected[32];

    paint_copy_source(vbo,color_program,sample_program);
    memset(readback,0xa5,sizeof(readback));
    memset(expected,0xa5,sizeof(expected));
    memcpy(expected,colors[0],4); memcpy(expected+4,colors[1],4);
    memcpy(expected+8,colors[0],4); memcpy(expected+16,colors[2],4);
    memcpy(expected+20,colors[3],4); memcpy(expected+24,colors[0],4);
    glPixelStorei(GL_PACK_ALIGNMENT,8);
    glReadPixels(2,2,3,2,GL_RGBA,GL_UNSIGNED_BYTE,readback);
    glPixelStorei(GL_PACK_ALIGNMENT,4); check_gl("aligned framebuffer readback");
    if (memcmp(readback,expected,sizeof(expected)))
        fail("aligned framebuffer readback bytes or padding differ");
    for (unsigned i=0;i<16;i++) memcpy(image+4*i,border,4);
    glBindTexture(GL_TEXTURE_2D,tex);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,4,4,0,GL_RGBA,GL_UNSIGNED_BYTE,image);
    glCopyTexSubImage2D(GL_TEXTURE_2D,0,1,1,2,2,2,2);
    check_gl("copy framebuffer subrectangle");
    for (unsigned i=0;i<4;i++)
        sample_at(vbo,label,"copied texture subrectangle",(i&1) ? .625f : .375f,
                  (i&2) ? .625f : .375f,colors[i],0);
    sample_at(vbo,label,"copy preserves texture border",.125f,.125f,border,0);

    /* Do not read back between this draw and the full-image copy. */
    paint_copy_source(vbo,color_program,sample_program);
    glCopyTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,2,2,2,2,0);
    check_gl("copy framebuffer image");
    for (unsigned i=0;i<4;i++)
        sample_at(vbo,label,"copied texture image",(i&1) ? .75f : .25f,
                  (i&2) ? .75f : .25f,colors[i],0);
    printf("gl_depth_stencil: %s texture copy matrix PASS\n",label);
    fflush(stdout);
}

/* A multi-texel resource, subimage update and interpolation through GLES. */
static void texture_resource_matrix(GLuint vbo, const char *label)
{
    static const char *sample_vs =
        "attribute vec4 pos; attribute vec2 uv; varying vec2 v_uv;\n"
        "void main() { gl_Position = pos; v_uv = uv; }\n";
    static const char *sample_fs =
        "precision mediump float; uniform sampler2D tex; varying vec2 v_uv;\n"
        "void main() { gl_FragColor = texture2D(tex, v_uv); }\n";
    static const uint8_t image[4][4] = {
        {16,32,48,255}, {80,96,112,255}, {144,160,176,255}, {208,224,240,255},
    };
    static const uint8_t replacement[4] = {32,64,96,255};
    static const uint8_t average[4] = {100,120,140,255};
    static const uint8_t upper_seam[4] = {176,192,208,255};
    static const uint8_t right_seam[4] = {144,160,176,255};
    static const uint8_t seam[4] = {48,64,80,255};
    /* GL_UNPACK_ALIGNMENT=4 pads each nine-byte RGB row to 12 bytes. */
    static const uint8_t rgb_rows[24] = {
        10,20,30, 40,50,60, 70,80,90, 0,0,0,
        11,21,31, 41,51,61, 71,81,91, 0,0,0,
    };
    static const uint8_t rgb_first[4] = {10,20,30,255};
    static const uint8_t rgb_last[4] = {71,81,91,255};
    static const uint8_t red[4] = {255,0,0,255}, green[4] = {0,255,0,255};
    GLuint vs = shader(GL_VERTEX_SHADER, sample_vs), fs = shader(GL_FRAGMENT_SHADER, sample_fs);
    GLuint program = glCreateProgram(), tex, source_fb;
    GLint linked = 0, previous_program = 0, previous_fb = 0, uniform;

    glGetIntegerv(GL_CURRENT_PROGRAM, &previous_program);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &previous_fb);
    glAttachShader(program, vs); glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos"); glBindAttribLocation(program, 1, "uv");
    glLinkProgram(program); glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) fail("link texture resource shader");
    glUseProgram(program); uniform = glGetUniformLocation(program, "tex");
    if (uniform < 0) fail("texture resource uniform missing");
    glUniform1i(uniform, 0);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)(4 * sizeof(float)));
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_DITHER);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glGenTextures(1, &tex); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, image);
    if (getenv("OPENGPU_GL_TEXTURE_NEAREST")) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        sample_at(vbo, label, "nearest RGBA texel", .375f, .375f, image[0], 0);
        sample_at(vbo, label, "nearest RGBA boundary", .5f, .5f, image[3], 0);
        sample_perspective_center(vbo, label, image[1]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }
    if (getenv("OPENGPU_GL_TEXTURE_REPEAT")) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        sample_at(vbo, label, "repeat positive first texel", 1.25f, .25f, image[0], 0);
        sample_at(vbo, label, "repeat positive last texel", 1.75f, 1.75f, image[3], 0);
        sample_at(vbo, label, "repeat negative", -.75f, -.75f, image[0], 0);
        sample_repeat_gradient(vbo, label, image[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        sample_at(vbo, label, "linear repeat seam", 1.0f, .25f, seam, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        sample_at(vbo, label, "switch back to clamp", 1.25f, .25f, image[1], 0);
        printf("gl_depth_stencil: %s repeat texture matrix PASS\n", label);
        fflush(stdout);
    }
    if (getenv("OPENGPU_GL_TEXTURE_AXIS_WRAP")) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        sample_at(vbo, label, "repeat S clamp T", 1.25f, 1.25f, image[2], 0);
        sample_at(vbo, label, "negative repeat S", -.75f, .25f, image[0], 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        sample_at(vbo, label, "repeat S linear seam", 1.0f, 1.25f, upper_seam, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        sample_at(vbo, label, "clamp S repeat T", 1.25f, 1.25f, image[1], 0);
        sample_at(vbo, label, "negative repeat T", .25f, -.75f, image[0], 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        sample_at(vbo, label, "repeat T linear seam", 1.25f, 1.0f, right_seam, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        printf("gl_depth_stencil: %s per-axis wrap matrix PASS\n", label);
        fflush(stdout);
    }
    for (unsigned i = 0; i < 4; i++)
        sample_at(vbo, label, "RGBA texel", (i & 1) ? .75f : .25f,
                  (i & 2) ? .75f : .25f, image[i], 0);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 1, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, replacement);
    sample_at(vbo, label, "updated RGBA texel", .75f, .25f, replacement, 0);
    sample_at(vbo, label, "untouched RGBA texel", .25f, .75f, image[2], 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    sample_at(vbo, label, "linear RGBA texels", .5f, .5f, average, 2);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 3, 2, 0, GL_RGB, GL_UNSIGNED_BYTE, rgb_rows);
    sample_at(vbo, label, "RGB padded first row", 1.0f/6.0f, .25f, rgb_first, 2);
    sample_at(vbo, label, "RGB padded second row", 5.0f/6.0f, .75f, rgb_last, 2);
    /* Render to a texture, then sample both rows through another FBO. */
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenFramebuffers(1, &source_fb); glBindFramebuffer(GL_FRAMEBUFFER, source_fb);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        fail("texture resource framebuffer incomplete");
    glClearColor(1, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST); glScissor(0, 1, 2, 1);
    glClearColor(0, 1, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST); glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)previous_fb);
    sample_at(vbo, label, "rendered lower row", .25f, .25f, red, 1);
    sample_at(vbo, label, "rendered upper row", .25f, .75f, green, 1);
    if (getenv("OPENGPU_GL_TEXTURE_COPIES"))
        texture_copy_matrix(vbo, tex, (GLuint)previous_program, program, label);
    glDeleteFramebuffers(1, &source_fb);
    glDeleteTextures(1, &tex);
    glUseProgram((GLuint)previous_program);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)(4 * sizeof(float)));
    glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    check_gl("destroy texture resource matrix");
    printf("gl_depth_stencil: %s texture resource matrix PASS\n", label);
    fflush(stdout);
}

/* Exercise default-window Y orientation without assuming an 8x8 viewport. */
static void texture_copy_window(GLuint vbo)
{
    static const char *sample_vs =
        "attribute vec4 pos; attribute vec2 uv; varying vec2 v_uv;\n"
        "void main() { gl_Position = pos; v_uv = uv; }\n";
    static const char *sample_fs =
        "precision mediump float; uniform sampler2D tex; varying vec2 v_uv;\n"
        "void main() { gl_FragColor = texture2D(tex, v_uv); }\n";
    GLuint vs=shader(GL_VERTEX_SHADER,sample_vs), fs=shader(GL_FRAGMENT_SHADER,sample_fs);
    GLuint program=glCreateProgram(), tex;
    GLint linked=0, previous_program=0, uniform;

    glGetIntegerv(GL_CURRENT_PROGRAM,&previous_program);
    glAttachShader(program,vs); glAttachShader(program,fs);
    glBindAttribLocation(program,0,"pos"); glBindAttribLocation(program,1,"uv");
    glLinkProgram(program); glGetProgramiv(program,GL_LINK_STATUS,&linked);
    if (!linked) fail("link window copy shader");
    glUseProgram(program); uniform=glGetUniformLocation(program,"tex");
    if (uniform<0) fail("window copy sampler missing");
    glUniform1i(uniform,0);
    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_DITHER);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    glGenTextures(1,&tex); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,tex);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    texture_copy_matrix(vbo,tex,(GLuint)previous_program,program,"default surface");
    glDeleteTextures(1,&tex); glUseProgram((GLuint)previous_program);
    glBindBuffer(GL_ARRAY_BUFFER,vbo);
    glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)0);
    glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)(4*sizeof(float)));
    glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    check_gl("destroy window texture copy matrix");
}

/* Exercise GBM export and EGL import with distinct pixels in both rows. The
 * source BO, dma-buf fd and EGLImage are released before the final GLES draws,
 * so the texture must retain the imported resource on its own. */
static void dma_buf_texture_matrix(GLuint vbo, struct gbm_device *gbm,
                                   EGLDisplay display, const char *label)
{
    static const char *sample_vs =
        "attribute vec4 pos; attribute vec2 uv; varying vec2 v_uv;\n"
        "void main() { gl_Position = pos; v_uv = uv; }\n";
    static const char *sample_fs =
        "precision mediump float; uniform sampler2D tex; varying vec2 v_uv;\n"
        "void main() { gl_FragColor = texture2D(tex, v_uv); }\n";
    static const uint8_t pixels[4][4] = {
        {13,37,71,255}, {97,19,41,255}, {23,113,53,255}, {47,61,127,255}
    };
    static const uint8_t replacement[4] = {83,43,17,255};
    PFNEGLCREATEIMAGEKHRPROC create_image =
        (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    PFNEGLDESTROYIMAGEKHRPROC destroy_image =
        (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC target_image =
        (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    struct gbm_bo *bo;
    void *mapping, *map_data = NULL;
    uint32_t map_stride, pitch;
    int dma_fd;
    EGLImageKHR image;
    GLuint vs, fs, program, tex;
    GLint linked = 0, previous_program = 0, uniform;
    const char *egl_extensions = eglQueryString(display, EGL_EXTENSIONS);
    const char *gl_extensions = (const char *)glGetString(GL_EXTENSIONS);

    if (!gbm || !create_image || !destroy_image || !target_image || !egl_extensions ||
        !gl_extensions || !strstr(egl_extensions, "EGL_EXT_image_dma_buf_import") ||
        !strstr(gl_extensions, "GL_OES_EGL_image"))
        fail("DMA-BUF EGL image extensions unavailable");
    bo = gbm_bo_create(gbm, 2, 2, GBM_FORMAT_ABGR8888,
                       GBM_BO_USE_RENDERING | GBM_BO_USE_LINEAR);
    if (!bo)
        fail("create DMA-BUF source BO");
    mapping = gbm_bo_map(bo, 0, 0, 2, 2, GBM_BO_TRANSFER_WRITE,
                         &map_stride, &map_data);
    if (!mapping || map_stride < 8)
        fail("map DMA-BUF source BO");
    for (unsigned y = 0; y < 2; y++)
        memcpy((uint8_t *)mapping + y * map_stride, &pixels[2 * y], 8);
    gbm_bo_unmap(bo, map_data);
    pitch = gbm_bo_get_stride(bo);
    dma_fd = gbm_bo_get_fd(bo);
    if (dma_fd < 0 || pitch < 8)
        fail("export DMA-BUF source BO");
    {
        const EGLint attributes[] = {
            EGL_WIDTH, 2, EGL_HEIGHT, 2,
            EGL_LINUX_DRM_FOURCC_EXT, GBM_FORMAT_ABGR8888,
            EGL_DMA_BUF_PLANE0_FD_EXT, dma_fd,
            EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
            EGL_DMA_BUF_PLANE0_PITCH_EXT, (EGLint)pitch, EGL_NONE
        };
        image = create_image(display, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT,
                             NULL, attributes);
    }
    if (image == EGL_NO_IMAGE_KHR)
        fail("import DMA-BUF EGL image");

    vs = shader(GL_VERTEX_SHADER, sample_vs); fs = shader(GL_FRAGMENT_SHADER, sample_fs);
    program = glCreateProgram();
    glGetIntegerv(GL_CURRENT_PROGRAM, &previous_program);
    glAttachShader(program, vs); glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos"); glBindAttribLocation(program, 1, "uv");
    glLinkProgram(program); glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) fail("link DMA-BUF sampling shader");
    glUseProgram(program); uniform = glGetUniformLocation(program, "tex");
    if (uniform < 0) fail("DMA-BUF sampling uniform missing");
    glUniform1i(uniform, 0);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)(4 * sizeof(float)));
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_DITHER);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glGenTextures(1, &tex); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex);
    target_image(GL_TEXTURE_2D, image);
    check_gl("bind imported DMA-BUF image");
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (!destroy_image(display, image)) fail("destroy DMA-BUF EGL image");
    close(dma_fd);
    sample_at(vbo, label, "DMA-BUF initial texel", .25f, .25f, pixels[0], 0);
    /* A completed draw must see a later producer update through the alias. */
    map_data = NULL;
    mapping = gbm_bo_map(bo, 0, 1, 1, 1, GBM_BO_TRANSFER_WRITE,
                         &map_stride, &map_data);
    if (!mapping || map_stride < 4)
        fail("remap DMA-BUF source BO");
    memcpy(mapping, replacement, sizeof(replacement));
    gbm_bo_unmap(bo, map_data);
    gbm_bo_destroy(bo);
    for (unsigned i = 0; i < 4; i++)
        sample_at(vbo, label, "DMA-BUF texel", (i & 1) ? .75f : .25f,
                  (i & 2) ? .75f : .25f, i == 2 ? replacement : pixels[i], 0);
    glDeleteTextures(1, &tex);
    glUseProgram((GLuint)previous_program);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)(4 * sizeof(float)));
    glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    check_gl("destroy DMA-BUF sampling resources");
    printf("gl_depth_stencil: %s DMA-BUF texture matrix PASS\n", label);
    fflush(stdout);
}

static void expect_pixel(const char *stage, int x, int y,
                         uint8_t r, uint8_t g, uint8_t b)
{
    uint8_t pixel[4] = {0};

    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    check_gl(stage);
    if (pixel[0] != r || pixel[1] != g || pixel[2] != b || pixel[3] != 255) {
        fprintf(stderr, "gl_depth_stencil: %s pixel (%d,%d) "
                "got %u,%u,%u,%u expected %u,%u,%u,255\n", stage, x, y,
                pixel[0], pixel[1], pixel[2], pixel[3], r, g, b);
        exit(1);
    }
}

static void expect_pair(const char *stage, const uint8_t inside[3],
                        const uint8_t outside[3])
{
    glFinish();
    check_gl(stage);
    expect_pixel(stage, INSIDE_X, INSIDE_Y, inside[0], inside[1], inside[2]);
    expect_pixel(stage, OUTSIDE_X, OUTSIDE_Y, outside[0], outside[1], outside[2]);
}

static void draw(GLuint vbo, float z, float r, float g, float b)
{
    static const float xy[6][2] = {
        {-1, -1}, {1, -1}, {-1, 1}, {1, -1}, {1, 1}, {-1, 1}
    };
    float vertices[6][8];
    unsigned i;

    for (i = 0; i < 6; i++) {
        vertices[i][0] = xy[i][0]; vertices[i][1] = xy[i][1];
        vertices[i][2] = z; vertices[i][3] = 1.0f;
        vertices[i][4] = r; vertices[i][5] = g;
        vertices[i][6] = b; vertices[i][7] = 1.0f;
    }
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    check_gl("draw");
}

/* Check the complete target, including viewport regions outside it. */
static void viewport_matrix(GLuint vbo, int width, int height, const char *label)
{
    static const int boxes[][4]={
        {2,1,4,4}, {-2,2,5,4}, {3,-1,4,5}, {6,6,5,5},
        {0,0,0,8}, {0,0,8,0}, {-3,-2,13,12},
    };
    uint8_t pixels[32*32*4];
    if (width<=0 || height<=0 || width>32 || height>32)
        fail("viewport fixture target size");
    glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    for (unsigned i=0;i<sizeof(boxes)/sizeof(boxes[0]);i++) {
        const int *b=boxes[i];
        glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
        glViewport(b[0],b[1],b[2],b[3]);
        draw(vbo,0,1,0,0);
        glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        check_gl("viewport matrix");
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            bool inside=x>=b[0] && x<b[0]+b[2] && y>=b[1] && y<b[1]+b[3];
            const uint8_t *p=pixels+(y*width+x)*4;
            if (p[0]!=(inside ? 255 : 0) || p[1] || p[2] || p[3]!=255) {
                fprintf(stderr,"viewport %s case %u (%d,%d): got %u,%u,%u,%u\n",
                        label,i,x,y,p[0],p[1],p[2],p[3]);
                fail("viewport pixel mismatch");
            }
        }
    }
    glDisable(GL_SCISSOR_TEST); glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
    glViewport(2,1,4,4); glEnable(GL_SCISSOR_TEST); glScissor(3,2,2,2);
    draw(vbo,0,1,0,0); glDisable(GL_SCISSOR_TEST);
    glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
        int in=x>=3 && x<5 && y>=2 && y<4; const uint8_t *p=pixels+(y*width+x)*4;
        if (p[0]!=(in?255:0) || p[1] || p[2] || p[3]!=255) fail("viewport scissor mismatch");
    }
    {
        static const float verts[6][8]={
          {-1,-1,0,1,1,0,0,1},{1,-1,0,1,1,0,0,1},{-1,1,0,1,0,1,0,1},
          {1,-1,0,1,1,0,0,1},{1,1,0,1,0,1,0,1},{-1,1,0,1,0,1,0,1}};
        glClear(GL_COLOR_BUFFER_BIT); glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glBufferData(GL_ARRAY_BUFFER,sizeof(verts),verts,GL_STREAM_DRAW); glDrawArrays(GL_TRIANGLES,0,6);
        glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels); check_gl("viewport orientation");
        const uint8_t *lo=pixels+(1*width+3)*4, *hi=pixels+(4*width+3)*4;
        if (!(lo[0]>lo[1] && hi[1]>hi[0])) fail("viewport orientation mismatch");
    }
    printf("gl_depth_stencil: %s viewport scissor and orientation PASS\n",label); fflush(stdout);
    {
        static const char *fragment=
          "precision mediump float; void main() { if (gl_FragCoord.y < 3.0) "
          "gl_FragColor=vec4(1.0,0.0,0.0,1.0); else gl_FragColor=vec4(0.0,1.0,0.0,1.0); }";
        GLuint vs=shader(GL_VERTEX_SHADER,vs_source), fs=shader(GL_FRAGMENT_SHADER,fragment);
        GLuint prog=glCreateProgram(); GLint ok=0, previous=0;
        glGetIntegerv(GL_CURRENT_PROGRAM,&previous); glAttachShader(prog,vs); glAttachShader(prog,fs);
        glBindAttribLocation(prog,0,"pos"); glBindAttribLocation(prog,1,"color");
        glLinkProgram(prog); glGetProgramiv(prog,GL_LINK_STATUS,&ok); if (!ok) fail("fragcoord link");
        glUseProgram(prog); glDisable(GL_SCISSOR_TEST); glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
        glViewport(2,1,4,4); draw(vbo,0,0,0,0);
        glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels); check_gl("viewport fragcoord");
        const uint8_t *lo=pixels+(1*width+3)*4, *hi=pixels+(4*width+3)*4;
        if (!(lo[0]>200 && lo[1]<10 && hi[1]>200 && hi[0]<10)) fail("viewport fragcoord mismatch");
        glUseProgram((GLuint)previous); glDeleteProgram(prog); glDeleteShader(vs); glDeleteShader(fs);
    }
    printf("gl_depth_stencil: %s viewport fragcoord PASS\n",label); fflush(stdout);
    /* Cull the same offset viewport under both winding conventions. */
    glEnable(GL_CULL_FACE); glCullFace(GL_BACK); glFrontFace(GL_CCW);
    glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT); draw(vbo,0,1,0,0);
    expect_pixel("viewport CCW front",3,2,255,0,0);
    expect_pixel("viewport outside culled draw",0,0,0,0,0);
    glFrontFace(GL_CW); glClear(GL_COLOR_BUFFER_BIT); draw(vbo,0,1,0,0);
    expect_pixel("viewport CW back",3,2,0,0,0);
    glDisable(GL_CULL_FACE); glFrontFace(GL_CCW);
    printf("gl_depth_stencil: %s viewport culling PASS\n",label); fflush(stdout);
    glViewport(0,0,width,height);
    printf("gl_depth_stencil: %s viewport matrix PASS\n",label);
    fflush(stdout);
}

/* These cases distinguish post-shader, post-clip Z/W from CPU input depth.
 * They use wide comparison margins so D24 rounding does not decide a pixel. */
static void clip_depth_matrix(GLuint vbo, const char *label)
{
    static const char *transform_vs =
        "attribute vec4 pos; attribute vec4 color; varying vec4 v_color;\n"
        "uniform mat4 transform;\n"
        "void main() { gl_Position = transform * pos; v_color = color; }\n";
    static const float varying_w[6][8] = {
        {-.5f,-.5f,-.25f,.5f, 0,0,1,1}, {1,-1,.5f,1, 0,0,1,1},
        {-2,2,-1,2, 0,0,1,1}, {1,-1,.5f,1, 0,0,1,1},
        {.5f,.5f,.25f,.5f, 0,0,1,1}, {-2,2,-1,2, 0,0,1,1}
    };
    static const float clipped[6][8] = {
        {-1,-1,-2,1, 0,0,1,1}, {2,-2,1,2, 0,0,1,1},
        {-1,1,-2,1, 0,0,1,1}, {2,-2,1,2, 0,0,1,1},
        {2,2,1,2, 0,0,1,1}, {-1,1,-2,1, 0,0,1,1}
    };
    float transform[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    GLuint vs = shader(GL_VERTEX_SHADER, transform_vs);
    GLuint fs = shader(GL_FRAGMENT_SHADER, fs_source);
    GLuint program = glCreateProgram();
    GLint linked = 0, previous_program = 0, uniform;

    glGetIntegerv(GL_CURRENT_PROGRAM, &previous_program);
    glAttachShader(program, vs); glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos"); glBindAttribLocation(program, 1, "color");
    glLinkProgram(program); glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) fail("link clip depth shader");
    uniform = glGetUniformLocation(program, "transform");
    if (uniform < 0) fail("clip depth transform missing");
    glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_BLEND);
    glDisable(GL_DITHER); glEnable(GL_DEPTH_TEST); glDepthMask(GL_TRUE);
    glDepthFunc(GL_LESS); glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    glClearColor(1,0,0,1); glClearDepthf(.5f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glUseProgram(program);
    transform[14] = 1;
    glUniformMatrix4fv(uniform, 1, GL_FALSE, transform);
    draw(vbo, -.5f, 0,0,1);
    glFinish(); expect_pixel("post-shader farther depth", 3,3, 255,0,0);
    transform[14] = -1;
    glUniformMatrix4fv(uniform, 1, GL_FALSE, transform);
    draw(vbo, .5f, 0,0,1);
    glFinish(); expect_pixel("post-shader nearer depth", 3,3, 0,0,255);

    glUseProgram((GLuint)previous_program);
    glClearDepthf(.5f); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(varying_w), varying_w, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glFinish();
    expect_pixel("varying W near", 1,1, 0,0,255);
    expect_pixel("varying W far", 6,1, 255,0,0);

    glClearDepthf(.6f); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glBufferData(GL_ARRAY_BUFFER, sizeof(clipped), clipped, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glFinish();
    expect_pixel("near clipped outside", 1,1, 255,0,0);
    expect_pixel("near clipped depth", 6,1, 0,0,255);
    glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    check_gl("clip depth matrix");
    printf("gl_depth_stencil: %s clip depth matrix PASS\n", label);
    fflush(stdout);
}

/* The same triangle twice makes destination-dependent blending sensitive to
 * whether disabled channels are restored between primitives. */
static void draw_overlapping(GLuint vbo, float r, float g, float b, float a)
{
    static const float xy[3][2] = {{-1,-1}, {1,-1}, {-1,1}};
    float vertices[6][8];

    for (unsigned i=0; i<6; i++) {
        vertices[i][0]=xy[i%3][0]; vertices[i][1]=xy[i%3][1];
        vertices[i][2]=0; vertices[i][3]=1;
        vertices[i][4]=r; vertices[i][5]=g; vertices[i][6]=b; vertices[i][7]=a;
    }
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    check_gl("overlapping draw");
}

static void expect_mask_pixel(const char *stage, const uint8_t expected[4])
{
    uint8_t pixel[4] = {0};
    glFinish();
    glReadPixels(INSIDE_X, INSIDE_Y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    check_gl(stage);
    for (unsigned i=0; i<4; i++) {
        if (abs((int)pixel[i]-(int)expected[i]) > 2) {
            fprintf(stderr, "gl_depth_stencil: %s got %u,%u,%u,%u expected %u,%u,%u,%u\n",
                    stage, pixel[0],pixel[1],pixel[2],pixel[3],
                    expected[0],expected[1],expected[2],expected[3]);
            exit(1);
        }
    }
}

static void color_mask_matrix(GLuint vbo, const char *label)
{
    static const uint8_t initial[4]={51,102,153,204};
    static const uint8_t drawn[4]={204,153,102,51};
    uint8_t expected[4];

    glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST); glDisable(GL_DITHER); glDisable(GL_BLEND);
    for (unsigned mask=0; mask<16; mask++) {
        glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
        glClearColor(.2f,.4f,.6f,.8f); glClear(GL_COLOR_BUFFER_BIT);
        glColorMask(!!(mask&1),!!(mask&2),!!(mask&4),!!(mask&8));
        draw_overlapping(vbo,.8f,.6f,.4f,.2f);
        for (unsigned i=0;i<4;i++) expected[i]=(mask & (1u<<i)) ? drawn[i] : initial[i];
        expect_mask_pixel("color write mask",expected);
    }

    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    glClearColor(0,.25f,.75f,.5f); glClear(GL_COLOR_BUFFER_BIT);
    glColorMask(GL_TRUE,GL_FALSE,GL_FALSE,GL_FALSE);
    glEnable(GL_BLEND); glBlendFunc(GL_DST_ALPHA,GL_ONE);
    draw_overlapping(vbo,.25f,0,0,.25f);
    { const uint8_t blended[4]={64,64,191,128}; expect_mask_pixel("masked overlapping blend",blended); }
    glDisable(GL_BLEND); glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    printf("gl_depth_stencil: %s color mask matrix PASS\n",label);
    fflush(stdout);
}

/* Exercise the same position and colour inputs with normalized, scaled and
 * fixed-point storage. All cases fit in an 8x8 routine framebuffer. */
static void vertex_format_matrix(GLuint vbo, const char *label)
{
    static const int xy[6][2]={{-1,-1},{1,-1},{-1,1},{1,-1},{1,1},{-1,1}};
    static const uint8_t red[3]={255,0,0};
    struct normalized_vertex { GLshort xy[2]; GLubyte rgba[4]; } normalized[6];
    struct scaled_vertex { GLbyte xy[2]; GLubyte rgba[4]; } scaled[6];
    struct fixed_vertex { GLfixed xy[2]; GLshort rgba[4]; } fixed[6];

    glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND); glDisable(GL_DITHER);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    for (unsigned i=0;i<6;i++) {
        for (unsigned c=0;c<2;c++) {
            normalized[i].xy[c]=xy[i][c]<0 ? -32768 : 32767;
            scaled[i].xy[c]=(GLbyte)xy[i][c];
            fixed[i].xy[c]=(GLfixed)(xy[i][c]*65536);
        }
        for (unsigned c=0;c<4;c++) {
            int on=(c==0 || c==3);
            normalized[i].rgba[c]=on ? 255 : 0;
            scaled[i].rgba[c]=on ? 1 : 0;
            fixed[i].rgba[c]=on ? 32767 : 0;
        }
    }
    glBindBuffer(GL_ARRAY_BUFFER,vbo);
    glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
    glBufferData(GL_ARRAY_BUFFER,sizeof(normalized),normalized,GL_STREAM_DRAW);
    glVertexAttribPointer(0,2,GL_SHORT,GL_TRUE,sizeof(normalized[0]),(void *)0);
    glVertexAttribPointer(1,4,GL_UNSIGNED_BYTE,GL_TRUE,sizeof(normalized[0]),(void *)4);
    glDrawArrays(GL_TRIANGLES,0,6); check_gl("normalized vertex draw");
    expect_pair("normalized vertex formats",red,red);

    glClear(GL_COLOR_BUFFER_BIT);
    glBufferData(GL_ARRAY_BUFFER,sizeof(scaled),scaled,GL_STREAM_DRAW);
    glVertexAttribPointer(0,2,GL_BYTE,GL_FALSE,sizeof(scaled[0]),(void *)0);
    glVertexAttribPointer(1,4,GL_UNSIGNED_BYTE,GL_FALSE,sizeof(scaled[0]),(void *)2);
    glDrawArrays(GL_TRIANGLES,0,6); check_gl("scaled vertex draw");
    expect_pair("scaled vertex formats",red,red);

    glClear(GL_COLOR_BUFFER_BIT);
    glBufferData(GL_ARRAY_BUFFER,sizeof(fixed),fixed,GL_STREAM_DRAW);
    glVertexAttribPointer(0,2,GL_FIXED,GL_FALSE,sizeof(fixed[0]),(void *)0);
    glVertexAttribPointer(1,4,GL_SHORT,GL_TRUE,sizeof(fixed[0]),(void *)8);
    glDrawArrays(GL_TRIANGLES,0,6); check_gl("fixed vertex draw");
    expect_pair("fixed vertex formats",red,red);

    /* The following attachment case returns to draw()'s float layout. */
    glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)0);
    glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)(4*sizeof(float)));
    check_gl("restore vertex format");
    printf("gl_depth_stencil: %s vertex format matrix PASS\n",label);
    fflush(stdout);
}

/* Incomplete GL_TRIANGLES tails must not discard preceding full triangles. */
static void triangle_tail_matrix(GLuint vbo, const char *label)
{
    static const float xy[5][2]={{-1,-1},{1,-1},{-1,1},{1,-1},{1,1}};
    static const GLubyte indices[5]={0,1,2,3,4};
    static const uint8_t black[3]={0,0,0}, red[3]={255,0,0};
    float vertices[5][8];
    GLuint ibo;

    glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND); glDisable(GL_DITHER);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    for (unsigned i=0;i<5;i++) {
        vertices[i][0]=xy[i][0]; vertices[i][1]=xy[i][1];
        vertices[i][2]=0; vertices[i][3]=1;
        vertices[i][4]=1; vertices[i][5]=0;
        vertices[i][6]=0; vertices[i][7]=1;
    }
    glBindBuffer(GL_ARRAY_BUFFER,vbo);
    glBufferData(GL_ARRAY_BUFFER,sizeof(vertices),vertices,GL_STREAM_DRAW);
    glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)0);
    glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)(4*sizeof(float)));
    glGenBuffers(1,&ibo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,sizeof(indices),indices,GL_STATIC_DRAW);
    glClearColor(0,0,0,1);
    for (unsigned indexed=0;indexed<2;indexed++) {
        for (GLsizei count=0;count<=5;count++) {
            glClear(GL_COLOR_BUFFER_BIT);
            if (indexed) glDrawElements(GL_TRIANGLES,count,GL_UNSIGNED_BYTE,(void *)0);
            else glDrawArrays(GL_TRIANGLES,0,count);
            check_gl("triangle tail draw");
            expect_pair("triangle tail image",count>=3 ? red : black,black);
        }
    }
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0); glDeleteBuffers(1,&ibo);
    check_gl("triangle tail cleanup");
    printf("gl_depth_stencil: %s triangle tail matrix PASS\n",label);
    fflush(stdout);
}

/* The legacy pipe expands GLES triangle topologies into list uploads.
 * Check both generated
 * triangles, winding, short draws and nonzero array/index offsets. */
static void triangle_topology_matrix(GLuint vbo, const char *label)
{
    static const struct {
        GLenum mode;
        float xy[4][2];
        int first_x, first_y, second_x, second_y;
    } modes[] = {
        {GL_TRIANGLE_STRIP, {{-1,-1},{1,-1},{-1,1},{1,1}}, 2,2,5,5},
        {GL_TRIANGLE_FAN,   {{-1,-1},{1,-1},{1,1},{-1,1}}, 5,2,2,5},
    };
    static const GLubyte indices8[] = {255, 254, 2, 4, 1, 3};
    static const GLushort indices16[] = {65535, 2, 4, 1, 3};
    static const unsigned slots[] = {2, 4, 1, 3};
    float vertices[5][8];
    GLuint ibo;

    glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND); glDisable(GL_DITHER);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    glEnable(GL_CULL_FACE); glCullFace(GL_BACK); glFrontFace(GL_CCW);
    glClearColor(0,0,0,1);
    glBindBuffer(GL_ARRAY_BUFFER,vbo);
    glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)0);
    glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)(4*sizeof(float)));
    glGenBuffers(1,&ibo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ibo);
    for (unsigned m=0;m<sizeof(modes)/sizeof(modes[0]);m++) {
        for (unsigned source=0;source<3;source++) {
            memset(vertices,0,sizeof(vertices));
            for (unsigned i=0;i<4;i++) {
                unsigned slot=source ? slots[i] : i+1;
                vertices[slot][0]=modes[m].xy[i][0];
                vertices[slot][1]=modes[m].xy[i][1];
                vertices[slot][3]=1; vertices[slot][4]=1; vertices[slot][7]=1;
            }
            glBufferData(GL_ARRAY_BUFFER,sizeof(vertices),vertices,GL_STREAM_DRAW);
            if (source==1)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER,sizeof(indices8),indices8,GL_STATIC_DRAW);
            else if (source==2)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER,sizeof(indices16),indices16,GL_STATIC_DRAW);
            for (GLsizei count=0;count<=4;count++) {
                char stage[96];
                snprintf(stage,sizeof(stage),"topology mode=0x%x source=%u count=%d",modes[m].mode,source,count);
                glClear(GL_COLOR_BUFFER_BIT);
                if (!source) glDrawArrays(modes[m].mode,1,count);
                else glDrawElements(modes[m].mode,count,
                                    source==1 ? GL_UNSIGNED_BYTE : GL_UNSIGNED_SHORT,
                                    (void *)2);
                glFinish(); check_gl(stage);
                expect_pixel(stage,modes[m].first_x,
                             modes[m].first_y,count>=3 ? 255 : 0,0,0);
                expect_pixel(stage,modes[m].second_x,
                             modes[m].second_y,count>=4 ? 255 : 0,0,0);
            }
            glFrontFace(GL_CW); glClear(GL_COLOR_BUFFER_BIT);
            if (!source) glDrawArrays(modes[m].mode,1,4);
            else glDrawElements(modes[m].mode,4,
                                source==1 ? GL_UNSIGNED_BYTE : GL_UNSIGNED_SHORT,
                                (void *)2);
            glFinish(); check_gl("triangle topology back-face culling");
            expect_pixel("triangle topology culled first",modes[m].first_x,modes[m].first_y,0,0,0);
            expect_pixel("triangle topology culled second",modes[m].second_x,modes[m].second_y,0,0,0);
            glFrontFace(GL_CCW);
        }
    }
    {
        /* Four strip triangles cross the six-vertex staging limit of the
         * small guest. The last batch must retain the original strip parity. */
        static const float xy[6][2]={{-1,1},{-1,-1},{0,1},{0,-1},{1,1},{1,-1}};
        static const unsigned slots6[6]={3,6,1,5,2,4};
        static const GLushort order[7]={65535,3,6,1,5,2,4};
        float batch[7][8];
        for (unsigned indexed=0;indexed<2;indexed++) {
            memset(batch,0,sizeof(batch));
            for (unsigned i=0;i<6;i++) {
                unsigned slot=indexed ? slots6[i] : i+1;
                batch[slot][0]=xy[i][0]; batch[slot][1]=xy[i][1];
                batch[slot][3]=1; batch[slot][4]=1; batch[slot][7]=1;
            }
            glBufferData(GL_ARRAY_BUFFER,sizeof(batch),batch,GL_STREAM_DRAW);
            if (indexed)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER,sizeof(order),order,GL_STATIC_DRAW);
            glClear(GL_COLOR_BUFFER_BIT);
            if (indexed) glDrawElements(GL_TRIANGLE_STRIP,6,GL_UNSIGNED_SHORT,(void *)2);
            else glDrawArrays(GL_TRIANGLE_STRIP,1,6);
            glFinish(); check_gl("strip staging boundary");
            expect_pixel("strip staging boundary",1,1,255,0,0);
            expect_pixel("strip staging boundary",1,6,255,0,0);
            expect_pixel("strip staging boundary",6,1,255,0,0);
            expect_pixel("strip staging boundary",6,6,255,0,0);
            glCullFace(GL_FRONT_AND_BACK); glClear(GL_COLOR_BUFFER_BIT);
            if (indexed) glDrawElements(GL_TRIANGLE_STRIP,6,GL_UNSIGNED_SHORT,(void *)2);
            else glDrawArrays(GL_TRIANGLE_STRIP,1,6);
            glFinish(); check_gl("strip cull both faces");
            expect_pixel("strip cull both faces",1,1,0,0,0);
            expect_pixel("strip cull both faces",6,6,0,0,0);
            glCullFace(GL_BACK);
        }
    }
    glDisable(GL_CULL_FACE);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0); glDeleteBuffers(1,&ibo);
    check_gl("triangle topology cleanup");
    printf("gl_depth_stencil: %s triangle topology matrix PASS\n",label);
    fflush(stdout);
}

/* The EGL default surface has the opposite Gallium Y orientation to the
 * FBOs above; test culling with a complete triangle-list draw on both. */
static void window_cull_matrix(GLuint vbo, EGLDisplay display, EGLSurface surface)
{
    EGLint width=0, height=0;
    if (!eglQuerySurface(display,surface,EGL_WIDTH,&width) ||
        !eglQuerySurface(display,surface,EGL_HEIGHT,&height) || width<4 || height<4)
        fail("query default surface dimensions");
    glBindFramebuffer(GL_FRAMEBUFFER,0); glViewport(0,0,width,height);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND); glDisable(GL_DITHER);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    glEnable(GL_CULL_FACE); glCullFace(GL_BACK); glClearColor(0,0,0,1);
    glFrontFace(GL_CCW); glClear(GL_COLOR_BUFFER_BIT); draw(vbo,0,1,0,0);
    glFinish(); check_gl("default surface front face");
    expect_pixel("default surface front face",width/4,height/4,255,0,0);
    expect_pixel("default surface front face",3*width/4,3*height/4,255,0,0);
    glFrontFace(GL_CW); glClear(GL_COLOR_BUFFER_BIT); draw(vbo,0,1,0,0);
    glFinish(); check_gl("default surface back face");
    expect_pixel("default surface back face",width/4,height/4,0,0,0);
    expect_pixel("default surface back face",3*width/4,3*height/4,0,0,0);
    glDisable(GL_CULL_FACE); glFrontFace(GL_CCW);
    printf("gl_depth_stencil: default surface culling matrix PASS\n");
    fflush(stdout);
}

static int compare(GLenum func, int value, int stored)
{
    switch (func) {
    case GL_NEVER: return 0;
    case GL_LESS: return value < stored;
    case GL_EQUAL: return value == stored;
    case GL_LEQUAL: return value <= stored;
    case GL_GREATER: return value > stored;
    case GL_NOTEQUAL: return value != stored;
    case GL_GEQUAL: return value >= stored;
    case GL_ALWAYS: return 1;
    default: fail("invalid test comparison"); return 0;
    }
}

static void expect_comparison(const char *stage, int pass)
{
    glFinish();
    check_gl(stage);
    expect_pixel(stage, INSIDE_X, INSIDE_Y, pass ? 255 : 0, 0, 0);
}

/* Explicitly requested matrix: 48 compare draws plus 16 stencil-op draws
 * per attachment. It is separate from the short routine smoke. */
static void compare_matrix(GLuint vbo, const char *label)
{
    static const GLenum functions[8] = {
        GL_NEVER, GL_LESS, GL_EQUAL, GL_LEQUAL,
        GL_GREATER, GL_NOTEQUAL, GL_GEQUAL, GL_ALWAYS
    };
    static const float z[3] = {-0.5f, 0.0f, 0.5f};
    static const GLenum ops[8] = {
        GL_KEEP, GL_ZERO, GL_REPLACE, GL_INCR,
        GL_DECR, GL_INVERT, GL_INCR_WRAP, GL_DECR_WRAP
    };
    static const int initial[8] = {0x12, 0x12, 0x12, 0xff, 0, 0x0f, 0xff, 0};
    static const int expected[8] = {0x12, 0, 0x42, 0xff, 0, 0xf0, 0, 0xff};
    unsigned i, j;

    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glEnable(GL_DEPTH_TEST);
    glDepthMask(GL_TRUE);
    glClearColor(0, 0, 0, 1);
    for (i = 0; i < 8; i++) for (j = 0; j < 3; j++) {
        glClearDepthf(0.5f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDepthFunc(functions[i]);
        draw(vbo, z[j], 1, 0, 0);
        expect_comparison("depth comparison matrix", compare(functions[i], (int)j+1, 2));
    }
    printf("gl_depth_stencil: %s depth comparison matrix PASS\n", label);
    fflush(stdout);

    glDisable(GL_DEPTH_TEST);
    glEnable(GL_STENCIL_TEST);
    glStencilMask(0xff);
    glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    for (i = 0; i < 8; i++) for (j = 1; j <= 3; j++) {
        glClearStencil(2);
        glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        glStencilFunc(functions[i], (GLint)j, 0xff);
        draw(vbo, 0, 1, 0, 0);
        expect_comparison("stencil comparison matrix", compare(functions[i], (int)j, 2));
    }
    printf("gl_depth_stencil: %s stencil comparison matrix PASS\n", label);
    fflush(stdout);

    for (i = 0; i < 8; i++) {
        glClearStencil(initial[i]);
        glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        glStencilFunc(GL_ALWAYS, 0x42, 0xff);
        glStencilOp(GL_KEEP, GL_KEEP, ops[i]);
        draw(vbo, 0, 1, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glStencilFunc(GL_EQUAL, expected[i], 0xff);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        draw(vbo, 0, 1, 0, 0);
        expect_comparison("stencil operation matrix", 1);
    }
    printf("gl_depth_stencil: %s stencil operation matrix PASS\n", label);
    fflush(stdout);
}

static void run_case(GLuint vbo, int use_texture, struct gbm_device *gbm,
                     EGLDisplay display)
{
    static const uint8_t red[3] = {255, 0, 0};
    static const uint8_t green[3] = {0, 255, 0};
    static const uint8_t blue[3] = {0, 0, 255};
    static const uint8_t yellow[3] = {255, 255, 0};
    GLuint fb = 0, color = 0, zs = 0;
    const char *label = use_texture ? "texture" : "renderbuffer";

    glGenFramebuffers(1, &fb);
    glBindFramebuffer(GL_FRAMEBUFFER, fb);
    if (use_texture) {
        glGenTextures(1, &color);
        glBindTexture(GL_TEXTURE_2D, color);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, SIDE, SIDE, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, color, 0);
    } else {
        glGenRenderbuffers(1, &color);
        glBindRenderbuffer(GL_RENDERBUFFER, color);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8_OES, SIDE, SIDE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                  GL_RENDERBUFFER, color);
    }
    glGenRenderbuffers(1, &zs);
    glBindRenderbuffer(GL_RENDERBUFFER, zs);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8_OES, SIDE, SIDE);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, zs);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, zs);
    check_gl("create attachments");
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        fail("depth/stencil framebuffer incomplete");
    if (getenv("OPENGPU_GL_VIEWPORT_MATRIX"))
        viewport_matrix(vbo,SIDE,SIDE,label);
    glViewport(0, 0, SIDE, SIDE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glDepthMask(GL_TRUE);
    glStencilMask(0xff);
    glClearColor(0, 0, 0, 1);
    glClearDepthf(0.25f);
    glClearStencil(3);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    /* Changing depth alone must keep stencil=3. */
    glClearDepthf(0.75f);
    glClear(GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LESS);
    glEnable(GL_STENCIL_TEST);
    glStencilFunc(GL_EQUAL, 3, 0xff);
    glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    draw(vbo, 0.0f, 1, 0, 0); /* depth 0.5 */
    expect_pair("depth clear preserves stencil", red, red);

    /* Changing stencil alone must keep the depth written by the first draw. */
    glClearStencil(5);
    glClear(GL_STENCIL_BUFFER_BIT);
    glStencilFunc(GL_EQUAL, 5, 0xff);
    draw(vbo, 0.5f, 0, 1, 0); /* depth 0.75 fails against 0.5 */
    expect_pair("stencil clear preserves depth", red, red);
    draw(vbo, -0.5f, 0, 0, 1); /* depth 0.25 passes */
    expect_pair("stencil value and depth update", blue, blue);

    glEnable(GL_SCISSOR_TEST);
    glScissor(2, 2, 3, 3);
    glClearDepthf(1.0f);
    glClear(GL_DEPTH_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);
    draw(vbo, 0.0f, 0, 1, 0); /* passes only inside the clear */
    expect_pair("scissored depth clear", green, blue);

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glEnable(GL_SCISSOR_TEST);
    glClearColor(1, 1, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);
    expect_pair("scissored color clear", yellow, blue);

    glClearStencil(0);
    glClear(GL_STENCIL_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);
    glClearStencil(7);
    glClear(GL_STENCIL_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_STENCIL_TEST);
    glStencilFunc(GL_EQUAL, 7, 0xff);
    draw(vbo, 0.0f, 1, 0, 0);
    expect_pair("scissored stencil clear", red, blue);
    printf("gl_depth_stencil: %s PASS (%dx%d)\n", label, SIDE, SIDE);
    fflush(stdout);
    if (getenv("OPENGPU_GL_COMPARE_MATRIX"))
        compare_matrix(vbo, label);
    if (getenv("OPENGPU_GL_CLIP_DEPTH"))
        clip_depth_matrix(vbo, label);
    if (getenv("OPENGPU_GL_COLOR_MASK_MATRIX"))
        color_mask_matrix(vbo, label);
    if (getenv("OPENGPU_GL_VERTEX_FORMAT_MATRIX"))
        vertex_format_matrix(vbo, label);
    if (getenv("OPENGPU_GL_TRIANGLE_TAILS"))
        triangle_tail_matrix(vbo, label);
    if (getenv("OPENGPU_GL_TRIANGLE_TOPOLOGIES"))
        triangle_topology_matrix(vbo, label);
    if (getenv("OPENGPU_GL_SAMPLE_FORMATS"))
        sample_format_matrix(vbo, label);
    if (getenv("OPENGPU_GL_TEXTURE_RESOURCES"))
        texture_resource_matrix(vbo, label);
    if (getenv("OPENGPU_GL_DMABUF"))
        dma_buf_texture_matrix(vbo, gbm, display, label);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteRenderbuffers(1, &zs);
    if (use_texture)
        glDeleteTextures(1, &color);
    else
        glDeleteRenderbuffers(1, &color);
    glDeleteFramebuffers(1, &fb);
    check_gl("destroy attachments");
}

int main(int argc, char **argv)
{
    const char *device = getenv("OPENGPU_DRM_DEVICE");
    const char *mode = argc > 1 ? argv[1] : "both";
    const char *renderer;
    int software = getenv("OPENGPU_GL_SURFACELESS") != NULL;
    struct gbm_device *gbm = NULL;
    struct gbm_surface *window = NULL;
    EGLDisplay display;
    EGLSurface surface;
    EGLContext context;
    EGLConfig config;
    EGLint count = 0, major, minor;
    GLint linked = 0;
    GLuint vs, fs, program, vbo;
    int fd = -1;
    const EGLint config_attrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE,
        EGL_OPENGL_ES2_BIT, EGL_NONE
    };
    const EGLint pbuffer_config_attrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE,
        EGL_OPENGL_ES2_BIT, EGL_NONE
    };
    const EGLint pbuffer_attrs[] = { EGL_WIDTH, SIDE, EGL_HEIGHT, SIDE, EGL_NONE };
    const EGLint context_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };

    if (argc > 2 || (strcmp(mode, "both") && strcmp(mode, "texture") &&
                     strcmp(mode, "renderbuffer"))) {
        fprintf(stderr, "usage: %s [both|texture|renderbuffer]\n", argv[0]);
        return 2;
    }
    if (software) {
        if (!getenv("OPENGPU_GL_ALLOW_SOFTWARE"))
            fail("surfaceless mode is only for the explicit software oracle");
        display = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA,
                                        EGL_DEFAULT_DISPLAY, NULL);
    } else {
        fd = open(device ? device : "/dev/dri/card0", O_RDWR);
        if (fd < 0)
            fail("open DRM device");
        gbm = gbm_create_device(fd);
        if (!gbm)
            fail("gbm_create_device");
        window = gbm_surface_create(gbm, 32, 32, GBM_FORMAT_ABGR8888, GBM_BO_USE_RENDERING);
        if (!window)
            fail("gbm_surface_create");
        display = eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR, gbm, NULL);
    }
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, &major, &minor) ||
        !eglBindAPI(EGL_OPENGL_ES_API))
        fail("initialize EGL");
    if (software) {
        if (!eglChooseConfig(display, pbuffer_config_attrs, &config, 1, &count) || count != 1)
            fail("choose software EGL config");
        surface = eglCreatePbufferSurface(display, config, pbuffer_attrs);
    } else {
        if (!opengpu_gl_choose_config(display, config_attrs, GBM_FORMAT_ABGR8888,
                                       &config, &count) || count != 1)
            fail("choose EGL config");
        surface = eglCreateWindowSurface(display, config, (EGLNativeWindowType)window, NULL);
    }
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
    if (surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(display, surface, surface, context))
        fail("create EGL context");
    renderer = (const char *)glGetString(GL_RENDERER);
    if (!renderer)
        fail("query renderer");
    printf("gl_depth_stencil: renderer %s\n", renderer);
    if (!strstr(renderer, "opengpu") && !getenv("OPENGPU_GL_ALLOW_SOFTWARE"))
        fail("renderer is not OpenGPU");

    vs = shader(GL_VERTEX_SHADER, vs_source);
    fs = shader(GL_FRAGMENT_SHADER, fs_source);
    program = glCreateProgram();
    glAttachShader(program, vs); glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos");
    glBindAttribLocation(program, 1, "color");
    glLinkProgram(program);
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked)
        fail("link shader");
    glUseProgram(program);
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)(4 * sizeof(float)));
    check_gl("initialize shaders and vertices");
    if (strcmp(mode, "renderbuffer"))
        run_case(vbo, 1, gbm, display);
    if (strcmp(mode, "texture"))
        run_case(vbo, 0, gbm, display);
    if (getenv("OPENGPU_GL_TEXTURE_COPY_WINDOW")) {
        EGLint width=0, height=0;
        if (!getenv("OPENGPU_GL_TEXTURE_COPIES") ||
            !eglQuerySurface(display,surface,EGL_WIDTH,&width) ||
            !eglQuerySurface(display,surface,EGL_HEIGHT,&height) || width<8 || height<8)
            fail("texture copy window setup");
        glBindFramebuffer(GL_FRAMEBUFFER,0); glViewport(0,0,width,height);
        texture_copy_window(vbo);
    }
    if (getenv("OPENGPU_GL_TRIANGLE_TOPOLOGIES"))
        window_cull_matrix(vbo, display, surface);
    if (getenv("OPENGPU_GL_VIEWPORT_MATRIX")) {
        EGLint width=0, height=0;
        if (!eglQuerySurface(display,surface,EGL_WIDTH,&width) ||
            !eglQuerySurface(display,surface,EGL_HEIGHT,&height))
            fail("viewport window size");
        glBindFramebuffer(GL_FRAMEBUFFER,0);
        viewport_matrix(vbo,width,height,"window");
    }
    glDeleteBuffers(1, &vbo);
    glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context); eglDestroySurface(display, surface);
    eglTerminate(display);
    if (window) gbm_surface_destroy(window);
    if (gbm) gbm_device_destroy(gbm);
    if (fd >= 0) close(fd);
    printf("gl_depth_stencil: all selected cases PASS%s\n",
           software ? " (software oracle, not OpenGPU)" : "");
    return 0;
}
