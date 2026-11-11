/* Exercise OpenGPU's actual map callbacks with Mesa's copy/clear helpers.
 * GEM and cache publication are mocked; no GPU execution is claimed. */
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "../gallium/opengpu_context.c"
#include "util/format/u_format.h"

struct pipe_opengpu_resource { uint8_t *data; uint64_t bytes; uint32_t pitch; };
static unsigned invalidations;
static unsigned fills, color_clears;
static struct pipe_opengpu_resource *bound_depth;
static struct pipe_opengpu_resource *bound_color;
static int depth_load, bound_sample_mode = -1;
static unsigned temporary_allocations, temporary_frees, fail_invalidation_at;
static unsigned fake_draws;
static int fake_draw_submit_fail, fake_draw_wait_fail;
static int fake_color_write, fake_color_blend;
static uint64_t last_offset, last_bytes;
struct opengpu_resource *opengpu_resource(struct pipe_resource *r) { return (void *)r; }
struct pipe_opengpu_screen *opengpu_screen_gpu(struct pipe_screen *s)
{ (void)s; return (struct pipe_opengpu_screen *)1; }
struct pipe_opengpu_resource *pipe_opengpu_resource_create(struct pipe_opengpu_screen *s,
                                                            uint32_t bytes)
{
    struct pipe_opengpu_resource *r=calloc(1,sizeof(*r));
    (void)s; assert(r); r->data=calloc(1,bytes); assert(r->data);
    r->bytes=bytes; ++temporary_allocations; return r;
}
void pipe_opengpu_resource_destroy(struct pipe_opengpu_screen *s, struct pipe_opengpu_resource *r)
{ (void)s; assert(r); free(r->data); free(r); ++temporary_frees; }
void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *r) { return r->data; }
uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *r) { return r->bytes; }
uint32_t pipe_opengpu_resource_pitch(const struct pipe_opengpu_resource *r) { return r->pitch; }
int pipe_opengpu_invalidate(struct pipe_opengpu_context *c, struct pipe_opengpu_resource *r,
                            uint64_t off, uint64_t bytes, struct pipe_opengpu_fence **f)
{
    (void)c; (void)r; ++invalidations; last_offset=off; last_bytes=bytes;
    if (fail_invalidation_at == invalidations) { errno=EIO; return -1; }
    *f=(struct pipe_opengpu_fence *)1; return 0;
}
int pipe_opengpu_fence_finish(struct pipe_opengpu_context *c, struct pipe_opengpu_fence *f, int64_t t)
{
    (void)c; (void)t;
    if (f==(struct pipe_opengpu_fence *)2 && fake_draw_wait_fail) {
        errno=ETIMEDOUT; return -1;
    }
    return 0;
}
void pipe_opengpu_fence_reference(struct pipe_opengpu_fence **dst, struct pipe_opengpu_fence *src)
{ *dst=src; }
int pipe_opengpu_fill(struct pipe_opengpu_context *c, struct pipe_opengpu_resource *r,
                      uint64_t off, uint64_t bytes, uint32_t pattern,
                      struct pipe_opengpu_fence **f)
{
    (void)c; assert(off + bytes <= r->bytes && !(bytes & 3));
    for (uint64_t i=off; i<off+bytes; i+=4) memcpy(r->data+i,&pattern,4);
    ++fills; *f=(struct pipe_opengpu_fence *)1; return 0;
}
int pipe_opengpu_clear(struct pipe_opengpu_context *c, uint32_t pattern,
                       struct pipe_opengpu_fence **f)
{ (void)c; (void)pattern; ++color_clears; *f=(struct pipe_opengpu_fence *)1; return 0; }
void pipe_opengpu_set_framebuffer_mrt(struct pipe_opengpu_context *c,
        struct pipe_opengpu_resource *colors[4], unsigned n, uint32_t w, uint32_t h)
{ (void)c; (void)w; (void)h; bound_color=n ? colors[0] : NULL; }
void pipe_opengpu_set_depth(struct pipe_opengpu_context *c,
                            struct pipe_opengpu_resource *depth, int load)
{ (void)c; bound_depth=depth; depth_load=load; }
int pipe_opengpu_set_sample_mode(struct pipe_opengpu_context *c, uint32_t mode)
{ (void)c; bound_sample_mode=(int)mode; return 0; }
int pipe_opengpu_draw_vertex_transient(struct pipe_opengpu_context *c,
        const struct drm_opengpu_vertex_draw *draw, struct pipe_opengpu_fence **f)
{
    (void)c; (void)draw; ++fake_draws;
    if (fake_draw_submit_fail) { errno=EIO; return -1; }
    assert(bound_depth && bound_depth->bytes >= 64);
    /* The fake GPU writes one packed depth/stencil word in the bound plane. */
    const uint32_t value=0x87654321u;
    memcpy(bound_depth->data,&value,sizeof(value));
    if (fake_color_write) {
        const uint8_t pixel[4]={0x12,0x34,0x56,0x78};
        assert(bound_color && bound_color->bytes >= sizeof(pixel));
        memcpy(bound_color->data,pixel,sizeof(pixel));
    }
    if (fake_color_blend) {
        unsigned delta;
        assert(bound_color && bound_color->bytes >= 4);
        delta=(64u*bound_color->data[3]+127u)/255u;
        bound_color->data[0]+=(uint8_t)delta;
        bound_color->data[1]=0; bound_color->data[2]=0;
        bound_color->data[3]+=(uint8_t)delta;
    }
    *f=(struct pipe_opengpu_fence *)2;
    return 0;
}

