/* SPDX-License-Identifier: MIT */
/* Native DRM regression: feature discovery, per-record admission, identical
 * pixels with/without discard, and persistent clear/load compatibility. */
#include "../opengpu.h"
#include "../gallium/opengpu_shader_abi.h"
#include "../gallium/opengpu_shaders.h"
#include "../../driver/opengpu_shader_resources.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define CHECK(x) do { if ((x) < 0) { perror(#x); return 1; } } while (0)
#define WORDS(x) (sizeof(x) / sizeof((x)[0]))
#define SIDE 64u
#define RED 0xff0000ffu

static int bind_buffer(int fd, uint32_t context, uint32_t slot,
                       uint32_t type, const struct opengpu_buffer *bo)
{
    struct drm_opengpu_resource r = {
        .context_id = context, .slot = slot, .type = type,
        .handle = bo->handle, .size = bo->size,
    };
    return opengpu_bind(fd, &r);
}

static int fill(int fd, uint32_t context, struct opengpu_buffer *bo,
                uint32_t pattern, uint32_t sync)
{
    struct drm_opengpu_fill f = {
        .context_id = context, .destination_handle = bo->handle,
        .bytes = SIDE * SIDE * 4u, .pattern = pattern, .out_syncobj = sync,
    };
    if (opengpu_fill(fd, &f)) return -1;
    return opengpu_sync_wait_success(fd, sync, 1800000);
}

static int rejected(int fd, struct drm_opengpu_submit *s)
{
    errno = 0;
    if (opengpu_render(fd, s) == -1 && errno == EINVAL) return 0;
    fprintf(stderr, "private depth discard accepted invalid flags/state (errno=%d)\n", errno);
    return -1;
}

