/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SHADER_IMAGE_COMMAND_H
#define OPENGPU_SHADER_IMAGE_COMMAND_H
#include "opengpu_compute_features.h"
#include "opengpu_shader_image_command_abi.h"

/* Consume validated GCR1 flags first. Legacy/unadvertised profiles are never
 * probed. An advertised but truncated or malformed transport fails closed. */
static inline bool opengpu_read_image_command_support(u32 compute_flags,
    unsigned long long mmio_bytes, void *context,
    u32 (*read_reg)(void *, u32), bool *supported)
{
    if (!supported)
        return false;
    *supported = false;
    if (!(compute_flags & OPENGPU_COMPUTE_RESOURCE_IMAGE_COMMANDS))
        return true;
    if (!read_reg || mmio_bytes < OPENGPU_IMAGE_COMMAND_END ||
        read_reg(context, OPENGPU_IMAGE_COMMAND_REG_MAGIC) !=
            OPENGPU_IMAGE_COMMAND_MAGIC_VALUE)
        return false;
    *supported = true;
    return true;
}

struct opengpu_image_command_context {
    u32 version, base, context, pass, generation;
    unsigned long long limit;
};

/* Encode the seven staging words without touching output on rejection.
 * Publication, descriptor memory, VM and cross-client coherence remain owned
 * by the caller until the software-visible completion is popped. */
static inline bool opengpu_encode_image_command_context(
    u32 words[OPENGPU_IMAGE_COMMAND_WORDS],
    const struct opengpu_image_command_context *image)
{
    if (!words || !image || image->version != 1 || (image->base & 63u) ||
        (image->limit & 63u) || image->limit <= image->base ||
        image->limit > (1ULL << 32) ||
        (image->limit - image->base) % OPENGPU_IMAGE_COMMAND_DESCRIPTOR_BYTES)
        return false;
    words[0] = OPENGPU_IMAGE_COMMAND_ENABLE |
        (image->version << OPENGPU_IMAGE_COMMAND_VERSION_SHIFT);
    words[1] = image->base;
    words[2] = (u32)image->limit;
    words[3] = (u32)(image->limit >> 32);
    words[4] = image->context;
    words[5] = image->pass;
    words[6] = image->generation;
    return true;
}
#endif
