/* Exercise actual Gallium sampler views and staging with mocked GEMs. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include "../gallium/opengpu_context.c"

struct pipe_opengpu_resource {
    uint8_t *data;
    uint64_t bytes;
    uint32_t width, height, pitch;
};

static unsigned invalidations, allocations, frees;
static int fail_invalidation, fail_wait;

struct pipe_opengpu_screen *opengpu_screen_gpu(struct pipe_screen *screen)
{ (void)screen; return (struct pipe_opengpu_screen *)1; }
int pipe_opengpu_screen_compute_buffer_textures(const struct pipe_opengpu_screen *screen)
{ (void)screen; return 0; }
struct pipe_opengpu_resource *pipe_opengpu_resource_create_linear(
    struct pipe_opengpu_screen *screen, uint32_t width, uint32_t height)
{
    struct pipe_opengpu_resource *r = calloc(1, sizeof(*r));
    (void)screen;
    assert(r);
    r->width = width; r->height = height; r->pitch = width * 4;
    r->bytes = (uint64_t)r->pitch * height;
    r->data = calloc(1, r->bytes);
    assert(r->data);
    allocations++;
    return r;
}
void pipe_opengpu_resource_destroy(struct pipe_opengpu_screen *screen,
                                   struct pipe_opengpu_resource *r)
{ (void)screen; free(r->data); free(r); frees++; }
void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *r) { return r->data; }
uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *r) { return r->bytes; }
uint32_t pipe_opengpu_resource_width(const struct pipe_opengpu_resource *r) { return r->width; }
uint32_t pipe_opengpu_resource_height(const struct pipe_opengpu_resource *r) { return r->height; }
uint32_t pipe_opengpu_resource_pitch(const struct pipe_opengpu_resource *r) { return r->pitch; }
int pipe_opengpu_invalidate(struct pipe_opengpu_context *ctx, struct pipe_opengpu_resource *r,
                            uint64_t offset, uint64_t bytes, struct pipe_opengpu_fence **fence)
{
    (void)ctx;
    assert(offset == 0 && bytes == r->bytes);
    invalidations++;
    if (fail_invalidation) return -1;
    *fence = (struct pipe_opengpu_fence *)1;
    return 0;
}
int pipe_opengpu_fence_finish(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence, int64_t timeout)
{ (void)ctx; (void)timeout; assert(fence == (struct pipe_opengpu_fence *)1); return fail_wait ? -1 : 0; }
void pipe_opengpu_fence_reference(struct pipe_opengpu_fence **dst, struct pipe_opengpu_fence *src)
{ *dst = src; }

int main(void)
{
    struct pipe_screen screen = {0}, other_screen = {0};
    struct opengpu_context ctx = {0};
    struct opengpu_resource texture = {0};
    struct pipe_sampler_view templ = {0}, *view;
    uint8_t pixels[24] = { 33, 22, 11, 44, 77, 66, 55, 88, 0xaa, 0xaa, 0xaa, 0xaa,
                           123, 111, 99, 135, 171, 159, 147, 183, 0xaa, 0xaa, 0xaa, 0xaa };
    const uint8_t expected[16] = {255,11,0,33, 255,55,0,77,
                                  255,99,0,123, 255,147,0,171};
    struct pipe_opengpu_resource source = { pixels, 20, 2, 2, 12 };
    struct pipe_opengpu_resource *staged;

    ctx.base.screen = &screen;
    ctx.base.sampler_view_destroy = sampler_view_destroy;
    texture.base.screen = &screen; texture.base.reference.count = 1;
    texture.base.target = PIPE_TEXTURE_2D; texture.base.width0 = texture.base.height0 = 2;
    texture.gpu = &source;
    templ.target = PIPE_TEXTURE_2D; templ.format = PIPE_FORMAT_B8G8R8X8_UNORM;
    templ.swizzle_r = PIPE_SWIZZLE_W; templ.swizzle_g = PIPE_SWIZZLE_X;
    templ.swizzle_b = PIPE_SWIZZLE_0; templ.swizzle_a = PIPE_SWIZZLE_Z;

    texture.base.screen = &other_screen;
    assert(!create_sampler_view(&ctx.base, &texture.base, &templ));
    texture.base.screen = &screen;
    templ.target = PIPE_TEXTURE_3D;
    assert(!create_sampler_view(&ctx.base, &texture.base, &templ));
    templ.target = PIPE_TEXTURE_2D; templ.u.tex.last_layer = 1;
    assert(!create_sampler_view(&ctx.base, &texture.base, &templ));
    templ.u.tex.last_layer = 0; templ.u.tex.last_level = 1;
    assert(!create_sampler_view(&ctx.base, &texture.base, &templ));
    templ.u.tex.last_level = 0; templ.swizzle_a = PIPE_SWIZZLE_NONE;
    assert(!create_sampler_view(&ctx.base, &texture.base, &templ));
    templ.swizzle_a = PIPE_SWIZZLE_Z;
    view = create_sampler_view(&ctx.base, &texture.base, &templ);
    assert(view && texture.base.reference.count == 2);
    assert(sampler_texture_format(view) == OPENGPU_FORMAT_RGBA8);
    staged = prepare_sample_texture(&ctx, &source, 2, 2, view);
    assert(staged && staged != &source && staged->bytes == 16);
    assert(allocations == 1 && invalidations == 1);
    assert(!memcmp(staged->data, expected, sizeof(expected)));

    /* A failed producer fence cannot return a stale staging image. */
    pixels[2] = 9;
    fail_invalidation = 1;
    assert(!prepare_sample_texture(&ctx, &source, 2, 2, view));
    fail_invalidation = 0; fail_wait = 1;
    assert(!prepare_sample_texture(&ctx, &source, 2, 2, view));
    fail_wait = 0;
    assert(prepare_sample_texture(&ctx, &source, 2, 2, view) == staged);
    assert(staged->data[1] == 9 && allocations == 1);

    view->swizzle_r = PIPE_SWIZZLE_X; view->swizzle_g = PIPE_SWIZZLE_Y;
    view->swizzle_b = PIPE_SWIZZLE_Z; view->swizzle_a = PIPE_SWIZZLE_W;
    assert(sampler_texture_format(view) == OPENGPU_FORMAT_BGRX8);
    source.pitch = 8; source.bytes = 16;
    assert(prepare_sample_texture(&ctx, &source, 2, 2, view) == &source);
    source.bytes = 15;
    assert(!prepare_sample_texture(&ctx, &source, 2, 2, view));
    assert(!prepare_sample_texture(&ctx, NULL, 2, 2, view));
    set_sampler_views(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, 0, false, &view);
    assert(ctx.fs_view == view && view->reference.count == 2);
    {
        struct opengpu_context foreign = {0};
        struct pipe_sampler_view bad = {.context = &foreign.base, .texture = &texture.base};
        struct pipe_sampler_view *badp = &bad;
        bad.reference.count = 1;
        set_sampler_views(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, 0, false, &badp);
        assert(!ctx.fs_view && view->reference.count == 1 && bad.reference.count == 1);
    }
    /* A transferred reference replaces the current slot exactly once. */
    set_sampler_views(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, 0, true, &view);
    assert(ctx.fs_view == view && view->reference.count == 1);
    view = NULL; /* The reference now belongs to the context. */
    pipe_sampler_view_reference(&ctx.fs_view, NULL);
    assert(texture.base.reference.count == 1);
    pipe_opengpu_resource_destroy(opengpu_screen_gpu(&screen), ctx.sample_upload);
    assert(frees == allocations);
    puts("Gallium sampler view and swizzle staging: PASS");
    return 0;
}
