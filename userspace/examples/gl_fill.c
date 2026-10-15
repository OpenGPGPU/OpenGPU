/* GLES2 clear and one filled triangle shifted by a uniform matrix.
 * Positions are vec2; the driver packs them as (x, y, 0, 1) and the vertex
 * core multiplies gl_Position = u_mvp * pos. The driver packs u_color into
 * the colour interpolant. The fragment core copies that colour.
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

#define COLOR_CLEAR 0x0000ffffu /* glClearColor(0, 0, 1, 1) */
#define COLOR_FILL 0xff0000ffu   /* glUniform4f u_color (1, 0, 0, 1) */

static const char *vs_src =
    "attribute vec2 pos;\n"
    "uniform mat4 u_mvp;\n"
    "void main() {\n"
    "  gl_Position = u_mvp * vec4(pos, 0.0, 1.0);\n"
    "}\n";

/* Column-major. Adds 0.5 to clip x, so the left edge sits at a quarter of the width. */
static float mvp[16] = {
    1.f, 0.f, 0.f, 0.f,
    0.f, 1.f, 0.f, 0.f,
    0.f, 0.f, 1.f, 0.f,
    0.5f, 0.f, 0.f, 1.f,
};

static const char *fs_src =
    "precision mediump float;\n"
    "uniform vec4 u_color;\n"
    "void main() {\n"
    "  gl_FragColor = u_color;\n"
    "}\n";

/* Clip triangle covering the GPU's top-left half. */
static float verts[] = {
    -1.f, -1.f,
     0.f, -1.f,
    -1.f, -0.75f,
};

