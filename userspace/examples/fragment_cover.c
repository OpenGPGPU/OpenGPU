/* SPDX-License-Identifier: MIT */
/* Guest pixels for the fragment programs in opengpu_fragment_cover.h.
 * sqrt and clamp paint one colour. gl_FragCoord.y splits the 64x64
 * triangle at y=32. The uniform if takes each side on its own submit.
 * KILL_IF drops the lanes below y=32, so the kept count is smaller than
 * the full triangle. Requires a fragment core, the scalar FPU, and no
 * vertex core. */
#include "../opengpu.h"
#include "../gallium/opengpu_fragment_cover.h"
#include "../gallium/opengpu_shader_abi.h"
#include "opengpu_shader_resources.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

enum {
    COVER_SOLID = 0,
    COVER_SPLIT = 1,
    COVER_PARTIAL = 2
};

static int cover_one(const char *name, const uint32_t *source, size_t source_words,
                     const uint32_t *uniform, size_t uniform_bytes, int mode,
                     uint32_t expect_a, uint32_t expect_b, int argc, char **argv)
{
    struct opengpu_buffer commands = { 0 }, color = { 0 };
    struct opengpu_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct drm_opengpu_draw *draw;
    uint32_t image[OPENGPU_ABI_TARGET_WORDS];
    uint32_t context = 0, fence = 0, config = 0;
    uint32_t width = 0, height = 0;
    uint32_t banks, stride, uniform_off, kernarg_bytes, gem_bytes, bank;
    uint64_t caps, features = 0;
    const uint32_t *program = source;
    size_t program_words = source_words;
    int fd = -1, status = 1;
    unsigned painted = 0, other = 0;

    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_capabilities(fd, &caps))
        goto done;
    if (!(caps & OPENGPU_CAP_FRAGMENT_CORE)) {
        printf("%s skipped; needs OPENGPU_CAP_FRAGMENT_CORE\n", name);
        close(fd);
        return 0;
    }
    if (caps & OPENGPU_CAP_VERTEX_CORE) {
        fprintf(stderr, "%s needs a fragment-only device\n", name);
        close(fd);
        return 2;
    }
    if (!(caps & OPENGPU_CAP_COMPUTE_SCALAR_FPU)) {
        printf("%s skipped; needs OPENGPU_CAP_COMPUTE_SCALAR_FPU\n", name);
        close(fd);
        return 0;
    }
    if (opengpu_display_size(fd, &width, &height) || !width || !height ||
        width > UINT32_MAX / 4u / height) {
        errno = EINVAL;
        goto done;
    }
    if ((mode == COVER_SPLIT || mode == COVER_PARTIAL) &&
        (width != 64 || height != 64)) {
        fprintf(stderr, "%s expects a 64x64 framebuffer, got %ux%u\n",
                name, width, height);
        errno = EINVAL;
        goto done;
    }
    if ((caps & OPENGPU_CAP_RUNTIME_SHADER_ABI) &&
        opengpu_shader_lower_runtime_abi(source, source_words, false, image,
                                          OPENGPU_ABI_TARGET_WORDS,
                                          &program_words)) {
        errno = EINVAL;
        goto done;
    }
    if (caps & OPENGPU_CAP_RUNTIME_SHADER_ABI)
        program = image;
    gem_bytes = ((uint32_t)program_words * 4u + 63u) & ~63u;
    if (opengpu_shader_config(fd, &config))
        config = 0;
    banks = opengpu_shader_resource_banks(config);
    stride = opengpu_shader_fragment_bank_stride(config);
    uniform_off = opengpu_shader_fragment_uniform_offset(config);
    if (!banks || !stride || uniform_off + uniform_bytes > stride ||
        stride > UINT32_MAX / banks) {
        errno = EINVAL;
        goto done;
    }
    kernarg_bytes = (stride * banks + 63u) & ~63u;
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, sizeof(struct drm_opengpu_draw), &commands) ||
        opengpu_buffer_create(fd, width * height * 4u, &color) ||
        opengpu_buffer_create(fd, gem_bytes, &shader) ||
        opengpu_buffer_create(fd, kernarg_bytes, &kernarg) ||
        opengpu_sync_create(fd, &fence))
        goto done;

    memset(shader.map, 0, shader.size);
    memcpy(shader.map, program, program_words * sizeof(uint32_t));
    memset(kernarg.map, 0, kernarg.size);
    for (bank = 0; bank < banks; bank++) {
        memcpy((uint8_t *)kernarg.map + (size_t)bank * stride + uniform_off,
               uniform, uniform_bytes);
    }

    binding.context_id = context;
    binding.slot = 2;
    binding.handle = shader.handle;
    binding.type = OPENGPU_RESOURCE_SHADER;
    binding.size = gem_bytes;
    if (opengpu_bind(fd, &binding))
        goto done;
    binding.slot = 3;
    binding.handle = kernarg.handle;
    binding.type = OPENGPU_RESOURCE_KERNARG;
    binding.size = kernarg_bytes;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        goto done;

    draw = commands.map;
    memset(draw, 0, sizeof(*draw));
    draw->v0[0] = -0x10000;
    draw->v0[1] = -0x10000;
    draw->v1[0] = 0x10000;
    draw->v1[1] = -0x10000;
    draw->v2[0] = -0x10000;
    draw->v2[1] = 0x10000;
    draw->v0[3] = draw->v1[3] = draw->v2[3] = 0x10000;
    draw->c0[0] = draw->c1[0] = draw->c2[0] = 0xfe;
    draw->c0[1] = draw->c1[1] = draw->c2[1] = 0x00;
    draw->c0[2] = draw->c1[2] = draw->c2[2] = 0xff;
    draw->d0 = draw->d1 = draw->d2 = 0x10;

    submit.context_id = context;
    submit.command_handle = commands.handle;
    submit.color_handle = color.handle;
    submit.stride = width * 4u;
    submit.command_count = 1;
    submit.shader_slot = 2;
    submit.kernarg_slot = 3;
    submit.out_syncobj = fence;
    if (!opengpu_driver_features(fd, &features) &&
        (caps & OPENGPU_CAP_FRAGMENT_INPUT_USAGE) &&
        (caps & OPENGPU_CAP_FRAGMENT_OUTPUT_USAGE) &&
        (features & OPENGPU_DRIVER_FEATURE_TRANSIENT_FRAGMENT_STAGING))
        submit.flags |= OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING;
    if (opengpu_render(fd, &submit) ||
        opengpu_sync_wait_success(fd, fence, OPENGPU_FENCE_TIMEOUT_MS))
        goto done;

    for (uint32_t i = 0; i < width * height; i++) {
        uint32_t pixel = ((uint32_t *)color.map)[i];

        if (pixel == 0)
            continue;
        if (pixel == expect_a)
            painted++;
        else if (mode == COVER_SPLIT && pixel == expect_b)
            other++;
        else {
            fprintf(stderr, "%s pixel %u got 0x%08x\n", name, i, pixel);
            errno = EIO;
            goto done;
        }
    }
    if (mode == COVER_SOLID) {
        if (!painted) {
            fprintf(stderr, "%s rendered no coloured pixels\n", name);
            errno = EIO;
            goto done;
        }
        printf("%s completed; pixels: %u sample=0x%08x\n", name, painted,
               expect_a);
    } else if (mode == COVER_SPLIT) {
        if (painted < 16 || other < 16) {
            fprintf(stderr, "%s split below=%u above=%u\n", name, painted,
                    other);
            errno = EIO;
            goto done;
        }
        printf("%s completed; below=%u above=%u\n", name, painted, other);
    } else {
        if (painted < 16 || painted > 1900) {
            fprintf(stderr, "%s kept %u pixels, want a partial triangle\n",
                    name, painted);
            errno = EIO;
            goto done;
        }
        printf("%s completed; kept=%u sample=0x%08x\n", name, painted,
               expect_a);
    }
    status = 0;
