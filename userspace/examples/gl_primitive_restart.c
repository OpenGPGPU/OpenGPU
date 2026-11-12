/* SPDX-License-Identifier: MIT */
/* Desktop GL primitive restart through Mesa, an 8x8 FBO and the GPU. */
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

#define GL_PRIMITIVE_RESTART_NV 0x8558
#define SIDE 8
typedef void (GL_APIENTRYP enable_client_fn)(GLenum);
typedef void (GL_APIENTRYP restart_index_fn)(GLuint);

static void fail(const char *what)
{
    fprintf(stderr, "gl_primitive_restart: %s (GL 0x%x EGL 0x%x)\n",
            what, glGetError(), eglGetError());
    exit(1);
}

static GLuint shader(GLenum type, const char *text)
{
    GLuint result = glCreateShader(type);
    GLint ok = 0;
    char log[1024];
    glShaderSource(result, 1, &text, NULL);
    glCompileShader(result);
    glGetShaderiv(result, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glGetShaderInfoLog(result, sizeof(log), NULL, log);
        fprintf(stderr, "gl_primitive_restart: shader: %s\n", log);
        exit(1);
    }
    return result;
}

static void pixel(int x, int y, unsigned red)
{
    uint8_t got[4] = {0};
    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, got);
    if (glGetError() != GL_NO_ERROR || got[0] != red || got[1] || got[2]) {
        fprintf(stderr, "gl_primitive_restart: pixel %d,%d = %u,%u,%u,%u, expected %u,0,0\n",
                x, y, got[0], got[1], got[2], got[3], red);
        exit(1);
    }
}

static void exercise(GLuint ibo, restart_index_fn restart_index)
{
    static const struct {
        GLenum mode;
        unsigned count;
        uint8_t indices[12];
    } cases[] = {
        {GL_TRIANGLES,      11, {0,1,2,253,253,3,4,5,4,6,5}},
        {GL_TRIANGLE_STRIP,  9, {0,1,2,253,253,3,4,5,6}},
        {GL_TRIANGLE_FAN,    9, {0,1,2,253,253,3,4,6,5}},
    };
    union { uint8_t u8[16]; uint16_t u16[16]; uint32_t u32[16]; } indices;
    const GLenum types[] = {GL_UNSIGNED_BYTE, GL_UNSIGNED_SHORT, GL_UNSIGNED_INT};
    const unsigned sizes[] = {1, 2, 4};

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
    restart_index(253); /* Arbitrary, not the fixed maximum for any type. */
    for (unsigned mode = 0; mode < sizeof(cases)/sizeof(cases[0]); mode++) {
        for (unsigned type = 0; type < 3; type++) {
            memset(&indices, 0, sizeof(indices));
            for (unsigned i = 0; i < cases[mode].count; i++) {
                unsigned v = cases[mode].indices[i];
                if (type == 0) indices.u8[i+1] = v;
                else if (type == 1) indices.u16[i+1] = v;
                else indices.u32[i+1] = v;
            }
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, (cases[mode].count + 1) * sizes[type],
                         &indices, GL_STREAM_DRAW);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawElements(cases[mode].mode, cases[mode].count, types[type],
                           (const void *)(uintptr_t)sizes[type]);
            glFinish();
            if (glGetError() != GL_NO_ERROR)
                fail("draw");
            pixel(1, 1, 255); pixel(2, 6, 0);
            pixel(5, 2, 255); pixel(6, 6, 255);
        }
    }
}

