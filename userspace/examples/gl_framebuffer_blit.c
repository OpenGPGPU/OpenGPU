/* SPDX-License-Identifier: MIT */
/* Small, targeted GLES framebuffer blits; not GLES 3 conformance. */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <gbm.h>
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "opengpu_gl_egl.h"

struct test_case {
    const char *name;
    int sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1;
    int scissor, x, y, w, h;
};

static void fail(const char *where)
{
    fprintf(stderr, "gl_framebuffer_blit: %s: GL 0x%x EGL 0x%x\n",
            where, glGetError(), eglGetError());
    exit(1);
}

static GLuint target(unsigned width, unsigned height, const uint8_t *data,
                     GLuint *texture)
{
    GLuint fb;
    glGenTextures(1, texture);
    glBindTexture(GL_TEXTURE_2D, *texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, data);
    glGenFramebuffers(1, &fb);
    glBindFramebuffer(GL_FRAMEBUFFER, fb);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, *texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        fail("framebuffer incomplete");
    return fb;
}

static void check_case(const struct test_case *c, const uint8_t source[4][4][4],
                       GLuint src, GLuint dst)
{
    uint8_t got[8][8][4];
    int xmin=c->dx0<c->dx1 ? c->dx0 : c->dx1;
    int xmax=c->dx0>c->dx1 ? c->dx0 : c->dx1;
    int ymin=c->dy0<c->dy1 ? c->dy0 : c->dy1;
    int ymax=c->dy0>c->dy1 ? c->dy0 : c->dy1;

    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_FRAMEBUFFER, dst);
    glClearColor(0,0,0,0);
    glClear(GL_COLOR_BUFFER_BIT);
    if (c->scissor) {
        glEnable(GL_SCISSOR_TEST);
        glScissor(c->x,c->y,c->w,c->h);
    }
    glBindFramebuffer(GL_READ_FRAMEBUFFER, src);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dst);
    glBlitFramebuffer(c->sx0,c->sy0,c->sx1,c->sy1,
                      c->dx0,c->dy0,c->dx1,c->dy1,
                      GL_COLOR_BUFFER_BIT,GL_NEAREST);
    if (glGetError() != GL_NO_ERROR) fail("blit framebuffer");
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_FRAMEBUFFER, dst);
    glReadPixels(0,0,8,8,GL_RGBA,GL_UNSIGNED_BYTE,got);
    if (glGetError() != GL_NO_ERROR) fail("read framebuffer");
    for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
        const uint8_t empty[4]={0,0,0,0};
        const uint8_t *expected=empty;
        if (x>=xmin && x<xmax && y>=ymin && y<ymax &&
            (!c->scissor || (x>=c->x && x<c->x+c->w &&
                             y>=c->y && y<c->y+c->h))) {
            double u=c->sx0+(x+0.5-c->dx0)*(c->sx1-c->sx0)/(c->dx1-c->dx0);
            double v=c->sy0+(y+0.5-c->dy0)*(c->sy1-c->sy0)/(c->dy1-c->dy0);
            int ix=(int)floor(u), iy=(int)floor(v);
            if (ix>=0 && ix<4 && iy>=0 && iy<4) expected=source[iy][ix];
        }
        if (memcmp(got[y][x],expected,4)) {
            fprintf(stderr,"%s (%d,%d): got %u,%u,%u,%u expected %u,%u,%u,%u\n",
                    c->name,x,y,got[y][x][0],got[y][x][1],got[y][x][2],got[y][x][3],
                    expected[0],expected[1],expected[2],expected[3]);
            fail("pixel mismatch");
        }
    }
    printf("gl_framebuffer_blit: %s PASS\n", c->name);
}

static GLuint compile_shader(GLenum type, const char *source)
{
    GLuint shader=glCreateShader(type);
    GLint ok=0;
    glShaderSource(shader,1,&source,NULL);
    glCompileShader(shader);
    glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(shader,sizeof(log),NULL,log);
        fprintf(stderr,"gl_framebuffer_blit: shader: %s\n",log);
        fail("compile shader");
    }
    return shader;
}

