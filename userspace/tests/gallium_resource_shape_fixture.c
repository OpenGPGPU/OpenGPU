/* Actual Gallium screen callbacks with mocked GEM allocations; no GPU. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "../gallium/opengpu_screen.c"
#include "opengpu_image_views.h"

struct pipe_opengpu_resource { uint32_t bytes, pitch; };
static unsigned allocations;
static unsigned buffer_allocations, image_allocations, linear_allocations;
static int typed_images;
static struct pipe_opengpu_resource backing;
struct pipe_opengpu_resource *pipe_opengpu_resource_create(struct pipe_opengpu_screen *s, uint32_t bytes)
{ (void)s; ++allocations; ++buffer_allocations; backing.bytes=bytes; backing.pitch=0; return &backing; }
struct pipe_opengpu_resource *pipe_opengpu_resource_create_2d(struct pipe_opengpu_screen *s, uint32_t w, uint32_t h)
{ (void)s; ++allocations; ++image_allocations; backing.bytes=w*h*4; backing.pitch=w*4; return &backing; }
struct pipe_opengpu_resource *pipe_opengpu_resource_create_linear(struct pipe_opengpu_screen *s, uint32_t w, uint32_t h)
{
    (void)s; ++allocations; ++linear_allocations;
    /* The real linear allocator owns the final cache line while keeping rows packed. */
    backing.bytes=(uint32_t)(((uint64_t)w*h*4+63u)&~UINT64_C(63));
    backing.pitch=w*4;
    return &backing;
}
struct pipe_opengpu_resource *pipe_opengpu_resource_from_handle(struct pipe_opengpu_screen *s, uint32_t w, uint32_t h, uint32_t pitch, uint32_t fd, int is_fd)
{ (void)s; (void)w; (void)h; (void)fd; (void)is_fd; ++allocations; backing.pitch=pitch; return &backing; }
void pipe_opengpu_resource_destroy(struct pipe_opengpu_screen *s, struct pipe_opengpu_resource *r)
{ (void)s; assert(r==&backing); }
int pipe_opengpu_resource_set_format(struct pipe_opengpu_screen *s, struct pipe_opengpu_resource *r, uint32_t f)
{ (void)s; (void)r; (void)f; return 0; }
uint32_t pipe_opengpu_resource_pitch(const struct pipe_opengpu_resource *r) { return r->pitch; }
uint32_t pipe_opengpu_resource_handle(const struct pipe_opengpu_resource *r) { (void)r; return 17; }
int pipe_opengpu_resource_export_fd(struct pipe_opengpu_screen *s, struct pipe_opengpu_resource *r)
{ (void)s; (void)r; return 9; }
int pipe_opengpu_screen_compute_buffer_textures(const struct pipe_opengpu_screen *s) { (void)s; return 0; }
int pipe_opengpu_screen_compute_buffer_images(const struct pipe_opengpu_screen *s) { (void)s; return 0; }
int pipe_opengpu_screen_compute_image_backings(const struct pipe_opengpu_screen *s) { (void)s; return typed_images; }
uint32_t pipe_opengpu_screen_storage_alignment(const struct pipe_opengpu_screen *s) { (void)s; return 0; }

