/* SPDX-License-Identifier: MIT */
/* Vertex program the Gallium menu does not classify. The words in
 * opengpu_vertex_alu.h copy eight slices and vadd.vx a uniform onto
 * position x. The uniform is zero, so the triangle stays on the vertex
 * buffer. fragment_color copies the vertex colour, and every covered
 * pixel is 0xff0000ff. Needs both cores. The runtime ABI lowerer rewrites
 * the VL=4 vsetivli and the uniform load, and leaves the vadd.vx word. */
#include "../pipe_opengpu.h"
#include "../gallium/opengpu_shaders.h"
#include "../gallium/opengpu_shader_abi.h"
#include "../gallium/opengpu_vertex_alu.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

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
    struct pipe_opengpu_resource *color = NULL, *vb = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_vertex_draw draw;
    struct vertex verts[3] = {
        { -0x10000, -0x10000, 0, 0x10000, 0xff0000ffu, 0x10, 0, 0 },
        { 0x10000, -0x10000, 0, 0x10000, 0xff0000ffu, 0x10, 0, 0 },
        { -0x10000, 0x10000, 0, 0x10000, 0xff0000ffu, 0x10, 0, 0 },
    };
    uint32_t image[OPENGPU_ABI_TARGET_WORDS];
    const uint32_t *program = opengpu_vertex_alu;
    size_t program_words = OPENGPU_VERTEX_ALU_WORDS;
    float matrix[16];
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
        puts("vertex_alu skipped; needs vertex+fragment cores");
        status = 0;
        goto done;
    }
    if (pipe_opengpu_screen_display_size(screen, &width, &height))
        goto done;
    if ((caps & OPENGPU_CAP_RUNTIME_SHADER_ABI) &&
        opengpu_shader_lower_runtime_abi(opengpu_vertex_alu,
                                          OPENGPU_VERTEX_ALU_WORDS, true,
                                          image, OPENGPU_ABI_TARGET_WORDS,
                                          &program_words)) {
        errno = EINVAL;
        goto done;
    }
    if (caps & OPENGPU_CAP_RUNTIME_SHADER_ABI)
        program = image;

    ctx = pipe_opengpu_context_create(screen);
    color = pipe_opengpu_resource_create_2d(screen, width, height);
    vb = pipe_opengpu_resource_create(screen, sizeof(verts));
    if (!ctx || !color || !vb)
        goto done;
    memcpy(pipe_opengpu_resource_map(vb), verts, sizeof(verts));

    pipe_opengpu_set_framebuffer(ctx, color, width, height);
    if (pipe_opengpu_bind_fs(ctx, opengpu_fragment_color,
                             sizeof(opengpu_fragment_color)) ||
        pipe_opengpu_bind_vs(ctx, program, program_words * sizeof(uint32_t)) ||
        pipe_opengpu_set_vertex_buffer(ctx, vb, sizeof(struct vertex),
                                       sizeof(verts)))
        goto done;
    memset(matrix, 0, sizeof(matrix));
    if (pipe_opengpu_write_vs_matrix(ctx, matrix))
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
        OPENGPU_DRAW_STATE_DEPTH_WRITE;
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
            fprintf(stderr, "inconsistent vertex_alu pixels\n");
            errno = EIO;
            goto done;
        }
        painted++;
    }
    if (!painted || sample != 0xff0000ffu) {
        fprintf(stderr, "vertex_alu coloured=%u sample=0x%08x\n", painted,
                sample);
        errno = EIO;
        goto done;
    }
    printf("vertex_alu completed; coloured=%u sample=0x%08x\n", painted,
           sample);
    status = 0;
done:
    if (status)
        perror("vertex_alu");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (vb)
        pipe_opengpu_resource_destroy(screen, vb);
    if (color)
        pipe_opengpu_resource_destroy(screen, color);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
