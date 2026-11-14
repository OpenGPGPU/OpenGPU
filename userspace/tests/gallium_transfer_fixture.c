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
static int fake_separate_blend;
static int fake_capture_blend;
static unsigned fake_fail_draw_at;
static struct drm_opengpu_vertex_draw captured_blend_draws[8];
static unsigned captured_blend_count;
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
int pipe_opengpu_screen_framebuffer_blit(const struct pipe_opengpu_screen *s)
{ (void)s; return 0; }
int pipe_opengpu_try_framebuffer_blit(struct pipe_opengpu_screen *s,
    struct pipe_opengpu_resource *d,struct pipe_opengpu_resource *r,
    const struct opengpu_framebuffer_blit *b,uint32_t t)
{ (void)s;(void)d;(void)r;(void)b;(void)t;assert(!"disabled GPU blit was called");return -1; }
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
    if (fake_draw_submit_fail || (fake_fail_draw_at && fake_draws == fake_fail_draw_at)) {
        errno=EIO; return -1;
    }
    assert(bound_depth && bound_depth->bytes >= 64);
    if (fake_separate_blend || fake_capture_blend) {
        assert(captured_blend_count < 8);
        captured_blend_draws[captured_blend_count++]=*draw;
    }
    if (fake_separate_blend) {
        const uint8_t source[4]={64,96,128,128};
        unsigned src=(draw->blend_config >> OPENGPU_DRAW_BLEND_SRC_SHIFT) & 15u;
        unsigned dst=(draw->blend_config >> OPENGPU_DRAW_BLEND_DST_SHIFT) & 15u;
        uint8_t old[4];
        assert(bound_color);
        memcpy(old,bound_color->data,sizeof(old));
        assert(((draw->blend_config >> OPENGPU_DRAW_BLEND_EQ_SHIFT) & 7u)==0);
        for (unsigned ch=0;ch<4;ch++) {
            unsigned result;
            if (!(draw->blend_config & OPENGPU_DRAW_BLEND_PRESENT))
                result=source[ch];
            else if (src==8 && dst==1)
                result=((unsigned)source[ch]*old[3]+127u)/255u + old[ch];
            else {
                assert(src==1 && dst==0);
                result=source[ch];
            }
            bound_color->data[ch]=(uint8_t)(result > 255 ? 255 : result);
        }
        if (draw->state & OPENGPU_DRAW_STATE_DEPTH_WRITE)
            bound_depth->data[0]=0x22;
        if (((draw->stencil_ref >> OPENGPU_DRAW_STENCIL_WMASK_SHIFT) & 0xffu) &&
            ((draw->stencil_config >> OPENGPU_DRAW_STENCIL_ZPASS_SHIFT) & 7u))
            ++bound_depth->data[3];
    } else {
        /* The fake GPU writes one packed depth/stencil word in the bound plane. */
        const uint32_t value=0x87654321u;
        memcpy(bound_depth->data,&value,sizeof(value));
    }
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
    r.base.format=fmt; r.base.reference.count=1; return r;
}

static unsigned mapped_resources_destroyed;

static void mapped_resource_destroy(struct pipe_screen *screen,
                                    struct pipe_resource *resource)
{
    (void)screen;
    ++mapped_resources_destroyed;
    free(resource);
}

static void transfer_lifetime(struct pipe_context *pipe,
                              enum pipe_texture_target target,
                              bool fail_publication)
{
    uint8_t data[128] = {0};
    struct pipe_opengpu_resource gpu = {data, sizeof(data), 0};
    struct pipe_screen screen = {.resource_destroy = mapped_resource_destroy};
    struct opengpu_resource *res = calloc(1, sizeof(*res));
    struct pipe_resource *owner;
    struct pipe_transfer *read = NULL, *write = NULL, *failed = (void *)1;
    struct pipe_box box = {.x=0, .y=0, .z=0, .width=2, .height=1, .depth=1};
    unsigned destroyed = mapped_resources_destroyed;
    unsigned published = invalidations;

    assert(res);
    *res = resource(&gpu, target, target == PIPE_BUFFER ? 64 : 4,
                    target == PIPE_BUFFER ? 1 : 4, PIPE_FORMAT_R8G8B8A8_UNORM);
    res->base.screen = &screen;
    owner = &res->base;

    box.x = -1;
    assert(!buffer_map(pipe, owner, 0, PIPE_MAP_READ, &box, &failed));
    assert(!failed && owner->reference.count == 1);
    box.x = 0;
    assert(buffer_map(pipe, owner, 0, PIPE_MAP_READ, &box, &read) == data);
    assert(buffer_map(pipe, owner, 0, PIPE_MAP_WRITE, &box, &write) == data);
    assert(owner->reference.count == 3);

    /* Each live transfer retains the resource even after the caller drops it. */
    pipe_resource_reference(&owner, NULL);
    assert(!owner && res->base.reference.count == 2);
    buffer_unmap(pipe, read);
    assert(res->base.reference.count == 1 && mapped_resources_destroyed == destroyed);
    if (fail_publication)
        fail_invalidation_at = published + 1;
    errno = 0;
    buffer_unmap(pipe, write);
    assert(mapped_resources_destroyed == destroyed + 1 && invalidations == published + 1);
    if (fail_publication)
        assert(errno == EIO);
    fail_invalidation_at = 0;
}

