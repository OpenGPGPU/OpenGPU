/* SPDX-License-Identifier: MIT */
/* Ordered GLES2 shared-texture visibility and lifetime across EGL contexts. */
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

struct target {
    GLuint framebuffer;
    GLuint color;
    bool texture;
};

static void fail(const char *where)
{
    fprintf(stderr, "gl_shared_context: %s (GL 0x%x, EGL 0x%x)\n",
            where, glGetError(), eglGetError());
    exit(1);
}

static void check_gl(const char *where)
{
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        fprintf(stderr, "gl_shared_context: %s: GL error 0x%x\n", where, error);
        exit(1);
    }
}

static GLuint compile_shader(GLenum type, const char *source)
{
    GLuint shader = glCreateShader(type);
    GLint ok = 0;
    char log[1024];

    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        fprintf(stderr, "gl_shared_context: shader: %s\n", log);
        exit(1);
    }
    return shader;
}

static GLuint make_program(const char *vs_source, const char *fs_source,
                           const char *second_attribute)
{
    GLuint vs = compile_shader(GL_VERTEX_SHADER, vs_source);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, fs_source);
    GLuint program = glCreateProgram();
    GLint ok = 0;
    char log[1024];

    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos");
    glBindAttribLocation(program, 1, second_attribute);
    glLinkProgram(program);
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        glGetProgramInfoLog(program, sizeof(log), NULL, log);
        fprintf(stderr, "gl_shared_context: program: %s\n", log);
        exit(1);
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return program;
}

static struct target make_target(bool texture)
{
    struct target out = {0, 0, texture};

    glGenFramebuffers(1, &out.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, out.framebuffer);
    if (texture) {
        glGenTextures(1, &out.color);
        glBindTexture(GL_TEXTURE_2D, out.color);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, SIDE, SIDE, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, out.color, 0);
    } else {
        glGenRenderbuffers(1, &out.color);
        glBindRenderbuffer(GL_RENDERBUFFER, out.color);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8_OES, SIDE, SIDE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                  GL_RENDERBUFFER, out.color);
    }
    check_gl("create output target");
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        fail("incomplete output target");
    return out;
}

static void delete_target(struct target *target)
{
    glDeleteFramebuffers(1, &target->framebuffer);
    if (target->texture)
        glDeleteTextures(1, &target->color);
    else
        glDeleteRenderbuffers(1, &target->color);
}

static void common_draw_state(void)
{
    glViewport(0, 0, SIDE, SIDE);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_DITHER);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
}

static void sample(GLuint program, GLuint vbo, GLuint source, bool rebind_source,
                   const struct target *target, float u, float v,
                   const uint8_t expected[4], const char *stage)
{
    static const float corners[6][2] = {
        {-1,-1}, {1,-1}, {-1,1}, {1,-1}, {1,1}, {-1,1}
    };
    float vertices[6][6];
    uint8_t pixels[SIDE * SIDE * 4];
    GLint uniform;

    for (unsigned i = 0; i < 6; i++) {
        vertices[i][0] = corners[i][0]; vertices[i][1] = corners[i][1];
        vertices[i][2] = 0; vertices[i][3] = 1;
        vertices[i][4] = u; vertices[i][5] = v;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, target->framebuffer);
    common_draw_state();
    glUseProgram(program);
    uniform = glGetUniformLocation(program, "tex");
    if (uniform < 0)
        fail("sampler uniform missing");
    glUniform1i(uniform, 0);
    glActiveTexture(GL_TEXTURE0);
    /* A consuming context rebinds after a producer update. The lifetime
     * test deliberately keeps the deleted object bound instead. */
    if (rebind_source)
        glBindTexture(GL_TEXTURE_2D, source);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)(4 * sizeof(float)));
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glFinish();
    glReadPixels(0, 0, SIDE, SIDE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    check_gl(stage);
    for (unsigned i = 0; i < SIDE * SIDE; i++) {
        if (memcmp(pixels + 4 * i, expected, 4)) {
            const uint8_t *p = pixels + 4 * i;
            fprintf(stderr, "gl_shared_context: %s %s pixel (%u,%u) "
                    "got %u,%u,%u,%u expected %u,%u,%u,%u\n", stage,
                    target->texture ? "texture" : "renderbuffer", i % SIDE, i / SIDE,
                    p[0], p[1], p[2], p[3], expected[0], expected[1], expected[2], expected[3]);
            exit(1);
        }
    }
}

static void check_pattern(GLuint program, GLuint vbo, GLuint source, bool rebind_source,
                          const struct target target[2], const uint8_t colors[4][4],
                          const char *stage)
{
    for (unsigned attachment = 0; attachment < 2; attachment++)
        for (unsigned quadrant = 0; quadrant < 4; quadrant++)
            sample(program, vbo, source, rebind_source, &target[attachment],
                   quadrant & 1 ? .75f : .25f, quadrant & 2 ? .75f : .25f,
                   colors[quadrant], stage);
}