static const struct util_format_description byte_fmt = { .block={1,1,1,8} };
static const struct util_format_description rgba_fmt = { .block={1,1,1,32} };
static const struct util_format_description zs_fmt = {
    .block={1,1,1,32}, .colorspace=UTIL_FORMAT_COLORSPACE_ZS,
    .swizzle={PIPE_SWIZZLE_X,PIPE_SWIZZLE_Y,PIPE_SWIZZLE_NONE,PIPE_SWIZZLE_NONE}
};
static const struct util_format_description z_fmt = {
    .block={1,1,1,32}, .colorspace=UTIL_FORMAT_COLORSPACE_ZS,
    .swizzle={PIPE_SWIZZLE_X,PIPE_SWIZZLE_NONE,PIPE_SWIZZLE_NONE,PIPE_SWIZZLE_NONE}
};
const struct util_format_description *util_format_description(enum pipe_format f)
{
    if (f == PIPE_FORMAT_NONE) return &byte_fmt;
    if (f == PIPE_FORMAT_Z24_UNORM_S8_UINT) return &zs_fmt;
    if (f == PIPE_FORMAT_Z24X8_UNORM) return &z_fmt;
    return &rgba_fmt;
}
static void pack_rgba(uint8_t *dst, unsigned ds, const float *src, unsigned ss,
                      unsigned width, unsigned height)
{
    (void)ds; (void)ss; assert(width==1 && height==1);
    for (unsigned i=0;i<4;i++) dst[i]=(uint8_t)(src[i]*255.0f+0.5f);
}
static void pack_bgra(uint8_t *dst, unsigned ds, const float *src, unsigned ss,
                      unsigned width, unsigned height)
{
    pack_rgba(dst,ds,src,ss,width,height);
    uint8_t red=dst[0]; dst[0]=dst[2]; dst[2]=red;
}
const struct util_format_pack_description *util_format_pack_description(enum pipe_format f)
{
    static const struct util_format_pack_description rgba={.pack_rgba_float=pack_rgba};
    static const struct util_format_pack_description bgra={.pack_rgba_float=pack_bgra};
    return f==PIPE_FORMAT_B8G8R8A8_UNORM ? &bgra : &rgba;
}

static struct opengpu_resource resource(struct pipe_opengpu_resource *g,
        enum pipe_texture_target target, unsigned width, unsigned height, enum pipe_format fmt)
{
    struct opengpu_resource r={0}; r.gpu=g; r.base.target=target;
    r.base.width0=width; r.base.height0=height; r.base.depth0=1; r.base.array_size=1;
    r.base.format=fmt; return r;
}

static uint32_t word(const uint8_t *p)
{ uint32_t v; memcpy(&v,p,4); return v; }
static void put_word(uint8_t *p, uint32_t v)
{ memcpy(p,&v,4); }

