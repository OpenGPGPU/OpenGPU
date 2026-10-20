/* SPDX-License-Identifier: MIT */
/* Gallium spike: vertex-core draw. Both shaders are clang --target=opengpu
 * -O2 .text: vertex_passthrough copies eight slices, fragment_texture
 * samples and stores dFdx(U). pipe_opengpu_bind_* copies these words
 * without the runtime ABI lowerer; this device's slice stride is 32. */
#include "../pipe_opengpu.h"
#include "../gallium/opengpu_shaders.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum { TEX_WORDS = 21 };

struct vertex {
    int32_t x, y, z, w;
    uint32_t color;
    int32_t depth;
    uint32_t u, v;
};

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *color = NULL, *tex = NULL, *vb = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_vertex_draw draw;
    struct vertex verts[3] = {
        { -0x10000, -0x10000, 0, 0x10000, 0x101010ffu, 0x10, 0, 0 },
        { 0x10000, -0x10000, 0, 0x10000, 0x202020ffu, 0x10, 0x80000, 0 },
        { -0x10000, 0x10000, 0, 0x10000, 0x303030ffu, 0x10, 0, 0x80000 },
    };
    uint32_t *texels;
    uint64_t caps;
    unsigned painted = 0, i;
    uint32_t sample = 0;
    uint32_t width = 0, height = 0;
    int status = 1;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    if (!(caps & OPENGPU_CAP_VERTEX_CORE) ||
        !(caps & OPENGPU_CAP_FRAGMENT_CORE)) {
        puts("pipe vertex_draw skipped; needs vertex+fragment cores");
        status = 0;
        goto done;
    }
    if (pipe_opengpu_screen_display_size(screen, &width, &height))
        goto done;
    verts[1].u = (width / 2) << 16;
    verts[2].v = (height / 2) << 16;

    ctx = pipe_opengpu_context_create(screen);
    color = pipe_opengpu_resource_create_2d(screen, width, height);
    tex = pipe_opengpu_resource_create(screen, TEX_WORDS * 4u);
    vb = pipe_opengpu_resource_create(screen, sizeof(verts));
    if (!ctx || !color || !tex || !vb)
        goto done;

    texels = pipe_opengpu_resource_map(tex);
    for (i = 0; i < 16; i++)
        texels[i] = 0x00ff00ffu;
    for (i = 16; i < 20; i++)
        texels[i] = 0xff0000ffu;
    texels[20] = 0x0000ffffu;
    memcpy(pipe_opengpu_resource_map(vb), verts, sizeof(verts));

    pipe_opengpu_set_framebuffer(ctx, color, width, height);
    if (pipe_opengpu_bind_fs(ctx, opengpu_fragment_texture,
                             sizeof(opengpu_fragment_texture)) ||
        pipe_opengpu_bind_vs(ctx, opengpu_vertex_passthrough,
                             sizeof(opengpu_vertex_passthrough)) ||
        pipe_opengpu_set_vertex_buffer(ctx, vb, sizeof(struct vertex),
                                       sizeof(verts)) ||
        pipe_opengpu_bind_texture(ctx, tex, 4, 4, TEX_WORDS * 4u,
                                  OPENGPU_RESOURCE_TEXTURE_CLAMP |
                                      (2u << OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT) |
                                      OPENGPU_RESOURCE_UNCACHED))
        goto done;

    if (pipe_opengpu_clear(ctx, 0x000000ffu, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    memset(&draw, 0, sizeof(draw));
    draw.vertex_count = 3;
    draw.vertex_stride = sizeof(struct vertex);
    draw.fragment_kernarg_bank_stride = 320;
    draw.state = OPENGPU_DRAW_STATE_OVERRIDE |
        OPENGPU_DRAW_STATE_DEPTH_TEST |
        OPENGPU_DRAW_STATE_DEPTH_WRITE |
        OPENGPU_DRAW_STATE_TEX_ENABLE |
        OPENGPU_DRAW_STATE_TEX_CLAMP |
        (2u << OPENGPU_DRAW_STATE_MAX_MIP_SHIFT);
    draw.blend_config = OPENGPU_DRAW_BLEND_PRESENT |
        (1u << OPENGPU_DRAW_BLEND_SRC_SHIFT);

    if (pipe_opengpu_draw_vertex(ctx, &draw, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    for (i = 0; i < width * height; i++) {
        uint32_t pixel = ((uint32_t *)pipe_opengpu_resource_map(color))[i];

        if (pixel == 0x000000ffu)
            continue;
        if (!painted)
            sample = pixel;
        else if (pixel != sample) {
            fprintf(stderr, "inconsistent vertex-draw pixels\n");
            errno = EIO;
            goto done;
        }
        painted++;
    }
    if (!painted || sample != 0xff0000ffu) {
        fprintf(stderr, "vertex_draw coloured=%u sample=0x%08x\n", painted,
                sample);
        errno = EIO;
        goto done;
    }
    printf("pipe vertex_draw completed; coloured=%u sample=0x%08x\n", painted,
           sample);
    status = 0;
done:
    if (status)
        perror("pipe_vertex_draw");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (vb)
        pipe_opengpu_resource_destroy(screen, vb);
    if (tex)
        pipe_opengpu_resource_destroy(screen, tex);
    if (color)
        pipe_opengpu_resource_destroy(screen, color);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
