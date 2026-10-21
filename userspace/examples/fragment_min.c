/* SPDX-License-Identifier: MIT */
/* Fragment program the Gallium menu does not classify. The words in
 * opengpu_fragment_min.h are what create_fs_state stores when TGSI is
 * gl_FragColor = min(CONST[0][0], CONST[0][1]), and bind_fs_state submits
 * them through the runtime ABI lowerer. CONST[0][0] is (1, 0, 1, 1) and
 * CONST[0][1] is (1, 1, 0, 1), so every covered pixel is 0xff0000ff.
 * Requires a fragment core and the scalar FPU (the emitter reads uniforms
 * with flw). A vertex core decodes this record as a vertex draw. */
#include "../opengpu.h"
#include "../gallium/opengpu_fragment_min.h"
#include "../gallium/opengpu_shader_abi.h"
#include "opengpu_shader_resources.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    struct opengpu_buffer commands = { 0 }, color = { 0 };
    struct opengpu_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct drm_opengpu_draw *draw;
    uint32_t image[OPENGPU_ABI_TARGET_WORDS];
    uint32_t context = 0, fence = 0, config = 0;
    uint32_t width = 0, height = 0;
    uint32_t banks, stride, uniform, kernarg_bytes, gem_bytes, bank;
    uint64_t caps, features = 0;
    const uint32_t *program = opengpu_fragment_min;
    size_t program_words = OPENGPU_FRAGMENT_MIN_WORDS;
    int fd = -1, status = 1;
    unsigned painted = 0;

    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_capabilities(fd, &caps))
        goto done;
    if (!(caps & OPENGPU_CAP_FRAGMENT_CORE)) {
        printf("fragment_min skipped; needs OPENGPU_CAP_FRAGMENT_CORE\n");
        close(fd);
        return 0;
    }
    if (caps & OPENGPU_CAP_VERTEX_CORE) {
        fprintf(stderr, "fragment_min needs a fragment-only device\n");
        close(fd);
        return 2;
    }
    if (!(caps & OPENGPU_CAP_COMPUTE_SCALAR_FPU)) {
        printf("fragment_min skipped; needs OPENGPU_CAP_COMPUTE_SCALAR_FPU\n");
        close(fd);
        return 0;
    }
    if (opengpu_display_size(fd, &width, &height) || !width || !height ||
        width > UINT32_MAX / 4u / height) {
        errno = EINVAL;
        goto done;
    }
    if ((caps & OPENGPU_CAP_RUNTIME_SHADER_ABI) &&
        opengpu_shader_lower_runtime_abi(opengpu_fragment_min,
                                          OPENGPU_FRAGMENT_MIN_WORDS, false,
                                          image, OPENGPU_ABI_TARGET_WORDS,
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
    uniform = opengpu_shader_fragment_uniform_offset(config);
    if (!banks || !stride || uniform + sizeof(opengpu_fragment_min_uniform) > stride ||
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
        memcpy((uint8_t *)kernarg.map + (size_t)bank * stride + uniform,
               opengpu_fragment_min_uniform, sizeof(opengpu_fragment_min_uniform));
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
        if (pixel != OPENGPU_FRAGMENT_MIN_EXPECTED) {
            fprintf(stderr, "fragment_min pixel %u got 0x%08x want 0x%08x\n",
                    i, pixel, OPENGPU_FRAGMENT_MIN_EXPECTED);
            errno = EIO;
            goto done;
        }
        painted++;
    }
    if (!painted) {
        fprintf(stderr, "fragment_min rendered no coloured pixels\n");
        errno = EIO;
        goto done;
    }
    printf("fragment_min completed; constant min pixels: %u sample=0x%08x\n",
           painted, OPENGPU_FRAGMENT_MIN_EXPECTED);
    status = 0;
done:
    if (status)
        perror("fragment_min");
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