static void upload_pattern(GLuint source, const uint8_t colors[4][4], bool create)
{
    uint8_t pixels[SIDE * SIDE * 4];

    for (unsigned y = 0; y < SIDE; y++)
        for (unsigned x = 0; x < SIDE; x++)
            memcpy(pixels + (y * SIDE + x) * 4, colors[(x >= SIDE / 2) + 2 * (y >= SIDE / 2)], 4);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, source);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    if (create)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, SIDE, SIDE, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    else
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, SIDE, SIDE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glFinish();
    check_gl("publish texture upload");
}

static void draw_color(GLuint vbo, const uint8_t color[4])
{
    static const float corners[6][2] = {
        {-1,-1}, {1,-1}, {-1,1}, {1,-1}, {1,1}, {-1,1}
    };
    float vertices[6][8];

    for (unsigned i = 0; i < 6; i++) {
        vertices[i][0] = corners[i][0]; vertices[i][1] = corners[i][1];
        vertices[i][2] = 0; vertices[i][3] = 1;
        for (unsigned c = 0; c < 4; c++)
            vertices[i][4 + c] = color[c] / 255.0f;
    }
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)0);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), (void *)(4 * sizeof(float)));
    glDrawArrays(GL_TRIANGLES, 0, 6);
}

static void gpu_write(GLuint framebuffer, GLuint source, GLuint program, GLuint vbo,
                      const uint8_t left[4], const uint8_t right[4])
{
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, source, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        fail("incomplete shared producer target");
    common_draw_state();
    glUseProgram(program);
    draw_color(vbo, left);
    glEnable(GL_SCISSOR_TEST);
    glScissor(SIDE / 2, 0, SIDE / 2, SIDE);
    draw_color(vbo, right);
    glDisable(GL_SCISSOR_TEST);
    glFinish();
    check_gl("publish GPU texture writes");
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

int main(void)
{
    static const char *sample_vs =
        "attribute vec4 pos; attribute vec2 uv; varying vec2 v_uv;\n"
        "void main() { gl_Position = pos; v_uv = uv; }\n";
    static const char *sample_fs =
        "precision mediump float; uniform sampler2D tex; varying vec2 v_uv;\n"
        "void main() { gl_FragColor = texture2D(tex, v_uv); }\n";
    static const char *color_vs =
        "attribute vec4 pos; attribute vec4 color; varying vec4 v_color;\n"
        "void main() { gl_Position = pos; v_color = color; }\n";
    static const char *color_fs =
        "precision mediump float; varying vec4 v_color;\n"
        "void main() { gl_FragColor = v_color; }\n";
    static const uint8_t initial[4][4] = {
        {255,0,0,255}, {0,255,0,255}, {0,0,255,255}, {255,255,0,255}
    };
    static const uint8_t updated[4][4] = {
        {0,255,255,255}, {255,0,255,255}, {255,255,255,255}, {0,0,0,255}
    };
    static const uint8_t gpu_pattern[4][4] = {
        {0,0,255,255}, {255,255,0,255}, {0,0,255,255}, {255,255,0,255}
    };
    const EGLint config_attrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE
    };
    const EGLint pbuffer_config_attrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE
    };
    const EGLint pbuffer_attrs[] = {EGL_WIDTH, SIDE, EGL_HEIGHT, SIDE, EGL_NONE};
    const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    bool software = getenv("OPENGPU_GL_SURFACELESS") != NULL;
    struct gbm_device *gbm = NULL;
    struct gbm_surface *window = NULL;
    struct target a[2], b[2], c[2];
    EGLDisplay display;
    EGLConfig config;
    EGLSurface surface;
    EGLContext context_a, context_b, context_c;
    GLint bound;
    EGLint count = 0, major, minor;
    GLuint source, sample_program, color_program, vbo, producer_fb;
    const char *renderer, *device = getenv("OPENGPU_DRM_DEVICE");
    int fd = -1;

    if (software) {
        if (!getenv("OPENGPU_GL_ALLOW_SOFTWARE"))
            fail("surfaceless mode requires the explicit software oracle");
        display = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    } else {
        fd = open(device ? device : "/dev/dri/card0", O_RDWR);
        if (fd < 0 || !(gbm = gbm_create_device(fd)) ||
            !(window = gbm_surface_create(gbm, 32, 32, GBM_FORMAT_ABGR8888, GBM_BO_USE_RENDERING)))
            fail("create GBM window");
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
        if (!opengpu_gl_choose_config(display, config_attrs, GBM_FORMAT_ABGR8888, &config, &count) || count != 1)
            fail("choose window EGL config");
        surface = eglCreateWindowSurface(display, config, (EGLNativeWindowType)window, NULL);
    }
    context_a = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
    context_b = eglCreateContext(display, config, context_a, context_attrs);
    if (surface == EGL_NO_SURFACE || context_a == EGL_NO_CONTEXT || context_b == EGL_NO_CONTEXT ||
        !eglMakeCurrent(display, surface, surface, context_a))
        fail("create shared EGL contexts");
    renderer = (const char *)glGetString(GL_RENDERER);
    if (!renderer || (!software && !strstr(renderer, "opengpu")))
        fail("unexpected renderer");
    printf("gl_shared_context: renderer %s\n", renderer);

    sample_program = make_program(sample_vs, sample_fs, "uv");
    color_program = make_program(color_vs, color_fs, "color");
    glGenBuffers(1, &vbo);
    glGenTextures(1, &source);
    upload_pattern(source, initial, true);
    a[0] = make_target(true); a[1] = make_target(false);
    glFinish();
    if (!eglMakeCurrent(display, surface, surface, context_b))
        fail("switch to consumer");
    b[0] = make_target(true); b[1] = make_target(false);
    glGenFramebuffers(1, &producer_fb);
    check_pattern(sample_program, vbo, source, true, b, initial, "A-to-B upload");
    printf("gl_shared_context: A-to-B upload PASS\n"); fflush(stdout);

    gpu_write(producer_fb, source, color_program, vbo, gpu_pattern[0], gpu_pattern[1]);
    if (!eglMakeCurrent(display, surface, surface, context_a))
        fail("switch to original producer");
    check_pattern(sample_program, vbo, source, true, a, gpu_pattern, "B-to-A GPU write");
    printf("gl_shared_context: B-to-A GPU write PASS\n"); fflush(stdout);

    upload_pattern(source, updated, false);
    delete_target(&a[0]); delete_target(&a[1]);
    glFinish();
    check_gl("retire original outputs");
    if (!eglMakeCurrent(display, surface, surface, context_b))
        fail("switch back to consumer");
    check_pattern(sample_program, vbo, source, true, b, updated, "A-to-B update");
    printf("gl_shared_context: A-to-B update PASS\n"); fflush(stdout);
    if (!eglDestroyContext(display, context_a))
        fail("destroy producer context");
    if (!glIsTexture(source) || !glIsProgram(sample_program) || !glIsBuffer(vbo))
        fail("shared object lost after producer destruction");
    check_pattern(sample_program, vbo, source, true, b, updated, "producer destruction");
    printf("gl_shared_context: producer destruction PASS\n"); fflush(stdout);

    /* Keep the old object bound in C while B deletes and recreates its name. */
    context_c = eglCreateContext(display, config, context_b, context_attrs);
    if (context_c == EGL_NO_CONTEXT || !eglMakeCurrent(display, surface, surface, context_c))
        fail("create retaining context");
    c[0] = make_target(true); c[1] = make_target(false);
    check_pattern(sample_program, vbo, source, true, c, updated, "retain before delete");
    glFinish();
    if (!eglMakeCurrent(display, surface, surface, context_b))
        fail("switch to deleting context");
    glBindFramebuffer(GL_FRAMEBUFFER, producer_fb);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteTextures(1, &source);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &bound);
    if (glIsTexture(source) || bound != 0)
        fail("deleted name or current-context binding still live");
    glFinish();
    check_gl("delete shared texture");

    if (!eglMakeCurrent(display, surface, surface, context_c))
        fail("switch to retaining context");
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &bound);
    if (bound != (GLint)source || glIsTexture(source))
        fail("deleted texture changed other context binding");
    check_pattern(sample_program, vbo, source, false, c, updated, "retained after delete");
    printf("gl_shared_context: deleted texture retention PASS\n"); fflush(stdout);

    if (!eglMakeCurrent(display, surface, surface, context_b))
        fail("switch to recreate texture");
    upload_pattern(source, initial, true);
    if (!glIsTexture(source))
        fail("recreated texture name is not valid");
    check_pattern(sample_program, vbo, source, true, b, initial, "recreated name");
    if (!eglMakeCurrent(display, surface, surface, context_c))
        fail("switch to retained texture after recreate");
    check_pattern(sample_program, vbo, source, false, c, updated, "old object after recreate");
    check_pattern(sample_program, vbo, source, true, c, initial, "rebind recreated name");
    printf("gl_shared_context: deleted name reuse PASS\n"); fflush(stdout);
    delete_target(&c[0]); delete_target(&c[1]);
    glBindTexture(GL_TEXTURE_2D, 0);
    glFinish();
    check_gl("retire retaining context resources");
    if (!eglMakeCurrent(display, surface, surface, context_b) ||
        !eglDestroyContext(display, context_c))
        fail("destroy retaining context");

    glDeleteFramebuffers(1, &producer_fb);
    delete_target(&b[0]); delete_target(&b[1]);
    glDeleteTextures(1, &source);
    glDeleteBuffers(1, &vbo);
    glDeleteProgram(sample_program); glDeleteProgram(color_program);
    glFinish();
    check_gl("destroy shared resources");
    if (!eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) ||
        !eglDestroyContext(display, context_b) || !eglDestroySurface(display, surface) ||
        !eglTerminate(display))
        fail("destroy EGL resources");
    if (window) gbm_surface_destroy(window);
    if (gbm) gbm_device_destroy(gbm);
    if (fd >= 0) close(fd);
    printf("gl_shared_context: PASS%s\n", software ? " (software oracle, not OpenGPU)" : "");
    return 0;
}
