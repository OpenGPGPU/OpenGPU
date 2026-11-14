/* Actual Gallium screen callbacks with mocked GEM allocations; no GPU. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "../gallium/opengpu_screen.c"

struct pipe_opengpu_resource { uint32_t bytes, pitch; };
static unsigned allocations;
static unsigned buffer_allocations, image_allocations, linear_allocations;
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
    puts("Gallium resource shape fixture: PASS");
    return 0;
}