int main(void)
{
    struct opengpu_screen screen={0};
    struct pipe_resource t={0}, bad;
    struct pipe_resource *r;
    struct winsys_handle wh={0};
    uint64_t value;
    assert(screen_get_param(&screen.base, PIPE_CAP_MAX_RENDER_TARGETS)==1);
    assert(screen_get_param(&screen.base, PIPE_CAP_CLEAR_SCISSORED)==1);
    assert(screen_get_param(&screen.base, PIPE_CAP_PRIMITIVE_RESTART)==1);
    assert(screen_get_param(&screen.base, PIPE_CAP_PRIMITIVE_RESTART_FIXED_INDEX)==1);
    assert(screen_get_param(&screen.base, PIPE_CAP_SUPPORTED_PRIM_MODES_WITH_RESTART)==
           screen_get_param(&screen.base, PIPE_CAP_SUPPORTED_PRIM_MODES));
    assert(screen_is_format_supported(&screen.base, PIPE_FORMAT_Z24_UNORM_S8_UINT,
        PIPE_TEXTURE_2D, 0, 0, PIPE_BIND_DEPTH_STENCIL));
    assert(!screen_is_format_supported(&screen.base, PIPE_FORMAT_Z24_UNORM_S8_UINT,
        PIPE_TEXTURE_2D, 0, 0, PIPE_BIND_DEPTH_STENCIL | PIPE_BIND_SAMPLER_VIEW));
    assert(!screen_is_format_supported(&screen.base, PIPE_FORMAT_R8G8B8A8_UNORM,
        PIPE_TEXTURE_2D, 0, 0, PIPE_BIND_SHADER_IMAGE));
    assert(!screen_is_format_supported(&screen.base, PIPE_FORMAT_R8G8B8A8_UNORM,
        PIPE_TEXTURE_RECT, 0, 0, PIPE_BIND_SAMPLER_VIEW));
    assert(screen_is_format_supported(&screen.base, PIPE_FORMAT_R8G8B8A8_UNORM,
        PIPE_TEXTURE_2D, 0, 0, PIPE_BIND_RENDER_TARGET | PIPE_BIND_SAMPLER_VIEW));
    assert(screen_is_format_supported(&screen.base, PIPE_FORMAT_R8G8B8A8_UNORM,
        PIPE_TEXTURE_RECT, 0, 0, PIPE_BIND_RENDER_TARGET));
    assert(screen_is_format_supported(&screen.base, PIPE_FORMAT_B8G8R8A8_UNORM,
        PIPE_TEXTURE_2D, 0, 0, PIPE_BIND_RENDER_TARGET));
    assert(!screen_is_format_supported(&screen.base, PIPE_FORMAT_B8G8R8A8_UNORM,
        PIPE_TEXTURE_2D, 0, 0, PIPE_BIND_RENDER_TARGET | PIPE_BIND_VERTEX_BUFFER));
    for (unsigned f=1; f<PIPE_FORMAT_COUNT; f++)
        assert(screen_is_format_supported(&screen.base, f, PIPE_BUFFER, 0, 0,
                   PIPE_BIND_VERTEX_BUFFER) == opengpu_vertex_format_supported(f));
    t.target=PIPE_TEXTURE_2D; t.format=PIPE_FORMAT_Z24_UNORM_S8_UINT;
    t.width0=17; t.height0=3; t.depth0=t.array_size=1; t.bind=PIPE_BIND_DEPTH_STENCIL;
    bad=t; bad.bind |= PIPE_BIND_SAMPLER_VIEW;
    assert(!resource_create(&screen.base, &bad) && allocations==0);
    bad=t; bad.format=PIPE_FORMAT_R8G8B8A8_UNORM; bad.bind=PIPE_BIND_SHADER_IMAGE;
    assert(!resource_create(&screen.base, &bad) && allocations==0);
    bad=t; bad.target=PIPE_TEXTURE_RECT; bad.format=PIPE_FORMAT_R8G8B8A8_UNORM;
    bad.bind=PIPE_BIND_SAMPLER_VIEW;
    assert(!resource_create(&screen.base, &bad) && allocations==0);
    r=resource_create(&screen.base,&t); assert(r && allocations==1 && backing.bytes==256);
    assert(linear_allocations==1 && buffer_allocations==0 && image_allocations==0);
    assert(resource_get_param(&screen.base,NULL,r,0,0,0,PIPE_RESOURCE_PARAM_STRIDE,0,&value) && value==68);
    assert(!resource_get_param(&screen.base,NULL,r,0,1,0,PIPE_RESOURCE_PARAM_STRIDE,0,&value));
    assert(!resource_get_param(&screen.base,NULL,r,0,0,1,PIPE_RESOURCE_PARAM_STRIDE,0,&value));
    resource_destroy(&screen.base,r);
#define REJECT(field, n) do { bad=t; bad.field=(n); assert(!resource_create(&screen.base,&bad)); assert(allocations==1); } while (0)
    REJECT(width0, 0); REJECT(height0, 0); REJECT(depth0, 2); REJECT(array_size, 2);
    REJECT(last_level, 1); REJECT(nr_samples, 2); REJECT(nr_storage_samples, 2);
    bad=t; bad.width0=0x40000010u; bad.height0=1; assert(!resource_create(&screen.base,&bad) && allocations==1);
    bad=t; bad.width0=65537; bad.height0=65535; assert(!resource_create(&screen.base,&bad) && allocations==1);
    bad=t; bad.width0=0x3ffffff0u; bad.height0=1; assert(image_shape_supported(&bad));
    bad=t; bad.width0=0x3ffffff1u; bad.height0=1; assert(!resource_create(&screen.base,&bad) && allocations==1);
    t.format=PIPE_FORMAT_R8G8B8A8_UNORM; t.bind=PIPE_BIND_RENDER_TARGET;
    bad=t; bad.last_level=1; assert(!resource_create(&screen.base,&bad) && allocations==1);
    wh.type=WINSYS_HANDLE_TYPE_FD; wh.handle=4; wh.stride=68;
    bad=t; bad.bind=PIPE_BIND_SHADER_IMAGE;
    assert(!resource_from_handle(&screen.base,&bad,&wh,0) && allocations==1);
    bad=t; bad.nr_storage_samples=2; assert(!resource_from_handle(&screen.base,&bad,&wh,0) && allocations==1);
    bad=t; bad.width0=0x40000000u; bad.height0=1; assert(!resource_from_handle(&screen.base,&bad,&wh,0) && allocations==1);
    r=resource_from_handle(&screen.base,&t,&wh,0); assert(r && allocations==2); resource_destroy(&screen.base,r);
    assert(screen_is_format_supported(&screen.base,t.format,t.target,0,0,t.bind));
    assert(!screen_is_format_supported(&screen.base,t.format,t.target,1,2,t.bind));
    t.target=PIPE_BUFFER; t.width0=65; t.height0=t.depth0=t.array_size=1; t.bind=PIPE_BIND_SHADER_BUFFER;
    r=resource_create(&screen.base,&t); assert(r && backing.bytes==128 && allocations==3); resource_destroy(&screen.base,r);
    assert(buffer_allocations==1 && linear_allocations==1 && image_allocations==0);
    bad=t; bad.last_level=1; assert(!resource_create(&screen.base,&bad) && allocations==3);
    bad=t; bad.height0=2; assert(!resource_create(&screen.base,&bad) && allocations==3);
    bad=t; bad.nr_storage_samples=2; assert(!resource_create(&screen.base,&bad) && allocations==3);
    t.target=PIPE_TEXTURE_2D; t.width0=17; t.height0=3;
    t.bind=PIPE_BIND_RENDER_TARGET | PIPE_BIND_SAMPLER_VIEW;
    r=resource_create(&screen.base,&t); assert(r && allocations==4);
    assert(backing.bytes==256 && backing.pitch==68 && linear_allocations==2 &&
           buffer_allocations==1 && image_allocations==0);
    resource_destroy(&screen.base,r);
    {
        static const struct {enum pipe_format format;unsigned native,bytes;} formats[]={
            {PIPE_FORMAT_R32G32B32A32_FLOAT,35,16},{PIPE_FORMAT_R16G16B16A16_FLOAT,34,8},
            {PIPE_FORMAT_R32_FLOAT,29,4},{PIPE_FORMAT_R32G32B32A32_UINT,26,16},
            {PIPE_FORMAT_R16G16B16A16_UINT,24,8},{PIPE_FORMAT_R8G8B8A8_UINT,22,4},
            {PIPE_FORMAT_R32_UINT,14,4},{PIPE_FORMAT_R32G32B32A32_SINT,25,16},
            {PIPE_FORMAT_R16G16B16A16_SINT,23,8},{PIPE_FORMAT_R8G8B8A8_SINT,21,4},
            {PIPE_FORMAT_R32_SINT,13,4},{PIPE_FORMAT_R8G8B8A8_UNORM,3,4},{PIPE_FORMAT_R8G8B8A8_SNORM,39,4}};
        static const enum pipe_texture_target targets[]={PIPE_TEXTURE_2D,PIPE_TEXTURE_2D_ARRAY,PIPE_TEXTURE_CUBE,PIPE_TEXTURE_3D};
        typed_images=1;
        for(unsigned f=0;f<13;f++)for(unsigned target=0;target<4;target++){
            t=(struct pipe_resource){.target=targets[target],.format=formats[f].format,
                .width0=8,.height0=target==2?8:4,.depth0=target==3?4:1,
                .array_size=target==1?3:target==2?6:1,.last_level=2,.bind=PIPE_BIND_SHADER_IMAGE};
            unsigned before=allocations;
            assert(screen_is_format_supported(&screen.base,t.format,t.target,0,0,t.bind));
            bad=t;bad.last_level=4;assert(!resource_create(&screen.base,&bad) && allocations==before);
            bad=t;bad.nr_samples=2;assert(!resource_create(&screen.base,&bad) && allocations==before);
            bad=t;bad.bind|=PIPE_BIND_SAMPLER_VIEW;assert(!resource_create(&screen.base,&bad) && allocations==before);
            r=resource_create(&screen.base,&t);assert(r && allocations==before+1);
            const struct opengpu_image_layout *layout=&opengpu_resource(r)->image;
            unsigned height=target==2?8:4,planes=target==1?3:target==2?6:target==3?4:1;
            unsigned row0=formats[f].bytes==16?128:64;
            unsigned planes1=target==3?2:planes,planes2=target==3?1:planes;
            unsigned first_bytes=row0*height*planes;
            assert(layout->bytes==first_bytes+64*(height/2)*planes1+64*(height/4)*planes2);
            assert(layout->levels==3 && layout->format==formats[f].native && layout->texel_bytes==formats[f].bytes);
            assert(layout->level[1].offset==first_bytes && layout->level[1].row_pitch==64 &&
                layout->level[1].plane_pitch==64*height/2 && layout->level[1].planes==planes1);
            struct pipe_image_view v={.resource=r,.format=t.format,.access=PIPE_IMAGE_ACCESS_READ_WRITE,
                                      .shader_access=PIPE_IMAGE_ACCESS_READ_WRITE};
            v.u.tex.level=1;v.u.tex.last_layer=planes1-1;
            struct drm_opengpu_compute_image_view native;
            assert(opengpu_gallium_image_init(&native,42,&v));
            assert(opengpu_compute_image_view_valid(&native,42,layout->bytes));
            assert(native.target==target && native.width==4 && native.height==height/2 && native.offset==first_bytes &&
                native.storage_format==formats[f].native && native.image_format==formats[f].native);
            assert(resource_get_param(&screen.base,NULL,r,0,0,1,PIPE_RESOURCE_PARAM_STRIDE,0,&value) && value==64);
            assert(resource_get_param(&screen.base,NULL,r,0,planes1-1,1,PIPE_RESOURCE_PARAM_OFFSET,0,&value) &&
                value==first_bytes+(planes1-1)*64*height/2);
            struct drm_opengpu_compute_image_view saved=native,wrong=native;
            wrong.offset=layout->bytes-1;assert(!opengpu_compute_image_view_valid(&wrong,42,layout->bytes));
            wrong=native;wrong.row_pitch=1;assert(!opengpu_compute_image_view_valid(&wrong,42,layout->bytes));
            wrong=native;wrong.pad=1;assert(!opengpu_compute_image_view_valid(&wrong,42,layout->bytes));
            v.u.tex.level=3;assert(!opengpu_gallium_image_init(&native,42,&v) && !memcmp(&native,&saved,sizeof(native)));
            v.u.tex.level=1;v.u.tex.first_layer=v.u.tex.last_layer=planes1-1;
            assert(opengpu_gallium_image_init(&native,42,&v) && native.layer==planes1-1 &&
                !(native.flags&OPENGPU_COMPUTE_IMAGE_LAYERED) && opengpu_compute_image_view_valid(&native,42,layout->bytes));
            assert(!resource_get_handle(&screen.base,NULL,r,&wh,0));
            assert(!resource_from_handle(&screen.base,&t,&wh,0));
            if(target==1 || target==3){
                v.u.tex.first_layer=v.u.tex.last_layer=0;
                assert(opengpu_gallium_image_for_shader(&native,42,&v,target) &&
                    (native.flags&OPENGPU_COMPUTE_IMAGE_LAYERED) && native.layers==1 && native.depth==1 &&
                    opengpu_compute_image_view_valid(&native,42,layout->bytes));
                assert(opengpu_gallium_image_for_shader(&native,42,&v,0) &&
                    !(native.flags&OPENGPU_COMPUTE_IMAGE_LAYERED) &&
                    opengpu_compute_image_view_valid(&native,42,layout->bytes));
            }
            resource_destroy(&screen.base,r);
        }
        t=(struct pipe_resource){.target=PIPE_TEXTURE_2D_ARRAY,.format=PIPE_FORMAT_R32_UINT,
            .width0=8,.height0=4,.depth0=1,.array_size=4,.bind=PIPE_BIND_SHADER_IMAGE};
        r=resource_create(&screen.base,&t);assert(r);
        struct pipe_image_view sub={.resource=r,.format=t.format,.access=PIPE_IMAGE_ACCESS_READ};
        sub.u.tex.first_layer=1;sub.u.tex.last_layer=2;
        struct drm_opengpu_compute_image_view view;
        assert(opengpu_gallium_image_init(&view,39,&sub) && view.offset==256 && view.layers==2 &&
            view.layer==0 && (view.flags&OPENGPU_COMPUTE_IMAGE_LAYERED) &&
            opengpu_compute_image_view_valid(&view,39,opengpu_resource(r)->image.bytes));
        resource_destroy(&screen.base,r);
    }
    puts("Gallium resource shape fixture: legacy + 52 typed mip/layer layouts PASS");
    return 0;
}