static void partial_cache_line_mapping(struct pipe_context *pipe)
{
    uint8_t data[180] = {0};
    struct pipe_opengpu_resource gpu = {data, sizeof(data), 20};
    struct opengpu_resource res = resource(&gpu, PIPE_TEXTURE_2D, 4, 9,
                                           PIPE_FORMAT_R8G8B8A8_UNORM);
    struct pipe_transfer *transfer = NULL;
    struct pipe_box box = {.x=0, .y=4, .z=0, .width=4, .height=2, .depth=1};
    unsigned published = invalidations;
    uint8_t *map;

    /* A full cache line inside the imported allocation remains publishable. */
    map = buffer_map(pipe, &res.base, 0, PIPE_MAP_WRITE, &box, &transfer);
    assert(map == data+80);
    *map = 0x5a;
    buffer_unmap(pipe, transfer);
    assert(data[80] == 0x5a && invalidations == ++published &&
           last_offset == 64 && last_bytes == 64);

    /* Ending exactly on a cache-line boundary does not touch the partial tail. */
    box = (struct pipe_box){.x=1, .y=6, .z=0, .width=1, .height=1, .depth=1};
    assert(buffer_map(pipe, &res.base, 0, PIPE_MAP_WRITE, &box, &transfer) == data+124);
    buffer_unmap(pipe, transfer);
    assert(invalidations == ++published && last_offset == 64 && last_bytes == 64);

    fail_invalidation_at = published + 1;
    assert(buffer_map(pipe, &res.base, 0, PIPE_MAP_WRITE, &box, &transfer) == data+124);
    errno = 0;
    buffer_unmap(pipe, transfer);
    assert(errno == EIO && invalidations == ++published && res.base.reference.count == 1);
    fail_invalidation_at = 0;

    box.x = 2; /* Starting at 128 requires the incomplete next line. */
    transfer = (void *)1;
    errno = 0;
    assert(!buffer_map(pipe, &res.base, 0, PIPE_MAP_READ | PIPE_MAP_WRITE, &box, &transfer));
    assert(!transfer && errno == EINVAL && invalidations == published);

    /* Reads may access the tail, but a write must fail before CPU mutation
     * when the covering cache line would run past the imported allocation. */
    box = (struct pipe_box){.x=0, .y=8, .z=0, .width=4, .height=1, .depth=1};
    assert(buffer_map(pipe, &res.base, 0, PIPE_MAP_READ, &box, &transfer) == data+160);
    buffer_unmap(pipe, transfer);
    transfer = (void *)1;
    errno = 0;
    assert(!buffer_map(pipe, &res.base, 0, PIPE_MAP_WRITE, &box, &transfer));
    assert(!transfer && errno == EINVAL && invalidations == published);

    /* Apply the same allocation bound to any unaligned buffer backing. */
    res = resource(&gpu, PIPE_BUFFER, sizeof(data), 1, PIPE_FORMAT_NONE);
    box = (struct pipe_box){.x=65, .y=0, .z=0, .width=4, .height=1, .depth=1};
    assert(buffer_map(pipe, &res.base, 0, PIPE_MAP_WRITE, &box, &transfer) == data+65);
    buffer_unmap(pipe, transfer);
    assert(invalidations == ++published && last_offset == 64 && last_bytes == 64);
    box.x = sizeof(data)-1; box.width = 1;
    transfer = (void *)1;
    errno = 0;
    assert(!buffer_map(pipe, &res.base, 0, PIPE_MAP_WRITE, &box, &transfer));
    assert(!transfer && errno == EINVAL && invalidations == published);
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
    assert(!draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL));
    put_word(expected,0x87654321u);
    assert(fake_draws==1 && fence==(struct pipe_opengpu_fence *)2);
    assert(bound_depth==&zg && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    pipe_opengpu_fence_reference(&fence,NULL);
    fake_draw_submit_fail=1;
    assert(draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL)==-1 && errno==EIO);
    assert(bound_depth==&zg && !memcmp(depth,expected,sizeof(depth)));
    assert(temporary_allocations==temporary_frees);
    fake_draw_submit_fail=0; fake_draw_wait_fail=1;
    assert(draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL)==-1 && errno==ETIMEDOUT);
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
        assert(!prepare_color_mask_staging(c,&stage,c->blend.rt[0].colormask));
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
    assert(!hw_blend(&c->blend, &config, false) && !config);
    c->blend.rt[0].blend_enable=1;
    c->blend.rt[0].rgb_func=c->blend.rt[0].alpha_func=PIPE_BLEND_ADD;
    c->blend.rt[0].rgb_src_factor=c->blend.rt[0].alpha_src_factor=PIPE_BLENDFACTOR_DST_ALPHA;
    c->blend.rt[0].rgb_dst_factor=c->blend.rt[0].alpha_dst_factor=PIPE_BLENDFACTOR_ONE;
    assert(!hw_blend(&c->blend, &config, false) && (config & OPENGPU_DRAW_BLEND_PRESENT));
    assert(color_mask_batch_capacity(&c->blend,12)==3);
    c->blend.rt[0].colormask=PIPE_MASK_RGBA;
    assert(color_mask_batch_capacity(&c->blend,12)==12);
    c->blend.rt[0].colormask=PIPE_MASK_R;

    /* The real submission sequence restores colour and staged depth. */
    memset(colors,0xab,sizeof(colors)); memset(depth,0x44,sizeof(depth));
    struct drm_opengpu_vertex_draw draw={0};
    struct pipe_opengpu_fence *fence=NULL;
    fake_color_write=1;
    assert(!draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL));
    assert(colors[0]==0x12 && colors[1]==0xab && colors[2]==0xab && colors[3]==0xab);
    assert(word(depth)==0x87654321u && bound_depth==&zg);
    fake_color_write=0;
    pipe_opengpu_fence_reference(&fence,NULL);

    /* Overlapping blended triangles must each see the original masked alpha. */
    colors[0]=0; colors[1]=64; colors[2]=191; colors[3]=128;
    fake_color_blend=1;
    assert(!draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL));
    pipe_opengpu_fence_reference(&fence,NULL);
    assert(!draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL));
    pipe_opengpu_fence_reference(&fence,NULL);
    assert(colors[0]==64 && colors[1]==64 && colors[2]==191 && colors[3]==128);
    fake_color_blend=0;

    /* Failures before and after copying are reported, and staging is freed. */
    assert(!prepare_color_mask_staging(c,&stage,c->blend.rt[0].colormask));
    colors[1]=0x33; fail_invalidation_at=invalidations+1;
    assert(finish_color_mask_staging(c,&stage,true)==-1 && errno==EIO && colors[1]==0x33);
    fail_invalidation_at=0;
    assert(!prepare_color_mask_staging(c,&stage,c->blend.rt[0].colormask));
    colors[1]=0x44; fail_invalidation_at=invalidations+2;
    assert(finish_color_mask_staging(c,&stage,true)==-1 && errno==EIO && colors[1]==0x33);
    fail_invalidation_at=0;

    /* Invalid layout or failed pre-draw publication must reject before submit. */
    unsigned draws=fake_draws;
    cg.bytes=63;
    assert(draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL)==-1 && fake_draws==draws);
    cg.bytes=128; fail_invalidation_at=invalidations+1;
    assert(draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL)==-1 && errno==EIO && fake_draws==draws);
    fail_invalidation_at=0;
    fake_draw_submit_fail=1;
    assert(draw_vertex_with_depth(c,&draw,&fence,c->blend.rt[0].colormask,NULL,NULL)==-1 && errno==EIO);
    fake_draw_submit_fail=0;
    c->blend.rt[0].blend_enable=0;
    c->blend.rt[0].colormask=PIPE_MASK_RGBA;
    fb.zsbuf=NULL; fb.nr_cbufs=0; fb.cbufs[0]=NULL;
    set_framebuffer(&c->base,&fb);
}

static unsigned blend_factor_reference(unsigned factor, unsigned sc, unsigned sa,
                                       unsigned dc, unsigned da, bool alpha)
{
    switch (factor) {
    case 0: return 0;
    case 1: return 255;
    case 2: return sc;
    case 3: return 255-sc;
    case 4: return sa;
    case 5: return 255-sa;
    case 6: return dc;
    case 7: return 255-dc;
    case 8: return da;
    case 9: return 255-da;
    case 10: return alpha ? 255 : (sa < 255-da ? sa : 255-da);
    default: abort();
    }
}

static uint8_t blend_component_reference(unsigned sc, unsigned dc, unsigned sa,
                                         unsigned da, unsigned sf, unsigned df,
                                         unsigned equation, bool alpha)
{
    unsigned s=(sc*blend_factor_reference(sf,sc,sa,dc,da,alpha)+127u)/255u;
    unsigned d=(dc*blend_factor_reference(df,sc,sa,dc,da,alpha)+127u)/255u;
    switch (equation) {
    case 0: return (uint8_t)(s+d > 255 ? 255 : s+d);
    case 1: return (uint8_t)(s > d ? s-d : 0);
    case 2: return (uint8_t)(d > s ? d-s : 0);
    case 3: return (uint8_t)(sc < dc ? sc : dc);
    case 4: return (uint8_t)(sc > dc ? sc : dc);
    default: abort();
    }
}