done:
    if (status && status != 2)
        perror(name);
    if (fd >= 0) {
        if (fence)
            opengpu_sync_destroy(fd, fence);
        if (context)
            opengpu_context_destroy(fd, context);
        if (kernarg.handle)
            opengpu_buffer_destroy(fd, &kernarg);
        if (shader.handle)
            opengpu_buffer_destroy(fd, &shader);
        if (color.handle)
            opengpu_buffer_destroy(fd, &color);
        if (commands.handle)
            opengpu_buffer_destroy(fd, &commands);
        close(fd);
    }
    return status;
}

int main(int argc, char **argv)
{
    int status;

    status = cover_one("fragment_sqrt", opengpu_fragment_sqrt,
                       OPENGPU_FRAGMENT_SQRT_WORDS,
                       opengpu_fragment_sqrt_uniform,
                       sizeof(opengpu_fragment_sqrt_uniform), COVER_SOLID,
                       OPENGPU_FRAGMENT_SQRT_EXPECTED, 0, argc, argv);
    if (status)
        return status;
    status = cover_one("fragment_coord", opengpu_fragment_coord,
                       OPENGPU_FRAGMENT_COORD_WORDS,
                       opengpu_fragment_coord_uniform,
                       sizeof(opengpu_fragment_coord_uniform), COVER_SPLIT,
                       OPENGPU_FRAGMENT_COORD_BELOW,
                       OPENGPU_FRAGMENT_COORD_ABOVE, argc, argv);
    if (status)
        return status;
    status = cover_one("fragment_if_then", opengpu_fragment_if,
                       OPENGPU_FRAGMENT_IF_WORDS,
                       opengpu_fragment_if_then_uniform,
                       sizeof(opengpu_fragment_if_then_uniform), COVER_SOLID,
                       OPENGPU_FRAGMENT_IF_THEN, 0, argc, argv);
    if (status)
        return status;
    status = cover_one("fragment_if_else", opengpu_fragment_if,
                       OPENGPU_FRAGMENT_IF_WORDS,
                       opengpu_fragment_if_else_uniform,
                       sizeof(opengpu_fragment_if_else_uniform), COVER_SOLID,
                       OPENGPU_FRAGMENT_IF_ELSE, 0, argc, argv);
    if (status)
        return status;
    status = cover_one("fragment_kill", opengpu_fragment_kill,
                     OPENGPU_FRAGMENT_KILL_WORDS,
                     opengpu_fragment_kill_uniform,
                     sizeof(opengpu_fragment_kill_uniform), COVER_PARTIAL,
                     OPENGPU_FRAGMENT_KILL_EXPECTED, 0, argc, argv);
    if (status)
        return status;
    return cover_one("fragment_clamp", opengpu_fragment_clamp,
                     OPENGPU_FRAGMENT_CLAMP_WORDS,
                     opengpu_fragment_clamp_uniform,
                     sizeof(opengpu_fragment_clamp_uniform), COVER_SOLID,
                     OPENGPU_FRAGMENT_CLAMP_EXPECTED, 0, argc, argv);
}
