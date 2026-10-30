/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_TEXTURE_COMMAND_H
#define OPENGPU_COMPUTE_TEXTURE_COMMAND_H
#include "opengpu_compute_features.h"
#include "opengpu_compute_texture_command_abi.h"

static inline bool opengpu_read_compute_texture_command_support(u32 flags,
    unsigned long long mmio_bytes, void *context, u32 (*read_reg)(void *, u32), bool *supported)
{
    if (!supported) return false;
    *supported = false;
    if (!(flags & OPENGPU_COMPUTE_RESOURCE_TEXTURE_COMMANDS)) return true;
    if (!read_reg || mmio_bytes < OPENGPU_COMPUTE_TEXTURE_COMMAND_END ||
        read_reg(context, OPENGPU_COMPUTE_TEXTURE_COMMAND_REG_MAGIC) !=
            OPENGPU_COMPUTE_TEXTURE_COMMAND_MAGIC_VALUE) return false;
    *supported = true;
    return true;
}

struct opengpu_compute_texture_command_context {
    u32 version, root, context, pass, generation;
};

/* The caller retains the records, backing data and VM through completion pop. */
static inline bool opengpu_encode_compute_texture_command_context(
    u32 words[OPENGPU_COMPUTE_TEXTURE_COMMAND_WORDS],
    const struct opengpu_compute_texture_command_context *t)
{
    if (!words || !t || t->version != 1 || (t->root & 63u) ||
        (unsigned long long)t->root + 128 > (1ULL << 32)) return false;
    words[0] = OPENGPU_COMPUTE_TEXTURE_COMMAND_ENABLE |
        (t->version << OPENGPU_COMPUTE_TEXTURE_COMMAND_VERSION_SHIFT);
    words[1] = t->root; words[2] = t->context; words[3] = t->pass; words[4] = t->generation;
    return true;
}
#endif