static void separate_blend_factor_matrix(void)
{
    static const unsigned factors[]={PIPE_BLENDFACTOR_ZERO,PIPE_BLENDFACTOR_ONE,
        PIPE_BLENDFACTOR_SRC_COLOR,PIPE_BLENDFACTOR_INV_SRC_COLOR,
        PIPE_BLENDFACTOR_SRC_ALPHA,PIPE_BLENDFACTOR_INV_SRC_ALPHA,
        PIPE_BLENDFACTOR_DST_COLOR,PIPE_BLENDFACTOR_INV_DST_COLOR,
        PIPE_BLENDFACTOR_DST_ALPHA,PIPE_BLENDFACTOR_INV_DST_ALPHA,
        PIPE_BLENDFACTOR_SRC_ALPHA_SATURATE};
    static const unsigned equations[]={PIPE_BLEND_ADD,PIPE_BLEND_SUBTRACT,
        PIPE_BLEND_REVERSE_SUBTRACT,PIPE_BLEND_MIN,PIPE_BLEND_MAX};
    static const unsigned masks[]={0,PIPE_MASK_R,PIPE_MASK_R|PIPE_MASK_G|PIPE_MASK_B,
        PIPE_MASK_A,PIPE_MASK_R|PIPE_MASK_A,PIPE_MASK_RGBA};
    const uint8_t source[4]={41,139,212,117}, initial[4]={194,77,58,149};
    struct pipe_blend_state blend={0};
    struct opengpu_blend_plan plan;
    unsigned checked=0;

    blend.rt[0].blend_enable=1;
    for (unsigned rs=0;rs<11;rs++) for (unsigned rd=0;rd<11;rd++)
    for (unsigned as=0;as<11;as++) for (unsigned ad=0;ad<11;ad++)
    for (unsigned re=0;re<5;re++) for (unsigned ae=0;ae<5;ae++)
    for (unsigned m=0;m<sizeof(masks)/sizeof(masks[0]);m++) {
        uint8_t actual[4], expected[4];
        unsigned mask=masks[m];
        blend.rt[0].rgb_src_factor=factors[rs]; blend.rt[0].rgb_dst_factor=factors[rd];
        blend.rt[0].alpha_src_factor=factors[as]; blend.rt[0].alpha_dst_factor=factors[ad];
        blend.rt[0].rgb_func=equations[re]; blend.rt[0].alpha_func=equations[ae];
        blend.rt[0].colormask=mask;
        assert(!make_blend_plan(&blend, &plan, false) && plan.count>=1 && plan.count<=2);
        memcpy(actual,initial,4); memcpy(expected,initial,4);
        for (unsigned ch=0;ch<4;ch++)
            if (mask & (1u<<ch))
                expected[ch]=blend_component_reference(source[ch],initial[ch],source[3],initial[3],
                    ch==3 ? as : rs,ch==3 ? ad : rd,ch==3 ? ae : re,ch==3);
        for (unsigned pass=0;pass<plan.count;pass++) {
            uint8_t old[4];
            unsigned cfg=plan.pass[pass].config;
            unsigned sf=(cfg>>OPENGPU_DRAW_BLEND_SRC_SHIFT)&15u;
            unsigned df=(cfg>>OPENGPU_DRAW_BLEND_DST_SHIFT)&15u;
            unsigned eq=(cfg>>OPENGPU_DRAW_BLEND_EQ_SHIFT)&7u;
            memcpy(old,actual,4);
            for (unsigned ch=0;ch<4;ch++) if (plan.pass[pass].color_mask & (1u<<ch))
                /* Model the older merger, which uses the RGB saturate factor
                 * even for alpha. The plan must correct that case. */
                actual[ch]=blend_component_reference(source[ch],old[ch],source[3],old[3],sf,df,eq,false);
        }
        if (memcmp(actual,expected,4)) {
            fprintf(stderr,"blend plan mismatch rs=%u rd=%u as=%u ad=%u re=%u ae=%u mask=%u\n",
                    rs,rd,as,ad,re,ae,mask);
            abort();
        }
        checked++;
    }
    assert(checked==2196150u);
}

static void minmax_constant_factors(void)
{
    const unsigned factors[] = {PIPE_BLENDFACTOR_ZERO, PIPE_BLENDFACTOR_ONE,
        PIPE_BLENDFACTOR_CONST_COLOR, PIPE_BLENDFACTOR_INV_CONST_COLOR,
        PIPE_BLENDFACTOR_CONST_ALPHA, PIPE_BLENDFACTOR_INV_CONST_ALPHA};
    struct pipe_blend_state blend = {0};
    struct opengpu_blend_plan plan;
    uint32_t config;
    const uint8_t source[4] = {193, 61, 127, 211};
    const uint8_t dest[4] = {37, 149, 225, 83};

    blend.rt[0].blend_enable = 1;
    blend.rt[0].colormask = PIPE_MASK_RGBA;
    for (unsigned eq = PIPE_BLEND_MIN; eq <= PIPE_BLEND_MAX; ++eq) {
        blend.rt[0].rgb_func = blend.rt[0].alpha_func = eq;
        for (unsigned s = 0; s < sizeof(factors)/sizeof(factors[0]); ++s)
            for (unsigned d = 0; d < sizeof(factors)/sizeof(factors[0]); ++d) {
                blend.rt[0].rgb_src_factor = blend.rt[0].alpha_src_factor = factors[s];
                blend.rt[0].rgb_dst_factor = blend.rt[0].alpha_dst_factor = factors[d];
                assert(!hw_blend(&blend, &config, false));
                for (unsigned ch = 0; ch < 4; ++ch) {
                    unsigned sf = (config >> OPENGPU_DRAW_BLEND_SRC_SHIFT) & 15u;
                    unsigned df = (config >> OPENGPU_DRAW_BLEND_DST_SHIFT) & 15u;
                    unsigned op = (config >> OPENGPU_DRAW_BLEND_EQ_SHIFT) & 7u;
                    unsigned expected = eq == PIPE_BLEND_MIN ?
                        (source[ch] < dest[ch] ? source[ch] : dest[ch]) :
                        (source[ch] > dest[ch] ? source[ch] : dest[ch]);
                    assert(blend_component_reference(source[ch], dest[ch], source[3],
                        dest[3], sf, df, op, ch == 3) == expected);
                }
            }
    }
    blend.rt[0].rgb_func = PIPE_BLEND_MIN;
    blend.rt[0].alpha_func = PIPE_BLEND_MAX;
    blend.rt[0].rgb_src_factor = PIPE_BLENDFACTOR_CONST_COLOR;
    blend.rt[0].rgb_dst_factor = PIPE_BLENDFACTOR_INV_CONST_COLOR;
    blend.rt[0].alpha_src_factor = PIPE_BLENDFACTOR_CONST_ALPHA;
    blend.rt[0].alpha_dst_factor = PIPE_BLENDFACTOR_INV_CONST_ALPHA;
    assert(!make_blend_plan(&blend, &plan, false) && plan.count == 2);
    assert(!plan.pass[0].write_depth_stencil && plan.pass[1].write_depth_stencil);
    blend.rt[0].rgb_func = PIPE_BLEND_ADD;
    assert(make_blend_plan(&blend, &plan, false) == -1);
    blend.rt[0].rgb_func = PIPE_BLEND_MIN;
    blend.rt[0].rgb_src_factor = 0x1f;
    assert(make_blend_plan(&blend, &plan, false) == -1);
    for (unsigned i = 2; i < sizeof(factors)/sizeof(factors[0]); ++i) {
        blend.rt[0].rgb_func = blend.rt[0].alpha_func = PIPE_BLEND_ADD;
        blend.rt[0].rgb_src_factor = blend.rt[0].alpha_src_factor = factors[i];
        blend.rt[0].rgb_dst_factor = blend.rt[0].alpha_dst_factor = PIPE_BLENDFACTOR_ONE;
        assert(hw_blend(&blend, &config, false) == -1);
        assert(!hw_blend(&blend, &config, true));
        assert(((config >> OPENGPU_DRAW_BLEND_SRC_SHIFT) & 15u) == i + 9);
        assert(!make_blend_plan(&blend, &plan, true) && plan.count == 1);
    }
}

