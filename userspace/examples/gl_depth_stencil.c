/* SPDX-License-Identifier: MIT */
/* GLES2 depth/stencil persistence and scissored clears through real GL calls.
 * The 8x8 FBO keeps this a routine rendering test, not a 720p qualification. */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <gbm.h>

#include <fcntl.h>
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

static void run_case(GLuint vbo, int use_texture)
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
    if (getenv("OPENGPU_GL_SAMPLE_FORMATS"))
        sample_format_matrix(vbo, label);
    if (getenv("OPENGPU_GL_TEXTURE_RESOURCES"))
        texture_resource_matrix(vbo, label);

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
        run_case(vbo, 1);
    if (strcmp(mode, "texture"))
        run_case(vbo, 0);
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