static void blit_draw(GLuint vbo, float red, float green, float blue)
{
    static const float xy[6][2]={
        {-1,-1},{1,-1},{-1,1},{1,-1},{1,1},{-1,1}
    };
    float vertices[6][8];
    for (unsigned i=0;i<6;i++) {
        vertices[i][0]=xy[i][0]; vertices[i][1]=xy[i][1];
        vertices[i][2]=0; vertices[i][3]=1;
        vertices[i][4]=red; vertices[i][5]=green;
        vertices[i][6]=blue; vertices[i][7]=1;
    }
    glBindBuffer(GL_ARRAY_BUFFER,vbo);
    glBufferData(GL_ARRAY_BUFFER,sizeof(vertices),vertices,GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES,0,6);
}

/* Verify copied and preserved aspects through depth/stencil tests, including
 * the complete image outside a scissored destination. */
static void check_depth_stencil_blits(void)
{
    static const struct { GLbitfield mask; const char *name; } cases[]={
        {GL_DEPTH_BUFFER_BIT,"depth"}, {GL_STENCIL_BUFFER_BIT,"stencil"},
        {GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT,"depth and stencil"},
    };
    static const char *vs_source=
        "attribute vec4 pos; attribute vec4 color; varying vec4 v_color;\n"
        "void main() { gl_Position=pos; v_color=color; }\n";
    static const char *fs_source=
        "precision mediump float; varying vec4 v_color;\n"
        "void main() { gl_FragColor=v_color; }\n";
    GLuint color[2], fb[2], zs[2], vbo, program, vs, fs;
    uint8_t got[8][8][4];
    GLint linked=0;

    for (unsigned i=0;i<2;i++) {
        fb[i]=target(8,8,NULL,&color[i]);
        glGenRenderbuffers(1,&zs[i]); glBindRenderbuffer(GL_RENDERBUFFER,zs[i]);
        glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH24_STENCIL8,8,8);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_STENCIL_ATTACHMENT,
                                  GL_RENDERBUFFER,zs[i]);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
            fail("depth/stencil framebuffer incomplete");
    }
    vs=compile_shader(GL_VERTEX_SHADER,vs_source);
    fs=compile_shader(GL_FRAGMENT_SHADER,fs_source);
    program=glCreateProgram(); glAttachShader(program,vs); glAttachShader(program,fs);
    glBindAttribLocation(program,0,"pos"); glBindAttribLocation(program,1,"color");
    glLinkProgram(program); glGetProgramiv(program,GL_LINK_STATUS,&linked);
    if (!linked) fail("link depth/stencil shader");
    glUseProgram(program); glGenBuffers(1,&vbo); glBindBuffer(GL_ARRAY_BUFFER,vbo);
    glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)0);
    glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,8*sizeof(float),(void *)(4*sizeof(float)));
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glViewport(0,0,8,8); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
    glDisable(GL_DITHER); glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    glDepthMask(GL_TRUE); glStencilMask(0xff);
    glBindFramebuffer(GL_FRAMEBUFFER,fb[0]);
    glDisable(GL_SCISSOR_TEST); glClearDepthf(.25f); glClearStencil(3);
    glClear(GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST); glScissor(2,2,4,4);
    glClearDepthf(.75f); glClearStencil(7);
    glClear(GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);

    for (unsigned i=0;i<sizeof(cases)/sizeof(cases[0]);i++) {
        glBindFramebuffer(GL_FRAMEBUFFER,fb[1]);
        glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_SCISSOR_TEST);
        glDepthMask(GL_TRUE); glStencilMask(0xff);
        glClearColor(0,0,0,1); glClearDepthf(.6f); glClearStencil(9);
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,fb[0]);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fb[1]);
        glEnable(GL_SCISSOR_TEST); glScissor(1,1,6,6);
        glBlitFramebuffer(0,0,8,8,0,0,8,8,cases[i].mask,GL_NEAREST);
        glDisable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER,fb[1]);
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS); glDepthMask(GL_FALSE);
        glDisable(GL_STENCIL_TEST); blit_draw(vbo,1,0,0);
        glReadPixels(0,0,8,8,GL_RGBA,GL_UNSIGNED_BYTE,got);
        if (glGetError()!=GL_NO_ERROR) fail("depth blit readback");
        for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
            int copied=x>=1 && x<7 && y>=1 && y<7;
            int inner=x>=2 && x<6 && y>=2 && y<6;
            int pass=!(cases[i].mask&GL_DEPTH_BUFFER_BIT) || !copied || inner;
            const uint8_t expected[4]={pass ? 255 : 0,0,0,255};
            if (memcmp(got[y][x],expected,4)) {
                fprintf(stderr,"%s depth (%d,%d): got %u,%u,%u,%u\n",cases[i].name,
                        x,y,got[y][x][0],got[y][x][1],got[y][x][2],got[y][x][3]);
                fail("depth aspect mismatch");
            }
        }
        glDisable(GL_DEPTH_TEST); glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_STENCIL_TEST); glStencilMask(0); glStencilOp(GL_KEEP,GL_KEEP,GL_KEEP);
        glStencilFunc(GL_EQUAL,3,0xff); blit_draw(vbo,1,0,0);
        glStencilFunc(GL_EQUAL,7,0xff); blit_draw(vbo,0,1,0);
        glStencilFunc(GL_EQUAL,9,0xff); blit_draw(vbo,0,0,1);
        glReadPixels(0,0,8,8,GL_RGBA,GL_UNSIGNED_BYTE,got);
        if (glGetError()!=GL_NO_ERROR) fail("stencil blit readback");
        for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
            int copied=(cases[i].mask&GL_STENCIL_BUFFER_BIT) &&
                       x>=1 && x<7 && y>=1 && y<7;
            int inner=x>=2 && x<6 && y>=2 && y<6;
            const uint8_t expected[4]={copied && !inner ? 255 : 0,
                                      copied && inner ? 255 : 0,
                                      copied ? 0 : 255,255};
            if (memcmp(got[y][x],expected,4)) {
                fprintf(stderr,"%s stencil (%d,%d): got %u,%u,%u,%u\n",cases[i].name,
                        x,y,got[y][x][0],got[y][x][1],got[y][x][2],got[y][x][3]);
                fail("stencil aspect mismatch");
            }
        }
        printf("gl_framebuffer_blit: %s aspects PASS\n",cases[i].name); fflush(stdout);
    }
    glStencilMask(0xff); glDepthMask(GL_TRUE); glDisable(GL_STENCIL_TEST);
    glDeleteBuffers(1,&vbo); glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    glDeleteFramebuffers(2,fb); glDeleteRenderbuffers(2,zs); glDeleteTextures(2,color);
}

