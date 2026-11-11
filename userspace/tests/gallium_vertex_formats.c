/* Actual Gallium vertex fetch/packing with Mesa's real format unpackers.
 * GEM mappings are mocked; no GPU execution is claimed. */
#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../gallium/opengpu_context.c"

struct pipe_opengpu_resource { uint8_t *data; uint64_t bytes; };
struct opengpu_resource *opengpu_resource(struct pipe_resource *r) { return (void *)r; }
void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *r) { return r->data; }
uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *r) { return r->bytes; }
static void near(float actual, float expected)
{
    if (isinf(expected)) assert(isinf(actual) && signbit(actual)==signbit(expected));
    else if (!(fabsf(actual-expected) <= 0.00001f * fmaxf(1.0f,fabsf(expected)))) {
        fprintf(stderr, "vertex value: got %g, expected %g\n", actual, expected);
        abort();
    }
}

static void decoded(enum pipe_format format, const void *input,
                    float x, float y, float z, float w)
{
    float out[4];
    assert(!load_vertex(input,format,out));
    near(out[0],x); near(out[1],y); near(out[2],z); near(out[3],w);
}

static void conversions(void)
{
    const uint8_t unorm[4]={0,127,255,64};
    const int8_t snorm[4]={-128,-127,0,127};
    const uint8_t scaled[3]={2,128,255};
    const int16_t signed16[2]={INT16_MIN,INT16_MAX};
    const uint16_t half[4]={0x3c00,0xb800,0x7c00,0x0001};
    const uint32_t uints[2]={UINT32_MAX,17};
    const int32_t sints[2]={INT32_MIN,INT32_MAX};
    const int32_t fixed[2]={32768,-65536};
    const uint32_t packed=512u | (1023u<<10) | (2u<<30);

    decoded(PIPE_FORMAT_R8G8B8A8_UNORM,unorm,0,127.0f/255.0f,1,64.0f/255.0f);
    decoded(PIPE_FORMAT_R8G8B8A8_SNORM,snorm,-1,-1,0,1);
    decoded(PIPE_FORMAT_R8G8B8_USCALED,scaled,2,128,255,1);
    decoded(PIPE_FORMAT_R16G16_SSCALED,signed16,-32768,32767,0,1);
    decoded(PIPE_FORMAT_R16G16B16A16_FLOAT,half,1,-.5f,INFINITY,0x1p-24f);
    { float out[4]; assert(!load_vertex((const uint8_t *)half,PIPE_FORMAT_R16G16B16A16_FLOAT,out));
      assert(out[3]==0x1p-24f); }
    decoded(PIPE_FORMAT_R32G32_UINT,uints,4294967296.0f,17,0,1);
    decoded(PIPE_FORMAT_R32G32_SINT,sints,-2147483648.0f,2147483648.0f,0,1);
    decoded(PIPE_FORMAT_R32G32_FIXED,fixed,.5f,-1,0,1);
    decoded(PIPE_FORMAT_R10G10B10A2_UNORM,&packed,512.0f/1023.0f,1,0,2.0f/3.0f);
}

static void format_bounds(void)
{
    struct opengpu_context ctx={0};
    struct pipe_opengpu_resource gpu={0};
    struct opengpu_resource resource={0};
    unsigned tested=0, comps;
    float value[4];
    ctx.nr_ve=1;
    ctx.ve[0].vertex_buffer_index=0;
    resource.base.target=PIPE_BUFFER;
    resource.gpu=&gpu;
    ctx.vb[0].buffer.resource=&resource.base;

    for (unsigned f=1;f<PIPE_FORMAT_COUNT;f++) {
        const struct util_format_description *desc;
        const struct util_format_unpack_description *unpack;
        uint8_t *data, *allocation;
        unsigned bytes;
        if (!opengpu_vertex_format_supported(f)) continue;
        desc=util_format_description(f); unpack=util_format_unpack_description(f);
        assert(desc && unpack && unpack->unpack_rgba && desc->block.bits &&
               !(desc->block.bits&7) && desc->block.width==1 && desc->block.height==1);
        bytes=desc->block.bits/8;
        allocation=calloc(bytes+1,1); assert(allocation);
        data=allocation+1; /* Deliberately unaligned, with an exact-sized tail. */
        gpu.data=data; gpu.bytes=bytes+64; resource.base.width0=bytes;
        ctx.ve[0].src_format=f; ctx.vb[0].stride=bytes;
        assert(attrib_ptr(&ctx,0,0,&comps)==data && comps==desc->nr_channels);
        assert(!load_vertex(data,f,value));
        assert(!attrib_ptr(&ctx,0,1,&comps));
        resource.base.width0=bytes-1;
        assert(!attrib_ptr(&ctx,0,0,&comps));
        resource.base.width0=bytes; gpu.bytes=bytes-1;
        assert(!attrib_ptr(&ctx,0,0,&comps));
        free(allocation);
        tested++;
    }
    assert(tested==90);
    ctx.vb[0].is_user_buffer=1;
    ctx.vb[0].buffer.user=(void *)(UINTPTR_MAX-2u);
    ctx.vb[0].buffer_offset=16;
    ctx.ve[0].src_format=PIPE_FORMAT_R8_UNORM;
    assert(!attrib_ptr(&ctx,0,0,&comps));
}

static void packed_draw_inputs(void)
{
    const int16_t position[2]={INT16_MIN,0};
    const uint8_t color[4]={255,128,0,64};
    const uint16_t coords[2]={16384,49151};
    struct opengpu_context ctx={0};
    struct opengpu_shader vs={0}, fs={0};
    struct hw_vertex output={0};

    ctx.nr_ve=2;
    ctx.ve[0].vertex_buffer_index=0; ctx.ve[0].src_format=PIPE_FORMAT_R16G16_SNORM;
    ctx.ve[1].vertex_buffer_index=1; ctx.ve[1].src_format=PIPE_FORMAT_R8G8B8A8_UNORM;
    ctx.vb[0].is_user_buffer=1; ctx.vb[0].buffer.user=position;
    ctx.vb[1].is_user_buffer=1; ctx.vb[1].buffer.user=color;
    vs.pos_src=0; vs.nlink=1; vs.link[0].semantic=TGSI_SEMANTIC_COLOR; vs.link[0].src=1;
    fs.color_sem=TGSI_SEMANTIC_COLOR;
    assert(!pack_one(&ctx,0,&vs,&fs,&output));
    assert(output.x==-65536 && output.y==0 && output.z==0 && output.w==65536);
    assert(output.color==0xff800040u);
    vs.mvp=1;
    assert(!pack_one(&ctx,0,&vs,&fs,&output));
    { float x,w; memcpy(&x,&output.x,4); memcpy(&w,&output.w,4); assert(x==-1 && w==1); }
    vs.mvp=0;

    ctx.ve[1].src_format=PIPE_FORMAT_R16G16_UNORM;
    ctx.vb[1].buffer.user=coords;
    fs.samples=1; fs.sample_sem=TGSI_SEMANTIC_COLOR;
    assert(!pack_one(&ctx,0,&vs,&fs,&output));
    assert(output.u==16384u && output.v==49152u);
}

int main(void)
{
    conversions(); format_bounds(); packed_draw_inputs();
    puts("Gallium Mesa vertex formats and resource bounds: PASS");
    return 0;
}
