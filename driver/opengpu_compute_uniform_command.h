/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_UNIFORM_COMMAND_H
#define OPENGPU_COMPUTE_UNIFORM_COMMAND_H
#include "opengpu_compute_features.h"
#include "opengpu_compute_uniform_command_abi.h"

static inline bool opengpu_read_compute_uniform_command_support(u32 flags,
    unsigned long long mmio_bytes, void *context, u32 (*read_reg)(void *, u32), bool *supported)
{
    if (!supported) return false;
    *supported = false;
    if (!(flags & OPENGPU_COMPUTE_RESOURCE_UNIFORM_COMMANDS)) return true;
    if (!read_reg || mmio_bytes < OPENGPU_COMPUTE_UNIFORM_COMMAND_END ||
        read_reg(context, OPENGPU_COMPUTE_UNIFORM_COMMAND_REG_MAGIC) !=
            OPENGPU_COMPUTE_UNIFORM_COMMAND_MAGIC_VALUE) return false;
    *supported = true;
    return true;
}

struct opengpu_compute_uniform_command_context {
    u32 version, root, context, pass, generation, private_base;
    unsigned long long private_limit;
};

/* Encode without changing output on rejection. The caller pins the root,
 * backing data and VM until the software completion is popped. */
static inline bool opengpu_encode_compute_uniform_command_context(
    u32 words[OPENGPU_COMPUTE_UNIFORM_COMMAND_WORDS],
    const struct opengpu_compute_uniform_command_context *u)
{
    if (!words || !u || u->version != 1 || (u->root & 63u) ||
        (unsigned long long)u->root + 64 > (1ULL << 32) ||
        u->private_limit > (1ULL << 32) ||
        (u->private_limit ? u->private_limit <= u->private_base : u->private_base != 0))
        return false;
    words[0] = OPENGPU_COMPUTE_UNIFORM_COMMAND_ENABLE |
        (u->version << OPENGPU_COMPUTE_UNIFORM_COMMAND_VERSION_SHIFT);
    words[1] = u->root; words[2] = u->context; words[3] = u->pass; words[4] = u->generation;
    words[5] = u->private_base; words[6] = (u32)u->private_limit; words[7] = (u32)(u->private_limit >> 32);
    return true;
}

/* Serialize under submit_lock; disable a previous publication before issuing
 * a command without uniforms. CONTROL is written last when enabling. */
static inline void opengpu_write_compute_uniform_command(void *context,
    void (*write_reg)(void *, u32, u32), const u32 *words)
{
    if (words) {
        for (u32 i = 1; i < OPENGPU_COMPUTE_UNIFORM_COMMAND_WORDS; ++i)
            write_reg(context, OPENGPU_COMPUTE_UNIFORM_COMMAND_BASE + 4u * i, words[i]);
    }
    write_reg(context, OPENGPU_COMPUTE_UNIFORM_COMMAND_REG_CONTROL, words ? words[0] : 0);
}
#endif