int main(void)
{
    struct opengpu_buffer command = {0}, color = {0}, depth = {0};
    struct opengpu_buffer vb = {0}, vs = {0}, fs = {0}, vk = {0}, fk = {0};
    struct drm_opengpu_vertex_draw *draw;
    struct drm_opengpu_submit s = {0};
    struct vertex { int32_t x,y,z,w; uint32_t color,depth,u,v; } vertices[] = {
        {-65536,-65536,0,65536,RED,16,0,0},
        { 65536,-65536,0,65536,RED,16,0,0},
        {-65536, 65536,0,65536,RED,16,0,0},
    };
    uint32_t config, context, sync, fstride, vstride;
    uint64_t features;
    size_t words;
    uint32_t baseline[SIDE * SIDE];
    unsigned i, j, painted = 0, empty = 0;
    int fd = opengpu_open(NULL);
    CHECK(fd);
    CHECK(opengpu_driver_features(fd, &features));
    if (!(features & OPENGPU_DRIVER_FEATURE_DISCARD_PRIVATE_DEPTH)) {
        puts("OPENGPU PRIVATE DEPTH DISCARD: unsupported driver");
        return 77;
    }
    CHECK(opengpu_shader_config(fd, &config));
    CHECK(opengpu_context_create(fd, &context));
    CHECK(opengpu_sync_create(fd, &sync));
    fstride = opengpu_shader_fragment_bank_stride(config);
    vstride = opengpu_shader_vertex_kernarg_bytes(config);
    CHECK(opengpu_buffer_create(fd, 2 * sizeof(*draw), &command));
    CHECK(opengpu_buffer_create(fd, SIDE * SIDE * 4u, &color));
    CHECK(opengpu_buffer_create(fd, SIDE * SIDE * 4u, &depth));
    CHECK(opengpu_buffer_create(fd, sizeof(vertices), &vb));
    CHECK(opengpu_buffer_create(fd, 2048, &vs));
    CHECK(opengpu_buffer_create(fd, 2048, &fs));
    CHECK(opengpu_buffer_create(fd, 2 * vstride, &vk));
    CHECK(opengpu_buffer_create(fd, opengpu_shader_resource_banks(config) * fstride, &fk));
    CHECK(opengpu_shader_lower_runtime_abi(opengpu_vertex_passthrough,
        WORDS(opengpu_vertex_passthrough), true, vs.map, 512, &words));
    CHECK(opengpu_shader_lower_runtime_abi(opengpu_fragment_color,
        WORDS(opengpu_fragment_color), false, fs.map, 512, &words));
    memcpy(vb.map, vertices, sizeof(vertices));
    CHECK(bind_buffer(fd, context, 1, OPENGPU_RESOURCE_VERTEX_BUFFER, &vb));
    CHECK(bind_buffer(fd, context, 2, OPENGPU_RESOURCE_VERTEX_SHADER, &vs));
    CHECK(bind_buffer(fd, context, 3, OPENGPU_RESOURCE_VERTEX_KERNARG, &vk));
    CHECK(bind_buffer(fd, context, 4, OPENGPU_RESOURCE_SHADER, &fs));
    CHECK(bind_buffer(fd, context, 5, OPENGPU_RESOURCE_KERNARG, &fk));
    draw = command.map;
    draw[0] = (struct drm_opengpu_vertex_draw) {
        .vertex_count = 3, .vertex_stride = sizeof(vertices[0]),
        .vertex_kernarg_bank_stride = vstride,
        .fragment_kernarg_bank_stride = fstride,
        .state = OPENGPU_DRAW_STATE_OVERRIDE,
    };
    draw[1] = draw[0];
    s = (struct drm_opengpu_submit) {
        .context_id = context, .command_handle = command.handle,
        .color_handle = color.handle, .stride = SIDE * 4u,
        .command_count = 2, .flags = OPENGPU_SUBMIT_VERTEX_CORE |
                                    OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH,
        .vertex_buffer_slot = 1, .vertex_shader_slot = 2,
        .vertex_kernarg_slot = 3, .shader_slot = 4, .kernarg_slot = 5,
        .out_syncobj = sync, .render_width = SIDE, .render_height = SIDE,
    };
    s.flags &= ~OPENGPU_SUBMIT_VERTEX_CORE;
    CHECK(rejected(fd, &s));
    s.flags |= OPENGPU_SUBMIT_VERTEX_CORE;
    s.depth_handle = depth.handle;
    CHECK(rejected(fd, &s));
    s.depth_handle = 0;
    s.flags |= OPENGPU_SUBMIT_DEPTH_LOAD;
    CHECK(rejected(fd, &s));
    s.flags &= ~OPENGPU_SUBMIT_DEPTH_LOAD;
    for (i = 0; i < 2; i++) {
        const uint32_t invalid[] = {0,
            OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_DEPTH_TEST,
            OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_DEPTH_WRITE,
            OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_STENCIL_TEST};
        for (j = 0; j < WORDS(invalid); j++) {
            draw[i].state = invalid[j];
            CHECK(rejected(fd, &s));
        }
        draw[i].state = OPENGPU_DRAW_STATE_OVERRIDE;
    }
    /* Default private clear is the pixel reference. Poison/clear the colour
     * via the ordered fill engine before every render, avoiding CPU/L2 races. */
    for (j = 0; j < 2; j++) {
        /* The 7x5 case has a 140-byte private depth plane, exercising the
         * CPU clear fallback as well as the ordinary cache-line HW path. */
        s.render_width = j ? 7 : SIDE;
        s.render_height = j ? 5 : SIDE;
        s.stride = s.render_width * 4u;
        s.flags &= ~OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH;
        CHECK(fill(fd, context, &color, 0, sync));
        CHECK(opengpu_render(fd, &s));
        CHECK(opengpu_sync_wait_success(fd, sync, 1800000));
        memcpy(baseline, color.map, sizeof(baseline));
        painted = empty = 0;
        for (i = 0; i < WORDS(baseline); i++) {
            if (baseline[i] == RED) painted++;
            else if (!baseline[i]) empty++;
            else { fprintf(stderr, "unexpected reference pixel %08x\n", baseline[i]); return 1; }
        }
        if (!painted || !empty) return 1;
        s.flags |= OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH;
        CHECK(fill(fd, context, &color, 0, sync));
        CHECK(opengpu_render(fd, &s));
        CHECK(opengpu_sync_wait_success(fd, sync, 1800000));
        if (memcmp(baseline, color.map, sizeof(baseline))) return 1;
        printf("private depth layout %ux%u: %u painted pixels identical\n",
               s.render_width, s.render_height, painted);
    }
    s.render_width = s.render_height = SIDE;
    s.stride = SIDE * 4u;
    s.flags &= ~OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH;
    s.depth_handle = depth.handle;
    for (j = 0; j < 2; j++) {
        uint32_t expected = j ? 0x12345678u : 0x00ffffffu;
        CHECK(fill(fd, context, &depth, 0x12345678u, sync));
        if (j) s.flags |= OPENGPU_SUBMIT_DEPTH_LOAD;
        CHECK(opengpu_render(fd, &s));
        CHECK(opengpu_sync_wait_success(fd, sync, 1800000));
        for (i = 0; i < SIDE * SIDE; i++)
            if (((uint32_t *)depth.map)[i] != expected) return 1;
    }
    printf("OPENGPU PRIVATE DEPTH DISCARD PASS: 11 invalid submits rejected, "
           "64x64 HW and7x5 CPU clear references identical; persistent clear/load preserved\n");
    CHECK(opengpu_sync_destroy(fd, sync));
    CHECK(opengpu_context_destroy(fd, context));
    CHECK(opengpu_buffer_destroy(fd, &command));
    CHECK(opengpu_buffer_destroy(fd, &color));
    CHECK(opengpu_buffer_destroy(fd, &depth));
    CHECK(opengpu_buffer_destroy(fd, &vb));
    CHECK(opengpu_buffer_destroy(fd, &vs));
    CHECK(opengpu_buffer_destroy(fd, &fs));
    CHECK(opengpu_buffer_destroy(fd, &vk));
    CHECK(opengpu_buffer_destroy(fd, &fk));
    close(fd);
    return 0;
}
