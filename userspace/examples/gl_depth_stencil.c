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