static void separate_blending(struct opengpu_context *c)
{
    uint8_t colors[64]={32,64,96,64}, depth[64]={0};
    struct pipe_opengpu_resource cg={colors,sizeof(colors),16}, zg={depth,sizeof(depth),16};
    struct opengpu_resource cr=resource(&cg,PIPE_TEXTURE_2D,4,4,PIPE_FORMAT_R8G8B8A8_UNORM);
    struct opengpu_resource zr=resource(&zg,PIPE_TEXTURE_2D,4,4,PIPE_FORMAT_Z24_UNORM_S8_UINT);
    struct pipe_surface cs={.texture=&cr.base,.reference={.count=1},.width=4,.height=4,
                            .format=PIPE_FORMAT_R8G8B8A8_UNORM};
    struct pipe_surface zs={.texture=&zr.base,.reference={.count=1},.width=4,.height=4,
                            .format=PIPE_FORMAT_Z24_UNORM_S8_UINT};
    struct pipe_framebuffer_state fb={.width=4,.height=4,.nr_cbufs=1,.cbufs={&cs},.zsbuf=&zs};
    struct opengpu_blend_plan plan;
    struct drm_opengpu_vertex_draw draw={0};
    struct pipe_opengpu_fence *fence=NULL;
    const unsigned rgb=PIPE_MASK_R|PIPE_MASK_G|PIPE_MASK_B;
    const uint8_t first[4]={48,88,128,128}, second[4]={80,136,192,128};

    set_framebuffer(&c->base,&fb);
    memset(&c->blend,0,sizeof(c->blend));
    c->blend.rt[0].blend_enable=1;
    c->blend.rt[0].colormask=PIPE_MASK_RGBA;
    c->blend.rt[0].rgb_func=c->blend.rt[0].alpha_func=PIPE_BLEND_ADD;
    c->blend.rt[0].rgb_src_factor=PIPE_BLENDFACTOR_DST_ALPHA;
    c->blend.rt[0].rgb_dst_factor=PIPE_BLENDFACTOR_ONE;
    c->blend.rt[0].alpha_src_factor=PIPE_BLENDFACTOR_ONE;
    c->blend.rt[0].alpha_dst_factor=PIPE_BLENDFACTOR_ZERO;
    assert(!make_blend_plan(&c->blend, &plan, false) && plan.count==2);
    assert(plan.pass[0].color_mask==rgb && !plan.pass[0].write_depth_stencil);
    assert(plan.pass[1].color_mask==PIPE_MASK_A && plan.pass[1].write_depth_stencil);
    assert(blend_plan_batch_capacity(&c->blend,&plan,12)==3);
    draw.state=OPENGPU_DRAW_STATE_DEPTH_TEST | OPENGPU_DRAW_STATE_DEPTH_WRITE |
               OPENGPU_DRAW_STATE_STENCIL_TEST;
    draw.stencil_config=GPU_STENCIL_OP_INCR << OPENGPU_DRAW_STENCIL_ZPASS_SHIFT;
    draw.stencil_ref=0xffu << OPENGPU_DRAW_STENCIL_WMASK_SHIFT;
    depth[0]=0x11; depth[3]=5;
    captured_blend_count=0; fake_separate_blend=1;
    assert(!draw_vertex_with_blend_plan(c,&draw,&plan,&fence,NULL,NULL));
    pipe_opengpu_fence_reference(&fence,NULL);
    assert(!memcmp(colors,first,4) && depth[0]==0x22 && depth[3]==6);
    assert(captured_blend_count==2);
    assert(!(captured_blend_draws[0].state & OPENGPU_DRAW_STATE_DEPTH_WRITE));
    assert(!(captured_blend_draws[0].stencil_ref & (0xffu << OPENGPU_DRAW_STENCIL_WMASK_SHIFT)));
    assert(!(captured_blend_draws[0].stencil_config & (7u << OPENGPU_DRAW_STENCIL_ZPASS_SHIFT)));
    assert(captured_blend_draws[1].state & OPENGPU_DRAW_STATE_DEPTH_WRITE);
    assert(captured_blend_draws[1].stencil_ref==draw.stencil_ref &&
           captured_blend_draws[1].stencil_config==draw.stencil_config);

    /* The next overlapping primitive must see the first primitive's alpha. */
    assert(!draw_vertex_with_blend_plan(c,&draw,&plan,&fence,NULL,NULL));
    pipe_opengpu_fence_reference(&fence,NULL);
    assert(!memcmp(colors,second,4) && depth[3]==7 && captured_blend_count==4);

    c->blend.rt[0].colormask=rgb;
    assert(!make_blend_plan(&c->blend, &plan, false) && plan.count==1 && plan.pass[0].color_mask==rgb);
    c->blend.rt[0].colormask=PIPE_MASK_A;
    assert(!make_blend_plan(&c->blend, &plan, false) && plan.count==1 && plan.pass[0].color_mask==PIPE_MASK_A);
    c->blend.rt[0].colormask=PIPE_MASK_RGBA;
    c->blend.rt[0].alpha_src_factor=PIPE_BLENDFACTOR_CONST_COLOR;
    assert(make_blend_plan(&c->blend, &plan, false)==-1);
    c->blend.rt[0].alpha_src_factor=c->blend.rt[0].rgb_src_factor=PIPE_BLENDFACTOR_SRC_ALPHA_SATURATE;
    c->blend.rt[0].alpha_dst_factor=c->blend.rt[0].rgb_dst_factor=PIPE_BLENDFACTOR_ZERO;
    assert(!make_blend_plan(&c->blend, &plan, false) && plan.count==2);
    assert(((plan.pass[0].config >> OPENGPU_DRAW_BLEND_SRC_SHIFT)&15u)==10);
    assert(((plan.pass[1].config >> OPENGPU_DRAW_BLEND_SRC_SHIFT)&15u)==1);

    /* Stop after a failed pass and retain the caller's fence ownership. */
    c->blend.rt[0].rgb_src_factor=PIPE_BLENDFACTOR_DST_ALPHA;
    c->blend.rt[0].rgb_dst_factor=PIPE_BLENDFACTOR_ONE;
    c->blend.rt[0].alpha_src_factor=PIPE_BLENDFACTOR_ONE;
    assert(!make_blend_plan(&c->blend, &plan, false) && plan.count==2);
    fake_fail_draw_at=fake_draws+2;
    assert(draw_vertex_with_blend_plan(c,&draw,&plan,&fence,NULL,NULL)==-1 && errno==EIO);
    assert(fake_draws==fake_fail_draw_at);
    fake_fail_draw_at=0;

    /* Masking every color channel must not suppress depth/stencil updates,
     * even when blend factors would be unsupported for a color-writing draw. */
    {
        uint8_t saved[sizeof(colors)];
        memcpy(saved,colors,sizeof(saved));
        c->blend.rt[0].colormask=0;
        c->blend.rt[0].rgb_src_factor=c->blend.rt[0].alpha_src_factor=PIPE_BLENDFACTOR_CONST_COLOR;
        c->blend.rt[0].rgb_dst_factor=c->blend.rt[0].alpha_dst_factor=PIPE_BLENDFACTOR_INV_CONST_COLOR;
        assert(!make_blend_plan(&c->blend, &plan, false));
        assert(plan.count==1 && plan.pass[0].color_mask==0 && plan.pass[0].write_depth_stencil);
        c->blend.alpha_to_coverage=1;
        assert(make_blend_plan(&c->blend, &plan, false)==-1);
        c->blend.alpha_to_coverage=0;
        c->blend.rt[0].rgb_src_factor=0x1f;
        assert(make_blend_plan(&c->blend, &plan, false)==-1);
        c->blend.rt[0].rgb_src_factor=PIPE_BLENDFACTOR_CONST_COLOR;
        assert(!make_blend_plan(&c->blend, &plan, false));
        captured_blend_count=0; depth[0]=0x11; depth[3]=5;
        assert(!draw_vertex_with_blend_plan(c,&draw,&plan,&fence,NULL,NULL));
        assert(!memcmp(colors,saved,sizeof(saved)) && depth[0]==0x22 && depth[3]==6);
        assert(captured_blend_count==1);
    }
    fake_separate_blend=0;
    pipe_opengpu_fence_reference(&fence,NULL);
    /* Only the pass using a constant factor carries the packed color. */
    {
        const struct pipe_blend_color constant = {{0.5f, 0.25f, 1.0f, 0.0f}};
        memset(&c->blend, 0, sizeof(c->blend));
        c->blend.rt[0].blend_enable=1;
        c->blend.rt[0].colormask=PIPE_MASK_RGBA;
        c->blend.rt[0].rgb_func=c->blend.rt[0].alpha_func=PIPE_BLEND_ADD;
        c->blend.rt[0].rgb_src_factor=PIPE_BLENDFACTOR_CONST_COLOR;
        c->blend.rt[0].rgb_dst_factor=PIPE_BLENDFACTOR_ONE;
        c->blend.rt[0].alpha_src_factor=PIPE_BLENDFACTOR_ONE;
        c->blend.rt[0].alpha_dst_factor=PIPE_BLENDFACTOR_ZERO;
        assert(make_blend_plan(&c->blend, &plan, false)==-1);
        assert(!make_blend_plan(&c->blend, &plan, true) && plan.count==2);
        set_blend_color(&c->base, &constant);
        fake_capture_blend=1; captured_blend_count=0;
        assert(!draw_vertex_with_blend_plan(c,&draw,&plan,&fence,NULL,NULL));
        assert(captured_blend_count==2);
        assert(captured_blend_draws[0].reserved0[4]==0x8040ff00u);
        assert(captured_blend_draws[1].reserved0[4]==0);
        fake_capture_blend=0;
        pipe_opengpu_fence_reference(&fence,NULL);
    }
    memset(&c->blend,0,sizeof(c->blend));
    c->blend.rt[0].colormask=PIPE_MASK_RGBA;
    fb.zsbuf=NULL; fb.nr_cbufs=0; fb.cbufs[0]=NULL;
    set_framebuffer(&c->base,&fb);
}

