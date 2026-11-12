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
    r->bytes = ((uint64_t)r->pitch * height + 63u) & ~UINT64_C(63);
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
    if (bytes & 63u) { errno = EINVAL; return -1; }
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
    uint8_t pixels[64] = { 33, 22, 11, 44, 77, 66, 55, 88, 0xaa, 0xaa, 0xaa, 0xaa,
                           123, 111, 99, 135, 171, 159, 147, 183, 0xaa, 0xaa, 0xaa, 0xaa };
    const uint8_t expected[16] = {255,11,0,33, 255,55,0,77,
                                  255,99,0,123, 255,147,0,171};
    struct pipe_opengpu_resource source = { pixels, 64, 2, 2, 12 };
    struct pipe_opengpu_resource *staged;

    ctx.base.screen = &screen;
    ctx.base.sampler_view_destroy = sampler_view_destroy;
    {
        struct pipe_sampler_state state = {0};
        void *handle, *slots[1];
        bool nearest = true, clamp_s = false, clamp_t = false;
        assert(!fragment_sampler_mode(&ctx, false, false, &nearest, &clamp_s, &clamp_t) &&
               !nearest && clamp_s && clamp_t);
        state.min_img_filter = state.mag_img_filter = PIPE_TEX_FILTER_NEAREST;
        handle = create_sampler_state(&ctx.base, &state);
        assert(handle);
        state.min_img_filter = state.mag_img_filter = PIPE_TEX_FILTER_LINEAR;
        slots[0] = handle;
        bind_sampler_states(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, slots);
        assert(fragment_sampler_mode(&ctx, false, false, &nearest, &clamp_s, &clamp_t) == -1 && errno == EOPNOTSUPP);
        assert(!fragment_sampler_mode(&ctx, true, false, &nearest, &clamp_s, &clamp_t) && nearest && !clamp_s && !clamp_t);
        delete_state(&ctx.base, handle); /* The context retains its copy. */
        assert(!fragment_sampler_mode(&ctx, true, false, &nearest, &clamp_s, &clamp_t) && nearest && !clamp_s && !clamp_t);
        state.min_img_filter = PIPE_TEX_FILTER_NEAREST;
        slots[0] = create_sampler_state(&ctx.base, &state); assert(slots[0]);
        bind_sampler_states(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, slots);
        assert(fragment_sampler_mode(&ctx, true, false, &nearest, &clamp_s, &clamp_t) == -1 && errno == EOPNOTSUPP);
        delete_state(&ctx.base, slots[0]);
        state.min_img_filter = state.mag_img_filter = PIPE_TEX_FILTER_LINEAR;
        state.wrap_s = state.wrap_t = PIPE_TEX_WRAP_CLAMP_TO_EDGE;
        slots[0] = create_sampler_state(&ctx.base, &state); assert(slots[0]);
        bind_sampler_states(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, slots);
        assert(!fragment_sampler_mode(&ctx, false, false, &nearest, &clamp_s, &clamp_t) && !nearest && clamp_s && clamp_t);
        delete_state(&ctx.base, slots[0]);
        state.wrap_t = PIPE_TEX_WRAP_REPEAT;
        slots[0] = create_sampler_state(&ctx.base, &state); assert(slots[0]);
        bind_sampler_states(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, slots);
        assert(fragment_sampler_mode(&ctx, true, false, &nearest, &clamp_s, &clamp_t) == -1 && errno == EOPNOTSUPP);
        assert(!fragment_sampler_mode(&ctx, true, true, &nearest, &clamp_s, &clamp_t) &&
               !nearest && clamp_s && !clamp_t);
        delete_state(&ctx.base, slots[0]);
        state.wrap_s = PIPE_TEX_WRAP_REPEAT;
        state.wrap_t = PIPE_TEX_WRAP_CLAMP_TO_EDGE;
        slots[0] = create_sampler_state(&ctx.base, &state); assert(slots[0]);
        bind_sampler_states(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, slots);
        assert(fragment_sampler_mode(&ctx, true, false, &nearest, &clamp_s, &clamp_t) == -1 && errno == EOPNOTSUPP);
        assert(!fragment_sampler_mode(&ctx, true, true, &nearest, &clamp_s, &clamp_t) &&
               !nearest && !clamp_s && clamp_t);
        delete_state(&ctx.base, slots[0]);
        state.wrap_s = state.wrap_t = PIPE_TEX_WRAP_MIRROR_REPEAT;
        slots[0] = create_sampler_state(&ctx.base, &state); assert(slots[0]);
        bind_sampler_states(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, slots);
        assert(fragment_sampler_mode(&ctx, true, true, &nearest, &clamp_s, &clamp_t) == -1 && errno == EOPNOTSUPP);
        delete_state(&ctx.base, slots[0]);
        state.wrap_s = state.wrap_t = PIPE_TEX_WRAP_REPEAT;
        state.unnormalized_coords = 1;
        slots[0] = create_sampler_state(&ctx.base, &state); assert(slots[0]);
        bind_sampler_states(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, slots);
        assert(fragment_sampler_mode(&ctx, true, true, &nearest, &clamp_s, &clamp_t) == -1 && errno == EOPNOTSUPP);
        delete_state(&ctx.base, slots[0]);
        slots[0] = NULL;
        bind_sampler_states(&ctx.base, PIPE_SHADER_FRAGMENT, 0, 1, slots);
        assert(!fragment_sampler_mode(&ctx, false, false, &nearest, &clamp_s, &clamp_t) && !nearest && clamp_s && clamp_t);
    }
    {
        struct hw_vertex vertices[3] = {
            { .u = (uint32_t)-32768, .v = (uint32_t)-81920 },
            { .u = 16384, .v = (uint32_t)-16384 },
            { .u = 81920, .v = 16384 },
        };
        struct hw_vertex rejected[3];
        memcpy(rejected, vertices, sizeof(vertices));
        assert(prepare_sampler_coordinates(rejected, 3, true, true, false, NULL) == -1);
        assert(prepare_sampler_coordinates(vertices, 2, false, false, false, NULL) == -1);
        assert(!prepare_sampler_coordinates(vertices, 3, false, false, false, NULL));
        assert(vertices[0].u == 32768 && vertices[1].u == 81920 && vertices[2].u == 147456);
        assert(vertices[0].v == 49152 && vertices[1].v == 114688 && vertices[2].v == 147456);
        memcpy(vertices, rejected, sizeof(vertices));
        for (unsigned i=0; i<3; ++i) vertices[i].v = 16384;
        assert(!prepare_sampler_coordinates(vertices, 3, false, true, false, NULL));
        assert(vertices[0].u == 32768 && vertices[2].u == 147456 && vertices[0].v == 16384);
        memcpy(vertices, rejected, sizeof(vertices));
        for (unsigned i=0; i<3; ++i) vertices[i].u = 16384;
        assert(!prepare_sampler_coordinates(vertices, 3, true, false, false, NULL));
        assert(vertices[0].u == 16384 && vertices[0].v == 49152 && vertices[2].v == 147456);
        /* A triangle wholly outside a clamped edge samples that edge even
         * with varying (or perspective-correct) coordinates. Keep the other
         * axis's repeat interpolation independent. */
        struct hw_vertex edge[3] = {
            { .u = (uint32_t)INT32_MIN, .v = (uint32_t)-16384 },
            { .u = (uint32_t)-1, .v = 16384 },
            { .u = 0, .v = 49152 },
        };
        assert(!prepare_sampler_coordinates(edge, 3, true, false, false, NULL));
        assert(edge[0].u == 0 && edge[1].u == 0 && edge[2].u == 0);
        assert(edge[0].v == 49152 && edge[1].v == 81920 && edge[2].v == 114688);
        struct hw_vertex both[3] = {
            { .u = (uint32_t)-65536, .v = 0 },
            { .u = 0, .v = (uint32_t)-32768 },
            { .u = (uint32_t)-1, .v = (uint32_t)INT32_MIN },
        };
        assert(!prepare_sampler_coordinates(both, 3, true, true, false, NULL));
        for (unsigned i=0; i<3; ++i)
            assert(both[i].u == 0 && both[i].v == 0);
        struct hw_vertex crosses[3] = {
            { .u = (uint32_t)-1, .v = 0 },
            { .u = 1, .v = 0 },
            { .u = 0, .v = 0 },
        };
        assert(prepare_sampler_coordinates(crosses, 3, true, true, false, NULL) == -1);
        uint32_t bias = 0;
        assert(!prepare_sampler_coordinates(crosses, 3, true, true, true, &bias));
        assert(bias == OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_MASK);
        assert(crosses[0].u == 0x7fffffffu && crosses[1].u == 0x80000001u &&
               crosses[2].u == 0x80000000u);
        for (unsigned i=0; i<3; ++i)
            assert(crosses[i].v == 0x80000000u);
        struct hw_vertex mixed[3] = {
            { .u = (uint32_t)INT32_MIN, .v = (uint32_t)-16384 },
            { .u = (uint32_t)INT32_MAX, .v = 16384 },
            { .u = 0, .v = 49152 },
        };
        assert(!prepare_sampler_coordinates(mixed, 3, true, false, true, &bias));
        assert(bias == OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_U);
        assert(mixed[0].u == 0 && mixed[1].u == UINT32_MAX && mixed[2].u == 0x80000000u);
        assert(mixed[0].v == 49152 && mixed[1].v == 81920 && mixed[2].v == 114688);
        assert(prepare_sampler_coordinates(mixed, 3, true, false, true, NULL) == -1);
    }
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
    assert(staged && staged != &source && staged->bytes == 64);
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
    /* Imported storage with a partial final cache line cannot be safely
     * invalidated and must not return an old staged image. */
    source.bytes = 20;
    assert(!prepare_sample_texture(&ctx, &source, 2, 2, view));

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
