/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SHARED_OWN_PROOF_H
#define OPENGPU_SHARED_OWN_PROOF_H
#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_shared_proof_u32;
typedef u64 opengpu_shared_proof_u64;
#else
#include <stdbool.h>
#include <stdint.h>
typedef uint32_t opengpu_shared_proof_u32;
typedef uint64_t opengpu_shared_proof_u64;
#endif

/* Each native instruction can introduce at most one store proof. Joins only
 * remove proofs, so the existing 512-word shader limit bounds every path.
 * Offsets are sorted, exact LOCAL mappings, with disjoint whole-group spans.
 * A shifted overlapping store may write another invocation's previous word:
 * discard that entire old proof, never infer own visibility from a prefix. */
#define OPENGPU_SHARED_OWN_MAX_STORES 512u
struct opengpu_shared_own_proof {
    opengpu_shared_proof_u32 count, span;
    opengpu_shared_proof_u32 offsets[OPENGPU_SHARED_OWN_MAX_STORES];
};

static inline bool opengpu_shared_own_contains(
    const struct opengpu_shared_own_proof *p, opengpu_shared_proof_u32 offset,
    opengpu_shared_proof_u32 span)
{
    opengpu_shared_proof_u32 i;
    if (!p || !span || p->span != span) return false;
    for (i = 0; i < p->count; ++i) {
        if (p->offsets[i] == offset) return true;
        if (p->offsets[i] > offset) break;
    }
    return false;
}

static inline bool opengpu_shared_own_store(struct opengpu_shared_own_proof *p,
    opengpu_shared_proof_u32 offset, opengpu_shared_proof_u32 span)
{
    opengpu_shared_proof_u32 i, n = 0, position;
    opengpu_shared_proof_u64 end = (opengpu_shared_proof_u64)offset + span;
    if (!p || !span) return false;
    if (p->span != span) p->count = 0;
    p->span = span;
    for (i = 0; i < p->count; ++i) {
        opengpu_shared_proof_u32 old = p->offsets[i];
        if ((opengpu_shared_proof_u64)old + span <= offset || old >= end)
            p->offsets[n++] = old;
    }
    p->count = n;
    if (n == OPENGPU_SHARED_OWN_MAX_STORES) return false;
    for (position = 0; position < n && p->offsets[position] < offset; ++position) {}
    for (i = n; i > position; --i) p->offsets[i] = p->offsets[i - 1];
    p->offsets[position] = offset;
    p->count = n + 1;
    return true;
}

static inline void opengpu_shared_own_intersect(struct opengpu_shared_own_proof *p,
    const struct opengpu_shared_own_proof *incoming)
{
    opengpu_shared_proof_u32 i = 0, j = 0, n = 0;
    if (!p) return;
    if (!incoming || p->span != incoming->span) { p->count = 0; return; }
    while (i < p->count && j < incoming->count) {
        opengpu_shared_proof_u32 a = p->offsets[i], b = incoming->offsets[j];
        if (a == b) { p->offsets[n++] = a; ++i; ++j; }
        else if (a < b) ++i;
        else ++j;
    }
    p->count = n;
}
#endif