static void color_blits(struct pipe_context *pipe)
{
    static const enum pipe_format formats[]={
        PIPE_FORMAT_R8G8B8A8_UNORM, PIPE_FORMAT_B8G8R8A8_UNORM,
        PIPE_FORMAT_R8G8B8X8_UNORM, PIPE_FORMAT_B8G8R8X8_UNORM,
    };
    uint8_t src[128], dst[128], expected[128];
    struct pipe_opengpu_resource sg={src,sizeof(src),24}, dg={dst,sizeof(dst),24};
    struct opengpu_resource s=resource(&sg,PIPE_TEXTURE_2D,4,3,formats[0]);
    struct opengpu_resource d=resource(&dg,PIPE_TEXTURE_2D,4,3,formats[0]);
    struct pipe_blit_info b={0};

    b.src.resource=&s.base; b.dst.resource=&d.base;
    b.src.box=(struct pipe_box){.x=1,.y=0,.width=2,.height=2,.depth=1};
    b.dst.box=(struct pipe_box){.x=1,.y=1,.width=2,.height=2,.depth=1};
    b.filter=PIPE_TEX_FILTER_NEAREST;
    for (unsigned sf=0;sf<4;sf++) for (unsigned df=0;df<4;df++) {
        bool src_bgra=sf&1, dst_bgra=df&1, src_alpha=sf<2, dst_alpha=df<2;
        s.base.format=b.src.format=formats[sf]; d.base.format=b.dst.format=formats[df];
        memset(src,0x5a,sizeof(src));
        for (unsigned y=0;y<3;y++) for (unsigned x=0;x<4;x++) {
            uint8_t *p=src+y*24+x*4;
            uint8_t r=10+x+8*y, g=40+x+8*y, blue=70+x+8*y, a=100+x+8*y;
            p[src_bgra ? 2 : 0]=r; p[1]=g; p[src_bgra ? 0 : 2]=blue; p[3]=a;
        }
        for (unsigned mask=0;mask<16;mask++) for (unsigned flip=0;flip<4;flip++) {
            unsigned before=invalidations;
            memset(dst,0xa5,sizeof(dst)); memcpy(expected,dst,sizeof(dst));
            b.mask=mask;
            b.src.box.x=(flip&1) ? 3 : 1; b.src.box.width=(flip&1) ? -2 : 2;
            b.src.box.y=(flip&2) ? 2 : 0; b.src.box.height=(flip&2) ? -2 : 2;
            for (unsigned y=0;y<2;y++) for (unsigned x=0;x<2;x++) {
                unsigned ix=1+((flip&1) ? 1-x : x), iy=(flip&2) ? 1-y : y;
                uint8_t *p=expected+(y+1)*24+(x+1)*4;
                if (mask&PIPE_MASK_R) p[dst_bgra ? 2 : 0]=10+ix+8*iy;
                if (mask&PIPE_MASK_G) p[1]=40+ix+8*iy;
                if (mask&PIPE_MASK_B) p[dst_bgra ? 0 : 2]=70+ix+8*iy;
                if ((mask&PIPE_MASK_A) && dst_alpha) p[3]=src_alpha ? 100+ix+8*iy : 255;
            }
            assert(!blit_image(pipe,&b));
            assert(!memcmp(dst,expected,sizeof(dst)));
            assert(invalidations==before+(mask!=0));
        }
    }
    /* Aliased rectangles must read the original bytes, not earlier writes. */
    s.base.format=b.src.format=b.dst.format=PIPE_FORMAT_R8G8B8A8_UNORM;
    b.dst.resource=&s.base; b.src.box=(struct pipe_box){.x=0,.y=0,.width=3,.height=1,.depth=1};
    b.dst.box=(struct pipe_box){.x=1,.y=0,.width=3,.height=1,.depth=1}; b.mask=PIPE_MASK_RGBA;
    for (unsigned i=0;i<sizeof(src);i++) src[i]=(uint8_t)i;
    memcpy(expected,src,sizeof(src)); memcpy(expected+4,src,12);
    pipe->blit(pipe,&b);
    assert(!memcmp(src,expected,sizeof(src)));

    /* Reject unsupported work and discard rectangles outside the images
     * before touching the destination. */
    b.dst.resource=&d.base; d.base.format=b.dst.format=PIPE_FORMAT_R8G8B8A8_UNORM;
    memcpy(expected,dst,sizeof(dst));
    unsigned before=invalidations;
    b.src.box.width=INT_MIN;
    assert(!blit_image(pipe,&b));
    b.src.box.width=3; b.dst.box.x=INT_MAX;
    assert(!blit_image(pipe,&b));
    b.dst.box.x=1; b.alpha_blend=true; assert(blit_image(pipe,&b)==-1);
    assert(!memcmp(dst,expected,sizeof(dst)) && invalidations==before);

    /* A failed source map or an unpublishable imported destination tail
     * must not change any destination bytes. */
    b.alpha_blend=false;
    sg.data=NULL; assert(blit_image(pipe,&b)==-1); sg.data=src;
    b.src.box=(struct pipe_box){.x=0,.y=0,.width=1,.height=1,.depth=1};
    b.dst.box=(struct pipe_box){.x=2,.y=2,.width=1,.height=1,.depth=1};
    dg.pitch=28; dg.bytes=72;
    assert(blit_image(pipe,&b)==-1 && errno==EINVAL);
    assert(!memcmp(dst,expected,sizeof(dst)) && invalidations==before);
}

