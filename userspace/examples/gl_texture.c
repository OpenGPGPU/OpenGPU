#define _POSIX_C_SOURCE 200809L
/* GLES2 clear and one textured triangle. The fragment shader is texture2D,
 * so the Gallium driver submits fragment_sample and the GPU sampler returns
 * the texel. This program only uploads the texture, packs the vertex buffer,
 * and reads the GEM back.
 */
#include <EGL/egl.h>
#include "opengpu_gl_egl.h"
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <drm/drm_fourcc.h>
#include <fcntl.h>
#include <gbm.h>
#include <xf86drm.h>
#include <xf86drmMode.h>
#include "opengpu_gl_mode.h"

#include <errno.h>
#include <execinfo.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#define COLOR_CLEAR 0x0000ffffu /* glClearColor(0, 0, 1, 1) */
#define COLOR_TEXEL 0xff0000ffu /* texture texel (1, 0, 0, 1) */

static const char *vs_src =
    "attribute vec4 pos;\n"
    "attribute vec2 uv;\n"
    "varying vec2 v_uv;\n"
    "void main() {\n"
    "  gl_Position = pos;\n"
    "  v_uv = uv;\n"
    "}\n";

static const char *fs_src =
    "precision mediump float;\n"
    "varying vec2 v_uv;\n"
    "uniform sampler2D tex;\n"
    "void main() {\n"
    "  gl_FragColor = texture2D(tex, v_uv);\n"
    "}\n";

/* GtkGLArea composition: keep the branch uniform dynamic so Mesa emits
 * the captured TEX/SEQ/CMP form. Test both paths with distinct R/B values. */
static const char *swizzle_fs_src =
    "precision mediump float;\n"
    "varying vec2 v_uv;\n"
    "uniform sampler2D tex;\n"
    "uniform float swap_rb;\n"
    "void main() {\n"
    "  vec4 c = texture2D(tex, v_uv);\n"
    "  gl_FragColor = swap_rb == 1.0 ? c.bgra : c;\n"
    "}\n";

/* Clip triangle covering the GPU's top-left half. UVs stay near texel 0 at
 * the first vertex, which is scanout row 0. */
/* Small top-left triangle. At 640x480 the vertices land on (0, 0),
 * (160, 0) and (0, 60). */
static const float verts[] = {
    -1.f, -1.f,   0.f, 1.f, 0.f, 0.f,
    -0.5f, -1.f,  0.f, 1.f, 1.f, 0.f,
    -1.f, -0.75f, 0.f, 1.f, 0.f, 1.f,
};