int main(void)
{
    static const float vertices[7][8] = {
        {-1,-1,0,1, 1,0,0,1}, {0,-1,0,1, 1,0,0,1}, {-1,1,0,1, 1,0,0,1},
        {0,-1,0,1, 1,0,0,1}, {1,-1,0,1, 1,0,0,1},
        {0,1,0,1, 1,0,0,1}, {1,1,0,1, 1,0,0,1},
    };
    const EGLint window_attrs[] = {
        EGL_SURFACE_TYPE,EGL_WINDOW_BIT, EGL_RED_SIZE,8, EGL_GREEN_SIZE,8,
        EGL_BLUE_SIZE,8, EGL_ALPHA_SIZE,8, EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_NONE
    };
    const EGLint pbuffer_attrs[] = {
        EGL_SURFACE_TYPE,EGL_PBUFFER_BIT, EGL_RED_SIZE,8, EGL_GREEN_SIZE,8,
        EGL_BLUE_SIZE,8, EGL_ALPHA_SIZE,8, EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_NONE
    };
    const EGLint size_attrs[] = {EGL_WIDTH,SIDE,EGL_HEIGHT,SIDE,EGL_NONE};
    const char *software = getenv("OPENGPU_GL_SURFACELESS");
    const char *device = getenv("OPENGPU_DRM_DEVICE");
    struct gbm_device *gbm = NULL;
    struct gbm_surface *window = NULL;
    EGLDisplay display;
    EGLSurface surface;
    EGLContext context;
    EGLConfig config;
    EGLint count = 0;
    GLuint vs, fs, program, vbo, ibo, framebuffer, color;
    GLint linked = 0;
    int fd = -1;

    if (software) {
        if (!getenv("OPENGPU_GL_ALLOW_SOFTWARE")) fail("software oracle not enabled");
        display = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    } else {
        fd = open(device ? device : "/dev/dri/card0", O_RDWR);
        if (fd < 0 || !(gbm = gbm_create_device(fd))) fail("open GBM device");
        window = gbm_surface_create(gbm, 32, 32, GBM_FORMAT_ABGR8888, GBM_BO_USE_RENDERING);
        if (!window) fail("create GBM surface");
        display = eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR, gbm, NULL);
    }
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, NULL, NULL) ||
        !eglBindAPI(EGL_OPENGL_API)) fail("initialize desktop GL");
    if (software) {
        if (!eglChooseConfig(display, pbuffer_attrs, &config, 1, &count) || count != 1)
            fail("choose software config");
        surface = eglCreatePbufferSurface(display, config, size_attrs);
    } else {
        if (!opengpu_gl_choose_config(display, window_attrs, GBM_FORMAT_ABGR8888, &config, &count) || count != 1)
            fail("choose GBM config");
        surface = eglCreateWindowSurface(display, config, (EGLNativeWindowType)window, NULL);
    }
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, NULL);
    if (surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(display, surface, surface, context)) fail("create GL context");
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    const char *extensions = (const char *)glGetString(GL_EXTENSIONS);
    if (!renderer || (!strstr(renderer, "opengpu") && !getenv("OPENGPU_GL_ALLOW_SOFTWARE")))
        fail("unexpected renderer");
    if (!extensions || !strstr(extensions, "GL_NV_primitive_restart"))
        fail("primitive restart extension missing");
    enable_client_fn enable_client = (enable_client_fn)eglGetProcAddress("glEnableClientState");
    restart_index_fn restart_index = (restart_index_fn)eglGetProcAddress("glPrimitiveRestartIndexNV");
    if (!enable_client || !restart_index) fail("primitive restart entry points missing");
    enable_client(GL_PRIMITIVE_RESTART_NV);

    vs = shader(GL_VERTEX_SHADER, "attribute vec4 pos; attribute vec4 color; varying vec4 c; void main(){gl_Position=pos;c=color;}");
    fs = shader(GL_FRAGMENT_SHADER, "varying vec4 c; void main(){gl_FragColor=c;}");
    program = glCreateProgram(); glAttachShader(program, vs); glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos"); glBindAttribLocation(program, 1, "color");
    glLinkProgram(program); glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) fail("link shader");
    glUseProgram(program); glGenBuffers(1, &vbo); glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)0);
    glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)(4*sizeof(float)));
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glGenBuffers(1,&ibo); glGenFramebuffers(1,&framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER,framebuffer); glViewport(0,0,SIDE,SIDE);
    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_DITHER);
    glEnable(GL_CULL_FACE); glCullFace(GL_BACK); glFrontFace(GL_CCW); glClearColor(0,0,0,1);
    for (unsigned texture=0; texture<2; texture++) {
        if (texture) {
            glGenTextures(1,&color); glBindTexture(GL_TEXTURE_2D,color);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,SIDE,SIDE,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
        } else {
            glGenRenderbuffers(1,&color); glBindRenderbuffer(GL_RENDERBUFFER,color);
            glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA8_OES,SIDE,SIDE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,color);
        }
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) fail("framebuffer incomplete");
        exercise(ibo, restart_index);
        if (texture) glDeleteTextures(1,&color); else glDeleteRenderbuffers(1,&color);
        printf("gl_primitive_restart: %s PASS\n", texture ? "texture" : "renderbuffer");
        fflush(stdout);
    }
    glDeleteFramebuffers(1,&framebuffer); glDeleteBuffers(1,&ibo); glDeleteBuffers(1,&vbo);
    glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    eglDestroyContext(display,context); eglDestroySurface(display,surface); eglTerminate(display);
    if (window) gbm_surface_destroy(window);
    if (gbm) gbm_device_destroy(gbm);
    if (fd >= 0) close(fd);
    return 0;
}