/* Independent pixel-centre reference for bounded nearest blits. */
static void scaled_color_reference(uint8_t *dst, const uint8_t *src,
                                   const struct pipe_blit_info *b)
{
    for (int y=0;y<5;y++) for (int x=0;x<6;x++) {
        double u, v;
        int ix, iy;
        if (x < b->dst.box.x || y < b->dst.box.y ||
            (int64_t)x >= (int64_t)b->dst.box.x+b->dst.box.width ||
            (int64_t)y >= (int64_t)b->dst.box.y+b->dst.box.height ||
            (b->scissor_enable && (x < b->scissor.minx || x >= b->scissor.maxx ||
                                   y < b->scissor.miny || y >= b->scissor.maxy)))
            continue;
        u=b->src.box.x+((x+0.5-b->dst.box.x)/b->dst.box.width)*b->src.box.width;
        v=b->src.box.y+((y+0.5-b->dst.box.y)/b->dst.box.height)*b->src.box.height;
        ix=(int)floor(u); iy=(int)floor(v);
        if (ix >= 0 && ix < 5 && iy >= 0 && iy < 4)
            for (unsigned c=0;c<4;c++)
                if (b->mask & (1u<<c)) dst[y*40+x*4+c]=src[iy*32+ix*4+c];
    }
}

static void scaled_color_blits(struct pipe_context *pipe)
{
    static const struct pipe_box sources[]={
        {.x=1,.y=1,.width=3,.height=2,.depth=1},
        {.x=4,.y=1,.width=-3,.height=2,.depth=1},
        {.x=-1,.y=0,.width=4,.height=4,.depth=1},
        {.x=4,.y=4,.width=-5,.height=-5,.depth=1},
        {.x=2,.y=0,.width=1,.height=4,.depth=1},
    };
    static const struct pipe_box destinations[]={
        {.x=1,.y=0,.width=3,.height=4,.depth=1},
        {.x=0,.y=1,.width=6,.height=3,.depth=1},
        {.x=-2,.y=-1,.width=7,.height=6,.depth=1},
        {.x=3,.y=2,.width=6,.height=4,.depth=1},
        {.x=1,.y=2,.width=1,.height=1,.depth=1},
    };
    uint8_t src[256], dst[256], expected[256], original[256];
    struct pipe_opengpu_resource sg={src,sizeof(src),32}, dg={dst,sizeof(dst),40};
    struct opengpu_resource s=resource(&sg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_R8G8B8A8_UNORM);
    struct opengpu_resource d=resource(&dg,PIPE_TEXTURE_2D,6,5,PIPE_FORMAT_R8G8B8A8_UNORM);
    struct pipe_blit_info b={0};

    b.src.resource=&s.base; b.dst.resource=&d.base;
    b.src.format=b.dst.format=PIPE_FORMAT_R8G8B8A8_UNORM;
    b.filter=PIPE_TEX_FILTER_NEAREST;
    for (unsigned i=0;i<sizeof(src);i++) src[i]=(uint8_t)(i*13u+7u);
    for (unsigned si=0;si<sizeof(sources)/sizeof(sources[0]);si++)
    for (unsigned di=0;di<sizeof(destinations)/sizeof(destinations[0]);di++)
    for (unsigned sc=0;sc<3;sc++) for (unsigned mask=0;mask<2;mask++) {
        memset(dst,0xa5,sizeof(dst)); memcpy(expected,dst,sizeof(dst));
        b.src.box=sources[si]; b.dst.box=destinations[di];
        b.scissor_enable=sc!=0;
        b.scissor=(struct pipe_scissor_state){.minx=1,.miny=1,.maxx=sc==2?1:5,.maxy=4};
        b.mask=mask ? PIPE_MASK_RGBA : PIPE_MASK_R|PIPE_MASK_B;
        scaled_color_reference(expected,src,&b);
        assert(!blit_image(pipe,&b));
        assert(!memcmp(dst,expected,sizeof(dst)));
    }
    /* Huge signed coordinates leave only a few pixels in either allocation.
     * The pixel-centre products must stay bounded and not overflow. */
    memset(dst,0xa5,sizeof(dst)); memcpy(expected,dst,sizeof(dst));
    b.src.box=(struct pipe_box){.x=INT_MAX,.y=0,.width=INT_MIN,.height=4,.depth=1};
    b.dst.box=(struct pipe_box){.x=INT_MIN+5,.y=0,.width=INT_MAX,.height=4,.depth=1};
    b.scissor_enable=false; b.mask=PIPE_MASK_RGBA;
    scaled_color_reference(expected,src,&b);
    assert(!blit_image(pipe,&b));
    assert(!memcmp(dst,expected,sizeof(dst)));
    /* Scale between overlapping views of the same backing allocation. */
    b.src.box=(struct pipe_box){.x=0,.y=0,.width=3,.height=3,.depth=1};
    b.dst.box=(struct pipe_box){.x=0,.y=0,.width=5,.height=4,.depth=1};
    b.dst.resource=&s.base; b.scissor_enable=false; b.mask=PIPE_MASK_RGBA;
    memcpy(original,src,sizeof(src)); memcpy(expected,src,sizeof(src));
    /* Reference uses a 40-byte destination pitch; repack its expected rows. */
    memset(dst,0,sizeof(dst));
    for (unsigned y=0;y<4;y++) memcpy(dst+y*40,src+y*32,20);
    scaled_color_reference(dst,original,&b);
    for (unsigned y=0;y<4;y++) memcpy(expected+y*32,dst+y*40,20);
    assert(!blit_image(pipe,&b));
    assert(!memcmp(src,expected,sizeof(src)));
}

static int clamp_int(int value, int lower, int upper)
{
    return value < lower ? lower : value >= upper ? upper-1 : value;
}

/* Reference uses direct double-precision pixel coordinates and four taps. */
static void linear_color_reference(uint8_t *dst, const uint8_t *src,
                                   const struct pipe_blit_info *b)
{
    int sx0=b->src.box.x, sx1=sx0+b->src.box.width;
    int sy0=b->src.box.y, sy1=sy0+b->src.box.height;
    bool sb=b->src.format==PIPE_FORMAT_B8G8R8A8_UNORM || b->src.format==PIPE_FORMAT_B8G8R8X8_UNORM;
    bool db=b->dst.format==PIPE_FORMAT_B8G8R8A8_UNORM || b->dst.format==PIPE_FORMAT_B8G8R8X8_UNORM;
    bool sa=b->src.format==PIPE_FORMAT_R8G8B8A8_UNORM || b->src.format==PIPE_FORMAT_B8G8R8A8_UNORM;
    bool da=b->dst.format==PIPE_FORMAT_R8G8B8A8_UNORM || b->dst.format==PIPE_FORMAT_B8G8R8A8_UNORM;
    if (sx0>sx1) { int t=sx0;sx0=sx1;sx1=t; }
    if (sy0>sy1) { int t=sy0;sy0=sy1;sy1=t; }
    sx0=clamp_int(sx0,0,6); sx1=clamp_int(sx1,0,6);
    sy0=clamp_int(sy0,0,5); sy1=clamp_int(sy1,0,5);
    if (sx0>=sx1 || sy0>=sy1) return;
    for (int y=0;y<5;y++) for (int x=0;x<6;x++) {
        double u,v,fx,fy;
        int ix,iy,x0,x1,y0,y1;
        if (x < b->dst.box.x || y < b->dst.box.y ||
            x >= b->dst.box.x+b->dst.box.width || y >= b->dst.box.y+b->dst.box.height ||
            (b->scissor_enable && (x < b->scissor.minx || x >= b->scissor.maxx ||
                                   y < b->scissor.miny || y >= b->scissor.maxy))) continue;
        u=b->src.box.x+((x+0.5-b->dst.box.x)/b->dst.box.width)*b->src.box.width;
        v=b->src.box.y+((y+0.5-b->dst.box.y)/b->dst.box.height)*b->src.box.height;
        ix=(int)floor(u); iy=(int)floor(v);
        if (ix<sx0 || ix>=sx1 || iy<sy0 || iy>=sy1) continue;
        x0=(int)floor(u-.5); y0=(int)floor(v-.5);
        fx=u-.5-x0; fy=v-.5-y0;
        x1=clamp_int(x0+1,sx0,sx1); y1=clamp_int(y0+1,sy0,sy1);
        x0=clamp_int(x0,sx0,sx1); y0=clamp_int(y0,sy0,sy1);
        for (unsigned ch=0;ch<4;ch++) {
            unsigned sch=ch==0 && sb ? 2 : ch==2 && sb ? 0 : ch;
            unsigned dch=ch==0 && db ? 2 : ch==2 && db ? 0 : ch;
            if (!(b->mask & (1u<<ch)) || (ch==3 && !da)) continue;
            double a=(ch==3 && !sa) ? 255 : src[y0*32+x0*4+sch];
            double c=(ch==3 && !sa) ? 255 : src[y1*32+x0*4+sch];
            double d=(ch==3 && !sa) ? 255 : src[y1*32+x1*4+sch];
            double e=(ch==3 && !sa) ? 255 : src[y0*32+x1*4+sch];
            dst[y*40+x*4+dch]=(uint8_t)(a*(1-fx)*(1-fy)+e*fx*(1-fy)+
                                         c*(1-fx)*fy+d*fx*fy+0.5);
        }
    }
}