static void crash(int sig)
{
    void *frames[32];
    int n = backtrace(frames, 32);
    static const char msg[] = "gl_texture: fatal signal, backtrace:\n";

    write(2, msg, sizeof(msg) - 1);
    backtrace_symbols_fd(frames, n, 2);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void step(const char *what)
{
    fprintf(stderr, "gl_texture: %s\n", what);
}

static void die(const char *what)
{
    fprintf(stderr, "gl_texture: %s\n", what);
    exit(1);
}

static void die_errno(const char *what)
{
    fprintf(stderr, "gl_texture: %s: %s\n", what, strerror(errno));
    exit(1);
}

static void die_gl(const char *what)
{
    fprintf(stderr, "gl_texture: %s (egl 0x%x)\n", what, eglGetError());
    exit(1);
}

static GLuint compile_shader(GLenum type, const char *src)
{
    GLuint shader = glCreateShader(type);
    GLint ok = 0;
    char log[512];

    glShaderSource(shader, 1, &src, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        fprintf(stderr, "gl_texture: shader compile failed: %s\n", log);
        exit(1);
    }
    return shader;
}

static uint32_t pixel_word(int x, int y)
{
    uint8_t px[4];

    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    return ((uint32_t)px[0] << 24) | ((uint32_t)px[1] << 16) |
           ((uint32_t)px[2] << 8) | (uint32_t)px[3];
}

int main(void)
{
    int fd;
    drmModeRes *res = NULL;
    drmModeConnector *conn = NULL;
    drmModeEncoder *enc = NULL;
    drmModeModeInfo mode;
    struct gbm_device *gbm = NULL;
    struct gbm_surface *gsurf = NULL;
    struct gbm_bo *bo = NULL;
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLConfig config;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLint major = 0, minor = 0, nconfig = 0;
    GLuint program, vbo, tex, vs, fs;
    GLint linked = 0, slot;
    uint32_t crtc_id, fb = 0;
    uint32_t handles[4] = { 0 }, pitches[4] = { 0 }, offsets[4] = { 0 };
    uint32_t tri, clear;
    int i;
    const int swizzle_test = getenv("OPENGPU_GL_GTK_SWIZZLE") != NULL;
    const uint32_t texture_color = getenv("OPENGPU_GL_DISTINCT_TEXTURE")
        ? 0x115395d3u : COLOR_TEXEL;
    uint8_t texels[4 * 4 * 4];
    const EGLint config_attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE
    };
    const EGLint context_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    char log[512];

    signal(SIGSEGV, crash);
    signal(SIGBUS, crash);
    signal(SIGILL, crash);
    signal(SIGABRT, crash);
    fd = open("/dev/dri/card0", O_RDWR);
    if (fd < 0)
        die("open /dev/dri/card0");
    res = drmModeGetResources(fd);
    if (!res)
        die("drmModeGetResources");
    for (i = 0; i < res->count_connectors; i++) {
        conn = drmModeGetConnector(fd, res->connectors[i]);
        if (conn && conn->connection == DRM_MODE_CONNECTED &&
            conn->count_modes > 0)
            break;
        drmModeFreeConnector(conn);
        conn = NULL;
    }
    if (!conn)
        die("no connected KMS mode");
    if (opengpu_gl_choose_mode(conn, &mode))
        die("requested KMS mode is unavailable");
    if (conn->encoder_id)
        enc = drmModeGetEncoder(fd, conn->encoder_id);
    if (!enc && conn->count_encoders > 0)
        enc = drmModeGetEncoder(fd, conn->encoders[0]);
    if (!enc)
        die("no KMS encoder");
    crtc_id = enc->crtc_id;
    if (!crtc_id) {
        for (i = 0; i < res->count_crtcs; i++) {
            if (enc->possible_crtcs & (1u << i)) {
                crtc_id = res->crtcs[i];
                break;
            }
        }
    }
    if (!crtc_id)
        die("no KMS crtc");

    if (opengpu_gl_activate_mode(fd, conn, crtc_id, &mode))
        die("activate selected KMS mode");
    gbm = gbm_create_device(fd);
    if (!gbm)
        die("gbm_create_device");
    gsurf = gbm_surface_create(gbm, mode.hdisplay, mode.vdisplay,
                               GBM_FORMAT_ABGR8888,
                               GBM_BO_USE_SCANOUT | GBM_BO_USE_RENDERING);
    if (!gsurf)
        die("gbm_surface_create");
    dpy = eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR, gbm, NULL);
    if (dpy == EGL_NO_DISPLAY)
        die_gl("eglGetPlatformDisplay");
    if (!eglInitialize(dpy, &major, &minor))
        die_gl("eglInitialize");
    if (!eglBindAPI(EGL_OPENGL_ES_API))
        die_gl("eglBindAPI");
    if (!opengpu_gl_choose_config(dpy, config_attribs, GBM_FORMAT_ABGR8888,
                                   &config, &nconfig) ||
        nconfig < 1) {
        fprintf(stderr, "gl_texture: eglChooseConfig matched %d configs\n",
                nconfig);
        return 1;
    }
    surface = eglCreateWindowSurface(dpy, config, (EGLNativeWindowType)gsurf,
                                     NULL);
    if (surface == EGL_NO_SURFACE)
        die_gl("eglCreateWindowSurface");
    ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, context_attribs);
    if (ctx == EGL_NO_CONTEXT)
        die_gl("eglCreateContext");
    if (!eglMakeCurrent(dpy, surface, surface, ctx))
        die_gl("eglMakeCurrent");

    step("context current");
    vs = compile_shader(GL_VERTEX_SHADER, vs_src);
    fs = compile_shader(GL_FRAGMENT_SHADER, swizzle_test ? swizzle_fs_src : fs_src);
    program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos");
    glBindAttribLocation(program, 1, "uv");
    glLinkProgram(program);
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        glGetProgramInfoLog(program, sizeof(log), NULL, log);
        fprintf(stderr, "gl_texture: link failed: %s\n", log);
        return 1;
    }
    glUseProgram(program);
    slot = glGetUniformLocation(program, "tex");
    if (slot < 0)
        die("sampler uniform missing");
    glUniform1i(slot, 0);

    memset(texels, 0, sizeof(texels));
    for (i = 0; i < 16; i++) {
        texels[i * 4 + 0] = texture_color >> 24;
        texels[i * 4 + 1] = texture_color >> 16;
        texels[i * 4 + 2] = texture_color >> 8;
        texels[i * 4 + 3] = texture_color;
    }
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (getenv("OPENGPU_GL_PADDED_TEXTURE")) {
        struct drm_mode_create_dumb image_dumb = { .width = 64, .height = 4, .bpp = 32 };
        struct drm_mode_map_dumb image_map = {0};
        uint32_t image_stride;
        void *mapped;
        int image_fd;
        EGLImageKHR image;
        PFNEGLCREATEIMAGEKHRPROC create_image =
            (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
        PFNGLEGLIMAGETARGETTEXTURE2DOESPROC image_target =
            (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)
                eglGetProcAddress("glEGLImageTargetTexture2DOES");
        if (!create_image || !image_target ||
            ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &image_dumb))
            die("create padded texture image");
        image_map.handle = image_dumb.handle;
        if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &image_map))
            die("map padded texture image");
        mapped = mmap(NULL, image_dumb.size, PROT_READ | PROT_WRITE,
                       MAP_SHARED, fd, image_map.offset);
        image_stride = image_dumb.pitch;
        if (mapped == MAP_FAILED || image_stride <= 16)
            die("padded texture requires a non-packed allocation");
        for (i = 0; i < 4; i++)
            memcpy((uint8_t *)mapped + i * image_stride, texels + i * 16, 16);
        /* GEM DMA mappings are coherent; order CPU writes before export. */
        __sync_synchronize();
        if (drmPrimeHandleToFD(fd, image_dumb.handle, DRM_CLOEXEC | DRM_RDWR, &image_fd))
            die_errno("export padded texture");
        munmap(mapped, image_dumb.size);
        {
            const EGLint attributes[] = {
                EGL_WIDTH, 4, EGL_HEIGHT, 4,
                EGL_LINUX_DRM_FOURCC_EXT, GBM_FORMAT_ABGR8888,
                EGL_DMA_BUF_PLANE0_FD_EXT, image_fd,
                EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
                EGL_DMA_BUF_PLANE0_PITCH_EXT, (EGLint)image_stride,
                EGL_NONE,
            };
            image = create_image(dpy, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT,
                                  NULL, attributes);
        }
        close(image_fd);
        if (image == EGL_NO_IMAGE_KHR)
            die_gl("import padded texture");
        image_target(GL_TEXTURE_2D, image);
        fprintf(stderr, "gl_texture: imported padded image pitch=%u\n", image_stride);
        /* Keep the image and its dumb BO alive through the native fixture draw. */
    } else {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 4, 4, 0, GL_RGBA,
                     GL_UNSIGNED_BYTE, texels);
    }
    step("texture uploaded");

    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float),
                          (void *)0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float),
                          (void *)(4 * sizeof(float)));
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    glViewport(0, 0, mode.hdisplay, mode.vdisplay);
    for (int variant = 0; variant < (swizzle_test ? 2 : 1); variant++) {
        if (swizzle_test) {
            GLint swap = glGetUniformLocation(program, "swap_rb");
            if (swap < 0)
                die("swizzle uniform missing");
            glUniform1f(swap, (float)variant);
            /* Green clear distinguishes a missing draw from either branch. */
            glClearColor(0.f, 1.f, 0.f, 1.f);
        } else {
            glClearColor(0.f, 0.f, 1.f, 1.f);
        }
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glFinish();
        tri = pixel_word(opengpu_gl_probe_x(&mode, 8), mode.vdisplay - 1 - opengpu_gl_probe_y(&mode, 8));
        clear = pixel_word(mode.hdisplay - opengpu_gl_probe_x(&mode, 8), mode.vdisplay - 1 - opengpu_gl_probe_y(&mode, 8));
        printf("gl_texture: variant=%d triangle=0x%08x clear=0x%08x\n",
               variant, tri, clear);
        uint32_t expected = variant
            ? (texture_color & 0x00ff00ffu) |
              ((texture_color & 0xff000000u) >> 16) |
              ((texture_color & 0x0000ff00u) << 16)
            : texture_color;
        if (glGetError() != GL_NO_ERROR ||
            tri != expected ||
            clear != (swizzle_test ? 0x00ff00ffu : COLOR_CLEAR)) {
            fprintf(stderr, "gl_texture: pixel mismatch\n");
            return 1;
        }
    }

    step("swap");
    if (!eglSwapBuffers(dpy, surface))
        die_gl("eglSwapBuffers");
    bo = gbm_surface_lock_front_buffer(gsurf);
    if (!bo)
        die("gbm_surface_lock_front_buffer");
    handles[0] = gbm_bo_get_handle(bo).u32;
    pitches[0] = gbm_bo_get_stride(bo);
    if (drmModeAddFB2(fd, mode.hdisplay, mode.vdisplay, gbm_bo_get_format(bo),
                      handles, pitches, offsets, &fb, 0))
        die_errno("drmModeAddFB2");
    if (drmModeSetCrtc(fd, crtc_id, fb, 0, 0, &conn->connector_id, 1, &mode))
        die_errno("drmModeSetCrtc (another DRM master holds the display?)");
    printf("OPENGPU GL TEXTURE PASS\n");
    fflush(stdout);
    if (getenv("OPENGPU_GL_EXIT"))
        return 0;
    fprintf(stderr, "gl_texture: holding the frame; Ctrl-C to exit\n");
    for (;;)
        pause();
    return 0;
}
