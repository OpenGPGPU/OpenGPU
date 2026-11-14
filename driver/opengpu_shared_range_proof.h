/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SHARED_RANGE_PROOF_H
#define OPENGPU_SHARED_RANGE_PROOF_H
#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_range_u32;
typedef u64 opengpu_range_u64;
#else
#include <stdbool.h>
#include <stdint.h>
typedef uint32_t opengpu_range_u32;
typedef uint64_t opengpu_range_u64;
#endif

/* Unsigned, non-wrapping intervals plus bits known zero for EVERY lane.
 * Unknown has valid=0. Joins widen the interval and intersect zero bits;
 * branch predicates never narrow it. This is a value bound, not an address,
 * initialization, alignment or active-lane ownership promise. */
struct opengpu_shared_range {
    opengpu_range_u32 first, last, zero, valid;
};
enum opengpu_shared_range_op {
    OPENGPU_RANGE_ADD, OPENGPU_RANGE_MUL, OPENGPU_RANGE_AND,
    OPENGPU_RANGE_OR, OPENGPU_RANGE_XOR, OPENGPU_RANGE_SHL, OPENGPU_RANGE_SHR
};
static inline struct opengpu_shared_range opengpu_range_constant(opengpu_range_u32 n)
{
    return (struct opengpu_shared_range){n, n, ~n, 1};
}
static inline opengpu_range_u32 opengpu_range_bits(opengpu_range_u32 n)
{
    n |= n >> 1; n |= n >> 2; n |= n >> 4; n |= n >> 8; n |= n >> 16;
    return n;
}
static inline struct opengpu_shared_range opengpu_range_join(
    struct opengpu_shared_range a, struct opengpu_shared_range b)
{
    if (!a.valid || !b.valid) return (struct opengpu_shared_range){0};
    return (struct opengpu_shared_range){a.first < b.first ? a.first : b.first,
        a.last > b.last ? a.last : b.last, a.zero & b.zero, 1};
}
static inline struct opengpu_shared_range opengpu_range_eval(
    unsigned op, struct opengpu_shared_range a, struct opengpu_shared_range b)
{
    struct opengpu_shared_range n = {0};
    opengpu_range_u64 lo, hi;
    /* AND is bounded even if the other defined operand has unknown data. */
    if (op == OPENGPU_RANGE_AND && (a.valid || b.valid)) {
        if (!a.valid) a = (struct opengpu_shared_range){0, ~(opengpu_range_u32)0, 0, 1};
        if (!b.valid) b = (struct opengpu_shared_range){0, ~(opengpu_range_u32)0, 0, 1};
    }
    if (!a.valid || !b.valid) return n;
    if (a.first == a.last && b.first == b.last) {
        if (op == OPENGPU_RANGE_AND) return opengpu_range_constant(a.first & b.first);
        if (op == OPENGPU_RANGE_OR) return opengpu_range_constant(a.first | b.first);
        if (op == OPENGPU_RANGE_XOR) return opengpu_range_constant(a.first ^ b.first);
    }
    n.valid = 1;
    switch (op) {
    case OPENGPU_RANGE_ADD:
        lo = (opengpu_range_u64)a.first + b.first;
        hi = (opengpu_range_u64)a.last + b.last;
        /* Only the common low zero suffix survives carries. */
        n.zero = (a.zero & b.zero) & (~(a.zero & b.zero) - 1u);
        break;
    case OPENGPU_RANGE_MUL:
        lo = (opengpu_range_u64)a.first * b.first;
        hi = (opengpu_range_u64)a.last * b.last;
        {
            unsigned x = 0, y = 0;
            while (x < 32u && (a.zero & ((opengpu_range_u32)1 << x))) ++x;
            while (y < 32u && (b.zero & ((opengpu_range_u32)1 << y))) ++y;
            n.zero = x + y >= 32u ? ~(opengpu_range_u32)0 :
                (opengpu_range_u32)(((opengpu_range_u64)1 << (x + y)) - 1u);
        }
        break;
    case OPENGPU_RANGE_AND:
        n.first = 0; n.last = a.last < b.last ? a.last : b.last;
        n.zero = a.zero | b.zero; return n;
    case OPENGPU_RANGE_OR:
    case OPENGPU_RANGE_XOR:
        n.first = 0; n.last = opengpu_range_bits(a.last) | opengpu_range_bits(b.last);
        n.zero = a.zero & b.zero; return n;
    case OPENGPU_RANGE_SHL:
    case OPENGPU_RANGE_SHR:
        if (b.first != b.last || b.last >= 32u) return (struct opengpu_shared_range){0};
        if (op == OPENGPU_RANGE_SHR) {
            n.first = a.first >> b.last; n.last = a.last >> b.last;
            n.zero = (a.zero >> b.last) | ~(~(opengpu_range_u32)0 >> b.last);
            return n;
        }
        lo = (opengpu_range_u64)a.first << b.last;
        hi = (opengpu_range_u64)a.last << b.last;
        n.zero = a.zero << b.last | (opengpu_range_u32)(((opengpu_range_u64)1 << b.last) - 1u);
        break;
    default: return (struct opengpu_shared_range){0};
    }
    if (hi > ~(opengpu_range_u32)0) return (struct opengpu_shared_range){0};
    n.first = (opengpu_range_u32)lo; n.last = (opengpu_range_u32)hi;
    if (n.first == n.last) n.zero = ~n.first;
    return n;
}
#endif