static void depth_stencil_clears(struct opengpu_context *c)
{
    struct pipe_context *p=&c->base;
    _Alignas(4) uint8_t data[128], expected[128], colors[128], color_expected[128];
    struct pipe_opengpu_resource zg={data,sizeof(data),32}, cg={colors,sizeof(colors),32};
    struct opengpu_resource zr=resource(&zg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_Z24_UNORM_S8_UINT);
    struct opengpu_resource cr=resource(&cg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_R8G8B8A8_UNORM);
    struct pipe_surface zs={.texture=&zr.base,.format=PIPE_FORMAT_Z24_UNORM_S8_UINT,.width=5,.height=4};
    struct pipe_surface cs={.texture=&cr.base,.format=PIPE_FORMAT_R8G8B8A8_UNORM,.width=5,.height=4};
    union pipe_color_union color={.f={1.0f,0.0f,0.5f,1.0f}};
    struct pipe_scissor_state scissor={.minx=1,.miny=1,.maxx=4,.maxy=3};
    unsigned n=invalidations;

    for (unsigned i=0;i<sizeof(data);i+=4) put_word(data+i,0x81000000u+i+3);
    memcpy(expected,data,sizeof(data));
    /* Read-modify-write depth only; leave stencil, padding and other pixels. */
    for (unsigned y=1;y<3;y++) for (unsigned x=1;x<4;x++) {
        unsigned o=y*32+x*4;
        put_word(expected+o,(word(expected+o)&0xff000000u)|0x800000u);
    }
    clear_depth_stencil(p,&zs,PIPE_CLEAR_DEPTH,0.5,0x55,1,1,3,2,false);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==++n && fills==0);
    /* Stencil only must preserve each depth value, including a truncated input. */
    for (unsigned y=0;y<2;y++) for (unsigned x=2;x<5;x++) {
        unsigned o=y*32+x*4;
        put_word(expected+o,(word(expected+o)&0x00ffffffu)|0x9a000000u);
    }
    clear_depth_stencil(p,&zs,PIPE_CLEAR_STENCIL,0.0,0x19a,2,0,3,2,false);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==++n && fills==0);
    /* Both components in a rectangle, followed by an empty clear. */
    put_word(expected+3*32+4,0x34000000u|0xffffffu);
    clear_depth_stencil(p,&zs,PIPE_CLEAR_DEPTHSTENCIL,1.0,0x34,1,3,1,1,false);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==++n && fills==0);
    clear_depth_stencil(p,&zs,0,0.0,0,0,0,5,4,false);
    clear_depth_stencil(p,&zs,PIPE_CLEAR_DEPTH,0.0,0,0,0,0,4,false);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==n);
    /* A depth-only view over Z24S8 storage preserves the stencil byte. */
    zs.format=PIPE_FORMAT_Z24X8_UNORM;
    for (unsigned y=0;y<4;y++) for (unsigned x=0;x<5;x++) {
        unsigned o=y*32+x*4;
        put_word(expected+o,word(expected+o)&0xff000000u);
    }
    clear_depth_stencil(p,&zs,PIPE_CLEAR_DEPTHSTENCIL,0.0,0,0,0,5,4,false);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==++n && fills==0);
    clear_depth_stencil(p,&zs,PIPE_CLEAR_STENCIL,0.0,1,0,0,5,4,false);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==n);
    zs.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;

    /* The framebuffer clear honors its scissor for both attachments. */
    memset(colors,0x5a,sizeof(colors)); memcpy(color_expected,colors,sizeof(colors));
    for (unsigned y=1;y<3;y++) for (unsigned x=1;x<4;x++) {
        unsigned o=y*32+x*4; const uint8_t rgba[4]={255,0,128,255};
        memcpy(color_expected+o,rgba,4);
        put_word(expected+o,0x56000000u|0xffffffu);
    }
    c->width=5; c->height=4; c->zsbuf=&zs; c->cbuf=&cs;
    clear(p,PIPE_CLEAR_COLOR0|PIPE_CLEAR_DEPTHSTENCIL,&scissor,&color,1.0,0x56);
    n+=2;
    assert(!memcmp(colors,color_expected,sizeof(colors)) && !memcmp(data,expected,sizeof(data)));
    assert(invalidations==n && fills==0 && color_clears==0);
    scissor.minx=5; scissor.maxx=7;
    clear(p,PIPE_CLEAR_COLOR0|PIPE_CLEAR_DEPTHSTENCIL,&scissor,&color,0.0,0);
    assert(!memcmp(colors,color_expected,sizeof(colors)) && !memcmp(data,expected,sizeof(data)));
    assert(invalidations==n && fills==0 && color_clears==0);

    /* Full Z24S8 clears retain the GPU fill fast path. */
    clear_depth_stencil(p,&zs,PIPE_CLEAR_DEPTHSTENCIL,1.0,0xab,0,0,5,4,false);
    for (unsigned i=0;i<sizeof(data);i+=4) assert(word(data+i)==0xabffffffu);
    assert(fills==1 && invalidations==n);
    memcpy(expected,data,sizeof(data));
    for (unsigned y=0;y<4;y++) for (unsigned x=0;x<5;x++)
        put_word(expected+y*32+x*4,0xab000000u);
    clear(p,PIPE_CLEAR_DEPTH,NULL,NULL,0.0,0);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==++n && fills==1);
    for (unsigned y=0;y<4;y++) for (unsigned x=0;x<5;x++)
        put_word(expected+y*32+x*4,0x12000000u);
    clear(p,PIPE_CLEAR_STENCIL,NULL,NULL,1.0,0x12);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==++n && fills==1);
    /* Depth-only storage ignores a stencil clear and uses fill for full depth. */
    zr.base.format=zs.format=PIPE_FORMAT_Z24X8_UNORM;
    clear_depth_stencil(p,&zs,PIPE_CLEAR_STENCIL,0.0,0,0,0,5,4,false);
    assert(!memcmp(data,expected,sizeof(data)) && invalidations==n && fills==1);
    clear_depth_stencil(p,&zs,PIPE_CLEAR_DEPTH,1.0,0,0,0,5,4,false);
    for (unsigned i=0;i<sizeof(data);i+=4) assert(word(data+i)==0x00ffffffu);
    assert(fills==2 && invalidations==n);

    /* A reinterpretation view must use its own colour byte order. */
    cs.format=PIPE_FORMAT_B8G8R8A8_UNORM;
    for (unsigned y=0;y<4;y++) for (unsigned x=0;x<5;x++) {
        const uint8_t bgra[4]={128,0,255,255};
        memcpy(color_expected+y*32+x*4,bgra,4);
    }
    clear(p,PIPE_CLEAR_COLOR0,NULL,&color,0.0,0);
    assert(!memcmp(colors,color_expected,sizeof(colors)) && invalidations==++n && color_clears==0);
    cs.format=cr.base.format;
    clear(p,PIPE_CLEAR_COLOR0,NULL,&color,0.0,0);
    assert(invalidations==n && color_clears==1);

    /* A complete 2x2 image is only 16 logical bytes. Use the mapped clear
     * and preserve the rest of its 64-byte allocation. */
    {
        uint8_t small[64], expected_small[64];
        struct pipe_opengpu_resource sg={small,sizeof(small),8};
        struct opengpu_resource sr=resource(&sg,PIPE_TEXTURE_2D,2,2,PIPE_FORMAT_R8G8B8A8_UNORM);
        struct pipe_surface ss={.texture=&sr.base,.format=PIPE_FORMAT_R8G8B8A8_UNORM,.width=2,.height=2};
        memset(small,0x5a,sizeof(small)); memcpy(expected_small,small,sizeof(small));
        for (unsigned i=0;i<16;i+=4) {
            const uint8_t rgba[4]={255,0,128,255};
            memcpy(expected_small+i,rgba,4);
        }
        c->width=2; c->height=2; c->cbuf=&ss;
        clear(p,PIPE_CLEAR_COLOR0,NULL,&color,0.0,0);
        assert(!memcmp(small,expected_small,sizeof(small)));
        assert(invalidations==++n && color_clears==1);
    }
    c->zsbuf=NULL; c->cbuf=NULL;
}