static void crash(int sig)
{
    void *frames[32];
    int n = backtrace(frames, 32);
    static const char msg[] = "gl_fill: fatal signal, backtrace:\n";

    write(2, msg, sizeof(msg) - 1);
    backtrace_symbols_fd(frames, n, 2);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void step(const char *what)
{
    fprintf(stderr, "gl_fill: %s\n", what);
}

static void die(const char *what)
{
    fprintf(stderr, "gl_fill: %s\n", what);
    exit(1);
}

static void die_errno(const char *what)
{
    fprintf(stderr, "gl_fill: %s: %s\n", what, strerror(errno));
    exit(1);
}

static void die_gl(const char *what)
{
    fprintf(stderr, "gl_fill: %s (egl 0x%x)\n", what, eglGetError());
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
        fprintf(stderr, "gl_fill: shader compile failed: %s\n", log);
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

/* Offscreen attachment sizes must not inherit the active KMS mode. Keep all
 * three targets alive, then rebind them in reverse order to catch accidental
 * writes into another target as well as incorrect viewport mapping. */
static void offscreen_regression(GLint matrix, GLint color)
{
    static const unsigned sizes[3][2] = {{64, 64}, {60, 32}, {280, 124}};
    static const GLfloat rectangle[] = {
        -.75f, -.5f, .75f, -.5f, .75f, .5f,
        -.75f, -.5f, .75f, .5f, -.75f, .5f,
    };
    static const GLfloat identity[16] = {
        1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
    };
    GLuint textures[3], targets[3];
    unsigned char *pixels[3];

    glUniformMatrix4fv(matrix, 1, GL_FALSE, identity);
    glBufferData(GL_ARRAY_BUFFER, sizeof(rectangle), rectangle, GL_STATIC_DRAW);
    glGenTextures(3, textures);
    glGenFramebuffers(3, targets);
    for (unsigned i = 0; i < 3; i++) {
        unsigned w = sizes[i][0], h = sizes[i][1];

        pixels[i] = malloc((size_t)w * h * 4);
        if (!pixels[i])
            die("offscreen readback allocation");
        glBindTexture(GL_TEXTURE_2D, textures[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        /* GtkGLArea without alpha uses an RGB attachment. Exercise its
         * implicit alpha and BGRA readback along with the RGBA targets. */
        GLenum format = i == 2 ? GL_RGB : GL_RGBA;
        glTexImage2D(GL_TEXTURE_2D, 0, format, w, h, 0,
                     format, GL_UNSIGNED_BYTE, NULL);
        glBindFramebuffer(GL_FRAMEBUFFER, targets[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                              GL_TEXTURE_2D, textures[i], 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            die("offscreen framebuffer incomplete");
        glViewport(0, 0, w, h);
        glClearColor(0, 0, 1, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glUniform4f(color, i != 1, i != 0, 0, 1);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels[i]);
        if (glGetError() != GL_NO_ERROR)
            die("offscreen draw/readback GL error");
        if (strstr((const char *)glGetString(GL_EXTENSIONS),
                   "GL_EXT_read_format_bgra")) {
            size_t bytes = (size_t)w * h * 4;
            unsigned char *bgra = malloc(bytes);

            if (!bgra)
                die("offscreen BGRA readback allocation");
            glReadPixels(0, 0, w, h, GL_BGRA_EXT, GL_UNSIGNED_BYTE, bgra);
            if (glGetError() != GL_NO_ERROR)
                die("offscreen BGRA readback GL error");
            for (size_t p = 0; p < bytes; p += 4) {
                if (bgra[p] != pixels[i][p + 2] || bgra[p + 1] != pixels[i][p + 1] ||
                    bgra[p + 2] != pixels[i][p] || bgra[p + 3] != pixels[i][p + 3])
                    die("offscreen BGRA readback channel order");
            }
            free(bgra);
        }
        for (unsigned y = 0; y < h; y++) {
            for (unsigned x = 0; x < w; x++) {
                int inside = x > w / 8 + 1 && x + 2 < 7 * w / 8 &&
                             y > h / 4 + 1 && y + 2 < 3 * h / 4;
                int outside = x + 2 < w / 8 || x > 7 * w / 8 + 1 ||
                              y + 2 < h / 4 || y > 3 * h / 4 + 1;
                const unsigned char *p = pixels[i] + ((size_t)y * w + x) * 4;
                const unsigned expected[4] = {
                    inside && i != 1 ? 255 : 0,
                    inside && i != 0 ? 255 : 0,
                    outside ? 255 : 0, 255,
                };

                if (!inside && !outside)
                    continue; /* Rasterized boundary pixels are rounded. */
                for (unsigned c = 0; c < 4; c++) {
                    if (abs((int)p[c] - (int)expected[c]) > 1) {
                        fprintf(stderr, "offscreen %ux%u pixel %u,%u channel %u: %u expected %u\n",
                                w, h, x, y, c, p[c], expected[c]);
                        exit(1);
                    }
                }
            }
        }
    }
    for (int i = 2; i >= 0; i--) {
        size_t bytes = (size_t)sizes[i][0] * sizes[i][1] * 4;
        unsigned char *again = malloc(bytes);

        if (!again)
            die("offscreen preservation allocation");
        glBindFramebuffer(GL_FRAMEBUFFER, targets[i]);
        glReadPixels(0, 0, sizes[i][0], sizes[i][1],
                     GL_RGBA, GL_UNSIGNED_BYTE, again);
        if (glGetError() != GL_NO_ERROR || memcmp(again, pixels[i], bytes))
            die("offscreen attachment changed after rebinding");
        free(again);
        free(pixels[i]);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(3, targets);
    glDeleteTextures(3, textures);
    puts("OPENGPU GL OFFSCREEN PASS: 64x64 60x32 280x124, interior, exterior and rebind preservation");
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
    GLuint program, vbo, vs, fs;
    GLint linked = 0, mvp_loc, color_loc;
    const char *repro = getenv("OPENGPU_GL_WESTON_REPRO");
    uint32_t expected_fill = COLOR_FILL;
    unsigned repro_width = 0, repro_height = 0;
    uint32_t crtc_id, fb = 0;
    uint32_t handles[4] = { 0 }, pitches[4] = { 0 }, offsets[4] = { 0 };
    uint32_t outside, inside, clear;
    int i;
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
        fprintf(stderr, "gl_fill: eglChooseConfig matched %d configs\n",
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
    if (repro) {
        if (strcmp(repro, "small") && strcmp(repro, "medium") && strcmp(repro, "full"))
            die("OPENGPU_GL_WESTON_REPRO must be small, medium, or full");
        repro_width = !strcmp(repro, "small") ? 32 :
                      !strcmp(repro, "medium") ? 160 : mode.hdisplay;
        repro_height = !strcmp(repro, "small") ? 24 :
                       !strcmp(repro, "medium") ? 120 : mode.vdisplay;
        memset(mvp, 0, sizeof(mvp));
        mvp[0] = 2.f / mode.hdisplay;
        mvp[5] = -2.f / mode.vdisplay;
        mvp[10] = mvp[15] = 1.f;
        mvp[12] = -1.f;
        mvp[13] = 1.f;
        verts[0] = verts[1] = verts[3] = 0.f;
        verts[2] = verts[4] = repro_width;
        verts[5] = repro_height;
        fs_src = "precision mediump float;\n"
                 "uniform float alpha;\n"
                 "uniform vec4 u_color;\n"
                 "void main() { gl_FragColor = u_color * alpha; }\n";
        fprintf(stderr, "gl_fill: Weston shader reproduction %ux%u\n",
                repro_width, repro_height);
    }
    fs = compile_shader(GL_FRAGMENT_SHADER, fs_src);
    program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos");
    glLinkProgram(program);
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        glGetProgramInfoLog(program, sizeof(log), NULL, log);
        fprintf(stderr, "gl_fill: link failed: %s\n", log);
        return 1;
    }
    glUseProgram(program);
    mvp_loc = glGetUniformLocation(program, "u_mvp");
    if (mvp_loc < 0)
        die("u_mvp uniform missing");
    glUniformMatrix4fv(mvp_loc, 1, GL_FALSE, mvp);
    color_loc = glGetUniformLocation(program, "u_color");
    if (color_loc < 0)
        die("u_color uniform missing");
    if (!repro && getenv("OPENGPU_GL_DISTINCT_COLOR")) {
        glUniform4f(color_loc, 17.f / 255.f, 83.f / 255.f,
                    149.f / 255.f, 211.f / 255.f);
        expected_fill = 0x115395d3u;
    } else {
        glUniform4f(color_loc, 1.f, 0.f, 0.f, 1.f);
    }
    if (repro) {
        GLint alpha_loc = glGetUniformLocation(program, "alpha");
        if (alpha_loc < 0)
            die("alpha uniform missing");
        glUniform1f(alpha_loc, 1.f);
    }

    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float),
                          (void *)0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    if (repro) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }
    if (getenv("OPENGPU_GL_OFFSCREEN")) {
        if (repro)
            die("offscreen regression cannot use the Weston reproduction shader");
        offscreen_regression(mvp_loc, color_loc);
        return 0;
    }
    glViewport(0, 0, mode.hdisplay, mode.vdisplay);
    glClearColor(0.f, 0.f, 1.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    step("clear submitted");
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    step("draw finished");

    /* NDC y=-1 is scanout row 0. Row 8 is the wide end: x=8 is left of the
     * shifted edge and x=100 is inside. GL y=8 is the narrow end, where
     * x=W-8 stays the clear colour. u_color (1, 0, 0, 1) is 0xff0000ff. */
    outside = pixel_word(opengpu_gl_probe_x(&mode, 8), mode.vdisplay - 1 - opengpu_gl_probe_y(&mode, 8));
    inside = pixel_word(100 * mode.hdisplay / 320, mode.vdisplay - 1 - opengpu_gl_probe_y(&mode, 8));
    clear = pixel_word(mode.hdisplay - opengpu_gl_probe_x(&mode, 8),
                       opengpu_gl_probe_y(&mode, 8));
    if (repro) {
        inside = pixel_word(3 * repro_width / 4, repro_height / 4);
        outside = pixel_word(repro_width / 4, 3 * repro_height / 4);
        clear = outside;
    }
    printf("gl_fill: mode %ux%u outside=0x%08x inside=0x%08x clear=0x%08x\n",
           mode.hdisplay, mode.vdisplay, outside, inside, clear);
    fflush(stdout);
    if (outside != COLOR_CLEAR || inside != expected_fill ||
        clear != COLOR_CLEAR) {
        fprintf(stderr, "gl_fill: pixel mismatch\n");
        return 1;
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
    printf("OPENGPU GL FILL PASS\n");
    fflush(stdout);
    if (getenv("OPENGPU_GL_EXIT"))
        return 0;
    fprintf(stderr, "gl_fill: holding the frame; Ctrl-C to exit\n");
    for (;;)
        pause();
    return 0;
}
