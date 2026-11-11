/* Exercise OpenGPU's actual map callbacks with Mesa's copy/clear helpers.
 * GEM and cache publication are mocked; no GPU execution is claimed. */
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "../gallium/opengpu_context.c"
#include "util/format/u_format.h"

struct pipe_opengpu_resource { uint8_t *data; uint64_t bytes; uint32_t pitch; };
static unsigned invalidations;
static uint64_t last_offset, last_bytes;
struct opengpu_resource *opengpu_resource(struct pipe_resource *r) { return (void *)r; }
void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *r) { return r->data; }
uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *r) { return r->bytes; }
uint32_t pipe_opengpu_resource_pitch(const struct pipe_opengpu_resource *r) { return r->pitch; }
int pipe_opengpu_invalidate(struct pipe_opengpu_context *c, struct pipe_opengpu_resource *r,
                            uint64_t off, uint64_t bytes, struct pipe_opengpu_fence **f)
{
    (void)c; (void)r; ++invalidations; last_offset=off; last_bytes=bytes;
    *f=(struct pipe_opengpu_fence *)1; return 0;
}
int pipe_opengpu_fence_finish(struct pipe_opengpu_context *c, struct pipe_opengpu_fence *f, int64_t t)
{ (void)c; (void)f; (void)t; return 0; }
void pipe_opengpu_fence_reference(struct pipe_opengpu_fence **dst, struct pipe_opengpu_fence *src)
{ *dst=src; }

static const struct util_format_description byte_fmt = { .block={1,1,1,8} };
static const struct util_format_description rgba_fmt = { .block={1,1,1,32} };
const struct util_format_description *util_format_description(enum pipe_format f)
{ return f == PIPE_FORMAT_NONE ? &byte_fmt : &rgba_fmt; }
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
    puts("Gallium transfer and unsupported-query fixture: PASS");
    return 0;
}