static void framebuffer_depth_binding(struct opengpu_context *c)
{
    uint8_t color[128], depth[128];
    struct pipe_opengpu_resource cg={color,sizeof(color),20}, zg={depth,sizeof(depth),0};
    struct opengpu_resource cr=resource(&cg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_R8G8B8A8_UNORM);
    struct opengpu_resource zr=resource(&zg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_Z24_UNORM_S8_UINT);
    struct pipe_surface cs={.texture=&cr.base,.reference={.count=1},.width=5,.height=4};
    struct pipe_surface zs={.texture=&zr.base,.reference={.count=1},.width=5,.height=4};
    struct pipe_framebuffer_state fb={.width=5,.height=4,.nr_cbufs=1,.cbufs={&cs},.zsbuf=&zs};
    struct depth_staging stage;
    unsigned n;

    c->rast.multisample=1;
    set_framebuffer(&c->base,&fb);
    assert(bound_depth==&zg && depth_load==1 && bound_sample_mode==0);
    assert(depth_layout_supported(c));
    n=invalidations;
    assert(!prepare_depth_staging(c,&stage) && !stage.temporary && invalidations==n);
    /* A mismatched depth pitch is staged at the kernel's shared stride. */
    cg.pitch=32;
    assert(!depth_layout_supported(c));
    for (unsigned i=0;i<sizeof(depth);i++) depth[i]=(uint8_t)(i+11);
    uint8_t expected[128]; memcpy(expected,depth,sizeof(depth));
    assert(!prepare_depth_staging(c,&stage));
    assert(stage.temporary && bound_depth==stage.temporary && depth_load==1);
    assert(invalidations==n+2 && stage.render_pitch==32 && stage.original_pitch==20);
    for (unsigned y=0;y<4;y++) {
        assert(!memcmp(stage.temporary->data+y*32,depth+y*20,20));
        memset(stage.temporary->data+y*32,0x40+y,20);
        memset(expected+y*20,0x40+y,20);
    }
    assert(!finish_depth_staging(c,&stage,true));
    assert(bound_depth==&zg && depth_load==1 && !stage.temporary);
    assert(!memcmp(depth,expected,sizeof(depth)) && invalidations==n+4);
    assert(temporary_allocations==temporary_frees);

    /* A failed preparation or failed draw cannot copy staged bytes back. */
    n=invalidations; fail_invalidation_at=n+2;
    assert(prepare_depth_staging(c,&stage)==-1 && errno==EIO);
    assert(bound_depth==&zg && !stage.temporary && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    fail_invalidation_at=0;
    assert(!prepare_depth_staging(c,&stage));
    memset(stage.temporary->data,0xee,stage.temporary->bytes);
    assert(!finish_depth_staging(c,&stage,false));
    assert(bound_depth==&zg && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    assert(!prepare_depth_staging(c,&stage));
    fail_invalidation_at=invalidations+1;
    assert(finish_depth_staging(c,&stage,true)==-1 && errno==EIO);
    assert(bound_depth==&zg && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    fail_invalidation_at=0;
    assert(!prepare_depth_staging(c,&stage));
    stage.temporary->data[0]=0x5b;
    fail_invalidation_at=invalidations+2; /* Fail after CPU copy-back. */
    assert(finish_depth_staging(c,&stage,true)==-1 && errno==EIO);
    expected[0]=0x5b;
    assert(bound_depth==&zg && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    fail_invalidation_at=0;

    /* Reverse the pitch mismatch and preserve rows/columns outside the FB. */
    cg.pitch=20; zg.pitch=32; c->width=3; c->height=2;
    for (unsigned i=0;i<sizeof(depth);i++) depth[i]=(uint8_t)(0xf0-i);
    memcpy(expected,depth,sizeof(depth));
    assert(!prepare_depth_staging(c,&stage));
    assert(stage.original_pitch==32 && stage.render_pitch==20 && stage.row_bytes==12);
    for (unsigned y=0;y<2;y++) {
        assert(!memcmp(stage.temporary->data+y*20,depth+y*32,12));
        memset(stage.temporary->data+y*20,0x71+y,12);
        memset(expected+y*32,0x71+y,12);
    }
    assert(!finish_depth_staging(c,&stage,true));
    assert(!memcmp(depth,expected,sizeof(depth)) && temporary_allocations==temporary_frees);
    c->width=5; c->height=4; cg.pitch=32;

    /* Exercise the same submission/wait/copy sequence used by draw_vbo. */
    struct drm_opengpu_vertex_draw draw={0};
    struct pipe_opengpu_fence *fence=NULL;
    zg.pitch=0;
    assert(!draw_vertex_with_depth(c,&draw,&fence,NULL,NULL));
    put_word(expected,0x87654321u);
    assert(fake_draws==1 && fence==(struct pipe_opengpu_fence *)2);
    assert(bound_depth==&zg && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    pipe_opengpu_fence_reference(&fence,NULL);
    fake_draw_submit_fail=1;
    assert(draw_vertex_with_depth(c,&draw,&fence,NULL,NULL)==-1 && errno==EIO);
    assert(bound_depth==&zg && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    fake_draw_submit_fail=0; fake_draw_wait_fail=1;
    assert(draw_vertex_with_depth(c,&draw,&fence,NULL,NULL)==-1 && errno==ETIMEDOUT);
    assert(bound_depth==&zg && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    fake_draw_wait_fail=0; pipe_opengpu_fence_reference(&fence,NULL);

    zg.pitch=32;
    assert(depth_layout_supported(c));
    zg.bytes=127;
    assert(!depth_layout_supported(c));
    zg.bytes=128; c->width=6;
    assert(!depth_layout_supported(c));
    c->width=5;
    fb.zsbuf=NULL;
    set_framebuffer(&c->base,&fb);
    assert(!bound_depth && !depth_load && depth_layout_supported(c));
    fb.nr_cbufs=0; fb.cbufs[0]=NULL;
    set_framebuffer(&c->base,&fb);
    assert(cs.reference.count==1 && zs.reference.count==1);
}

static void compare_and_stencil_encoding(void)
{
    /* PIPE_FUNC order is NEVER, LESS, EQUAL, LEQUAL, GREATER, NOTEQUAL,
     * GEQUAL, ALWAYS; these expected values are the RTL comparator ABI. */
    const unsigned compare[8]={7,0,4,1,2,5,3,6};
    /* Mesa's last three stencil ops differ from the RTL op order. */
    const unsigned op[8]={0,1,2,3,4,6,7,5};
    struct pipe_depth_stencil_alpha_state dsa={0};
    struct pipe_stencil_ref ref={{0x35,0}};
    uint32_t bits, config, reference, state;

    dsa.depth_enabled=1; dsa.depth_writemask=1;
    dsa.stencil[0].enabled=1; dsa.stencil[0].valuemask=0xa5;
    dsa.stencil[0].writemask=0x5a;
    for (unsigned i=0;i<8;i++) {
        dsa.depth_func=i; dsa.stencil[0].func=i;
        dsa.stencil[0].fail_op=i;
        dsa.stencil[0].zfail_op=(i+1)%8;
        dsa.stencil[0].zpass_op=(i+2)%8;
        assert(!hw_depth(&dsa,&bits));
        assert((bits & OPENGPU_DRAW_STATE_DEPTH_TEST) &&
               (bits & OPENGPU_DRAW_STATE_DEPTH_WRITE));
        assert(((bits & OPENGPU_DRAW_STATE_DEPTH_FUNC_MASK) >>
                 OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT)==compare[i]);
        assert(!hw_stencil(&dsa,&ref,&config,&reference,&state));
        assert(state==OPENGPU_DRAW_STATE_STENCIL_TEST);
        assert((config & OPENGPU_DRAW_STENCIL_FUNC_MASK)==compare[i]);
        assert(((config & OPENGPU_DRAW_STENCIL_FAIL_MASK) >>
                 OPENGPU_DRAW_STENCIL_FAIL_SHIFT)==op[i]);
        assert(((config & OPENGPU_DRAW_STENCIL_ZFAIL_MASK) >>
                 OPENGPU_DRAW_STENCIL_ZFAIL_SHIFT)==op[(i+1)%8]);
        assert(((config & OPENGPU_DRAW_STENCIL_ZPASS_MASK) >>
                 OPENGPU_DRAW_STENCIL_ZPASS_SHIFT)==op[(i+2)%8]);
        assert(reference==0x5aa535u);
    }
    dsa.depth_enabled=0; dsa.stencil[0].enabled=0;
    assert(!hw_depth(&dsa,&bits) && !bits);
    assert(!hw_stencil(&dsa,&ref,&config,&reference,&state));
    assert(!config && !reference && !state);
}

static void color_write_masks(struct opengpu_context *c)
{
    uint8_t colors[128], depth[128], expected[128];
    struct pipe_opengpu_resource cg={colors,sizeof(colors),32}, zg={depth,sizeof(depth),0};
    struct opengpu_resource cr=resource(&cg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_R8G8B8A8_UNORM);
    struct opengpu_resource zr=resource(&zg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_Z24_UNORM_S8_UINT);
    struct pipe_surface cs={.texture=&cr.base,.reference={.count=1},.width=5,.height=4};
    struct pipe_surface zs={.texture=&zr.base,.reference={.count=1},.width=5,.height=4};
    struct pipe_framebuffer_state fb={.width=5,.height=4,.nr_cbufs=1,.cbufs={&cs},.zsbuf=&zs};
    const enum pipe_format formats[]={PIPE_FORMAT_R8G8B8A8_UNORM,PIPE_FORMAT_B8G8R8A8_UNORM,
        PIPE_FORMAT_R8G8B8X8_UNORM,PIPE_FORMAT_B8G8R8X8_UNORM};
    struct color_mask_staging stage;
    uint32_t config;

    set_framebuffer(&c->base,&fb);
    for (unsigned f=0; f<4; f++) for (unsigned mask=0; mask<16; mask++) {
        cr.base.format=cs.format=formats[f];
        c->blend.rt[0].colormask=mask;
        for (unsigned i=0;i<sizeof(colors);i++) colors[i]=(uint8_t)(i+17);
        memcpy(expected,colors,sizeof(colors));
        assert(!prepare_color_mask_staging(c,&stage));
        for (unsigned y=0;y<4;y++) for (unsigned x=0;x<5;x++) {
            unsigned off=y*32+x*4;
            const unsigned channels[4]={f%2 ? PIPE_MASK_B : PIPE_MASK_R, PIPE_MASK_G,
                                        f%2 ? PIPE_MASK_R : PIPE_MASK_B, PIPE_MASK_A};
            for (unsigned ch=0;ch<4;ch++) {
                colors[off+ch]=(uint8_t)(0xa0+ch);
                if (mask & channels[ch]) expected[off+ch]=(uint8_t)(0xa0+ch);
            }
        }
        assert(!finish_color_mask_staging(c,&stage,true));
        assert(!memcmp(colors,expected,sizeof(colors)));
    }
    cr.base.format=cs.format=PIPE_FORMAT_R8G8B8A8_UNORM;
    c->blend.rt[0].colormask=PIPE_MASK_R;
    assert(color_mask_batch_capacity(&c->blend,12)==12);
    assert(!hw_blend(&c->blend,&config) && !config);
    c->blend.rt[0].blend_enable=1;
    c->blend.rt[0].rgb_func=c->blend.rt[0].alpha_func=PIPE_BLEND_ADD;
    c->blend.rt[0].rgb_src_factor=c->blend.rt[0].alpha_src_factor=PIPE_BLENDFACTOR_DST_ALPHA;
    c->blend.rt[0].rgb_dst_factor=c->blend.rt[0].alpha_dst_factor=PIPE_BLENDFACTOR_ONE;
    assert(!hw_blend(&c->blend,&config) && (config & OPENGPU_DRAW_BLEND_PRESENT));
    assert(color_mask_batch_capacity(&c->blend,12)==3);
    c->blend.rt[0].colormask=PIPE_MASK_RGBA;
    assert(color_mask_batch_capacity(&c->blend,12)==12);
    c->blend.rt[0].colormask=PIPE_MASK_R;

    /* The real submission sequence restores colour and staged depth. */
    memset(colors,0xab,sizeof(colors)); memset(depth,0x44,sizeof(depth));
    struct drm_opengpu_vertex_draw draw={0};
    struct pipe_opengpu_fence *fence=NULL;
    fake_color_write=1;
    assert(!draw_vertex_with_depth(c,&draw,&fence,NULL,NULL));
    assert(colors[0]==0x12 && colors[1]==0xab && colors[2]==0xab && colors[3]==0xab);
    assert(word(depth)==0x87654321u && bound_depth==&zg);
    fake_color_write=0;
    pipe_opengpu_fence_reference(&fence,NULL);

    /* Overlapping blended triangles must each see the original masked alpha. */
    colors[0]=0; colors[1]=64; colors[2]=191; colors[3]=128;
    fake_color_blend=1;
    assert(!draw_vertex_with_depth(c,&draw,&fence,NULL,NULL));
    pipe_opengpu_fence_reference(&fence,NULL);
    assert(!draw_vertex_with_depth(c,&draw,&fence,NULL,NULL));
    pipe_opengpu_fence_reference(&fence,NULL);
    assert(colors[0]==64 && colors[1]==64 && colors[2]==191 && colors[3]==128);
    fake_color_blend=0;

    /* Failures before and after copying are reported, and staging is freed. */
    assert(!prepare_color_mask_staging(c,&stage));
    colors[1]=0x33; fail_invalidation_at=invalidations+1;
    assert(finish_color_mask_staging(c,&stage,true)==-1 && errno==EIO && colors[1]==0x33);
    fail_invalidation_at=0;
    assert(!prepare_color_mask_staging(c,&stage));
    colors[1]=0x44; fail_invalidation_at=invalidations+2;
    assert(finish_color_mask_staging(c,&stage,true)==-1 && errno==EIO && colors[1]==0x33);
    fail_invalidation_at=0;

    /* Invalid layout or failed pre-draw publication must reject before submit. */
    unsigned draws=fake_draws;
    cg.bytes=63;
    assert(draw_vertex_with_depth(c,&draw,&fence,NULL,NULL)==-1 && fake_draws==draws);
    cg.bytes=128; fail_invalidation_at=invalidations+1;
    assert(draw_vertex_with_depth(c,&draw,&fence,NULL,NULL)==-1 && errno==EIO && fake_draws==draws);
    fail_invalidation_at=0;
    fake_draw_submit_fail=1;
    assert(draw_vertex_with_depth(c,&draw,&fence,NULL,NULL)==-1 && errno==EIO);
    fake_draw_submit_fail=0;
    c->blend.rt[0].blend_enable=0;
    c->blend.rt[0].colormask=PIPE_MASK_RGBA;
    fb.zsbuf=NULL; fb.nr_cbufs=0; fb.cbufs[0]=NULL;
    set_framebuffer(&c->base,&fb);
}
int main(void)
{
    struct opengpu_context c={0}; struct pipe_context *p=&c.base;
    uint8_t sb[256], db[256], expected[256];
    struct pipe_opengpu_resource sg={sb,sizeof(sb),32}, dg={db,sizeof(db),40};
    struct opengpu_resource s=resource(&sg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_R8G8B8A8_UNORM);
    struct opengpu_resource d=resource(&dg,PIPE_TEXTURE_2D,6,4,PIPE_FORMAT_R8G8B8A8_UNORM);
    struct pipe_box box={.x=1,.y=1,.z=0,.width=3,.height=2,.depth=1};
    struct pipe_surface surf={0}; union pipe_color_union color={.f={1.0f,0.0f,0.5f,1.0f}};
    p->buffer_map=buffer_map; p->texture_map=buffer_map;
    p->buffer_unmap=buffer_unmap; p->texture_unmap=buffer_unmap;
    c.gpu=(struct pipe_opengpu_context *)1; c.fence_timeout_ms=1000;
    c.blend.rt[0].colormask=PIPE_MASK_RGBA;
    for (unsigned i=0;i<sizeof(sb);i++) sb[i]=(uint8_t)i;
    memset(db,0xcc,sizeof(db)); memcpy(expected,db,sizeof(db));
    for (unsigned y=0;y<2;y++) memcpy(expected+(y+1)*40+2*4,sb+(y+1)*32+4,12);
    util_resource_copy_region(p,&d.base,0,2,1,0,&s.base,0,&box);
    assert(!memcmp(db,expected,sizeof(db)) && invalidations==1 && last_offset==0 && last_bytes==256);
    surf.texture=&d.base; surf.format=d.base.format; surf.width=6; surf.height=4;
    surf.u.tex.first_layer=surf.u.tex.last_layer=0; surf.u.tex.level=0;
    for (unsigned y=1;y<3;y++) for(unsigned x=1;x<3;x++) {
        uint8_t rgba[4]={255,0,128,255}; memcpy(expected+y*40+x*4,rgba,4);
    }
    clear_render_target(p,&surf,&color,1,1,2,2,false);
    assert(!memcmp(db,expected,sizeof(db)) && invalidations==2);
    surf.format=PIPE_FORMAT_B8G8R8A8_UNORM;
    { uint8_t bgra[4]={128,0,255,255}; memcpy(expected+3*40+5*4,bgra,4); }
    clear_render_target(p,&surf,&color,5,3,1,1,false);
    assert(!memcmp(db,expected,sizeof(db)) && invalidations==3);
    {
        uint8_t b[128], other[128]; struct pipe_opengpu_resource bg={b,sizeof(b),0};
        struct pipe_opengpu_resource og={other,sizeof(other),0};
        struct opengpu_resource br=resource(&bg,PIPE_BUFFER,100,1,PIPE_FORMAT_NONE);
        struct opengpu_resource or=resource(&og,PIPE_BUFFER,100,1,PIPE_FORMAT_NONE);
        const uint8_t pat[3]={0x12,0x34,0x56}; memset(b,0xa5,sizeof(b));
        u_default_clear_buffer(p,&br.base,5,11,pat,3);
        for (unsigned i=0;i<sizeof(b);i++)
            assert(b[i] == (i>=5 && i<16 ? pat[(i-5)%3] : 0xa5));
        assert(invalidations==4 && last_offset==0 && last_bytes==64);
        memset(other,0x7b,sizeof(other));
        box=(struct pipe_box){.x=5,.y=0,.z=0,.width=11,.height=1,.depth=1};
        util_resource_copy_region(p,&or.base,0,67,0,0,&br.base,0,&box);
        for (unsigned i=0;i<sizeof(other);i++)
            assert(other[i] == (i>=67 && i<78 ? pat[(i-67)%3] : 0x7b));
        assert(invalidations==5 && last_offset==64 && last_bytes==64);
        box=(struct pipe_box){.x=98,.y=0,.z=0,.width=3,.height=1,.depth=1};
        struct pipe_transfer *transfer=(void *)1;
        assert(!buffer_map(p,&br.base,0,PIPE_MAP_READ,&box,&transfer) && !transfer);
    }
    {
        struct pipe_transfer *transfer=NULL;
        struct pipe_opengpu_resource zg={sb,sizeof(sb),0};
        struct opengpu_resource zr=resource(&zg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_Z24_UNORM_S8_UINT);
        box=(struct pipe_box){.x=2,.y=2,.z=0,.width=2,.height=1,.depth=1};
        assert(buffer_map(p,&zr.base,0,PIPE_MAP_READ,&box,&transfer)==sb+48);
        assert(transfer->stride==20); buffer_unmap(p,transfer);
    }
    assert(!create_query(p,PIPE_QUERY_OCCLUSION_COUNTER,0));
    assert(!create_query(p,PIPE_QUERY_PRIMITIVES_GENERATED,0));
    depth_stencil_clears(&c);
    framebuffer_depth_binding(&c);
    compare_and_stencil_encoding();
    color_write_masks(&c);
    puts("Gallium transfer and unsupported-query fixture: PASS");
    return 0;
}
