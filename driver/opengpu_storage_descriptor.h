/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_STORAGE_DESCRIPTOR_H
#define OPENGPU_STORAGE_DESCRIPTOR_H
#include "opengpu_compute_features.h"
#include "opengpu_storage_descriptor_abi.h"

#define OPENGPU_STORAGE_DESCRIPTOR_MAX_BYTES (128ULL * 1024 * 1024)

struct opengpu_storage_descriptor {
    u32 context, pass, generation, binding, base, allocation_base;
    bool readable, writable;
    unsigned long long limit, allocation_limit;
};

/* Encode a GSB1 record without changing output on rejection. Both allocation
 * endpoints cover complete 64-byte transport lines. */
static inline bool opengpu_encode_storage_descriptor(
    u32 words[OPENGPU_STORAGE_DESCRIPTOR_WORDS],
    const struct opengpu_storage_descriptor *d)
{
    u32 result[OPENGPU_STORAGE_DESCRIPTOR_WORDS] = {0};
    if (!words || !d || d->binding >= 4 || (!d->readable && !d->writable) ||
        (d->base & 255u) || (d->allocation_base & 63u) ||
        (d->allocation_limit & 63u) || d->limit <= d->base ||
        d->limit > (1ULL << 32) || d->allocation_limit > (1ULL << 32) ||
        d->limit - d->base > OPENGPU_STORAGE_DESCRIPTOR_MAX_BYTES ||
        d->base < d->allocation_base || d->limit > d->allocation_limit)
        return false;
    result[0] = OPENGPU_STORAGE_DESCRIPTOR_MAGIC; result[1] = 64; result[2] = 1;
    result[OPENGPU_STORAGE_DESCRIPTOR_CONTEXT_WORD] = d->context; result[OPENGPU_STORAGE_DESCRIPTOR_PASS_WORD] = d->pass; result[OPENGPU_STORAGE_DESCRIPTOR_GENERATION_WORD] = d->generation;
    result[OPENGPU_STORAGE_DESCRIPTOR_BINDING_WORD] = d->binding; result[OPENGPU_STORAGE_DESCRIPTOR_BOUND_WORD] = 1; result[OPENGPU_STORAGE_DESCRIPTOR_READABLE_WORD] = d->readable;
    result[OPENGPU_STORAGE_DESCRIPTOR_WRITABLE_WORD] = d->writable; result[OPENGPU_STORAGE_DESCRIPTOR_BASE_WORD] = d->base;
    result[OPENGPU_STORAGE_DESCRIPTOR_LIMIT_WORD] = (u32)d->limit; result[OPENGPU_STORAGE_DESCRIPTOR_LIMIT_WORD + 1] = (u32)(d->limit >> 32);
    result[OPENGPU_STORAGE_DESCRIPTOR_ALLOCATION_BASE_WORD] = d->allocation_base; result[OPENGPU_STORAGE_DESCRIPTOR_ALLOCATION_LIMIT_WORD] = (u32)d->allocation_limit;
    result[OPENGPU_STORAGE_DESCRIPTOR_ALLOCATION_LIMIT_WORD + 1] = (u32)(d->allocation_limit >> 32);
    for (unsigned i = 0; i < OPENGPU_STORAGE_DESCRIPTOR_WORDS; ++i)
        words[i] = result[i];
    return true;
}
#endif
