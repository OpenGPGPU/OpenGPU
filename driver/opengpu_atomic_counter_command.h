/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_ATOMIC_COUNTER_COMMAND_H
#define OPENGPU_ATOMIC_COUNTER_COMMAND_H
#include "opengpu_compute_features.h"
#include "opengpu_atomic_counter_command_abi.h"

/* Consume validated GCR1 flags first. Legacy/unadvertised profiles are never
 * probed. An advertised but truncated or malformed transport fails closed. */
static inline bool opengpu_read_atomic_counter_command_support(u32 compute_flags,
    unsigned long long mmio_bytes, void *context,
    u32 (*read_reg)(void *, u32), bool *supported)
{
    if (!supported)
        return false;
    *supported = false;
    if (!(compute_flags & OPENGPU_COMPUTE_RESOURCE_ATOMIC_COUNTER_COMMANDS))
        return true;
    if (!read_reg || mmio_bytes < OPENGPU_ATOMIC_COUNTER_COMMAND_END ||
        read_reg(context, OPENGPU_ATOMIC_COUNTER_COMMAND_REG_MAGIC) !=
            OPENGPU_ATOMIC_COUNTER_COMMAND_MAGIC_VALUE)
        return false;
    *supported = true;
    return true;
}

struct opengpu_atomic_counter_command_context {
    u32 version, base, context, pass, generation;
    unsigned long long limit;
};

/* Encode the seven staging words without touching output on rejection.
 * Publication, descriptor memory, VM and cross-client coherence remain owned
 * by the caller until the software-visible completion is popped. */
static inline bool opengpu_encode_atomic_counter_command_context(
    u32 words[OPENGPU_ATOMIC_COUNTER_COMMAND_WORDS],
    const struct opengpu_atomic_counter_command_context *storage)
{
    if (!words || !storage || storage->version != 1 || (storage->base & 63u) ||
        (storage->limit & 63u) || storage->limit <= storage->base ||
        storage->limit > (1ULL << 32) ||
        (storage->limit - storage->base) % OPENGPU_ATOMIC_COUNTER_COMMAND_DESCRIPTOR_BYTES)
        return false;
    words[0] = OPENGPU_ATOMIC_COUNTER_COMMAND_ENABLE |
        (storage->version << OPENGPU_ATOMIC_COUNTER_COMMAND_VERSION_SHIFT);
    words[1] = storage->base;
    words[2] = (u32)storage->limit;
    words[3] = (u32)(storage->limit >> 32);
    words[4] = storage->context;
    words[5] = storage->pass;
    words[6] = storage->generation;
    return true;
}
#endif
