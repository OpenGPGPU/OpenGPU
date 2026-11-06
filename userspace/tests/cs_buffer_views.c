/* SPDX-License-Identifier: MIT */
/* Actual pipe callbacks and GEM lifetimes, with the existing modeled OS/DRM
 * boundary. This does not submit/execute a buffer shader or expose GL caps. */
#define main storage_callback_main
#include "cs_callback_flow.c"
#undef main
#include "opengpu_buffer_views.h"

struct buffer_format_case { enum pipe_format pipe; unsigned native,bytes; };
static const struct buffer_format_case sampler_formats[]={
    {PIPE_FORMAT_R8_UNORM,0,1},{PIPE_FORMAT_R16_FLOAT,28,2},{PIPE_FORMAT_R32_FLOAT,29,4},
    {PIPE_FORMAT_R8_SINT,9,1},{PIPE_FORMAT_R16_SINT,11,2},{PIPE_FORMAT_R32_SINT,13,4},
    {PIPE_FORMAT_R8_UINT,10,1},{PIPE_FORMAT_R16_UINT,12,2},{PIPE_FORMAT_R32_UINT,14,4},
    {PIPE_FORMAT_R8G8_UNORM,1,2},{PIPE_FORMAT_R16G16_FLOAT,30,4},{PIPE_FORMAT_R32G32_FLOAT,31,8},
    {PIPE_FORMAT_R8G8_SINT,15,2},{PIPE_FORMAT_R16G16_SINT,17,4},{PIPE_FORMAT_R32G32_SINT,19,8},
    {PIPE_FORMAT_R8G8_UINT,16,2},{PIPE_FORMAT_R16G16_UINT,18,4},{PIPE_FORMAT_R32G32_UINT,20,8},
    {PIPE_FORMAT_R32G32B32_FLOAT,33,12},{PIPE_FORMAT_R32G32B32_SINT,44,12},{PIPE_FORMAT_R32G32B32_UINT,45,12},
    {PIPE_FORMAT_R8G8B8A8_UNORM,3,4},{PIPE_FORMAT_R16G16B16A16_FLOAT,34,8},{PIPE_FORMAT_R32G32B32A32_FLOAT,35,16},
    {PIPE_FORMAT_R8G8B8A8_SINT,21,4},{PIPE_FORMAT_R16G16B16A16_SINT,23,8},{PIPE_FORMAT_R32G32B32A32_SINT,25,16},
    {PIPE_FORMAT_R8G8B8A8_UINT,22,4},{PIPE_FORMAT_R16G16B16A16_UINT,24,8},{PIPE_FORMAT_R32G32B32A32_UINT,26,16},
};
static const struct buffer_format_case image_formats[]={
    {PIPE_FORMAT_R32G32B32A32_FLOAT,35,16},{PIPE_FORMAT_R32G32B32A32_UINT,26,16},{PIPE_FORMAT_R32G32B32A32_SINT,25,16},
    {PIPE_FORMAT_R16G16B16A16_FLOAT,34,8},{PIPE_FORMAT_R16G16B16A16_UINT,24,8},{PIPE_FORMAT_R16G16B16A16_SINT,23,8},
    {PIPE_FORMAT_R32_FLOAT,29,4},{PIPE_FORMAT_R32_UINT,14,4},{PIPE_FORMAT_R32_SINT,13,4},
    {PIPE_FORMAT_R8G8B8A8_UINT,22,4},{PIPE_FORMAT_R8G8B8A8_SINT,21,4},
    {PIPE_FORMAT_R8G8B8A8_UNORM,3,4},{PIPE_FORMAT_R8G8B8A8_SNORM,39,4},
};
static struct pipe_sampler_view template(enum pipe_format f,unsigned bytes)
{
    struct pipe_sampler_view t={0};t.target=PIPE_BUFFER;t.format=f;
    t.swizzle_r=PIPE_SWIZZLE_Z;t.swizzle_g=PIPE_SWIZZLE_1;
    t.swizzle_b=PIPE_SWIZZLE_X;t.swizzle_a=PIPE_SWIZZLE_0;
    t.u.buf.offset=63;t.u.buf.size=65536u*bytes+bytes-1;return t;
}
static struct pipe_image_view image_view(struct pipe_resource *r,enum pipe_format f,unsigned bytes,unsigned access)
{
    struct pipe_image_view i={0};i.resource=r;i.format=f;i.access=access;
    i.u.buf.offset=63;i.u.buf.size=65536u*bytes+bytes-1;return i;
}
static struct pipe_sampler_view *new_view(struct pipe_context *p,struct pipe_resource *r)
{
    struct pipe_sampler_view t=template(PIPE_FORMAT_R32_UINT,4);
    struct pipe_sampler_view *v=p->create_sampler_view(p,r,&t);assert(v);return v;
}
static void formats_and_ranges(struct pipe_screen *s,struct pipe_context *p,struct pipe_resource *r)
{
    unsigned samplers=0,images=0;
    for(unsigned f=0;f<PIPE_FORMAT_COUNT;f++) {
        int expected=-1,expected_image=-1;
        for(unsigned i=0;i<30;i++)if(sampler_formats[i].pipe==(enum pipe_format)f)expected=(int)sampler_formats[i].native;
        for(unsigned i=0;i<13;i++)if(image_formats[i].pipe==(enum pipe_format)f)expected_image=(int)image_formats[i].native;
        assert(opengpu_buffer_sampler_format((enum pipe_format)f)==expected);
        assert(opengpu_buffer_image_format((enum pipe_format)f)==expected_image);
        samplers+=expected>=0;images+=expected_image>=0;
    }
    assert(samplers==30 && images==13 && r->reference.count==1);
    assert(!s->is_format_supported(s,PIPE_FORMAT_R32_UINT,PIPE_BUFFER,2,0,PIPE_BIND_SAMPLER_VIEW));
    assert(!s->is_format_supported(s,PIPE_FORMAT_R32_UINT,PIPE_BUFFER,0,2,PIPE_BIND_SHADER_IMAGE));
    for(unsigned i=0;i<30;i++) {
        const struct buffer_format_case *f=&sampler_formats[i];
        struct pipe_sampler_view t=template(f->pipe,f->bytes);
        struct drm_opengpu_compute_texture_view native;
        struct pipe_sampler_view *v=p->create_sampler_view(p,r,&t);assert(v && r->reference.count==2);
        assert(opengpu_gallium_buffer_sampler_init(&native,34,r,v));
        assert(native.slot==34 && native.target==5 && native.format==f->native && native.buffer_texels==65536);
        assert(native.level_count==1 && native.base_level==0 && native.max_level==0);
        assert(native.levels[0].offset==63 && native.levels[0].buffer_bytes==65536u*f->bytes+f->bytes-1);
        assert(native.levels[0].buffer_texels==65536 && native.levels[0].height==1 && native.levels[0].depth==1);
        assert(native.levels[0].row_pitch==native.levels[0].buffer_bytes && !native.levels[0].slice_pitch && !native.levels[0].layer_pitch);
        assert(native.swizzle_version==1 && native.swizzle[0]==2 && native.swizzle[1]==5 && native.swizzle[2]==0 && native.swizzle[3]==4);
        assert(s->is_format_supported(s,f->pipe,PIPE_BUFFER,0,0,PIPE_BIND_SAMPLER_VIEW));
        pipe_sampler_view_reference(&v,NULL);assert(r->reference.count==1);
    }
    assert(!s->is_format_supported(s,PIPE_FORMAT_R8G8B8A8_SNORM,PIPE_BUFFER,0,0,PIPE_BIND_SAMPLER_VIEW));
    for(unsigned i=0;i<13;i++)for(unsigned access=1;access<=3;access++) {
        const struct buffer_format_case *f=&image_formats[i];
        struct pipe_image_view v=image_view(r,f->pipe,f->bytes,access);
        struct drm_opengpu_compute_image_view native;
        /* shader_access=0 is used for metadata-only queries; permissions
         * still come from the actual API binding, including write-only. */
        assert(opengpu_gallium_buffer_image_init(&native,16,&v));
        assert(native.slot==16 && native.target==5 && native.image_format==f->native && native.buffer_texels==65536);
        assert(native.storage_format==(f->native==39?3:f->native));
        assert(native.flags==(access|(f->native==39?OPENGPU_COMPUTE_IMAGE_BY_SIZE:0)));
        assert(native.offset==63 && native.buffer_bytes==65536u*f->bytes+f->bytes-1);
        assert(native.height==1 && native.depth==1 && native.layers==1 && !native.slice_pitch);
        assert(s->is_format_supported(s,f->pipe,PIPE_BUFFER,0,0,PIPE_BIND_SHADER_IMAGE));
    }
    /* Padding supplied by GEM allocation cannot enlarge the original view. */
    struct pipe_sampler_view t=template(PIPE_FORMAT_R32_UINT,4);
    struct drm_opengpu_compute_texture_view out,saved;
    memset(&out,0x73,sizeof(out));saved=out;
    t.u.buf.offset=r->width0;t.u.buf.size=1;
    assert(!p->create_sampler_view(p,r,&t));
    assert(!opengpu_gallium_buffer_sampler_init(&out,3,r,&t) && !memcmp(&out,&saved,sizeof(out)));
    t.u.buf.offset=0;t.u.buf.size=r->width0+1;
    assert(!p->create_sampler_view(p,r,&t));
    t=template(PIPE_FORMAT_R32_UINT,4);t.u.buf.size=3;assert(!p->create_sampler_view(p,r,&t));
    t=template(PIPE_FORMAT_R32_UINT,4);t.swizzle_a=6;assert(!p->create_sampler_view(p,r,&t));
    t=template(PIPE_FORMAT_R8G8B8A8_SNORM,4);assert(!p->create_sampler_view(p,r,&t));
    t=template(PIPE_FORMAT_R32_UINT,4);t.target=PIPE_TEXTURE_2D;assert(!p->create_sampler_view(p,r,&t));
    struct pipe_image_view v=image_view(r,PIPE_FORMAT_R32_UINT,4,0);
    struct drm_opengpu_compute_image_view giu,old;
    memset(&giu,0x29,sizeof(giu));old=giu;
    assert(!opengpu_gallium_buffer_image_init(&giu,13,&v) && !memcmp(&giu,&old,sizeof(giu)));
    v.access=3;v.u.buf.offset=r->width0;v.u.buf.size=1;
    assert(!opengpu_gallium_buffer_image_init(&giu,13,&v));
    v=image_view(r,PIPE_FORMAT_R32_UINT,4,3);v.shader_access=4;
    assert(!opengpu_gallium_buffer_image_init(&giu,13,&v));
    assert(r->reference.count==1);
    /* Metadata-only limits retain the native 2^27 count and the complete
     * original range; no allocation or traversal of this large input occurs. */
    struct pipe_resource large={.target=PIPE_BUFFER,.width0=UINT32_MAX};
    t=template(PIPE_FORMAT_R8_UNORM,1);t.u.buf.offset=0;t.u.buf.size=UINT32_MAX;
    assert(opengpu_gallium_buffer_sampler_init(&out,34,&large,&t));
    assert(out.buffer_texels==134217728u && out.levels[0].buffer_bytes==UINT32_MAX);
    saved=out;t.u.buf.offset=1;
    assert(!opengpu_gallium_buffer_sampler_init(&out,34,&large,&t) && !memcmp(&out,&saved,sizeof(out)));
}
static void dense_state_lifetimes(struct pipe_screen *s)
{
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    struct pipe_resource *r=buffer(s,1048703);assert(r);
    struct pipe_sampler_view *views[32],*borrowed[32];
    for(unsigned i=0;i<32;i++) {
        const struct buffer_format_case *f=&sampler_formats[i%30];
        struct pipe_sampler_view t=template(f->pipe,f->bytes);t.u.buf.offset+=i;
        views[i]=p->create_sampler_view(p,r,&t);assert(views[i]);borrowed[i]=views[i];
    }
    assert(r->reference.count==33);
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,32,0,false,views);
    for(unsigned i=0;i<32;i++) {
        pipe_sampler_view_reference(&views[i],NULL);assert(borrowed[i]->reference.count==1);
    }
    struct pipe_image_view images[4];struct pipe_resource *backing[4];unsigned handles[4];
    for(unsigned i=0;i<4;i++) {
        backing[i]=buffer(s,1048703);assert(backing[i]);
        handles[i]=pipe_opengpu_resource_handle(opengpu_resource(backing[i])->gpu);
        images[i]=image_view(backing[i],image_formats[i].pipe,image_formats[i].bytes,1+i%3);
    }
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,4,0,images);
    for(unsigned i=0;i<4;i++){assert(backing[i]->reference.count==2);pipe_resource_reference(&backing[i],NULL);}
    struct pipe_image_view rotated[4];
    for(unsigned i=0;i<4;i++)rotated[i]=images[(i+1)%4];
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,4,0,rotated);
    for(unsigned i=0;i<4;i++)assert(rotated[i].resource->reference.count==1);
    p->destroy(p);assert(r->reference.count==1);
    for(unsigned i=0;i<4;i++)assert(!bos[handles[i]].data);
    pipe_resource_reference(&r,NULL);
}
static void sampler_lifetimes(struct pipe_screen *s,struct pipe_context *p,struct pipe_resource *a,struct pipe_resource *b)
{
    struct pipe_sampler_view *x=new_view(p,a),*y=new_view(p,b),*all[32];
    for(unsigned i=0;i<32;i++)all[i]=x;
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,32,0,false,all);
    assert(x->reference.count==33 && a->reference.count==2);
    struct pipe_sampler_view *pair[2]={x,y};
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,2,0,false,pair);
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,2,0,30,false,NULL);
    assert(x->reference.count==2 && y->reference.count==2);
    struct pipe_sampler_view *borrowed_x=x,*borrowed_y=y;
    pipe_sampler_view_reference(&x,NULL);pipe_sampler_view_reference(&y,NULL);
    assert(borrowed_x->reference.count==1 && borrowed_y->reference.count==1);
    pair[0]=borrowed_y;pair[1]=borrowed_x;
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,2,0,false,pair);
    assert(borrowed_x->reference.count==1 && borrowed_y->reference.count==1);
    unsigned size=borrowed_y->u.buf.size;borrowed_y->u.buf.size=0;
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,2,0,false,pair);
    assert(borrowed_x->reference.count==1 && borrowed_y->reference.count==1);
    borrowed_y->u.buf.size=size;p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,2,0,false,pair);
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,0,32,false,NULL);
    assert(a->reference.count==1 && b->reference.count==1);

    x=new_view(p,a);y=NULL;pipe_sampler_view_reference(&y,x);
    pair[0]=x;pair[1]=y;p->set_sampler_views(p,PIPE_SHADER_COMPUTE,30,2,0,true,pair);
    assert(x->reference.count==2);x=y=NULL;
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,30,0,2,false,NULL);
    assert(a->reference.count==1);
    x=new_view(p,a);y=new_view(p,b);pair[0]=x;pair[1]=y;
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,31,2,0,true,pair);x=y=NULL;
    assert(a->reference.count==1 && b->reference.count==1);
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,0,32,false,NULL);
    struct pipe_context *foreign=s->context_create(s,NULL,0);assert(foreign);
    x=new_view(foreign,a);pair[0]=x;p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,1,0,true,pair);x=NULL;
    assert(a->reference.count==1);foreign->destroy(foreign);
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,0,32,false,NULL);
    x=new_view(p,a);pair[0]=x;
    p->set_sampler_views(p,PIPE_SHADER_FRAGMENT,0,1,0,true,pair);x=NULL;
    assert(a->reference.count==1); /* no legacy normalized-2D reinterpretation */
    x=new_view(p,a);pair[0]=x;
    p->set_sampler_views(p,PIPE_SHADER_FRAGMENT,1,1,0,true,pair);x=NULL;
    assert(a->reference.count==1); /* unsupported legacy unit consumes ownership */
    /* Context destruction, rather than an explicit unbind, owns the final
     * reference to a sparse high unit. */
    x=new_view(p,a);pair[0]=x;p->set_sampler_views(p,PIPE_SHADER_COMPUTE,31,1,0,false,pair);
    pipe_sampler_view_reference(&x,NULL);assert(a->reference.count==2);
}
static void image_lifetimes(struct pipe_context *p,struct pipe_resource **owner_a,struct pipe_resource **owner_b)
{
    struct pipe_resource *a=*owner_a,*b=*owner_b;
    struct pipe_image_view v[4]={image_view(a,PIPE_FORMAT_R32_UINT,4,1),
        image_view(b,PIPE_FORMAT_R32_UINT,4,2),{0},image_view(a,PIPE_FORMAT_R32_UINT,4,3)};
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,4,0,v);
    assert(a->reference.count==4 && b->reference.count==2); /* a has sparse sampler31 too */
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,2,0,2,NULL);
    /* Keep a caller ref to a: the sparse sampler is tested at destroy. b's
     * sole reference is now image1 and must survive a cross-slot reorder. */
    pipe_resource_reference(owner_b,NULL);assert(b->reference.count==1);
    struct pipe_image_view pair[2]={v[1],v[0]};
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,2,0,pair);
    assert(b->reference.count==1 && a->reference.count==3);
    pair[0].u.buf.size=0;p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,2,0,pair);
    assert(b->reference.count==1 && a->reference.count==3);
    pair[0]=v[1];p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,2,0,pair);
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,4,1,0,pair);
    assert(b->reference.count==1 && a->reference.count==3);
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,2,0,pair);
}
static void old_feature_screens(void)
{
    for(unsigned mask=0;mask<16;mask++) {
        features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE |
            (mask&1?OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE:0) |
            (mask&2?OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE:0) |
            (mask&4?OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES:0) |
            (mask&8?OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES:0);
        struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
        struct pipe_opengpu_screen *gpu=opengpu_screen_gpu(s);
        assert(!!pipe_opengpu_screen_compute_buffer_textures(gpu)==((mask&3)==3));
        assert(!!pipe_opengpu_screen_compute_buffer_images(gpu)==((mask&12)==12));
        struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
        struct pipe_resource *r=buffer(s,1048703);assert(r);
        struct pipe_sampler_view t=template(PIPE_FORMAT_R32_UINT,4);
        struct pipe_sampler_view *v=p->create_sampler_view(p,r,&t);
        assert(!!v==((mask&3)==3));pipe_sampler_view_reference(&v,NULL);
        assert(r->reference.count==1);
        assert(s->is_format_supported(s,PIPE_FORMAT_R32_UINT,PIPE_BUFFER,0,0,PIPE_BIND_SAMPLER_VIEW)==((mask&3)==3));
        assert(s->is_format_supported(s,PIPE_FORMAT_R32_UINT,PIPE_BUFFER,0,0,PIPE_BIND_SHADER_IMAGE)==((mask&12)==12));
        struct pipe_image_view iv=image_view(r,PIPE_FORMAT_R32_UINT,4,2);
        p->set_shader_images(p,PIPE_SHADER_COMPUTE,3,1,0,&iv);
        assert(r->reference.count==1+((mask&12)==12));
        p->destroy(p);assert(r->reference.count==1);
        pipe_resource_reference(&r,NULL);s->destroy(s);no_leaks();
    }
}
int main(void)
{
    features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE | OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE |
        OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE | OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES |
        OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    assert(!s->get_param(s,PIPE_CAP_COMPUTE) && !s->get_param(s,PIPE_CAP_TEXTURE_BUFFER_OBJECTS));
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p && p->set_shader_images);
    struct pipe_resource *a=buffer(s,1048703),*b=buffer(s,1048703);assert(a && b);
    unsigned hb=pipe_opengpu_resource_handle(opengpu_resource(b)->gpu);
    formats_and_ranges(s,p,a);sampler_lifetimes(s,p,a,b);image_lifetimes(p,&a,&b);assert(!b);
    p->destroy(p);assert(a->reference.count==1 && !bos[hb].data);
    pipe_resource_reference(&a,NULL);s->destroy(s);no_leaks();
    s=opengpu_drm_screen_create(7,NULL);assert(s);dense_state_lifetimes(s);s->destroy(s);no_leaks();
    old_feature_screens();
    assert(!computes && !fills && !invalidates);
    puts("Gallium buffer views: all30 sampler/13image formats, logical ranges/swizzles, 32 sparse samplers/4 images, transferred/borrowed/reordered refs, invalid/fresh reuse and old feature gates PASS; 0 shader submits/0 GPU/API caps");
    return 0;
}