int main(void)
{
    static const struct test_case cases[]={
        {"upscale",0,0,4,4,0,0,8,8,0,0,0,0,0},
        {"flip and scissor",3,0,0,3,1,1,5,5,1,2,2,3,3},
        {"source bounds",-1,-1,3,3,0,0,8,8,0,0,0,0,0},
        {"destination bounds",0,0,3,3,-1,-1,3,3,0,0,0,0,0},
        {"destination flip",0,0,3,3,6,5,2,1,0,0,0,0,0},
        {"downscale",0,0,3,3,2,2,4,4,0,0,0,0,0},
        {"forward ties",0,0,4,4,0,0,2,2,0,0,0,0,0},
        {"reverse ties",4,4,0,0,0,0,2,2,0,0,0,0,0},
    };
    const EGLint window_attrs[]={EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RED_SIZE,8,
        EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,
        EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE};
    const EGLint buffer_attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RED_SIZE,8,
        EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,
        EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE};
    const EGLint pbuffer_attrs[]={EGL_WIDTH,8,EGL_HEIGHT,8,EGL_NONE};
    const EGLint context_attrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
    const char *device=getenv("OPENGPU_DRM_DEVICE"), *renderer;
    int software=getenv("OPENGPU_GL_SURFACELESS")!=NULL, fd=-1;
    EGLDisplay display; EGLSurface surface; EGLContext context;
    EGLConfig config; EGLint count=0, major, minor;
    struct gbm_device *gbm=NULL; struct gbm_surface *window=NULL;
    GLuint src, dst, src_tex, dst_tex; uint8_t source[4][4][4];

    if (software) {
        if (!getenv("OPENGPU_GL_ALLOW_SOFTWARE")) fail("software oracle not enabled");
        display=eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA,EGL_DEFAULT_DISPLAY,NULL);
    } else {
        fd=open(device ? device : "/dev/dri/card0",O_RDWR);
        if (fd<0 || !(gbm=gbm_create_device(fd)) ||
            !(window=gbm_surface_create(gbm,32,32,GBM_FORMAT_ABGR8888,GBM_BO_USE_RENDERING)))
            fail("open GBM device");
        display=eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR,gbm,NULL);
    }
    if (display==EGL_NO_DISPLAY || !eglInitialize(display,&major,&minor) ||
        !eglBindAPI(EGL_OPENGL_ES_API)) fail("initialize EGL");
    if (software) {
        if (!eglChooseConfig(display,buffer_attrs,&config,1,&count) || count!=1)
            fail("choose pbuffer config");
        surface=eglCreatePbufferSurface(display,config,pbuffer_attrs);
    } else {
        if (!opengpu_gl_choose_config(display,window_attrs,GBM_FORMAT_ABGR8888,&config,&count) || count!=1)
            fail("choose window config");
        surface=eglCreateWindowSurface(display,config,(EGLNativeWindowType)window,NULL);
    }
    context=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attrs);
    if (surface==EGL_NO_SURFACE || context==EGL_NO_CONTEXT ||
        !eglMakeCurrent(display,surface,surface,context)) fail("create GLES3 context");
    renderer=(const char *)glGetString(GL_RENDERER);
    if (!renderer || (!strstr(renderer,"opengpu") && !software)) fail("unexpected renderer");
    printf("gl_framebuffer_blit: renderer %s, version %s\n",renderer,glGetString(GL_VERSION));

    for (int y=0;y<4;y++) for (int x=0;x<4;x++) {
        source[y][x][0]=(uint8_t)(17+50*x); source[y][x][1]=(uint8_t)(21+50*y);
        source[y][x][2]=(uint8_t)(11+7*x+11*y); source[y][x][3]=255;
    }
    src=target(4,4,NULL,&src_tex);
    dst=target(8,8,NULL,&dst_tex);
    glBindFramebuffer(GL_FRAMEBUFFER,src);
    /* A whole-image clear uses the GPU fill path. Preserve its centre while
     * uploading distinct border texels through mapped texture transfers. */
    glDisable(GL_SCISSOR_TEST);
    glClearColor(1,0,1,1); glClear(GL_COLOR_BUFFER_BIT);
    glBindTexture(GL_TEXTURE_2D,src_tex);
    glTexSubImage2D(GL_TEXTURE_2D,0,0,0,4,1,GL_RGBA,GL_UNSIGNED_BYTE,source[0]);
    glTexSubImage2D(GL_TEXTURE_2D,0,0,3,4,1,GL_RGBA,GL_UNSIGNED_BYTE,source[3]);
    for (int y=1;y<3;y++) {
        glTexSubImage2D(GL_TEXTURE_2D,0,0,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,source[y][0]);
        glTexSubImage2D(GL_TEXTURE_2D,0,3,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,source[y][3]);
    }
    for (int y=1;y<3;y++) for (int x=1;x<3;x++) {
        source[y][x][0]=255; source[y][x][1]=0;
        source[y][x][2]=255; source[y][x][3]=255;
    }
    if (!getenv("OPENGPU_GL_DEPTH_BLIT_ONLY"))
        for (unsigned i=0;i<sizeof(cases)/sizeof(cases[0]);i++)
            check_case(&cases[i],source,src,dst);
    glDeleteFramebuffers(1,&src); glDeleteFramebuffers(1,&dst);
    glDeleteTextures(1,&src_tex); glDeleteTextures(1,&dst_tex);
    check_depth_stencil_blits();
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    eglDestroyContext(display,context); eglDestroySurface(display,surface); eglTerminate(display);
    if (window) gbm_surface_destroy(window);
    if (gbm) gbm_device_destroy(gbm);
    if (fd>=0) close(fd);
    puts("gl_framebuffer_blit: all cases PASS");
    return 0;
}
