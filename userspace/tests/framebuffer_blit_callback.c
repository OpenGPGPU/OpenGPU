/* SPDX-License-Identifier: MIT */
/* Actual Gallium -> private winsys -> ioctl -> production shader validator.
 * Reuse modeled GEM/sync/late completion services and the independent word VM;
 * this CPU gate does not replace actual CU/VM/host qualification. */
#define main original_compute_callback_main
#define OPENGPU_CS_CALLBACK_MAX_GRID_AXIS 8u
#include "cs_callback_flow.c"
#undef main
#include "opengpu_framebuffer_blit.h"

static unsigned blit_captures;
static void inspect_blit(const struct drm_opengpu_compute_storage *p,
    const struct context *c,const struct job *j,const struct drm_opengpu_compute_uniform_resources *u)
{
    (void)j;assert(!u && p->launch.local[0]==1 && p->launch.local[1]==1 && p->launch.local[2]==1);
    for(unsigned i=0;i<3;i++)assert(p->bindings[i].slot==35+i);
    assert(p->bindings[0].access==1 && p->bindings[1].access==3 && p->bindings[2].access==1);
    assert(!p->bindings[3].slot && !p->bindings[3].access);
    const uint32_t *maps=(const void *)bos[c->b[37].handle].data;
    assert(maps[0]==2 && maps[1]==sizeof(struct opengpu_framebuffer_blit));
    blit_captures++;
}