static void linear_color_blits(struct pipe_context *pipe)
{
    static const enum pipe_format formats[]={PIPE_FORMAT_R8G8B8A8_UNORM,
        PIPE_FORMAT_B8G8R8A8_UNORM,PIPE_FORMAT_R8G8B8X8_UNORM,PIPE_FORMAT_B8G8R8X8_UNORM};
    static const struct pipe_box sources[]={
        {.x=0,.y=0,.width=5,.height=4,.depth=1},
        {.x=4,.y=4,.width=-4,.height=-3,.depth=1},
        {.x=-1,.y=0,.width=4,.height=4,.depth=1},
        {.x=1,.y=1,.width=2,.height=2,.depth=1},
    };
    static const struct pipe_box destinations[]={
        {.x=0,.y=0,.width=6,.height=5,.depth=1},
        {.x=1,.y=1,.width=3,.height=2,.depth=1},
        {.x=-2,.y=0,.width=7,.height=5,.depth=1},
        {.x=2,.y=2,.width=1,.height=1,.depth=1},
    };
    uint8_t src[256], dst[256], expected[256];
    struct pipe_opengpu_resource sg={src,sizeof(src),32}, dg={dst,sizeof(dst),40};
    struct opengpu_resource s=resource(&sg,PIPE_TEXTURE_2D,5,4,formats[0]);
    struct opengpu_resource d=resource(&dg,PIPE_TEXTURE_2D,6,5,formats[0]);
    struct pipe_blit_info b={0};
    b.src.resource=&s.base; b.dst.resource=&d.base; b.filter=PIPE_TEX_FILTER_LINEAR;
    for (unsigned i=0;i<sizeof(src);i++) src[i]=(uint8_t)(i*13u+7u);
    for (unsigned sf=0;sf<4;sf++) for (unsigned df=0;df<4;df++)
    for (unsigned si=0;si<4;si++) for (unsigned di=0;di<4;di++)
    for (unsigned sc=0;sc<2;sc++) for (unsigned mask=0;mask<2;mask++) {
        s.base.format=b.src.format=formats[sf]; d.base.format=b.dst.format=formats[df];
        b.src.box=sources[si]; b.dst.box=destinations[di];
        b.scissor_enable=sc; b.scissor=(struct pipe_scissor_state){.minx=1,.miny=1,.maxx=5,.maxy=4};
        b.mask=mask ? PIPE_MASK_RGBA : PIPE_MASK_R|PIPE_MASK_B;
        memset(dst,0xa5,sizeof(dst)); memcpy(expected,dst,sizeof(dst));
        linear_color_reference(expected,src,&b);
        assert(!blit_image(pipe,&b));
        for (unsigned i=0;i<sizeof(dst);i++) {
            int diff=(int)dst[i]-expected[i];
            assert(expected[i]==0xa5 ? diff==0 : diff>=-1 && diff<=1);
        }
    }
    /* Bounded images with extreme legal coordinates still use exact
     * quotient/remainder arithmetic for the sample location. */
    s.base.format=b.src.format=d.base.format=b.dst.format=PIPE_FORMAT_R8G8B8A8_UNORM;
    b.src.box=(struct pipe_box){.x=INT_MAX,.y=0,.width=INT_MIN,.height=4,.depth=1};
    b.dst.box=(struct pipe_box){.x=INT_MIN+5,.y=0,.width=INT_MAX,.height=4,.depth=1};
    b.scissor_enable=false; b.mask=PIPE_MASK_RGBA;
    memset(dst,0xa5,sizeof(dst)); memcpy(expected,dst,sizeof(dst));
    linear_color_reference(expected,src,&b);
    assert(!blit_image(pipe,&b));
    for (unsigned i=0;i<sizeof(dst);i++) {
        int diff=(int)dst[i]-expected[i];
        assert(expected[i]==0xa5 ? diff==0 : diff>=-1 && diff<=1);
    }

    /* A scaled overlapping copy reads the original source, including taps
     * which have already been overwritten in the destination. */
    uint8_t original[256], packed[256];
    b.src.box=(struct pipe_box){.x=0,.y=0,.width=3,.height=3,.depth=1};
    b.dst.box=(struct pipe_box){.x=0,.y=0,.width=5,.height=4,.depth=1};
    b.dst.resource=&s.base;
    memcpy(original,src,sizeof(src)); memcpy(expected,src,sizeof(src));
    memset(packed,0,sizeof(packed));
    for (unsigned y=0;y<4;y++) memcpy(packed+y*40,src+y*32,20);
    linear_color_reference(packed,original,&b);
    for (unsigned y=0;y<4;y++) memcpy(expected+y*32,packed+y*40,20);
    assert(!blit_image(pipe,&b));
    for (unsigned i=0;i<sizeof(src);i++) {
        int diff=(int)src[i]-expected[i];
        if (i/32>=4 || i%32>=20) assert(diff==0);
        else assert(diff>=-1 && diff<=1);
    }
}

/* Independent reference preserves the other packed aspect and row padding. */
static void depth_blit_reference(uint8_t *dst, unsigned dpitch, unsigned dw, unsigned dh,
                                 const uint8_t *src, unsigned spitch, unsigned sw, unsigned sh,
                                 const struct pipe_blit_info *b)
{
    uint32_t mask = ((b->mask & PIPE_MASK_Z) ? 0x00ffffffu : 0) |
                    ((b->mask & PIPE_MASK_S) ? 0xff000000u : 0);
    for (unsigned y=0;y<dh;y++) for (unsigned x=0;x<dw;x++) {
        double u, v;
        int ix, iy;
        if ((int64_t)x < b->dst.box.x || (int64_t)y < b->dst.box.y ||
            (int64_t)x >= (int64_t)b->dst.box.x+b->dst.box.width ||
            (int64_t)y >= (int64_t)b->dst.box.y+b->dst.box.height ||
            (b->scissor_enable && (x < b->scissor.minx || x >= b->scissor.maxx ||
                                   y < b->scissor.miny || y >= b->scissor.maxy)))
            continue;
        u=b->src.box.x+((x+0.5-b->dst.box.x)/b->dst.box.width)*b->src.box.width;
        v=b->src.box.y+((y+0.5-b->dst.box.y)/b->dst.box.height)*b->src.box.height;
        ix=(int)floor(u); iy=(int)floor(v);
        if (ix >= 0 && (unsigned)ix < sw && iy >= 0 && (unsigned)iy < sh) {
            uint8_t *d=dst+y*dpitch+x*4;
            const uint8_t *s=src+(unsigned)iy*spitch+(unsigned)ix*4;
            put_word(d,(word(d)&~mask)|(word(s)&mask));
        }
    }
}