int main(void)
{
    features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    struct pipe_resource templ={.target=PIPE_TEXTURE_2D,.format=PIPE_FORMAT_R8G8B8A8_UNORM,
        .width0=4,.height0=4,.depth0=1,.array_size=1,.bind=PIPE_BIND_RENDER_TARGET|PIPE_BIND_SAMPLER_VIEW};
    struct pipe_resource *src=s->resource_create(s,&templ),*dst=s->resource_create(s,&templ);assert(src&&dst);
    struct pipe_opengpu_resource *gs=opengpu_resource(src)->gpu,*gd=opengpu_resource(dst)->gpu;
    uint8_t *input=pipe_opengpu_resource_map(gs),*output=pipe_opengpu_resource_map(gd);
    uint32_t sp=pipe_opengpu_resource_pitch(gs),dp=pipe_opengpu_resource_pitch(gd);
    uint64_t sb=pipe_opengpu_resource_size(gs),db=pipe_opengpu_resource_size(gd);
    assert(sb<=65536 && db<=65536);
    for(unsigned y=0;y<4;y++)for(unsigned x=0;x<4;x++)for(unsigned c=0;c<4;c++)input[y*sp+x*4+c]=(uint8_t)(x*31+y*17+c*53);
    uint8_t *source_copy=malloc((size_t)sb);assert(source_copy);memcpy(source_copy,input,(size_t)sb);
    const enum pipe_format formats[]={PIPE_FORMAT_R8G8B8A8_UNORM,PIPE_FORMAT_B8G8R8A8_UNORM,
        PIPE_FORMAT_R8G8B8X8_UNORM,PIPE_FORMAT_B8G8R8X8_UNORM};
    unsigned graphics_context=next_context-1;struct context original=contexts[graphics_context];
    submission_capture=inspect_blit;
    for(unsigned i=0;i<16;i++) {
        struct pipe_blit_info b={0};
        src->format=b.src.format=formats[i/4];dst->format=b.dst.format=formats[i%4];
        b.src.resource=src;b.dst.resource=dst;b.filter=PIPE_TEX_FILTER_NEAREST;b.mask=PIPE_MASK_RGBA;
        b.src.box=(struct pipe_box){.x=4,.y=0,.width=-4,.height=4,.depth=1};
        b.dst.box=(struct pipe_box){.x=-1,.y=0,.width=6,.height=3,.depth=1};
        b.scissor_enable=1;b.scissor=(struct pipe_scissor_state){.minx=1,.maxx=3,.miny=0,.maxy=2};
        memset(output,0xa5,(size_t)db);
        unsigned before=computes;p->blit(p,&b);assert(computes==before+1 && blit_captures==i+1);
        /* Independent fixed affine pixel-centre oracle for this cropped,
         * flipped 4-to-6 by 4-to-3 case; retain pitch/padding sentinels. */
        for(unsigned y=0;y<4;y++)for(unsigned x=0;x<dp;x++) {
            uint8_t expected=0xa5;
            if(y<2 && x>=4 && x<12) {
                unsigned pixel=x/4,channel=x%4;
                unsigned sx=pixel==1?2:1,sy=y==0?0:2;
                unsigned sbgra=i/4==1 || i/4==3,dbgra=i%4==1 || i%4==3;
                unsigned canonical=channel==0&&dbgra?2:channel==2&&dbgra?0:channel;
                unsigned sch=canonical==0&&sbgra?2:canonical==2&&sbgra?0:canonical;
                if(canonical!=3 || i%4<2)expected=canonical==3 && i/4>=2?255:input[sy*sp+sx*4+sch];
            }
            assert(output[y*dp+x]==expected);
        }
        assert(!memcmp(input,source_copy,(size_t)sb));
        assert(!memcmp(&contexts[graphics_context],&original,sizeof(original)));
    }
    submission_capture=NULL;
    for(unsigned format=0;format<2;format++)for(unsigned mask=1;mask<4;mask++) {
        if(format && (mask&2))continue;
        struct pipe_blit_info b={0};
        b.src.resource=src;b.dst.resource=dst;
        src->format=dst->format=b.src.format=b.dst.format=format?PIPE_FORMAT_Z24X8_UNORM:PIPE_FORMAT_Z24_UNORM_S8_UINT;
        b.src.box=(struct pipe_box){.x=4,.y=0,.width=-4,.height=4,.depth=1};
        b.dst.box=(struct pipe_box){.width=2,.height=2,.depth=1};
        b.filter=PIPE_TEX_FILTER_NEAREST;b.mask=(mask&1?PIPE_MASK_Z:0)|(mask&2?PIPE_MASK_S:0);
        memset(output,0xa5,(size_t)db);unsigned before=computes;
        p->blit(p,&b);assert(computes==before+1);
        for(unsigned y=0;y<4;y++)for(unsigned x=0;x<dp;x++) {
            uint8_t expected=0xa5;
            if(y<2 && x<8 && (x%4==3?mask&2:mask&1)) {
                unsigned sx=x/4?1:3,sy=y*2+1;
                expected=input[sy*sp+sx*4+x%4];
            }
            assert(output[y*dp+x]==expected);
        }
    }
    struct opengpu_framebuffer_blit d={.version=1,.bytes=sizeof(d),.mask=15,
        .source_format=1,.destination_format=2,.source_width=4,.source_height=4,.source_pitch=sp,
        .destination_width=4,.destination_height=4,.destination_pitch=dp,
        .source_w=4,.source_h=4,.destination_w=2,.destination_h=2};
    unsigned before=computes,created=next_context,calls=ioctl_calls;
    d.version=3;assert(pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gd,gs,&d,1000)==-1);
    assert(computes==before && next_context==created && ioctl_calls==calls);d.version=1;
    /* A held ACK cannot turn into CPU replay or reuse the abandoned context.
     * The screen retains the original fence until a later safe reap. */
    wait_error=1;assert(pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gd,gs,&d,1000)==-1);
    assert(computes==before); /* Here the coordinate publication is held. */
    assert(live_contexts()==2);wait_error=0;
    assert(!pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gd,gs,&d,1000));
    assert(computes==before+1 && live_contexts()==1);
    before=computes;wait_error=1;wait_error_kind=1;
    assert(pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gd,gs,&d,1000)==-1);
    assert(computes==before+1 && live_contexts()==2);
    unsigned held=last_compute_context;
    /* No new submission while the screen cannot retire the original owner. */
    assert(pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gd,gs,&d,1000)==-1);
    assert(computes==before+1 && contexts[held].live);
    wait_error=0;wait_error_kind=0;
    assert(!pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gd,gs,&d,1000));
    assert(computes==before+2 && last_compute_context!=held && !contexts[held].live && live_contexts()==1);
    /* Successful submission followed by GPU fault remains an error and a
     * later private context can recover without changing graphics bindings. */
    before=computes;status_error=1;status_error_kind=1;
    assert(pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gd,gs,&d,1000)==-1);
    assert(computes==before+1);
    status_error=0;status_error_kind=0;
    assert(!pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gd,gs,&d,1000));
    assert(!memcmp(&contexts[graphics_context],&original,sizeof(original)));
    d.version=2;d.source_format=d.destination_format=1;
    d.source_w=d.destination_w=2;d.source_h=d.destination_h=1;
    d.destination_x=2;d.destination_y=0;before=computes;
    assert(!pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gs,gs,&d,1000));
    assert(computes==before+1 && !memcmp(input,source_copy,8) && !memcmp(input+8,source_copy,8));
    d.destination_x=1;before=computes;calls=ioctl_calls;
    assert(pipe_opengpu_try_framebuffer_blit(opengpu_screen_gpu(s),gs,gs,&d,1000)==1);
    assert(computes==before && ioctl_calls==calls);
    free(source_copy);pipe_resource_reference(&src,NULL);pipe_resource_reference(&dst,NULL);
    p->destroy(p);s->destroy(s);no_leaks();
    puts("GPU framebuffer blit CPU callbacks: actual Gallium/private pipe/ioctl/validator,16 format pairs,permissions/guards/held-ACK/fault/reuse PASS");
    return 0;
}