static void depth_stencil_blits(struct pipe_context *pipe)
{
    static const struct pipe_box sources[]={
        {.x=0,.y=0,.width=5,.height=4,.depth=1},
        {.x=4,.y=4,.width=-4,.height=-3,.depth=1},
        {.x=-1,.y=1,.width=4,.height=3,.depth=1},
    };
    static const struct pipe_box destinations[]={
        {.x=0,.y=0,.width=5,.height=4,.depth=1},
        {.x=1,.y=1,.width=4,.height=3,.depth=1},
        {.x=-2,.y=0,.width=7,.height=5,.depth=1},
    };
    static const unsigned masks[]={0, PIPE_MASK_Z, PIPE_MASK_S, PIPE_MASK_ZS};
    uint8_t src[256], dst[256], expected[256], original[256];
    struct pipe_opengpu_resource sg={src,sizeof(src),32}, dg={dst,sizeof(dst),40};
    struct opengpu_resource s=resource(&sg,PIPE_TEXTURE_2D,5,4,PIPE_FORMAT_Z24_UNORM_S8_UINT);
    struct opengpu_resource d=resource(&dg,PIPE_TEXTURE_2D,6,5,PIPE_FORMAT_Z24_UNORM_S8_UINT);
    struct pipe_blit_info b={0};

    for (unsigned i=0;i<sizeof(src);i++) src[i]=(uint8_t)(i*13u+7u);
    b.src.resource=&s.base; b.dst.resource=&d.base;
    b.src.format=b.dst.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    b.filter=PIPE_TEX_FILTER_NEAREST;
    for (unsigned si=0;si<3;si++) for (unsigned di=0;di<3;di++)
    for (unsigned sc=0;sc<2;sc++) for (unsigned mi=0;mi<4;mi++) {
        unsigned before=invalidations;
        for (unsigned i=0;i<sizeof(dst);i++) dst[i]=(uint8_t)(i*3u+11u);
        memcpy(expected,dst,sizeof(dst));
        b.src.box=sources[si]; b.dst.box=destinations[di]; b.mask=masks[mi];
        b.scissor_enable=sc;
        b.scissor=(struct pipe_scissor_state){.minx=1,.miny=1,.maxx=5,.maxy=4};
        depth_blit_reference(expected,40,6,5,src,32,5,4,&b);
        assert(!blit_image(pipe,&b));
        assert(!memcmp(dst,expected,sizeof(dst)));
        assert(invalidations==before+(b.mask!=0));
    }

    /* A depth-only view aliases Z24S8 without changing its stencil byte. */
    b.scissor_enable=false; b.mask=PIPE_MASK_Z;
    b.src.format=PIPE_FORMAT_Z24X8_UNORM;
    b.dst.format=PIPE_FORMAT_Z24X8_UNORM;
    memcpy(expected,dst,sizeof(dst));
    depth_blit_reference(expected,40,6,5,src,32,5,4,&b);
    pipe->blit(pipe,&b);
    assert(!memcmp(dst,expected,sizeof(dst)));

    /* Source bytes must be snapshotted before an overlapping copy. */
    b.src.format=b.dst.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    b.dst.resource=&s.base; b.mask=PIPE_MASK_ZS;
    b.src.box=(struct pipe_box){.x=0,.y=0,.width=4,.height=3,.depth=1};
    b.dst.box=(struct pipe_box){.x=1,.y=1,.width=4,.height=3,.depth=1};
    memcpy(original,src,sizeof(src)); memcpy(expected,src,sizeof(src));
    depth_blit_reference(expected,32,5,4,original,32,5,4,&b);
    assert(!blit_image(pipe,&b));
    assert(!memcmp(src,expected,sizeof(src)));

    /* Both physical formats can exchange depth without copying X8 padding. */
    b.dst.resource=&d.base; b.mask=PIPE_MASK_Z;
    for (unsigned sf=0;sf<2;sf++) for (unsigned df=0;df<2;df++) {
        s.base.format=b.src.format=sf ? PIPE_FORMAT_Z24X8_UNORM : PIPE_FORMAT_Z24_UNORM_S8_UINT;
        d.base.format=b.dst.format=df ? PIPE_FORMAT_Z24X8_UNORM : PIPE_FORMAT_Z24_UNORM_S8_UINT;
        memcpy(expected,dst,sizeof(dst));
        depth_blit_reference(expected,40,6,5,src,32,5,4,&b);
        assert(!blit_image(pipe,&b));
        assert(!memcmp(dst,expected,sizeof(dst)));
    }

    /* An absent stencil aspect, mixed aspects and unsupported filters fail
     * before either allocation is modified. */
    s.base.format=b.src.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    d.base.format=b.dst.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    memcpy(expected,dst,sizeof(dst));
    unsigned before=invalidations;
    b.src.format=PIPE_FORMAT_Z24X8_UNORM; b.mask=PIPE_MASK_S;
    assert(blit_image(pipe,&b)==-1 && errno==EOPNOTSUPP);
    b.src.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    d.base.format=PIPE_FORMAT_Z24X8_UNORM;
    assert(blit_image(pipe,&b)==-1 && errno==EOPNOTSUPP);
    d.base.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    b.mask=PIPE_MASK_Z|PIPE_MASK_R;
    assert(blit_image(pipe,&b)==-1 && errno==EOPNOTSUPP);
    b.mask=PIPE_MASK_Z; d.base.format=PIPE_FORMAT_R8G8B8A8_UNORM;
    assert(blit_image(pipe,&b)==-1 && errno==EOPNOTSUPP);
    d.base.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    b.src.format=s.base.format=PIPE_FORMAT_R8G8B8A8_UNORM;
    b.mask=PIPE_MASK_RGBA;
    assert(blit_image(pipe,&b)==-1 && errno==EOPNOTSUPP);
    b.src.format=s.base.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    b.mask=PIPE_MASK_ZS; b.filter=PIPE_TEX_FILTER_LINEAR;
    assert(blit_image(pipe,&b)==-1 && errno==EOPNOTSUPP);
    assert(!memcmp(dst,expected,sizeof(dst)) && invalidations==before);
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
    p->blit=blit;
    c.gpu=(struct pipe_opengpu_context *)1; c.fence_timeout_ms=1000;
    c.blend.rt[0].colormask=PIPE_MASK_RGBA;
    for (unsigned i=0;i<sizeof(sb);i++) sb[i]=(uint8_t)i;
    memset(db,0xcc,sizeof(db)); memcpy(expected,db,sizeof(db));
    for (unsigned y=0;y<2;y++) memcpy(expected+(y+1)*40+2*4,sb+(y+1)*32+4,12);
    resource_copy_region(p,&d.base,0,2,1,0,&s.base,0,&box);
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
        resource_copy_region(p,&or.base,0,67,0,0,&br.base,0,&box);
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
    separate_blend_factor_matrix();
    minmax_constant_factors();
    separate_blending(&c);
    transfer_lifetime(p, PIPE_BUFFER, false);
    transfer_lifetime(p, PIPE_TEXTURE_2D, false);
    transfer_lifetime(p, PIPE_BUFFER, true);
    transfer_lifetime(p, PIPE_TEXTURE_2D, true);
    partial_cache_line_mapping(p);
    color_blits(p);
    scaled_color_blits(p);
    linear_color_blits(p);
    depth_stencil_blits(p);
    puts("Gallium transfers, image blits and unsupported queries: PASS");
    return 0;
}
