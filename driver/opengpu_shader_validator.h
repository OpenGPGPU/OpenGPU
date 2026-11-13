/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SHADER_VALIDATOR_H
#define OPENGPU_SHADER_VALIDATOR_H

#ifdef __KERNEL__
#include <linux/types.h>
#include <linux/slab.h>
typedef u32 opengpu_shader_u32;
typedef s32 opengpu_shader_s32;
typedef u64 opengpu_shader_u64;
typedef s64 opengpu_shader_s64;
#else
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
typedef uint32_t opengpu_shader_u32;
typedef int32_t opengpu_shader_s32;
typedef uint64_t opengpu_shader_u64;
typedef int64_t opengpu_shader_s64;
#endif

#include "opengpu_shared_own_proof.h"

#define OPENGPU_SHADER_MAX_INSTRUCTIONS OPENGPU_SHARED_OWN_MAX_STORES
#define OPENGPU_SHADER_MAX_FORWARD_BRANCHES 4u
#define OPENGPU_SHADER_CEASE 0x30500073u
/* csrrwi x0, vxrm, mode: shader ABI admits only immediate modes 0..3. */
#define OPENGPU_SHADER_SET_VXRM(mode) (0x00a05073u | ((mode) << 15))

enum opengpu_shader_value_kind {
    OPENGPU_SHADER_VALUE_UNKNOWN,
    OPENGPU_SHADER_VALUE_KERNARG,
    OPENGPU_SHADER_VALUE_LOCAL_INDEX,
    OPENGPU_SHADER_VALUE_LOCAL_BYTES,
    OPENGPU_SHADER_VALUE_KERNARG_LOCAL,
    OPENGPU_SHADER_VALUE_CONSTANT,
    OPENGPU_SHADER_VALUE_LOCAL_BYTES_PERMUTED,
    OPENGPU_SHADER_VALUE_SHARED_LOCAL,
    OPENGPU_SHADER_VALUE_SHARED_LOCAL_READ,
};

/* Four branch snapshots live on the kernel stack. All value kinds fit in a
 * byte; packing avoids per-register padding in each snapshot. */
struct opengpu_shader_value {
    opengpu_shader_s32 offset;
    unsigned char kind;
} __attribute__((packed));

struct opengpu_shader_state {
    struct opengpu_shader_value values[32];
    bool scalar_defined[32];
    bool vector_defined[32];
    bool fp_defined[32];
    opengpu_shader_u32 vector_local_indices;
    opengpu_shader_u32 vector_local_bytes;
    /* Scalar registers broadcast as kernarg pointers to every active lane. */
    opengpu_shader_u32 vector_packet_valid;
    opengpu_shader_u32 vector_packet_indexed;
    opengpu_shader_u32 vector_local_packets;
    unsigned char vector_packet_sources[32];
    opengpu_shader_u32 vector_length;
    opengpu_shader_u32 texture_parameter_pairs;
    opengpu_shader_u32 image_parameter_pairs;
    opengpu_shader_u32 shared_written_prefix;
    opengpu_shader_u32 shared_visible_prefix;
    struct opengpu_shared_own_proof *shared_own;
    bool image_result_valid;
    bool storage_values_valid;
    bool storage_atomic_values_valid;
    bool shared_atomic_values_valid;
};

struct opengpu_shader_branch_state {
    struct opengpu_shader_state state;
    opengpu_shader_u32 target;
    bool used;
};

/* A canonical if uses one marker and, at worst, one alternate entry. The
 * native product has eight physical entries. Four software scopes must not
 * be mistaken for four physical entries, or consume four more state copies. */
#define OPENGPU_SHADER_SIMT_STACK_ENTRIES 8u
#define OPENGPU_SHADER_SIMT_JOIN 0x0000205bu

static inline opengpu_shader_s32 opengpu_shader_simt_displacement(
    opengpu_shader_u32 insn)
{
    opengpu_shader_u32 n = ((insn >> 31) & 1u) << 12 |
        ((insn >> 7) & 1u) << 11 | ((insn >> 25) & 63u) << 5 |
        ((insn >> 8) & 15u) << 1;
    return (opengpu_shader_s32)(n | (n & 4096u ? ~8191u : 0u));
}

static inline bool opengpu_shader_simt_scope_at(const opengpu_shader_u32 *words,
    opengpu_shader_u32 i, opengpu_shader_u32 *branch,
    opengpu_shader_u32 *taken, opengpu_shader_u32 *marker)
{
    opengpu_shader_u32 k = i;
    /* Re-read the bounded, already validated prefix rather than allocating a
     * second kernel-stack frame array. Closed nested scopes are skipped. */
    while (k) {
        --k;
        if ((words[k] & 0x707fu) == 0x305bu) {
            opengpu_shader_u32 end = k +
                (opengpu_shader_u32)opengpu_shader_simt_displacement(words[k]) / 4u;
            if (end >= i) {
                *branch = k + 1u; *marker = end;
                *taken = k + 1u + (opengpu_shader_u32)
                    opengpu_shader_simt_displacement(words[k + 1u]) / 4u;
                return true;
            }
        }
    }
    return false;
}

static inline void opengpu_shader_simt_boundary(struct opengpu_shader_state *s)
{
    /* Scalars and capture protocols are warp-shared, not lane-private. A
     * prior arm must never authorize this arm using a snapshot of old state. */
    s->scalar_defined[10] = false;
    s->values[10].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
    s->values[10].offset = 0;
    s->texture_parameter_pairs = s->image_parameter_pairs = 0;
    s->image_result_valid = false;
    s->storage_values_valid = s->storage_atomic_values_valid = false;
    s->shared_atomic_values_valid = false;
    s->vector_packet_valid = s->vector_local_packets = 0;
}

static inline void opengpu_shader_simt_swap(struct opengpu_shader_state *a,
    struct opengpu_shader_state *b)
{
    /* Reuse the existing four branch snapshots without a whole-state stack
     * temporary. The object representation may legally be copied as bytes. */
    unsigned char *x = (unsigned char *)a, *y = (unsigned char *)b;
    opengpu_shader_u32 i;
    for (i = 0; i < sizeof(*a); ++i) {
        unsigned char t = x[i]; x[i] = y[i]; y[i] = t;
    }
}

static inline bool opengpu_shader_simt_arm_instruction(opengpu_shader_u32 w)
{
    opengpu_shader_u32 op = w & 127u, rd = (w >> 7) & 31u;
    opengpu_shader_u32 f = (w >> 12) & 7u, a = (w >> 15) & 31u;
    opengpu_shader_u32 fn = w >> 26;
    bool unmasked = (w & (1u << 25)) != 0u;
    if (op == 0x5bu) return true; /* separate exact envelope checks */
    if (op == 0x0fu) return w == 0x0330000fu;
    if (op == 0x37u) return rd == 10u;
    if (op == 0x13u) return rd == 10u && !f && (!a || a == 10u);
    if (op == 0x03u) /* Immutable XYZ grid words only. */
        return rd == 10u && f == 2u && a == 1u &&
            (w >> 20) <= 8u && !(w & (3u << 20));
    if (op == 0x2bu)
        return unmasked && !f && (fn == 7u || (fn >= 14u && fn <= 16u));
    if (op != 0x57u || f == 7u) return false; /* no VL/config/CSR changes */
    if (fn == 0x17u)
        return unmasked ? f == 4u : (f == 0u || f == 3u);
    if (!unmasked) return false;
    if (fn == 0x18u || fn == 0x19u || fn == 0x1au || fn == 0x1cu)
        return f == 0u || f == 4u;
    if (f == 0u)
        return fn == 0u || fn == 9u || fn == 10u || fn == 11u ||
            fn == 0x25u || fn == 0x28u;
    if (f == 4u) return fn == 0u || fn == 0x25u;
    if (f == 2u) return fn == 0x25u;
    return f == 6u && (fn == 0x20u || fn == 0x22u);
}

static inline bool opengpu_shader_shared_simt_arm_instruction(
    const struct opengpu_shader_state *s, opengpu_shader_u32 w, opengpu_shader_u32 lanes)
{
    opengpu_shader_u32 op = w & 127u, fn = w >> 26, f = w >> 12 & 7u;
    if (!(w & (1u << 25))) return false;
    if (op == 0x2bu) return (!f && fn == 30u) || fn == 31u || (fn == 32u && f < 2u);
    /* Pure per-lane shifts used to rematerialize checked byte addresses.
     * Configuration, predication, CSR and unrelated resource owners are not
     * part of this opt-in extension. The legacy arm helper stays unchanged. */
    return op == 0x57u && ((f == 3u && (fn == 0x25u || fn == 0x28u)) ||
        (f == 4u && fn == 11u && (w >> 15 & 31u) == 10u &&
         s->values[10].kind == OPENGPU_SHADER_VALUE_CONSTANT &&
         (opengpu_shader_u32)s->values[10].offset == lanes));
}

static inline bool opengpu_shader_shared_simt_prefix_instruction(
    const struct opengpu_shader_state *s, opengpu_shader_u32 w, opengpu_shader_u32 lanes)
{
    opengpu_shader_u32 op = w & 127u, rd = w >> 7 & 31u;
    opengpu_shader_u32 a = w >> 15 & 31u, b = w >> 20 & 31u, f = w >> 12 & 7u;
    opengpu_shader_s32 imm = (opengpu_shader_s32)w >> 20;
    if (w == (0xc1007057u | lanes << 15) || w == 0x0000000bu) return true;
    if (rd == 11u || rd == 12u) {
        if (op == 0x13u)
            return (f == 1u && a == 8u && imm == 2) ||
                (f == 4u && a == rd && imm == 64) ||
                (!f && a == rd && !(imm & 3));
        if (op == 0x33u) return !f && !(w >> 25) && a == rd && b == 10u;
    }
    if ((a == 11u || a == 12u) && s->vector_length == lanes &&
        (s->values[a].kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL ||
         s->values[a].kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL_READ))
        return (w & 0xfff0707fu) == 0x02006007u ||
            (w & 0xfff0707fu) == 0x02006027u;
    return false;
}

static inline void opengpu_shader_merge_state(
    struct opengpu_shader_state *state,
    const struct opengpu_shader_state *incoming)
{
    opengpu_shader_u32 reg;

    for (reg = 0; reg < 32; reg++) {
        state->scalar_defined[reg] &= incoming->scalar_defined[reg];
        state->vector_defined[reg] &= incoming->vector_defined[reg];
        state->fp_defined[reg] &= incoming->fp_defined[reg];
        if (!(incoming->vector_packet_valid & (1u << reg)) ||
            state->vector_packet_sources[reg] != incoming->vector_packet_sources[reg] ||
            ((state->vector_packet_indexed ^ incoming->vector_packet_indexed) & (1u << reg)))
            state->vector_packet_valid &= ~(1u << reg);
        if (!state->scalar_defined[reg] ||
            state->values[reg].kind != incoming->values[reg].kind ||
            state->values[reg].offset != incoming->values[reg].offset) {
            state->values[reg].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            state->values[reg].offset = 0;
        }
    }
    for (reg = 0; reg < 32; reg++) {
        opengpu_shader_u32 source = state->vector_packet_sources[reg];
        if (!state->scalar_defined[source] ||
            state->values[source].kind != OPENGPU_SHADER_VALUE_KERNARG)
            state->vector_packet_valid &= ~(1u << reg);
    }
    state->texture_parameter_pairs &= incoming->texture_parameter_pairs;
    state->image_parameter_pairs &= incoming->image_parameter_pairs;
    if (incoming->shared_written_prefix < state->shared_written_prefix)
        state->shared_written_prefix = incoming->shared_written_prefix;
    if (incoming->shared_visible_prefix < state->shared_visible_prefix)
        state->shared_visible_prefix = incoming->shared_visible_prefix;
    opengpu_shared_own_intersect(state->shared_own, incoming->shared_own);
    if (state->shared_own) for (reg = 0; reg < 32; ++reg)
        state->shared_own->vectors[reg] = incoming->shared_own ?
            opengpu_range_join(state->shared_own->vectors[reg], incoming->shared_own->vectors[reg]) :
            (struct opengpu_shared_range){0};
    state->image_result_valid &= incoming->image_result_valid;
    state->storage_values_valid &= incoming->storage_values_valid;
    state->storage_atomic_values_valid &= incoming->storage_atomic_values_valid;
    state->shared_atomic_values_valid &= incoming->shared_atomic_values_valid;
    state->vector_local_indices &= incoming->vector_local_indices;
    state->vector_local_bytes &= incoming->vector_local_bytes;
    state->vector_local_packets &= incoming->vector_local_packets;
    if (state->vector_length != incoming->vector_length)
        state->vector_length = 0;
}

/* Preserve distinct heap slots when taking/restoring branch snapshots. The
 * SIMT swap moves whole states (including their unique proof pointers). */
static inline void opengpu_shader_copy_state(struct opengpu_shader_state *to,
    const struct opengpu_shader_state *from)
{
    struct opengpu_shared_own_proof *proof = to->shared_own;
    *to = *from;
    to->shared_own = proof;
    if (proof) {
        if (from->shared_own) *proof = *from->shared_own;
        else {
            opengpu_shader_u32 reg;
            proof->count = proof->span = 0;
            for (reg = 0; reg < 32; ++reg)
                proof->vectors[reg] = (struct opengpu_shared_range){0};
        }
    }
}

static inline bool opengpu_shader_queue_branch(
    struct opengpu_shader_branch_state *branches,
    opengpu_shader_u32 target, const struct opengpu_shader_state *state)
{
    opengpu_shader_u32 slot;

    for (slot = 0; slot < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; slot++) {
        if (branches[slot].used && branches[slot].target == target) {
            opengpu_shader_merge_state(&branches[slot].state, state);
            return true;
        }
    }
    for (slot = 0; slot < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; slot++) {
        if (!branches[slot].used) {
            branches[slot].used = true;
            branches[slot].target = target;
            opengpu_shader_copy_state(&branches[slot].state, state);
            return true;
        }
    }
    return false;
}

/* Scalar uniform pointers may be formed by bounded ADDI chains from x1.
 * Warp-relative, unknown and constant-only pointers cannot authorize a read. */
static inline bool opengpu_shader_scalar_read_valid(
    const struct opengpu_shader_value *base, opengpu_shader_s32 immediate,
    opengpu_shader_u32 bytes, opengpu_shader_u64 kernarg_size)
{
    opengpu_shader_s64 offset = (opengpu_shader_s64)base->offset + immediate;

    return bytes && base->kind == OPENGPU_SHADER_VALUE_KERNARG &&
           offset >= 0 && !(offset & (bytes - 1u)) &&
           (opengpu_shader_u64)offset <= kernarg_size &&
           bytes <= kernarg_size - (opengpu_shader_u64)offset;
}

/* Width field of a vector load or store: 0 is 8 bits, 5 is 16, 6 is 32. */
static inline opengpu_shader_u32 opengpu_shader_element_bytes(
    opengpu_shader_u32 width)
{
    if (width == 0u)
        return 1u;
    if (width == 5u)
        return 2u;
    if (width == 6u)
        return 4u;
    return 0u;
}

/* Unit-stride (lumop 0), constant-stride, or ordered/unordered 32-bit index.
 * Narrow indexed forms are not implemented. */
static inline bool opengpu_shader_vector_memory_form(
    opengpu_shader_u32 insn, opengpu_shader_u32 opcode,
    bool *strided, bool *indexed, opengpu_shader_u32 *elem_bytes)
{
    opengpu_shader_u32 width = (insn >> 12) & 7u;
    opengpu_shader_u32 mop = insn & 0xfc00707fu;
    opengpu_shader_u32 bytes = opengpu_shader_element_bytes(width);
    bool unit = bytes && (insn & 0xfdf0707fu) == ((width << 12) | opcode);

    *elem_bytes = bytes;
    *strided = bytes && mop == (0x08000000u | (width << 12) | opcode);
    *indexed = mop == (0x04006000u | opcode) || mop == (0x0c006000u | opcode);
    return *strided || *indexed || unit;
}

static inline bool opengpu_shader_vector_access_valid(
    const struct opengpu_shader_value *base, opengpu_shader_u32 vl,
    opengpu_shader_u32 elem_bytes, bool store, opengpu_shader_u64 kernarg_size,
    opengpu_shader_u32 batch_capacity, opengpu_shader_u64 output_start,
    opengpu_shader_u64 output_end)
{
    opengpu_shader_u64 start, bytes, end;

    if (!vl || !elem_bytes || base->offset < 0 ||
        (base->offset & (elem_bytes - 1u)))
        return false;
    start = (opengpu_shader_u32)base->offset;
    if (base->kind == OPENGPU_SHADER_VALUE_KERNARG)
        bytes = (opengpu_shader_u64)elem_bytes * vl;
    else if (base->kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL)
        /* x8 is the trusted localLinearBase.  Active lanes cover logical
         * fragment indices [0, batch_capacity), including a partial warp. */
        bytes = (opengpu_shader_u64)elem_bytes * batch_capacity;
    else
        return false;
    end = start + bytes;
    if (end < start || end > kernarg_size)
        return false;
    return !store || (start >= output_start && end <= output_end);
}

/* A trusted warp base plus its lanes covers a workgroup-wide stripe. Never
 * confuse a constant/global/packet address with this provenance. Only full
 * unmasked e32 unit-stride accesses are admitted; permutations are read-only.
 * Written and barrier-visible prefixes start at zero for every validation.
 * A local read may also consume any retained exact local store stripe:
 * each invocation reads its own acknowledged store, without exchanging data.
 * Prefix coverage alone cannot prove this for a shifted or permuted read. */
static inline bool opengpu_shader_shared_access_valid(
    struct opengpu_shader_state *state, const struct opengpu_shader_value *base,
    opengpu_shader_u32 vl, opengpu_shader_u32 elem, bool store,
    bool unmasked, bool unit, opengpu_shader_u32 local_items,
    opengpu_shader_u32 shared_base, opengpu_shader_u32 shared_bytes,
    opengpu_shader_u32 lanes)
{
    opengpu_shader_u64 start, end;

    if (!shared_bytes || !lanes || vl != lanes || elem != 4u ||
        !unmasked || !unit || base->offset < 0 || (base->offset & 3) ||
        (base->kind != OPENGPU_SHADER_VALUE_SHARED_LOCAL &&
         (store || base->kind != OPENGPU_SHADER_VALUE_SHARED_LOCAL_READ)))
        return false;
    start = (opengpu_shader_u32)base->offset;
    end = start + 4ull * local_items;
    if (end > shared_bytes || (opengpu_shader_u64)shared_base + end > 0x100000000ull)
        return false;
    if (!store)
        return end <= state->shared_visible_prefix ||
            (base->kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL &&
             opengpu_shared_own_contains(state->shared_own,
                 (opengpu_shader_u32)start, 4u * local_items));
    if (!opengpu_shared_own_store(state->shared_own,
            (opengpu_shader_u32)start, 4u * local_items)) return false;
    if (start <= state->shared_written_prefix && end > state->shared_written_prefix)
        state->shared_written_prefix = (opengpu_shader_u32)end;
    return true;
}

static inline bool opengpu_shader_vector_strided_access_valid(
    const struct opengpu_shader_value *base,
    const struct opengpu_shader_value *stride, opengpu_shader_u32 vl,
    opengpu_shader_u32 elem_bytes, bool store, opengpu_shader_u64 kernarg_size,
    opengpu_shader_u64 output_start, opengpu_shader_u64 output_end)
{
    opengpu_shader_u32 lane;

    if (!vl || !elem_bytes || base->kind != OPENGPU_SHADER_VALUE_KERNARG ||
        stride->kind != OPENGPU_SHADER_VALUE_CONSTANT ||
        base->offset < 0 || (base->offset & (elem_bytes - 1u)) ||
        (stride->offset & (elem_bytes - 1u)))
        return false;
    for (lane = 0; lane < vl; lane++) {
        opengpu_shader_s64 start = (opengpu_shader_s64)base->offset +
            (opengpu_shader_s64)lane * stride->offset;
        opengpu_shader_u64 end;

        if (start < 0)
            return false;
        end = (opengpu_shader_u64)start + elem_bytes;
        if (end < (opengpu_shader_u64)start || end > kernarg_size)
            return false;
        if (store && ((opengpu_shader_u64)start < output_start ||
                      end > output_end))
            return false;
    }
    return true;
}

static inline bool opengpu_shader_vector_indexed_access_valid(
    const struct opengpu_shader_value *base,
    bool trusted_local_bytes,
    bool store, opengpu_shader_u64 kernarg_size,
    opengpu_shader_u32 batch_capacity, opengpu_shader_u64 output_start,
    opengpu_shader_u64 output_end)
{
    opengpu_shader_u64 start, end;

    if (base->kind != OPENGPU_SHADER_VALUE_KERNARG || base->offset < 0 ||
        (base->offset & 3) ||
        !trusted_local_bytes)
        return false;
    start = (opengpu_shader_u32)base->offset;
    end = start + 4ull * batch_capacity;
    if (end < start || end > kernarg_size)
        return false;
    return !store || (start >= output_start && end <= output_end);
}

static inline bool opengpu_shader_vector_alu_valid(opengpu_shader_u32 insn)
{
    opengpu_shader_u32 funct6 = insn >> 26;
    opengpu_shader_u32 form = (insn >> 12) & 7;
    opengpu_shader_u32 vd = (insn >> 7) & 0x1f;
    opengpu_shader_u32 vs2 = (insn >> 20) & 0x1f;

    /* Masked operations require a defined v0 and old vd below. Keep the
     * form allow-list authoritative so reserved encodings stay rejected. */
    if (!(insn & (1u << 25))) {
        bool lane_local =
            ((form == 0 || form == 3 || form == 4) &&
             (funct6 < 0x0c || funct6 >= 0x20)) ||
            ((form == 2 || form == 6) && funct6 >= 0x20) ||
            (form == 2 && (funct6 == 0x12 || funct6 == 0x14));
        bool comparison =
            ((form == 0 || form == 3 || form == 4) &&
             funct6 >= 0x18 && funct6 <= 0x1f) ||
            (form == 1 &&
             (funct6 == 0x18 || funct6 == 0x19 || funct6 == 0x1b ||
              funct6 == 0x1c)) ||
            (form == 5 &&
             (funct6 == 0x18 || funct6 == 0x19 || funct6 == 0x1b ||
              funct6 == 0x1c || funct6 == 0x1d || funct6 == 0x1f));
        bool reduction = form == 2 && funct6 <= 0x07;
        /* OPFRED: funct6 000001 is vfredusum and 000011 is vfredosum. Both
         * fold into element 0 like the integer reductions do. */
        bool fp_reduction = form == 1 && (funct6 == 0x01 || funct6 == 0x03);
        bool gather = (form == 0 || form == 3 || form == 4) &&
                      funct6 == 0x0c;
        bool slide = ((form == 0 || form == 3 || form == 4) &&
                      (funct6 == 0x0e || funct6 == 0x0f)) ||
                     (form == 6 && (funct6 == 0x0e || funct6 == 0x0f));

        /* vfmerge.vfm is masked by definition. vfmv.v.f is its unmasked form. */
        bool fp_merge = form == 5 && funct6 == 0x17;
        /* vmerge.vvm/vxm/vim. vmv is the unmasked form and is handled below. */
        bool integer_merge =
            (form == 0 || form == 3 || form == 4) && funct6 == 0x17;
        /* Other non-compare FP ops keep the old destination where v0 is clear. */
        bool fp_data =
            (form == 1 &&
             (funct6 == 0x00 || funct6 == 0x02 || funct6 == 0x04 ||
              funct6 == 0x06 || funct6 == 0x08 || funct6 == 0x09 ||
              funct6 == 0x0a || funct6 == 0x12 || funct6 == 0x13 ||
              funct6 == 0x20 || funct6 == 0x24 ||
              (funct6 >= 0x28 && funct6 <= 0x2f))) ||
            (form == 5 &&
             (funct6 == 0x00 || funct6 == 0x02 || funct6 == 0x04 ||
              funct6 == 0x06 || funct6 == 0x08 || funct6 == 0x09 ||
              funct6 == 0x0a || funct6 == 0x20 || funct6 == 0x21 ||
              funct6 == 0x24 || funct6 == 0x27 ||
              (funct6 >= 0x28 && funct6 <= 0x2f)));
        if (!(lane_local || comparison || reduction || gather || slide ||
              fp_merge || fp_data || integer_merge || fp_reduction) ||
            vd == 0)
            return false;
    }
    if (funct6 == 0x0e && vd == vs2) /* vslideup overlap is reserved */
        return false;
    /* vcompress packs vs2 into vd, so the destination must be disjoint from
     * both the source and the vs1 mask. */
    if (funct6 == 0x17 && form == 2 &&
        (vd == vs2 || vd == ((insn >> 15) & 0x1f)))
        return false;
    /* viota.m reads vs2 and writes a per-element rank, so the destination
     * must be disjoint from it, and from v0 under a mask. */
    if (funct6 == 0x14 && form == 2 &&
        ((insn >> 15) & 0x1f) == 16 &&
        (vd == vs2 || (!(insn & (1u << 25)) && vd == 0)))
        return false;
    if (form == 2 && funct6 == 0x12 && vd == vs2)
        return false; /* widening source/destination overlap */
    if ((form == 0 || form == 3 || form == 4) &&
        (funct6 >= 0x2c && funct6 <= 0x2f) &&
        ((vs2 & 1) || vd == vs2 || vd == vs2 + 1))
        return false; /* narrowing needs an even vs2 pair disjoint from vd */
    switch (form) {
    case 0: /* integer vv */
        switch (funct6) {
        case 0x17: /* vmerge; vmv requires vs2 = v0 */
            return !(insn & (1u << 25)) || vs2 == 0;
        case 0x00: /* vadd */
        case 0x02: /* vsub */
        case 0x04: /* vminu */
        case 0x05: /* vmin */
        case 0x06: /* vmaxu */
        case 0x07: /* vmax */
        case 0x09: /* vand */
        case 0x0a: /* vor */
        case 0x0b: /* vxor */
        case 0x0c: /* vrgather */
        case 0x0e: /* vslideup */
        case 0x0f: /* vslidedown */
        case 0x18: /* vmseq */
        case 0x19: /* vmsne */
        case 0x1a: /* vmsltu */
        case 0x1b: /* vmslt */
        case 0x1c: /* vmsleu */
        case 0x1d: /* vmsle */
        case 0x20: /* vsaddu */
        case 0x21: /* vsadd */
        case 0x22: /* vssubu */
        case 0x23: /* vssub */
        case 0x25: /* vsll */
        case 0x27: /* vsmul */
        case 0x28: /* vsrl */
        case 0x29: /* vsra */
        case 0x2a: /* vssrl */
        case 0x2b: /* vssra */
        case 0x2c: /* vnsrl */
        case 0x2d: /* vnsra */
        case 0x2e: /* vnclipu */
        case 0x2f: /* vnclip */
        case 0x30: /* vwadd */
        case 0x31: /* vwsub */
        case 0x32: /* vwmul */
        case 0x33: /* vwmulu */
        case 0x34: /* vwmulsu */
            return true;
        default:
            return false;
        }
    case 2: /* integer reduction, mask logical, vmv.x.s, or multiply/divide vv */
        /* vmand/vmor/vmxor and the negated forms are unmasked. */
        if (funct6 >= 0x18 && funct6 <= 0x1f)
            return (insn & (1u << 25)) != 0;
        /* vmv.x.s: VWXUNARY0, vs1 = 0, unmasked. Other vs1 values stay reserved. */
        if (funct6 == 0x10)
            return (insn & (1u << 25)) && ((insn >> 15) & 0x1f) == 0;
        /* vcompress.vm shares funct6 010111 with the OPIVV merge forms. The
         * masked form of the same encoding is reserved. */
        if (funct6 == 0x17)
            return (insn & (1u << 25)) != 0;
        /* funct6 010100 in OPMVV selects its operation in vs1: 10001 is
         * vid.v, whose vs2 is fixed to v0, and 10000 is viota.m, which reads
         * vs2 as its mask. Neither names a register in vs1. */
        if (funct6 == 0x14) {
            opengpu_shader_u32 selector = (insn >> 15) & 0x1f;
            return selector == 16 || (selector == 17 && vs2 == 0);
        }
        return funct6 <= 0x07 || funct6 == 0x12 ||
               (funct6 >= 0x20 && funct6 <= 0x27) ||
               (funct6 == 0x29 || funct6 == 0x2b || funct6 == 0x2d ||
                funct6 == 0x2f);
    case 6: /* vslide1*.vx, vmv.s.x, or multiply/divide vx */
        /* vslide1up.vx / vslide1down.vx insert the scalar and shift by one. */
        if (funct6 == 0x0e || funct6 == 0x0f)
            return true;
        /* vmv.s.x: VRXUNARY0, vs2 = v0, unmasked. */
        if (funct6 == 0x10)
            return (insn & (1u << 25)) && vs2 == 0;
        return (funct6 >= 0x20 && funct6 <= 0x27) ||
               funct6 == 0x29 || funct6 == 0x2b || funct6 == 0x2d ||
               funct6 == 0x2f;
    case 1: { /* OPFVV: FP32 VFUNARY1 plus unmasked binary FVV */
        opengpu_shader_u32 vs1 = (insn >> 15) & 0x1f;

        if (funct6 == 0x12) {
            /* vfcvt.* ; vs1 selects the conversion and is not a VGPR.
             * 0 xu.f, 1 x.f, 2 f.xu, 3 f.x, 4 rtz.f.xu, 5 rtz.f.x,
             * 6 rtz.xu.f, 7 rtz.x.f. */
            return vs1 <= 7u;
        }
        if (funct6 == 0x13) {
            /* vfsqrt / vfrec7 / vfrsqrt7 / vfclass; vs1 is not a VGPR. */
            return vs1 == 0 || vs1 == 4 || vs1 == 5 || vs1 == 16;
        }
        /* vfmv.f.s: vs1 = 0, unmasked. Other vs1 values stay reserved. */
        if (funct6 == 0x10)
            return (insn & (1u << 25)) && vs1 == 0;
        switch (funct6) {
        case 0x00: /* vfadd */
        case 0x01: /* vfredusum */
        case 0x02: /* vfsub */
        case 0x03: /* vfredosum */
        case 0x04: /* vfmin */
        case 0x06: /* vfmax */
        case 0x08: /* vfsgnj */
        case 0x09: /* vfsgnjn */
        case 0x0a: /* vfsgnjx */
        case 0x18: /* vmfeq */
        case 0x19: /* vmfle */
        case 0x1b: /* vmflt */
        case 0x1c: /* vmfne */
        case 0x20: /* vfdiv */
        case 0x24: /* vfmul */
        case 0x28: /* vfmadd */
        case 0x29: /* vfnmadd */
        case 0x2a: /* vfmsub */
        case 0x2b: /* vfnmsub */
        case 0x2c: /* vfmacc */
        case 0x2d: /* vfnmacc */
        case 0x2e: /* vfmsac */
        case 0x2f: /* vfnmsac */
            return true;
        default:
            return false;
        }
    }
    case 5: /* OPFVF: rs1 names a scalar FP register */
        /* vfmv.s.f: vs2 = v0, unmasked. */
        if (funct6 == 0x10)
            return (insn & (1u << 25)) && vs2 == 0;
        switch (funct6) {
        case 0x00: /* vfadd */
        case 0x02: /* vfsub */
        case 0x04: /* vfmin */
        case 0x06: /* vfmax */
        case 0x08: /* vfsgnj */
        case 0x09: /* vfsgnjn */
        case 0x0a: /* vfsgnjx */
        case 0x17: /* vfmerge.vfm / vfmv.v.f */
        case 0x18: /* vmfeq */
        case 0x19: /* vmfle */
        case 0x1b: /* vmflt */
        case 0x1c: /* vmfne */
        case 0x1d: /* vmfgt */
        case 0x1f: /* vmfge */
        case 0x20: /* vfdiv */
        case 0x21: /* vfrdiv */
        case 0x24: /* vfmul */
        case 0x27: /* vfrsub */
        case 0x28: /* vfmadd */
        case 0x29: /* vfnmadd */
        case 0x2a: /* vfmsub */
        case 0x2b: /* vfnmsub */
        case 0x2c: /* vfmacc */
        case 0x2d: /* vfnmacc */
        case 0x2e: /* vfmsac */
        case 0x2f: /* vfnmsac */
            return true;
        default:
            return false;
        }
    case 3: /* integer vi */
        switch (funct6) {
        case 0x17: /* vmerge; vmv requires vs2 = v0 */
            return !(insn & (1u << 25)) || vs2 == 0;
        case 0x00: /* vadd */
        case 0x03: /* vrsub */
        case 0x09: /* vand */
        case 0x0a: /* vor */
        case 0x0b: /* vxor */
        case 0x0c: /* vrgather */
        case 0x0e: /* vslideup */
        case 0x0f: /* vslidedown */
        case 0x18: /* vmseq */
        case 0x19: /* vmsne */
        case 0x1c: /* vmsleu */
        case 0x1d: /* vmsle */
        case 0x1e: /* vmsgtu */
        case 0x1f: /* vmsgt */
        case 0x20: /* vsaddu */
        case 0x21: /* vsadd */
        case 0x25: /* vsll */
        case 0x28: /* vsrl */
        case 0x29: /* vsra */
        case 0x2a: /* vssrl */
        case 0x2b: /* vssra */
        case 0x2c: /* vnsrl */
        case 0x2d: /* vnsra */
        case 0x2e: /* vnclipu */
        case 0x2f: /* vnclip */
        case 0x30: /* vwadd.vi */
            return true;
        default:
            return false;
        }
    case 4: /* integer vx */
        switch (funct6) {
        case 0x17: /* vmerge; vmv requires vs2 = v0 */
            return !(insn & (1u << 25)) || vs2 == 0;
        case 0x00: /* vadd */
        case 0x02: /* vsub */
        case 0x03: /* vrsub */
        case 0x04: /* vminu */
        case 0x05: /* vmin */
        case 0x06: /* vmaxu */
        case 0x07: /* vmax */
        case 0x09: /* vand */
        case 0x0a: /* vor */
        case 0x0b: /* vxor */
        case 0x0c: /* vrgather */
        case 0x0e: /* vslideup */
        case 0x0f: /* vslidedown */
        case 0x18: /* vmseq */
        case 0x19: /* vmsne */
        case 0x1a: /* vmsltu */
        case 0x1b: /* vmslt */
        case 0x1c: /* vmsleu */
        case 0x1d: /* vmsle */
        case 0x1e: /* vmsgtu */
        case 0x1f: /* vmsgt */
        case 0x20: /* vsaddu */
        case 0x21: /* vsadd */
        case 0x22: /* vssubu */
        case 0x23: /* vssub */
        case 0x25: /* vsll */
        case 0x27: /* vsmul */
        case 0x28: /* vsrl */
        case 0x29: /* vsra */
        case 0x2a: /* vssrl */
        case 0x2b: /* vssra */
        case 0x2c: /* vnsrl */
        case 0x2d: /* vnsra */
        case 0x2e: /* vnclipu */
        case 0x2f: /* vnclip */
        case 0x30: /* vwadd.vx */
        case 0x31: /* vwsub.vx */
        case 0x32: /* vwmul.vx */
        case 0x33: /* vwmulu.vx */
            return true;
        default:
            return false;
        }
    default:
        return false;
    }
}

/* RNE, RTZ, RDN, RUP, RMM, and dynamic. Reserved modes 5 and 6 are rejected. */
static inline bool opengpu_shader_fp_rounding_valid(opengpu_shader_u32 rm)
{
    return rm <= 4u || rm == 7u;
}

/* An integer result is a plain value. It must not keep a pointer kind that
 * would let a later vector store treat it as kernarg. */
static inline void opengpu_shader_define_integer(
    struct opengpu_shader_state *state, opengpu_shader_u32 rd)
{
    state->values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
    state->values[rd].offset = 0;
    state->scalar_defined[rd] = true;
}

/* Shared shader sandbox. Unreconverged forward scalar branches are limited
 * to four. Paths may reconverge or terminate independently in CEASE; every
 * reachable path must terminate. x1 remains the immutable kernarg base.
 * Scalar loads and stores are imm(x1) only: lb/lbu/lh/lhu/lw and sb/sh/sw,
 * naturally aligned, inside kernarg for loads and inside the profile output
 * window for stores. When the scalar FPU is enabled, flw is limited to aligned reads
 * from a proven kernarg-relative pointer and fsw to aligned imm(x1) writes of a defined
 * f-register into that profile's output window. The same capability admits
 * the FP32 operations that retire on the scalar FPU fast path: fadd/fsub/fmul,
 * fsgnj/fsgnjn/fsgnjx, fmin/fmax, and the four fused multiply-add forms.
 * Each source f-register must already be defined, and the destination becomes
 * defined. The same path admits feq/flt/fle, fcvt.w.s/fcvt.wu.s, fmv.x.w and
 * fclass.s, which write an integer other than x1, and fcvt.s.w/fcvt.s.wu and
 * fmv.w.x, which read a defined integer and write an f-register. An integer
 * destination loses any pointer kind it had. fdiv.s reads two defined
 * f-registers. fsqrt.s reads one defined f-register and requires rs2 = 0.
 * Both write a defined f-register.
 * vfmv.v.f broadcasts a defined f-register. vfmerge.vfm is its masked form:
 * v0 and the old destination must be defined, and vs2 supplies the
 * mask-clear lanes.
 * vmerge.vvm/vxm/vim select vs1, a scalar, or a sign-extended immediate on
 * set mask lanes and vs2 on clear mask lanes. v0, vs2, and the old
 * destination must be defined, and the destination cannot be v0. vmv.v.v,
 * vmv.v.x, and vmv.v.i are the unmasked forms: vs2 must be v0 and is not
 * read. Lanes outside VL keep the old destination.
 * vmand, vmor, vmxor and the negated forms vmandn, vmorn, vmnand, vmnor
 * and vmxnor combine two defined mask registers. The encoding is unmasked;
 * a clear vm bit is rejected. The destination may be v0. Bits outside VL
 * keep the old destination.
 * vmv.s.x writes a defined integer into element 0 of vd. vs2 must be v0
 * and is not read. vmv.x.s copies element 0 of a defined vector into an
 * integer register other than x1. vs1 must be 0 and is not a vector source.
 * Both encodings are unmasked.
 * vfmv.s.f writes a defined f-register into element 0 of vd. vs2 must be v0
 * and is not read. vfmv.f.s copies element 0 of a defined vector into an
 * f-register. vs1 must be 0 and is not a vector source. Both need the scalar
 * FPU and are unmasked. An inactive element 0 writes the canonical NaN.
 * vslide1up.vx inserts a defined integer at element 0 and slides vs2 up by
 * one. vslide1down.vx slides vs2 down by one and writes that integer at
 * element vl-1. Both are OPMVX. vslide1up keeps the vslideup rule that vd
 * must already be defined and must not overlap vs2. Masked-off lanes and
 * lanes outside vl keep the old destination.
 * vslideup and vslidedown also take a vector of per-element offsets in a
 * defined vs1, so each lane shifts by its own amount rather than by one
 * shared scalar or immediate. The vslideup rules carry over unchanged: vd
 * must already be defined and must not overlap vs2.
 * vcompress.vm packs the mask-selected elements of vs2 into the low elements
 * of vd. It is only encoded unmasked, the destination must be disjoint from
 * both vs2 and the vs1 mask, and both must be defined.
 * The funct6 010100 family (vid.v and viota.m) selects its operation in the
 * vs1 field instead of naming a register: 10001 is vid.v, whose vs2 field is
 * fixed to v0 and which reads no vector source at all, and 10000 is viota.m,
 * which accumulates the mask in vs2 into a per-element rank. The destination
 * must be disjoint from that source, and from v0 under a mask.
 * vmadd, vnmsub, vmacc and vnmsac read the destination as their third
 * operand, so vd must already be a defined vector register; the sources must
 * be defined as for any other multiply. Only the low half of the product is
 * architecturally visible, so there is no signedness distinction.
 * vfredusum.vs and vfredosum.vs fold vs1[0] and the participating vs2
 * elements into element 0 of vd and keep the old destination in every other
 * element, so vd must already be defined. vs1 is the seed register, not an
 * opcode. The masked form additionally needs a defined v0. The min/max
 * reductions are not admitted: vfredmin.vs would be funct6 001010, which this
 * profile already spends on vfsgnjx.vv.
 * The RVV profile admits vsetivli and VLMAX vsetvli e32,m1, the implemented lane-local
 * integer ALU, comparison, saturating, reduction, gather, slide, multiply,
 * divide and remainder forms, vssrl/vssra rounded scaling shifts,
 * masked lane-local integer arithmetic, comparisons, reductions, gathers,
 * slides and extensions, the two OPFRED sum reductions, fixed-profile
 * vnsrl/vnsra narrowing shifts over even/odd
 * register pairs, vnclipu/vnclip rounded saturating narrowing, FP32
 * VFUNARY0 (`vfcvt.xu.f.v`/`vfcvt.x.f.v`/`vfcvt.f.xu.v`/`vfcvt.f.x.v` and the
 * two rtz float-to-integer forms) and VFUNARY1
 * (`vfsqrt`/`vfrec7`/`vfrsqrt7`/`vfclass`), OPFVV
 * `vfadd`/`vfsub`/`vfmul`/`vfdiv`/`vfmin`/`vfmax`/`vfsgnj`/`vfsgnjn`/`vfsgnjx`,
 * OPFVV compares (`vmfeq`/`vmfle`/`vmflt`/`vmfne`), the eight fused FMA
 * forms, the matching OPFVF forms plus `vfrdiv`/`vfrsub`/`vmfgt`/`vmfge`
 * whose scalar operand must be a defined f-register. Masked non-compare
 * forms of those FP ops need a defined v0 and a defined old destination,
 * which supplies the mask-clear lanes. The same profile admits masked or
 * unmasked unit-stride and constant-stride memory at 8, 16, and 32 bits,
 * plus trusted-local-index word accesses. Each lane holds one element,
 * zero-extended to 32 bits. The base and a constant stride must be aligned
 * to that element. Indexed accesses stay 32-bit.
 * Defined-register tracking prevents stale SGPR/VGPR data from being exported.
 * A small abstract interpreter recognizes x1 + 4*x8 + constant, where x8 is
 * the trusted warp localLinearBase, and proves every active vector lane
 * remains in kernarg; each profile selects the writable portion. The fragment
 * profile restricts stores to colour/depth/validity slices, while general
 * compute permits the whole explicitly bound kernarg range. The bounded
 * vector texture sample requires a validated texture binding. Backward
 * branches, jumps and other custom instructions remain rejected. Only the
 * explicitly capability-gated compute entry point admits bounded word atomics.
 */
/* Conservative union of input SoA slices touched by a proven read range. */
static inline opengpu_shader_u32 opengpu_shader_input_range_mask(
    opengpu_shader_u64 start, opengpu_shader_u64 bytes,
    opengpu_shader_u64 stride)
{
    opengpu_shader_u32 slice, mask = 0;
    opengpu_shader_u64 end = start + bytes;
    if (!stride || end < start)
        return 0x3fu;
    for (slice = 0; slice < 6; slice++)
        if (start < (slice + 1ull) * stride && end > slice * stride)
            mask |= 1u << slice;
    return mask;
}

/* Conservative first-nine-SoA read/write range usage. */
static inline opengpu_shader_u32 opengpu_shader_io_range_mask(
    opengpu_shader_u64 start, opengpu_shader_u64 bytes,
    opengpu_shader_u64 stride)
{
    opengpu_shader_u32 slice, mask = 0;
    opengpu_shader_u64 end = start + bytes;
    if (!stride || end < start)
        return 0x1ffu;
    for (slice = 0; slice < 9; slice++)
        if (start < (slice + 1ull) * stride && end > slice * stride)
            mask |= 1u << slice;
    return mask;
}

/* Optional read/write unions for the first nine fragment SoA slices.
 * Publish only after complete validation; failed or complex accesses retain
 * conservative masks. The six-input API below preserves its existing contract. */
/* Compute before updating rd: aliased source/destination values belong to the
 * instruction's input epoch. Any unsupported/masked/short-VL write kills its
 * range. Never infer a range from vector_defined alone. */
static inline bool opengpu_shader_writes_vector(opengpu_shader_u32 w)
{
    opengpu_shader_u32 op = w & 127u, fn = w >> 26, f = w >> 12 & 7u;
    if (op == 0x07u) return f != 2u; /* scalar flw does not write vector rd */
    if (op == 0x57u) return f != 7u &&
        !(fn == 0x10u && (f == 1u || f == 2u) && !(w & (31u << 15)) && (w & (1u << 25)));
    if (op != 0x2bu) return false;
    /* Parameter capture and stores have an encoded rd field but no vector
     * writeback. In particular fn30 must preserve a bounded value in v0. */
    return fn != 3u && fn != 8u && fn != 10u && fn != 14u &&
        fn != 16u && fn != 24u && fn != 30u;
}

static inline struct opengpu_shared_range opengpu_shader_vector_range(
    const struct opengpu_shader_state *s, opengpu_shader_u32 w,
    opengpu_shader_u32 lanes)
{
    opengpu_shader_u32 fn = w >> 26, f = w >> 12 & 7u;
    opengpu_shader_u32 a = w >> 15 & 31u, b = w >> 20 & 31u;
    struct opengpu_shared_range left, right;
    unsigned op;
    if (!s->shared_own || s->vector_length != lanes || !lanes ||
        !(w & (1u << 25)) || (w & 127u) != 0x57u)
        return (struct opengpu_shared_range){0};
    left = s->shared_own->vectors[b];
    if (f == 0u || f == 2u) right = s->shared_own->vectors[a];
    else if (f == 3u) right = opengpu_range_constant(
        (fn == 0x25u || fn == 0x28u || !(a & 16u)) ? a : a | ~31u);
    else if ((f == 4u || f == 6u) && (!a ||
        s->values[a].kind == OPENGPU_SHADER_VALUE_CONSTANT))
        right = opengpu_range_constant(a ? (opengpu_shader_u32)s->values[a].offset : 0u);
    else return (struct opengpu_shared_range){0};
    if (fn == 0x17u) {
        if (f == 0u) return right; /* vmv.v.v uses vs1 */
        if (f == 4u) return right;
        if (f == 3u) return opengpu_range_constant((a & 16u) ? a | ~31u : a);
        return (struct opengpu_shared_range){0};
    }
    if (f == 2u || f == 6u) {
        if (fn != 0x25u) return (struct opengpu_shared_range){0};
        op = OPENGPU_RANGE_MUL;
    } else {
        switch (fn) {
        case 0: op = OPENGPU_RANGE_ADD; break;
        case 9: op = OPENGPU_RANGE_AND; break;
        case 10: op = OPENGPU_RANGE_OR; break;
        case 11: op = OPENGPU_RANGE_XOR; break;
        case 0x25: op = OPENGPU_RANGE_SHL; break;
        case 0x28: op = OPENGPU_RANGE_SHR; break;
        default: return (struct opengpu_shared_range){0};
        }
    }
    return opengpu_range_eval(op, left, right);
}

static inline bool opengpu_shader_validate_words_profile_runtime_features_buffer_images_proofs(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask,
    opengpu_shader_u32 *output_write_mask, bool word_atomics_enabled,
    bool texture_backings_disjoint, bool storage_enabled, bool image_enabled,
    bool image_atomics_enabled, bool storage_atomics_enabled, bool counter_enabled,
    bool uniform_enabled, opengpu_shader_u32 shared_base,
    opengpu_shader_u32 shared_bytes, opengpu_shader_u32 shared_lanes,
    bool buffer_image_size_enabled, bool shared_atomics_enabled,
    struct opengpu_shared_own_proof *proofs)
{
    opengpu_shader_u64 stride, output_start, output_end;
    struct opengpu_shader_state state = { 0 };
    struct opengpu_shader_branch_state branches[
        OPENGPU_SHADER_MAX_FORWARD_BRANCHES] = { 0 };
    struct opengpu_shader_value *values = state.values;
    bool *scalar_defined = state.scalar_defined;
    bool *vector_defined = state.vector_defined;
    bool reachable = true;
    bool divergent_exit = false;
    bool simt_program = false, scalar_branch_seen = false;
    bool simt_compatible = true;
    opengpu_shader_u32 simt_depth = 0;
    opengpu_shader_u32 i, branch, input_reads = 0, output_writes = 0;

    if (proofs) {
        state.shared_own = proofs;
        for (branch = 0; branch < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; ++branch)
            branches[branch].state.shared_own = proofs + branch + 1;
    }

    /* Failed validation never publishes an optimistic input promise. */
    if (input_read_mask)
        *input_read_mask = 0x1ffu;
    if (output_write_mask)
        *output_write_mask = 0x1ffu;

    /* The advertised fragment batch capacity occupies eight ABI bits. */
    if (!words || !word_count || !batch_capacity || batch_capacity > 255)
        return false;
    if (uniform_enabled && (!all_kernarg_writable || fragment_ops_enabled))
        return false;
    if ((shared_base || shared_bytes || shared_lanes) &&
        (!shared_bytes || !shared_lanes ||
         ((shared_base | shared_bytes) & 3u) ||
         (opengpu_shader_u64)shared_base + shared_bytes > 0x100000000ull ||
         !all_kernarg_writable || fragment_ops_enabled))
        return false;
    stride = 4ull * batch_capacity;
    if (all_kernarg_writable) {
        output_start = 0;
        output_end = kernarg_size;
    } else {
        output_start = output_start_slice * stride;
        output_end = output_end_slice * stride;
        if (output_start_slice >= output_end_slice ||
            kernarg_size < output_end)
            return false;
    }
    if (word_count > OPENGPU_SHADER_MAX_INSTRUCTIONS)
        word_count = OPENGPU_SHADER_MAX_INSTRUCTIONS;
    for (i = 0; i <= 8; i++)
        scalar_defined[i] = true;
    vector_defined[1] = true; /* launch-time local IDs */
    state.vector_local_indices = 1u << 1;
    values[1].kind = OPENGPU_SHADER_VALUE_KERNARG;
    values[8].kind = OPENGPU_SHADER_VALUE_LOCAL_INDEX;
    if (proofs) proofs->vectors[1] = (struct opengpu_shared_range){0,
        batch_capacity - 1u, ~opengpu_range_bits(batch_capacity - 1u), 1};
    /* Only the capability-gated graphics caller may assert this launch value.
     * General compute admission does not confuse local size with CU capacity. */
    if (launch_capacity) {
        if (launch_capacity != batch_capacity || all_kernarg_writable)
            return false;
        scalar_defined[9] = true;
        values[9].kind = OPENGPU_SHADER_VALUE_CONSTANT;
        values[9].offset = (opengpu_shader_s32)launch_capacity;
    }

    for (i = 0; i < word_count; i++) {
        opengpu_shader_u32 insn = words[i];
        opengpu_shader_u32 opcode = insn & 0x7f;
        opengpu_shader_u32 rd = (insn >> 7) & 0x1f;
        opengpu_shader_u32 funct3 = (insn >> 12) & 7;
        opengpu_shader_u32 rs1 = (insn >> 15) & 0x1f;
        opengpu_shader_u32 rs2 = (insn >> 20) & 0x1f;
        opengpu_shader_u32 funct7 = insn >> 25;
        struct opengpu_shader_value lhs, rhs, old_destination;
        struct opengpu_shared_range vector_range = {0};
        opengpu_shader_s32 imm;
        opengpu_shader_u64 end;
        bool target_reachable = false;

        for (branch = 0; branch < OPENGPU_SHADER_MAX_FORWARD_BRANCHES;
             branch++) {
            if (branches[branch].used && branches[branch].target < i)
                return false;
            if (branches[branch].used && branches[branch].target == i) {
                if (reachable || target_reachable)
                    opengpu_shader_merge_state(
                        &state, &branches[branch].state);
                else
                    opengpu_shader_copy_state(&state, &branches[branch].state);
                target_reachable = true;
                branches[branch].used = false;
            }
        }
        reachable |= target_reachable;
        if (!reachable)
            continue;
        if (insn == OPENGPU_SHADER_CEASE) {
            if (simt_depth) return false;
            for (branch = 0;
                 branch < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; branch++) {
                if (branches[branch].used)
                    break;
            }
            if (branch == OPENGPU_SHADER_MAX_FORWARD_BRANCHES) {
                if (input_read_mask)
                    *input_read_mask = input_reads;
                if (output_write_mask)
                    *output_write_mask = output_writes;
                return true;
            }
            /* A path terminated while peers may still be executing. */
            divergent_exit = true;
            reachable = false;
            continue;
        }
        lhs = values[rs1];
        rhs = values[rs2];
        old_destination = values[rd];
        if (proofs) vector_range = opengpu_shader_vector_range(&state, insn, shared_lanes);
        if (shared_atomics_enabled && shared_bytes && opengpu_shader_writes_vector(insn) &&
            state.vector_length != shared_lanes)
            simt_compatible = false;
        /* Branch comparisons use the warp mask, independently of RVV VL.
         * A prior short-VL or lane-zero-only definition cannot authorize all
         * branch lanes after restoring VLMAX. Track the canonical full-lane
         * integer dialect monotonically without changing legacy programs. */
        {
            bool shared_simt = shared_atomics_enabled && shared_bytes;
            bool arm_instruction = opengpu_shader_simt_arm_instruction(insn) ||
                (shared_simt && opengpu_shader_shared_simt_arm_instruction(&state, insn, shared_lanes));
            if (!arm_instruction && insn != 0x01007557u) {
                /* Shared initialization and rendezvous are allowed only outside
                 * scopes. Their ordinary validators still prove actual stores
                 * and converged publication. A short-VL vector definition cannot
                 * authorize lanes after a later VL restore. Legacy stays exact. */
                bool shared_prefix = shared_simt && !simt_depth &&
                    opengpu_shader_shared_simt_prefix_instruction(&state, insn, shared_lanes);
                bool vector_write = opengpu_shader_writes_vector(insn);
                if (!shared_prefix || (vector_write && state.vector_length != shared_lanes))
                    simt_compatible = false;
            }
            if (simt_program && !simt_compatible) return false;
            if (simt_depth && !arm_instruction) return false;
        }
        switch (opcode) {
        case 0x5b: {
            opengpu_shader_u32 branch_pc, taken, marker;
            if (!all_kernarg_writable || fragment_ops_enabled ||
                (!storage_enabled && !(shared_atomics_enabled && shared_bytes)) ||
                scalar_branch_seen || !simt_compatible ||
                state.vector_length != (shared_atomics_enabled && shared_bytes ? shared_lanes : batch_capacity))
                return false;
            simt_program = true;
            if (funct3 == 3u) {
                opengpu_shader_u32 b, bound = word_count;
                opengpu_shader_s32 distance = opengpu_shader_simt_displacement(insn);
                if (rs1 || rs2 || distance <= 0 || (distance & 3) ||
                    simt_depth == OPENGPU_SHADER_MAX_FORWARD_BRANCHES ||
                    2u * (simt_depth + 1u) > OPENGPU_SHADER_SIMT_STACK_ENTRIES ||
                    i + 1u >= word_count)
                    return false;
                marker = i + (opengpu_shader_u32)distance / 4u;
                b = words[i + 1u];
                if ((b & 127u) != 0x5bu || ((b >> 12) & 7u) > 1u)
                    return false;
                distance = opengpu_shader_simt_displacement(b);
                if (distance <= 0 || (distance & 3)) return false;
                taken = i + 1u + (opengpu_shader_u32)distance / 4u;
                if (simt_depth) {
                    opengpu_shader_u32 parent_taken, parent_marker;
                    if (!opengpu_shader_simt_scope_at(words, i, &branch_pc,
                            &parent_taken, &parent_marker)) return false;
                    bound = i < parent_taken ? parent_taken - 1u : parent_marker - 1u;
                }
                if (taken <= i + 2u || marker <= taken || marker >= bound ||
                    marker >= word_count ||
                    words[taken - 1u] != OPENGPU_SHADER_SIMT_JOIN ||
                    words[marker - 1u] != OPENGPU_SHADER_SIMT_JOIN ||
                    words[marker] != OPENGPU_SHADER_SIMT_JOIN)
                    return false;
                ++simt_depth;
                opengpu_shader_simt_boundary(&state);
            } else if (funct3 <= 1u) {
                if (!simt_depth || !opengpu_shader_simt_scope_at(words, i,
                        &branch_pc, &taken, &marker)) return false;
                if (i != branch_pc || !vector_defined[rs1] || !vector_defined[rs2])
                    return false;
                for (branch = 0; branch < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; ++branch)
                    if (!branches[branch].used) break;
                if (branch == OPENGPU_SHADER_MAX_FORWARD_BRANCHES) return false;
                branches[branch].used = true;
                branches[branch].target = taken;
                opengpu_shader_copy_state(&branches[branch].state, &state);
            } else if (insn == OPENGPU_SHADER_SIMT_JOIN) {
                if (!simt_depth || !opengpu_shader_simt_scope_at(words, i,
                        &branch_pc, &taken, &marker)) return false;
                opengpu_shader_simt_boundary(&state);
                for (branch = 0; branch < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; ++branch)
                    if (branches[branch].used && branches[branch].target ==
                            (i < taken ? taken : marker + 1u)) break;
                if (branch == OPENGPU_SHADER_MAX_FORWARD_BRANCHES) return false;
                if (i == taken - 1u) {
                    struct opengpu_shader_branch_state *saved = &branches[branch];
                    opengpu_shader_simt_swap(&state, &saved->state);
                    saved->target = marker + 1u;
                    opengpu_shader_simt_boundary(&state);
                } else if (i == marker) {
                    --simt_depth;
                } else if (i != marker - 1u) return false;
            } else return false;
            break;
        }
        case 0x0f: /* canonical fence rw,rw for compute resource clients */
            if (insn != 0x0330000fu || !all_kernarg_writable ||
                fragment_ops_enabled)
                return false;
            break;
        case 0x73: /* shader-profile vxrm immediate write only */
            if ((insn & 0xfff07fffu) != 0x00a05073u ||
                (rs1 & ~3u))
                return false;
            break;
        case 0x13: /* RV32I OP-IMM */
            if (rd == 1 || !scalar_defined[rs1])
                return false;
            if (funct3 == 1 && funct7 != 0)
                return false;
            if (funct3 == 5 && funct7 != 0 && funct7 != 0x20)
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            imm = (opengpu_shader_s32)insn >> 20;
            if (funct3 == 0 && rs1 == 0) {
                values[rd].kind = OPENGPU_SHADER_VALUE_CONSTANT;
                values[rd].offset = imm;
            } else if (lhs.kind == OPENGPU_SHADER_VALUE_CONSTANT &&
                       (funct3 == 0 || (funct3 == 1 && funct7 == 0))) {
                opengpu_shader_s64 result = funct3 == 0 ?
                    (opengpu_shader_s64)lhs.offset + imm :
                    (opengpu_shader_s64)lhs.offset * (1ll << rs2);
                if (result >= -2147483647ll - 1 && result <= 2147483647ll) {
                    values[rd].kind = OPENGPU_SHADER_VALUE_CONSTANT;
                    values[rd].offset = (opengpu_shader_s32)result;
                }
            } else if (funct3 == 0 &&
                (lhs.kind == OPENGPU_SHADER_VALUE_KERNARG ||
                 lhs.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES ||
                 lhs.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES_PERMUTED ||
                 lhs.kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL ||
                 lhs.kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL_READ ||
                 lhs.kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL)) {
                opengpu_shader_s64 result = (opengpu_shader_s64)lhs.offset + imm;
                if (result >= -2147483647ll - 1 && result <= 2147483647ll) {
                    values[rd] = lhs;
                    values[rd].offset = (opengpu_shader_s32)result;
                }
            } else if (funct3 == 1 &&
                       lhs.kind == OPENGPU_SHADER_VALUE_LOCAL_INDEX &&
                       ((insn >> 20) & 0x1f) == 2) {
                values[rd].kind = OPENGPU_SHADER_VALUE_LOCAL_BYTES;
            } else if (shared_bytes && shared_lanes == 16u && batch_capacity == 128u &&
                       funct3 == 4u && imm == 64 &&
                       lhs.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES && !lhs.offset) {
                /* Eight warp bases are multiples of 64 bytes. XOR64 swaps
                 * adjacent warps, preserving every lane and the whole range.
                 * This exact 128-item proof earns no initialization credit. */
                values[rd].kind = OPENGPU_SHADER_VALUE_LOCAL_BYTES_PERMUTED;
            }
            if (rd == 0) {
                values[0].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
                values[0].offset = 0;
            }
            scalar_defined[rd] = true;
            break;
        case 0x33: /* RV32I/M OP */
            if (rd == 1 || !scalar_defined[rs1] || !scalar_defined[rs2] ||
                (funct7 != 0 && funct7 != 1 && funct7 != 0x20) ||
                (funct7 == 0x20 && funct3 != 0 && funct3 != 5))
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            if (funct7 == 0 && funct3 == 0 &&
                ((lhs.kind == OPENGPU_SHADER_VALUE_KERNARG &&
                  rhs.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES) ||
                 (rhs.kind == OPENGPU_SHADER_VALUE_KERNARG &&
                  lhs.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES))) {
                opengpu_shader_s64 result = (opengpu_shader_s64)lhs.offset + rhs.offset;
                if (result >= -2147483647ll - 1 && result <= 2147483647ll) {
                    values[rd].kind = OPENGPU_SHADER_VALUE_KERNARG_LOCAL;
                    values[rd].offset = (opengpu_shader_s32)result;
                }
            }
            if (funct3 == 0 && (funct7 == 0 || funct7 == 1)) {
                opengpu_shader_s64 result;
                if (lhs.kind == OPENGPU_SHADER_VALUE_CONSTANT &&
                    rhs.kind == OPENGPU_SHADER_VALUE_CONSTANT) {
                    result = funct7 == 1 ? (opengpu_shader_s64)lhs.offset * rhs.offset :
                                          (opengpu_shader_s64)lhs.offset + rhs.offset;
                    if (result >= -2147483647ll - 1 && result <= 2147483647ll) {
                        values[rd].kind = OPENGPU_SHADER_VALUE_CONSTANT;
                        values[rd].offset = (opengpu_shader_s32)result;
                    }
                } else if (funct7 == 0 &&
                           (lhs.kind == OPENGPU_SHADER_VALUE_CONSTANT ||
                            rhs.kind == OPENGPU_SHADER_VALUE_CONSTANT)) {
                    struct opengpu_shader_value base =
                        lhs.kind == OPENGPU_SHADER_VALUE_CONSTANT ? rhs : lhs;
                    struct opengpu_shader_value offset =
                        lhs.kind == OPENGPU_SHADER_VALUE_CONSTANT ? lhs : rhs;
                    result = (opengpu_shader_s64)base.offset + offset.offset;
                    if (shared_bytes && (opengpu_shader_u32)offset.offset == shared_base &&
                        (base.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES ||
                         base.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES_PERMUTED)) {
                        values[rd] = base;
                        values[rd].kind = base.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES ?
                            OPENGPU_SHADER_VALUE_SHARED_LOCAL :
                            OPENGPU_SHADER_VALUE_SHARED_LOCAL_READ;
                    } else if ((base.kind == OPENGPU_SHADER_VALUE_KERNARG ||
                         base.kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL ||
                         base.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES ||
                         base.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES_PERMUTED ||
                         base.kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL ||
                         base.kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL_READ) &&
                        result >= -2147483647ll - 1 && result <= 2147483647ll) {
                        values[rd] = base;
                        values[rd].offset = (opengpu_shader_s32)result;
                    }
                }
            }
            if (rd == 0) {
                values[0].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
                values[0].offset = 0;
            }
            scalar_defined[rd] = true;
            break;
        case 0x17: /* AUIPC */
        case 0x37: /* LUI */
            if (rd == 1)
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            if (opcode == 0x37 && rd && shared_bytes) {
                values[rd].kind = OPENGPU_SHADER_VALUE_CONSTANT;
                values[rd].offset = (opengpu_shader_s32)(insn & 0xfffff000u);
            }
            scalar_defined[rd] = true;
            break;
        case 0x03: { /* bounded scalar read from a proven kernarg base */
            opengpu_shader_u32 bytes =
                funct3 == 0u || funct3 == 4u ? 1u :
                funct3 == 1u || funct3 == 5u ? 2u :
                funct3 == 2u ? 4u : 0u;

            imm = (opengpu_shader_s32)insn >> 20;
            if (!scalar_defined[rs1] || rd == 1 ||
                !opengpu_shader_scalar_read_valid(&lhs, imm, bytes, kernarg_size))
                return false;
            if (input_read_mask)
                input_reads |= opengpu_shader_io_range_mask(
                    (opengpu_shader_s64)lhs.offset + imm, bytes, stride);
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            scalar_defined[rd] = true;
            break;
        }
        case 0x2f: { /* Capability-gated, bounded compute word AMO/CAS. */
            opengpu_shader_u32 operation = insn >> 27;
            bool supported = operation == 0u || operation == 1u ||
                operation == 4u || operation == 5u || operation == 8u ||
                operation == 12u || operation == 16u || operation == 20u ||
                operation == 24u || operation == 28u;

            /* Graphics payloads and arbitrary pointers have a different
             * ownership contract. Do not admit them through this compute gate.
             * Acquire/release bits await the broader ordering qualification. */
            if (!word_atomics_enabled || !all_kernarg_writable ||
                fragment_ops_enabled || (texture_enabled && !texture_backings_disjoint) || funct3 != 2u ||
                (insn & (3u << 25)) || !supported || rd == 1u ||
                !scalar_defined[rs1] || !scalar_defined[rs2] ||
                (operation == 5u && !scalar_defined[rd]) ||
                !opengpu_shader_scalar_read_valid(&lhs, 0, 4, kernarg_size))
                return false;
            input_reads |= opengpu_shader_io_range_mask(lhs.offset, 4, stride);
            output_writes |= opengpu_shader_io_range_mask(lhs.offset, 4, stride);
            /* The old memory value is defined, but cannot prove a pointer. */
            opengpu_shader_define_integer(&state, rd);
            break;
        }
        case 0x23: { /* sb/sh/sw imm(x1) into the output window */
            opengpu_shader_u32 bytes =
                funct3 == 0u ? 1u : funct3 == 1u ? 2u : funct3 == 2u ? 4u : 0u;

            imm = (opengpu_shader_s32)(((insn >> 7) & 0x1f) |
                                       ((insn >> 25) << 5));
            if (imm & 0x800)
                imm |= (opengpu_shader_s32)~0xfff;
            if (!bytes || rs1 != 1 || !scalar_defined[rs2] || imm < 0 ||
                (imm & (opengpu_shader_s32)(bytes - 1u)) ||
                (opengpu_shader_u64)(opengpu_shader_u32)imm < output_start)
                return false;
            end = (opengpu_shader_u64)(opengpu_shader_u32)imm + bytes;
            if (end > output_end)
                return false;
            if (output_write_mask)
                output_writes |= opengpu_shader_io_range_mask(
                    (opengpu_shader_u32)imm, bytes, stride);
            break;
        }
        case 0x57: /* fixed-profile vector configuration or allow-listed ALU */
            /* vsetvli rd,x0,e32,m1: nonzero rd requests VLMAX. Bound vector
             * accesses by the full admitted batch, independently of VLEN. */
            if ((insn & 0xfffff07fu) == 0x01007057u) {
                if (!rd || rd == 1)
                    return false;
                state.texture_parameter_pairs = 0;
                state.image_parameter_pairs = 0;
                state.image_result_valid = false;
                state.storage_values_valid = false;
                state.storage_atomic_values_valid = false;
                state.shared_atomic_values_valid = false;
                state.vector_packet_valid = 0;
                state.vector_local_packets = 0;
                state.vector_length = batch_capacity;
                opengpu_shader_define_integer(&state, rd);
            } else if ((insn & 0xfff07fffu) == 0xc1007057u) {
                state.texture_parameter_pairs = 0;
                state.image_parameter_pairs = 0;
                state.image_result_valid = false;
                state.storage_values_valid = false;
                state.storage_atomic_values_valid = false;
                state.shared_atomic_values_valid = false;
                state.vector_packet_valid = 0;
                state.vector_local_packets = 0;
                state.vector_length = rs1;
                if (!state.vector_length ||
                    state.vector_length > batch_capacity)
                    return false;
            } else {
                bool opfvv = funct3 == 1;
                bool opfvf = funct3 == 5;
                bool fp = opfvv || opfvf;
                bool vfunary0 = opfvv && (insn >> 26) == 0x12;
                bool vfunary1 = opfvv && (insn >> 26) == 0x13;
                bool vfmerge = opfvf && (insn >> 26) == 0x17;
                /* vfmv.v.f is vfmerge with the mask bit set; vs2 is unused. */
                bool vfmv = vfmerge && (insn & (1u << 25));
                /* vmv.v.v/x/i encode vs2 as v0 and do not read it. */
                bool integer_mv = (insn >> 26) == 0x17 &&
                    (insn & (1u << 25)) &&
                    (funct3 == 0 || funct3 == 3 || funct3 == 4);
                /* vmv.x.s copies vs2[0] into integer rd. vs1 is the opcode. */
                bool vmv_xs = funct3 == 2 && (insn >> 26) == 0x10 &&
                    (insn & (1u << 25)) && rs1 == 0;
                /* vmv.s.x writes the scalar into vd[0]. vs2 is v0 and unread. */
                bool vmv_sx = funct3 == 6 && (insn >> 26) == 0x10 &&
                    (insn & (1u << 25)) && rs2 == 0;
                /* vfmv.f.s copies vs2[0] into f[rd]. vs1 is the opcode. */
                bool vfmv_fs = funct3 == 1 && (insn >> 26) == 0x10 &&
                    (insn & (1u << 25)) && rs1 == 0;
                /* vfmv.s.f writes f[rs1] into vd[0]. vs2 is v0 and unread. */
                bool vfmv_sf = funct3 == 5 && (insn >> 26) == 0x10 &&
                    (insn & (1u << 25)) && rs2 == 0;
                /* vfredusum.vs / vfredosum.vs. vs1 holds the seed. */
                bool fp_reduction = opfvv &&
                    ((insn >> 26) == 0x01 || (insn >> 26) == 0x03);
                /* The funct6 010100 family selects its operation in vs1, so
                 * that field never names a register. viota.m does read vs2. */
                bool mask_scan = funct3 == 2 && (insn >> 26) == 0x14;
                /* vid.v is the only member that reads no vector source at
                 * all; viota.m reads vs2 as the mask it accumulates. */
                bool element_index = mask_scan &&
                    ((insn >> 15) & 0x1f) == 17;
                /* vmadd/vnmsub/vmacc/vnmsac: the destination is the third
                 * operand, so it must be a defined vector register. */
                bool multiply_accumulate =
                    (funct3 == 2 || funct3 == 6) &&
                    ((insn >> 26) == 0x29 || (insn >> 26) == 0x2b ||
                     (insn >> 26) == 0x2d || (insn >> 26) == 0x2f);

                if (!state.vector_length ||
                    !opengpu_shader_vector_alu_valid(insn) ||
                    (!vfmv && !integer_mv && !vmv_sx && !vfmv_sf &&
                     !element_index && !vector_defined[rs2]) ||
                    (vfmerge && !scalar_fpu_enabled) ||
                    ((vfmv_fs || vfmv_sf) && !scalar_fpu_enabled) ||
                    (!fp && (insn >> 26) >= 0x2c && (insn >> 26) <= 0x2f &&
                     !vector_defined[rs2 + 1]) ||
                    (!(insn & (1u << 25)) &&
                     (!vector_defined[0] || !vector_defined[rd])) ||
                    /* OPMVV vsext/vzext. OPFVV vfcvt uses the same funct6
                     * with the conversion opcode in vs1. */
                    ((insn >> 26) == 0x12 && !vfunary0 &&
                     !(rs1 == 2 || rs1 == 3 || rs1 == 4 || rs1 == 5 ||
                       rs1 == 6 || rs1 == 7)) ||
                    ((insn >> 26) <= 0x07 && funct3 == 2 &&
                     !vector_defined[rd]) ||
                    /* A reduction only rewrites element 0 and reads the old
                     * destination for the rest, so vd must be defined. */
                    (fp_reduction && !vector_defined[rd]) ||
                    (multiply_accumulate && !vector_defined[rd]) ||
                    ((insn >> 26) == 0x0e && !vector_defined[rd]) ||
                    ((funct3 == 0 || funct3 == 2) && (insn >> 26) != 0x12 &&
                     !vmv_xs && !mask_scan &&
                     !vector_defined[rs1]) ||
                    /* Binary OPFVV reads vs1. VFUNARY0/1 encode the op there. */
                    (opfvv && !vfunary0 && !vfunary1 && !vfmv_fs &&
                     !vector_defined[rs1]) ||
                    (opfvf && !state.fp_defined[rs1]) ||
                    /* Fused FMA forms read the old destination. */
                    (fp && (insn >> 26) >= 0x28 && (insn >> 26) <= 0x2f &&
                     !vector_defined[rd]) ||
                    ((funct3 == 4 || funct3 == 6) &&
                     !scalar_defined[rs1]))
                    return false;
                if (vfmv_fs) {
                    state.fp_defined[rd] = true;
                    break;
                }
                if (vmv_xs) {
                    if (rd == 1)
                        return false;
                    values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
                    values[rd].offset = 0;
                    if (rd == 0) {
                        values[0].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
                        values[0].offset = 0;
                    }
                    scalar_defined[rd] = true;
                    break;
                }
                /* Save the source before clearing rd: vmv.v.v may be in-place. */
                {
                    bool packet_valid = false;
                    bool packet_indexed = false;
                    bool local_packets = (insn & (1u << 25)) &&
                        (insn >> 26) == 0x25 && funct3 == 3 && rs1 == 6 &&
                        (state.vector_local_indices & (1u << rs2));
                    unsigned char packet_source = 0;
                    if (integer_mv && funct3 == 4 &&
                        values[rs1].kind == OPENGPU_SHADER_VALUE_KERNARG) {
                        packet_valid = true;
                        packet_source = (unsigned char)rs1;
                    } else if (integer_mv && funct3 == 0 &&
                               (state.vector_packet_valid & (1u << rs1))) {
                        packet_valid = true;
                        packet_source = state.vector_packet_sources[rs1];
                        packet_indexed = !!(state.vector_packet_indexed & (1u << rs1));
                    } else if ((insn & (1u << 25)) && (insn >> 26) == 0 &&
                               funct3 == 4 && (state.vector_local_packets & (1u << rs2)) &&
                               values[rs1].kind == OPENGPU_SHADER_VALUE_KERNARG) {
                        packet_valid = true;
                        packet_indexed = true;
                        packet_source = (unsigned char)rs1;
                    }
                    state.vector_packet_valid &= ~(1u << rd);
                    state.vector_packet_indexed &= ~(1u << rd);
                    state.vector_local_packets &= ~(1u << rd);
                    if (local_packets)
                        state.vector_local_packets |= 1u << rd;
                    if (packet_valid) {
                        state.vector_packet_valid |= 1u << rd;
                        if (packet_indexed)
                            state.vector_packet_indexed |= 1u << rd;
                        state.vector_packet_sources[rd] = packet_source;
                    }
                }
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                /* A masked shift can retain arbitrary old destination lanes. */
                if ((insn & (1u << 25)) &&
                    (insn >> 26) == 0x25 && funct3 == 3 && rs1 == 2 &&
                    (state.vector_local_indices & (1u << rs2)))
                    state.vector_local_bytes |= 1u << rd;
                vector_defined[rd] = true;
            }
            break;
        case 0x07: { /* proven kernarg flw, or an 8/16/32-bit vector load */
            bool strided, indexed;
            bool shared = lhs.kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL ||
                          lhs.kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL_READ;
            opengpu_shader_u32 elem;

            if (funct3 == 2) {
                imm = (opengpu_shader_s32)insn >> 20;
                if (!scalar_fpu_enabled || !scalar_defined[rs1] ||
                    !opengpu_shader_scalar_read_valid(&lhs, imm, 4, kernarg_size))
                    return false;
                if (input_read_mask)
                    input_reads |= opengpu_shader_io_range_mask(
                        (opengpu_shader_s64)lhs.offset + imm, 4, stride);
                state.fp_defined[rd] = true;
                break;
            }
            if (!opengpu_shader_vector_memory_form(
                    insn, 0x07u, &strided, &indexed, &elem) ||
                !scalar_defined[rs1] ||
                (strided && !scalar_defined[rs2]) ||
                (indexed && !vector_defined[rs2]) ||
                (!(insn & (1u << 25)) &&
                 (!vector_defined[0] || rd == 0 || !vector_defined[rd])) ||
                (shared ? !opengpu_shader_shared_access_valid(
                    &state, &lhs, state.vector_length, elem, false,
                    !!(insn & (1u << 25)), !strided && !indexed,
                    batch_capacity, shared_base, shared_bytes, shared_lanes) : indexed ?
                 !opengpu_shader_vector_indexed_access_valid(
                     &values[rs1],
                     !!(state.vector_local_bytes & (1u << rs2)), false,
                     kernarg_size,
                     batch_capacity, output_start, output_end) : strided ?
                 !opengpu_shader_vector_strided_access_valid(
                     &values[rs1], &values[rs2], state.vector_length, elem,
                     false, kernarg_size, output_start, output_end) :
                 !opengpu_shader_vector_access_valid(
                     &values[rs1], state.vector_length, elem, false,
                     kernarg_size, batch_capacity, output_start, output_end)))
                return false;
            if (input_read_mask && !shared) {
                /* Keep complex and narrow warp-relative accesses conservative.
                 * Their validation/admission is completely unchanged. */
                if (strided || indexed ||
                    (elem != 4 && lhs.kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL))
                    input_reads = 0x1ffu;
                else
                    input_reads |= opengpu_shader_io_range_mask(
                        (opengpu_shader_u32)lhs.offset,
                        (opengpu_shader_u64)elem *
                            (lhs.kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL ?
                             batch_capacity : state.vector_length), stride);
            }
            state.vector_packet_valid &= ~(1u << rd);
            state.vector_local_packets &= ~(1u << rd);
            state.vector_local_indices &= ~(1u << rd);
            state.vector_local_bytes &= ~(1u << rd);
            vector_defined[rd] = true;
            break;
        }
        case 0x27: { /* fsw imm(x1), or an 8/16/32-bit vector store */
            bool strided, indexed;
            bool shared = lhs.kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL ||
                          lhs.kind == OPENGPU_SHADER_VALUE_SHARED_LOCAL_READ;
            opengpu_shader_u32 elem;

            /* funct3 2 is the scalar FP store. Vector stores use funct3
             * 0, 5, or 6, so the two do not share an encoding. */
            if (funct3 == 2) {
                imm = (opengpu_shader_s32)(((insn >> 7) & 0x1f) |
                                           ((insn >> 25) << 5));
                if (imm & 0x800)
                    imm |= (opengpu_shader_s32)~0xfff;
                end = (opengpu_shader_u64)(opengpu_shader_u32)imm + 4;
                if (!scalar_fpu_enabled || rs1 != 1 ||
                    !state.fp_defined[rs2] || imm < 0 || (imm & 3) ||
                    (opengpu_shader_u64)(opengpu_shader_u32)imm <
                        output_start ||
                    end > output_end)
                    return false;
                if (output_write_mask)
                    output_writes |= opengpu_shader_io_range_mask(
                        (opengpu_shader_u32)imm, 4, stride);
                break;
            }
            if (!opengpu_shader_vector_memory_form(
                    insn, 0x27u, &strided, &indexed, &elem) ||
                !scalar_defined[rs1] || !vector_defined[rd] ||
                (strided && !scalar_defined[rs2]) ||
                (indexed && !vector_defined[rs2]) ||
                (!(insn & (1u << 25)) && !vector_defined[0]) ||
                (shared ? !opengpu_shader_shared_access_valid(
                    &state, &lhs, state.vector_length, elem, true,
                    !!(insn & (1u << 25)), !strided && !indexed,
                    batch_capacity, shared_base, shared_bytes, shared_lanes) : indexed ?
                 !opengpu_shader_vector_indexed_access_valid(
                     &values[rs1],
                     !!(state.vector_local_bytes & (1u << rs2)), true,
                     kernarg_size,
                     batch_capacity, output_start, output_end) : strided ?
                 !opengpu_shader_vector_strided_access_valid(
                     &values[rs1], &values[rs2], state.vector_length, elem,
                     true, kernarg_size, output_start, output_end) :
                 !opengpu_shader_vector_access_valid(
                     &values[rs1], state.vector_length, elem, true,
                     kernarg_size, batch_capacity, output_start, output_end)))
                return false;
            if (output_write_mask && !shared) {
                if (strided || indexed ||
                    (elem != 4 && lhs.kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL))
                    output_writes = 0x1ffu;
                else
                    output_writes |= opengpu_shader_io_range_mask(
                        (opengpu_shader_u32)lhs.offset,
                        (opengpu_shader_u64)elem *
                            (lhs.kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL ?
                             batch_capacity : state.vector_length), stride);
            }
            break;
        }
        case 0x63: { /* bounded forward scalar conditional branch */
            opengpu_shader_s32 target;

            if (simt_program) return false;
            scalar_branch_seen = true;

            imm = ((insn >> 31) & 1) << 12 |
                  ((insn >> 7) & 1) << 11 |
                  ((insn >> 25) & 0x3f) << 5 |
                  ((insn >> 8) & 0xf) << 1;
            if (imm & 0x1000)
                imm |= (opengpu_shader_s32)~0x1fff;
            target = (opengpu_shader_s32)i + imm / 4;
            if (imm <= 0 || (imm & 3) || target <= (opengpu_shader_s32)i ||
                target >= (opengpu_shader_s32)word_count ||
                funct3 == 2 || funct3 == 3 ||
                !scalar_defined[rs1] || !scalar_defined[rs2] ||
                !opengpu_shader_queue_branch(branches,
                    (opengpu_shader_u32)target, &state))
                return false;
            break;
        }
        case 0x0b: /* compute workgroup barrier or scalar texture operations */
            if (insn == 0x0000000bu) {
                /* Every live path must have reconverged here, and no peer
                 * may have terminated before reaching this generation. */
                if (!all_kernarg_writable || fragment_ops_enabled || divergent_exit)
                    return false;
                for (branch = 0; branch < OPENGPU_SHADER_MAX_FORWARD_BRANCHES;
                     ++branch)
                    if (branches[branch].used)
                        return false;
                state.shared_visible_prefix = state.shared_written_prefix;
                break;
            }
            if (funct7 == 7u) {
                /* GUR1/GUB1 checks the dynamic block and byte offset. A loaded
                 * value does not authorize ordinary memory or packet reads. */
                if (!uniform_enabled || funct3 || rd == 1u ||
                    !scalar_defined[rs1] || !scalar_defined[rs2])
                    return false;
                opengpu_shader_define_integer(&state, rd);
                break;
            }
            if (!texture_enabled || !scalar_defined[rs1] ||
                !scalar_defined[rs2])
                return false;
            if (funct7 == 3u && rd == 0u) {
                /* Pair zero starts a new complete on-chip operand tuple. */
                state.texture_parameter_pairs = funct3 == 0u ? 1u :
                    state.texture_parameter_pairs | (1u << funct3);
                break;
            }
            if (rd == 1u || funct3 >= (funct7 == 6u ? 3u : 4u) ||
                (funct7 != 2u && funct7 != 4u && funct7 != 5u && funct7 != 6u && funct7 != 28u && funct7 != 29u) ||
                ((funct7 == 4u || funct7 == 5u || funct7 == 28u || funct7 == 29u) && state.texture_parameter_pairs != 0xffu))
                return false;
            /* A packet read must retain the existing bounded-memory sandbox.
             * Loaded/unknown addresses cannot authorize an indirect GXQ1 read.
             * The uniform tail is immutable; private local payloads are not
             * exposed through the separate texture-memory client. */
            if (funct7 == 2u &&
                (!opengpu_shader_scalar_read_valid(&lhs, 0, 64, kernarg_size) ||
                 (opengpu_shader_u64)(opengpu_shader_u32)lhs.offset < output_end))
                return false;
            opengpu_shader_define_integer(&state, rd);
            break;
        case 0x2b: { /* OpenGPU texture and fragment-quad operations */
            opengpu_shader_u32 function = insn >> 26;
            bool unmasked = (insn & (1u << 25)) != 0u;
            bool texture_result =
                (function == 1u && funct3 == 0u) ||
                (function == 2u && funct3 < 4u) ||
                ((function == 4u || function == 5u) && funct3 < 4u) ||
                (function == 6u && funct3 < 3u) ||
                ((function == 28u || function == 29u) && funct3 < 4u);

            if (function == 7u) {
                if (!uniform_enabled || !unmasked || funct3 ||
                    !state.vector_length || !vector_defined[rs1] ||
                    !vector_defined[rs2])
                    return false;
                vector_defined[rd] = true;
                state.vector_packet_valid &= ~(1u << rd);
                state.vector_local_packets &= ~(1u << rd);
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                break;
            }

            if (function >= 30u && function <= 32u) {
                /* Distinct per-invocation shared instructions. Never authorize
                 * them from scalar WORD_ATOMICS or an unrelated texture bit. */
                if (!shared_atomics_enabled || !shared_bytes || !unmasked ||
                    state.vector_length != shared_lanes || !shared_lanes)
                    return false;
                if (function == 30u) {
                    if (rd || funct3 || !vector_defined[rs1] || !vector_defined[rs2])
                        return false;
                    state.shared_atomic_values_valid = true;
                    break;
                }
                if ((function == 32u && funct3 >= 2u) ||
                    !state.shared_atomic_values_valid || !scalar_defined[rs1] ||
                    lhs.kind != OPENGPU_SHADER_VALUE_CONSTANT ||
                    (rs2 && !vector_defined[rs2]))
                    return false;
                {
                    opengpu_shader_u64 address = (opengpu_shader_u32)lhs.offset;
                    struct opengpu_shared_range r = rs2 && state.shared_own ?
                        state.shared_own->vectors[rs2] : opengpu_range_constant(0);
                    opengpu_shader_u64 first, end;
                    opengpu_shader_u64 offset;
                    if ((address & 3u) || address < shared_base)
                        return false;
                    offset = address - shared_base;
                    first = offset + r.first; end = offset + r.last + 4ull;
                    if (!r.valid || (r.zero & 3u) != 3u || first > end ||
                        end > shared_bytes || address + r.last + 4ull > 0x100000000ull ||
                        (end > state.shared_visible_prefix &&
                         !(!simt_depth && rs2 && (state.vector_local_bytes & (1u << rs2)) &&
                           opengpu_shared_own_contains(state.shared_own,
                            (opengpu_shader_u32)offset, 4u * batch_capacity))))
                        return false;
                }
                vector_defined[rd] = true;
                state.vector_packet_valid &= ~(1u << rd);
                state.vector_local_packets &= ~(1u << rd);
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                break;
            }
            if (function >= 17u && function <= 19u) {
                /* The retained GAM1 table checks the dynamic lane index and
                 * permissions; masked side effects are not admitted here. */
                if (!counter_enabled || !unmasked || funct3 || rs2 ||
                    !state.vector_length || !vector_defined[rs1])
                    return false;
                vector_defined[rd] = true;
                state.vector_packet_valid &= ~(1u << rd);
                state.vector_local_packets &= ~(1u << rd);
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                break;
            }
            if (function >= 24u && function <= 26u) {
                /* Per-lane atomic operand/compare capture and checked storage
                 * access require an explicitly enabled, retained GSB1 task. */
                if (!storage_enabled || !storage_atomics_enabled || !unmasked ||
                    !state.vector_length || !vector_defined[rs1] ||
                    !vector_defined[rs2]) return false;
                if (function == 24u) {
                    if (rd || funct3) return false;
                    state.storage_atomic_values_valid = true;
                    break;
                }
                if ((function == 26u && funct3 >= 2u) ||
                    !state.storage_atomic_values_valid) return false;
                vector_defined[rd] = true;
                state.vector_packet_valid &= ~(1u << rd);
                state.vector_local_packets &= ~(1u << rd);
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                break;
            }
            if (function == 23u) {
                /* Atomic operands and compare value are captured in pair 2;
                 * the image service checks format, bounds and permissions. */
                if (!image_enabled || !image_atomics_enabled || !unmasked ||
                    !state.vector_length || !vector_defined[rs1] ||
                    !vector_defined[rs2] || (state.image_parameter_pairs & 7u) != 7u)
                    return false;
                vector_defined[rd] = true;
                state.vector_packet_valid &= ~(1u << rd);
                state.vector_local_packets &= ~(1u << rd);
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                break;
            }
            if (function == 27u) {
                /* A metadata-only buffer size query takes direct operands;
                 * it neither consumes parameter nor grants load-result credit. */
                if (!image_enabled || !buffer_image_size_enabled ||
                    !unmasked || funct3 || !state.vector_length ||
                    !vector_defined[rs1] || !vector_defined[rs2]) return false;
                vector_defined[rd] = true;
                state.vector_packet_valid &= ~(1u << rd);
                state.vector_local_packets &= ~(1u << rd);
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                break;
            }
            if (function >= 8u && function <= 11u) {
                if (!image_enabled || !unmasked || !state.vector_length)
                    return false;
                if (function == 8u) {
                    if (rd || funct3 >= 4u || !vector_defined[rs1] ||
                        !vector_defined[rs2]) return false;
                    if (!funct3) {
                        state.image_parameter_pairs = 1u;
                        state.image_result_valid = false;
                    } else {
                        state.image_parameter_pairs |= 1u << funct3;
                    }
                    break;
                }
                if (function == 11u) {
                    if (funct3 < 1u || funct3 > 3u || rs1 || rs2 ||
                        !state.image_result_valid) return false;
                } else {
                    if (funct3 || !vector_defined[rs1] || !vector_defined[rs2] ||
                        (state.image_parameter_pairs & 3u) != 3u) return false;
                    if (function == 10u) {
                        if (rd || state.image_parameter_pairs != 15u) return false;
                        break;
                    }
                    state.image_result_valid = true;
                }
                vector_defined[rd] = true;
                state.vector_packet_valid &= ~(1u << rd);
                state.vector_local_packets &= ~(1u << rd);
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                break;
            }

            if (function >= 14u && function <= 16u) {
                /* The storage service checks each dynamic binding and word
                 * offset against retained descriptor permissions and bounds. */
                if (!storage_enabled || !unmasked || funct3 ||
                    !state.vector_length || !vector_defined[rs1])
                    return false;
                if (function == 14u) {
                    if (rd) return false;
                    state.storage_values_valid = true;
                    break;
                }
                if (!vector_defined[rs2]) return false;
                if (function == 16u) {
                    if (rd || !state.storage_values_valid) return false;
                    break;
                }
                vector_defined[rd] = true;
                state.vector_packet_valid &= ~(1u << rd);
                state.vector_local_packets &= ~(1u << rd);
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                break;
            }
            if (function == 3u && rd == 0u && unmasked) {
                if (!texture_enabled || !state.vector_length ||
                    !vector_defined[rs1] || !vector_defined[rs2])
                    return false;
                state.texture_parameter_pairs = funct3 == 0u ? 1u :
                    state.texture_parameter_pairs | (1u << funct3);
                /* No vector destination: preserve v0 provenance/definition. */
                break;
            }
            if (texture_result && unmasked) {
                if (!texture_enabled || !state.vector_length ||
                    !vector_defined[rs1] || !vector_defined[rs2] ||
                    (function == 2u &&
                     (!(state.vector_packet_valid & (1u << rs1)) ||
                      !opengpu_shader_scalar_read_valid(
                          &values[state.vector_packet_sources[rs1]], 0, 64, kernarg_size) ||
                      (opengpu_shader_u64)(opengpu_shader_u32)
                          values[state.vector_packet_sources[rs1]].offset < output_end ||
                      ((state.vector_packet_indexed & (1u << rs1)) &&
                       (opengpu_shader_u64)(opengpu_shader_u32)
                           values[state.vector_packet_sources[rs1]].offset +
                           64ull * batch_capacity > kernarg_size))) ||
                    ((function == 4u || function == 5u || function == 28u || function == 29u) && state.texture_parameter_pairs != 0xffu))
                    return false;
            } else if ((insn & 0xfe0ff07fu) == 0x3200002bu ||
                       (insn & 0xfe0ff07fu) == 0x3600002bu) {
                if (!fragment_ops_enabled || !state.vector_length ||
                    !vector_defined[rs2])
                    return false;
            } else {
                return false;
            }
            vector_defined[rd] = true;
            state.vector_packet_valid &= ~(1u << rd);
            state.vector_local_packets &= ~(1u << rd);
            state.vector_local_indices &= ~(1u << rd);
            state.vector_local_bytes &= ~(1u << rd);
            break;
        }
        case 0x43: /* fmadd.s */
        case 0x47: /* fmsub.s */
        case 0x4b: /* fnmsub.s */
        case 0x4f: { /* fnmadd.s */
            opengpu_shader_u32 rs3 = insn >> 27;
            opengpu_shader_u32 fmt = (insn >> 25) & 3u;

            if (!scalar_fpu_enabled || fmt ||
                !opengpu_shader_fp_rounding_valid(funct3) ||
                !state.fp_defined[rs1] || !state.fp_defined[rs2] ||
                !state.fp_defined[rs3])
                return false;
            state.fp_defined[rd] = true;
            break;
        }
        case 0x53: { /* FP32 ops that retire on the scalar FPU fast path */
            opengpu_shader_u32 funct5 = insn >> 27;
            opengpu_shader_u32 fmt = (insn >> 25) & 3u;
            bool fp_sources =
                state.fp_defined[rs1] && state.fp_defined[rs2];
            bool binop = funct5 <= 2u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool div = funct5 == 0x03u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool sqrt = funct5 == 0x0bu && rs2 == 0u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool sgnj = funct5 == 0x04u && funct3 <= 2u;
            bool minmax = funct5 == 0x05u && funct3 <= 1u;
            bool compare = funct5 == 0x14u && funct3 <= 2u;
            bool fcvt_to_int = funct5 == 0x18u && rs2 <= 1u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool move_or_class = funct5 == 0x1cu && rs2 == 0u &&
                funct3 <= 1u;
            bool fcvt_to_fp = funct5 == 0x1au && rs2 <= 1u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool move_to_fp = funct5 == 0x1eu && rs2 == 0u && funct3 == 0u;

            if (!scalar_fpu_enabled || fmt)
                return false;
            if (binop || sgnj || minmax || div) {
                if (!fp_sources)
                    return false;
                state.fp_defined[rd] = true;
                break;
            }
            if (sqrt) {
                if (!state.fp_defined[rs1])
                    return false;
                state.fp_defined[rd] = true;
                break;
            }
            if (compare || fcvt_to_int || move_or_class) {
                if (rd == 1 ||
                    (compare ? !fp_sources : !state.fp_defined[rs1]))
                    return false;
                opengpu_shader_define_integer(&state, rd);
                break;
            }
            if (fcvt_to_fp || move_to_fp) {
                if (!scalar_defined[rs1])
                    return false;
                state.fp_defined[rd] = true;
                break;
            }
            return false;
        }
        default:
            return false;
        }
        if (state.shared_own && opengpu_shader_writes_vector(insn))
            state.shared_own->vectors[rd] = vector_range;
        /* The vector still contains the old value if its scalar source changes. */
        if (old_destination.kind != values[rd].kind ||
            old_destination.offset != values[rd].offset || !scalar_defined[rd]) {
            opengpu_shader_u32 reg;
            for (reg = 0; reg < 32; reg++)
                if (state.vector_packet_sources[reg] == rd)
                    state.vector_packet_valid &= ~(1u << reg);
        }
    }
    return false;
}

static inline bool opengpu_shader_validate_words_profile_runtime_features_buffer_images_shared_atomics(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask,
    opengpu_shader_u32 *output_write_mask, bool word_atomics_enabled,
    bool texture_backings_disjoint, bool storage_enabled, bool image_enabled,
    bool image_atomics_enabled, bool storage_atomics_enabled, bool counter_enabled,
    bool uniform_enabled, opengpu_shader_u32 shared_base,
    opengpu_shader_u32 shared_bytes, opengpu_shader_u32 shared_lanes,
    bool buffer_image_size_enabled, bool shared_atomics_enabled)
{
    struct opengpu_shared_own_proof *proofs = NULL;
    bool valid;
    /* Fixed per-validation heap workspace, never five large kernel-stack
     * arrays. Non-shared profiles allocate nothing; every exit frees it. */
    if (shared_bytes) {
#ifdef __KERNEL__
        proofs = kcalloc(OPENGPU_SHADER_MAX_FORWARD_BRANCHES + 1u,
                         sizeof(*proofs), GFP_KERNEL);
#else
        proofs = calloc(OPENGPU_SHADER_MAX_FORWARD_BRANCHES + 1u, sizeof(*proofs));
#endif
        if (!proofs) {
            if (input_read_mask) *input_read_mask = 0x1ffu;
            if (output_write_mask) *output_write_mask = 0x1ffu;
            return false;
        }
    }
    valid = opengpu_shader_validate_words_profile_runtime_features_buffer_images_proofs(
        words, word_count, kernarg_size, batch_capacity, output_start_slice,
        output_end_slice, fragment_ops_enabled, texture_enabled,
        all_kernarg_writable, scalar_fpu_enabled, launch_capacity,
        input_read_mask, output_write_mask, word_atomics_enabled,
        texture_backings_disjoint, storage_enabled, image_enabled,
        image_atomics_enabled, storage_atomics_enabled, counter_enabled,
        uniform_enabled, shared_base, shared_bytes, shared_lanes,
        buffer_image_size_enabled, shared_atomics_enabled, proofs);
#ifdef __KERNEL__
    kfree(proofs);
#else
    free(proofs);
#endif
    return valid;
}

static inline bool opengpu_shader_validate_words_profile_runtime_features_buffer_images(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask,
    opengpu_shader_u32 *output_write_mask, bool word_atomics_enabled,
    bool texture_backings_disjoint, bool storage_enabled, bool image_enabled,
    bool image_atomics_enabled, bool storage_atomics_enabled, bool counter_enabled,
    bool uniform_enabled, opengpu_shader_u32 shared_base,
    opengpu_shader_u32 shared_bytes, opengpu_shader_u32 shared_lanes,
    bool buffer_image_size_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_buffer_images_shared_atomics(
        words, word_count, kernarg_size, batch_capacity, output_start_slice,
        output_end_slice, fragment_ops_enabled, texture_enabled,
        all_kernarg_writable, scalar_fpu_enabled, launch_capacity,
        input_read_mask, output_write_mask, word_atomics_enabled,
        texture_backings_disjoint, storage_enabled, image_enabled,
        image_atomics_enabled, storage_atomics_enabled, counter_enabled,
        uniform_enabled, shared_base, shared_bytes, shared_lanes,
        buffer_image_size_enabled, false);
}

/* Trusted native callers retain the existing image-instruction contract.
 * Linux submission uses the separately discovered entry below instead. */
static inline bool opengpu_shader_validate_words_profile_runtime_features_shared(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask,
    opengpu_shader_u32 *output_write_mask, bool word_atomics_enabled,
    bool texture_backings_disjoint, bool storage_enabled, bool image_enabled,
    bool image_atomics_enabled, bool storage_atomics_enabled, bool counter_enabled,
    bool uniform_enabled, opengpu_shader_u32 shared_base,
    opengpu_shader_u32 shared_bytes, opengpu_shader_u32 shared_lanes)
{
    return opengpu_shader_validate_words_profile_runtime_features_buffer_images(
        words, word_count, kernarg_size, batch_capacity, output_start_slice,
        output_end_slice, fragment_ops_enabled, texture_enabled,
        all_kernarg_writable, scalar_fpu_enabled, launch_capacity,
        input_read_mask, output_write_mask, word_atomics_enabled,
        texture_backings_disjoint, storage_enabled, image_enabled,
        image_atomics_enabled, storage_atomics_enabled, counter_enabled,
        uniform_enabled, shared_base, shared_bytes, shared_lanes, image_enabled);
}

/* Existing callers keep local SRAM closed even on discovered hardware. */
static inline bool opengpu_shader_validate_words_profile_runtime_features_uniforms(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask,
    opengpu_shader_u32 *output_write_mask, bool word_atomics_enabled,
    bool texture_backings_disjoint, bool storage_enabled, bool image_enabled,
    bool image_atomics_enabled, bool storage_atomics_enabled, bool counter_enabled,
    bool uniform_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_shared(
        words, word_count, kernarg_size, batch_capacity, output_start_slice,
        output_end_slice, fragment_ops_enabled, texture_enabled,
        all_kernarg_writable, scalar_fpu_enabled, launch_capacity,
        input_read_mask, output_write_mask, word_atomics_enabled,
        texture_backings_disjoint, storage_enabled, image_enabled,
        image_atomics_enabled, storage_atomics_enabled, counter_enabled,
        uniform_enabled, 0, 0, 0);
}

/* Existing callers never authorize a compute uniform publication. */
static inline bool opengpu_shader_validate_words_profile_runtime_features_disjoint(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask,
    opengpu_shader_u32 *output_write_mask, bool word_atomics_enabled,
    bool texture_backings_disjoint, bool storage_enabled, bool image_enabled,
    bool image_atomics_enabled, bool storage_atomics_enabled, bool counter_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_uniforms(
        words, word_count, kernarg_size, batch_capacity, output_start_slice,
        output_end_slice, fragment_ops_enabled, texture_enabled,
        all_kernarg_writable, scalar_fpu_enabled, launch_capacity,
        input_read_mask, output_write_mask, word_atomics_enabled,
        texture_backings_disjoint, storage_enabled, image_enabled,
        image_atomics_enabled, storage_atomics_enabled, counter_enabled, false);
}

/* Existing callers do not promise texture/kernarg backing disjointness. */
static inline bool opengpu_shader_validate_words_profile_runtime_features(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask,
    opengpu_shader_u32 *output_write_mask, bool word_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, batch_capacity, output_start_slice,
        output_end_slice, fragment_ops_enabled, texture_enabled,
        all_kernarg_writable, scalar_fpu_enabled, launch_capacity,
        input_read_mask, output_write_mask, word_atomics_enabled, false, false, false, false, false, false);
}

/* Existing graphics/metadata callers never enable compute atomics. */
static inline bool opengpu_shader_validate_words_profile_runtime_io_usage(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask,
    opengpu_shader_u32 *output_write_mask)
{
    return opengpu_shader_validate_words_profile_runtime_features(
        words, word_count, kernarg_size, batch_capacity, output_start_slice,
        output_end_slice, fragment_ops_enabled, texture_enabled,
        all_kernarg_writable, scalar_fpu_enabled, launch_capacity,
        input_read_mask, output_write_mask, false);
}

/* Retain the committed six-input metadata API and failure contract. */
static inline bool opengpu_shader_validate_words_profile_runtime_usage(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity, opengpu_shader_u32 *input_read_mask)
{
    opengpu_shader_u32 reads = 0x1ffu;
    bool valid = opengpu_shader_validate_words_profile_runtime_io_usage(
        words, word_count, kernarg_size, batch_capacity, output_start_slice,
        output_end_slice, fragment_ops_enabled, texture_enabled,
        all_kernarg_writable, scalar_fpu_enabled, launch_capacity,
        input_read_mask ? &reads : (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0);
    if (input_read_mask)
        *input_read_mask = valid ? reads & 0x3fu : 0x3fu;
    return valid;
}

/* Compatibility entry point: callers not requesting metadata keep the
 * exact existing validation contract and do not pay for read-mask tracking. */
static inline bool opengpu_shader_validate_words_profile_runtime(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled,
    opengpu_shader_u32 launch_capacity)
{
    return opengpu_shader_validate_words_profile_runtime_usage(words, word_count,
        kernarg_size, batch_capacity, output_start_slice, output_end_slice,
        fragment_ops_enabled, texture_enabled, all_kernarg_writable,
        scalar_fpu_enabled, launch_capacity, (opengpu_shader_u32 *)0);
}

static inline bool opengpu_shader_validate_words_profile(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_profile_runtime(words, word_count,
        kernarg_size, batch_capacity, output_start_slice, output_end_slice,
        fragment_ops_enabled, texture_enabled, all_kernarg_writable,
        scalar_fpu_enabled, 0);
}

/* Fragment/vertex profile. scalar_fpu_enabled admits flw and fsw, which is
 * what a `uniform float` lowers to and how that value is stored. Pass it only
 * when GPU_CAP_COMPUTE_SCALAR_FPU is advertised: the graphics shader CU is
 * built with the FP backend whenever that capability is set, and without it
 * flw never completes and hangs the warp. */
static inline bool opengpu_shader_validate_words_with_texture(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    bool texture_enabled, bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_profile(
        words, word_count, kernarg_size, batch_capacity,
        6, 9, true, texture_enabled, false, scalar_fpu_enabled);
}

static inline bool opengpu_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_with_texture(
        words, word_count, kernarg_size, batch_capacity, false,
        scalar_fpu_enabled);
}

/* Vertex profile: the fixed-function stage owns input slices 0..7 and reads
 * transformed attributes from slices 8..15. Fragment-only texture and quad
 * derivative instructions are not meaningful during a vertex launch. */
static inline bool opengpu_vertex_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_profile(
        words, word_count, kernarg_size, batch_capacity,
        8, 16, false, false, false, scalar_fpu_enabled);
}

/* General-compute profile: retain the bounded instruction and address
 * analysis, reject fragment-only operations, and permit writes anywhere
 * inside the explicitly bound kernarg range. scalar_fpu_enabled admits flw,
 * fsw and OPFVF; pass it only when GPU_CAP_COMPUTE_SCALAR_FPU is advertised,
 * since flw never completes on compute CUs built without the scalar FPU. */
/* Texture admission requires a separately validated, retained resource root.
 * The compute kernarg is writable, so the common validator deliberately rejects
 * packet-based fetches from it; on-chip operand tuples and size are supported.
 * This profile does not itself admit a job or establish resource disjointness. */
static inline bool opengpu_compute_shader_validate_words_resources(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled);
}

/* Call only after admission has proved every texture backing disjoint from
 * the writable kernarg and retained those resources through completion. */
static inline bool opengpu_compute_shader_validate_words_disjoint_textures(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, true,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, true, false, false, false, false, false);
}

/* Only for a job with validated, retained GSB1 descriptors and backing
 * allocations proven disjoint from the writable kernarg. This profile does not
 * itself establish that ownership or any cross-resource ordering. */
static inline bool opengpu_compute_shader_validate_words_storage_disjoint_atomics(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, false,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, false, true, false, false, false, false);
}

/* Without a backing-disjointness proof, keep storage and atomics separate. */
static inline bool opengpu_compute_shader_validate_words_storage(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled)
{
    return opengpu_compute_shader_validate_words_storage_disjoint_atomics(
        words, word_count, kernarg_size, local_items, scalar_fpu_enabled, false);
}

/* Caller proves storage, texture and writable kernarg backing disjoint and
 * retains all resources through completion. */
static inline bool opengpu_compute_shader_validate_words_disjoint_resources(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, true,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, true, true, false, false, false, false);
}

/* Only for validated, retained image descriptors and backing allocations
 * proven disjoint from the writable kernarg and shader snapshot. */
static inline bool opengpu_compute_shader_validate_words_images(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, false,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, false, false, true, false, false, false);
}

/* Requires retained image and texture tables and pairwise-disjoint writable
 * image, texture and kernarg backing. */
static inline bool opengpu_compute_shader_validate_words_textured_images(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, true,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, true, false, true, false, false, false);
}

/* Image backings are disjoint from retained storage and texture backings. */
static inline bool opengpu_compute_shader_validate_words_image_resources(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, texture_enabled, true, true, false, false, false);
}

/* Called only after image, optional texture/storage, and kernarg resources
 * have been validated and retained by the submission path. */
static inline bool opengpu_compute_shader_validate_words_image_atomics(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled,
    bool storage_enabled, bool image_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, texture_enabled,
        storage_enabled, true, image_atomics_enabled, false, false);
}

/* Only after all resource tables and backing dependencies are retained. */
static inline bool opengpu_compute_shader_validate_words_all_resources(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled,
    bool storage_enabled, bool image_enabled, bool image_atomics_enabled,
    bool storage_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, texture_enabled,
        storage_enabled, image_enabled, image_atomics_enabled, storage_atomics_enabled, false);
}

/* Only after retaining a counter table and proving its backing disjoint from
 * all other resources in the job. Existing profiles never admit these ops. */
static inline bool opengpu_compute_shader_validate_words_counter_resources(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled,
    bool storage_enabled, bool image_enabled, bool image_atomics_enabled,
    bool storage_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_disjoint(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, texture_enabled,
        storage_enabled, image_enabled, image_atomics_enabled, storage_atomics_enabled, true);
}

/* Only after the driver has snapshotted uniforms and retained every enabled
 * resource domain. All pre-existing profiles keep rejecting uniform ops. */
static inline bool opengpu_compute_shader_validate_words_uniform_resources(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled,
    bool storage_enabled, bool image_enabled, bool image_atomics_enabled,
    bool storage_atomics_enabled, bool counter_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_uniforms(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, texture_enabled,
        storage_enabled, image_enabled, image_atomics_enabled,
        storage_atomics_enabled, counter_enabled, true);
}

/* Only the real compute caller opts in with its validated GCR1 shared profile.
 * Resource publication and dependencies retain their separate admission gates. */
static inline bool opengpu_compute_shader_validate_words_shared_resources(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled,
    bool storage_enabled, bool image_enabled, bool image_atomics_enabled,
    bool storage_atomics_enabled, bool counter_enabled, bool uniform_enabled,
    opengpu_shader_u32 shared_base, opengpu_shader_u32 shared_bytes,
    opengpu_shader_u32 shared_lanes)
{
    return opengpu_shader_validate_words_profile_runtime_features_shared(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, texture_enabled,
        storage_enabled, image_enabled, image_atomics_enabled,
        storage_atomics_enabled, counter_enabled, uniform_enabled,
        shared_base, shared_bytes, shared_lanes);
}

/* Linux's retained-resource caller also supplies the discovered target5 image
 * version. An old image command owner must not authorize function27. The
 * checked image owner still rejects nonbuffer image-size operations. */
static inline bool opengpu_compute_shader_validate_words_discovered_resources(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled,
    bool storage_enabled, bool image_enabled, bool image_atomics_enabled,
    bool storage_atomics_enabled, bool counter_enabled, bool uniform_enabled,
    opengpu_shader_u32 shared_base, opengpu_shader_u32 shared_bytes,
    opengpu_shader_u32 shared_lanes, bool buffer_image_size_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_buffer_images(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, texture_enabled,
        storage_enabled, image_enabled, image_atomics_enabled,
        storage_atomics_enabled, counter_enabled, uniform_enabled,
        shared_base, shared_bytes, shared_lanes, buffer_image_size_enabled);
}

static inline bool opengpu_compute_shader_validate_words_shared_atomics(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled, bool texture_enabled,
    bool storage_enabled, bool image_enabled, bool image_atomics_enabled,
    bool storage_atomics_enabled, bool counter_enabled, bool uniform_enabled,
    opengpu_shader_u32 shared_base, opengpu_shader_u32 shared_bytes,
    opengpu_shader_u32 shared_lanes, bool buffer_image_size_enabled, bool shared_atomics_enabled)
{
    return opengpu_shader_validate_words_profile_runtime_features_buffer_images_shared_atomics(
        words, word_count, kernarg_size, local_items, 0, 0, false, texture_enabled,
        true, scalar_fpu_enabled, 0, (opengpu_shader_u32 *)0,
        (opengpu_shader_u32 *)0, word_atomics_enabled, texture_enabled,
        storage_enabled, image_enabled, image_atomics_enabled,
        storage_atomics_enabled, counter_enabled, uniform_enabled,
        shared_base, shared_bytes, shared_lanes, buffer_image_size_enabled, shared_atomics_enabled);
}

/* Enable word atomics only from the ABI-v2 GCR1 WORD_ATOMICS flag. */
static inline bool opengpu_compute_shader_validate_words_features(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled, bool word_atomics_enabled)
{
    return opengpu_compute_shader_validate_words_resources(words, word_count,
        kernarg_size, local_items, scalar_fpu_enabled, word_atomics_enabled, false);
}

static inline bool opengpu_compute_shader_validate_words_fpu(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_profile(
        words, word_count, kernarg_size, local_items,
        0, 0, false, false, true, scalar_fpu_enabled);
}

static inline bool opengpu_compute_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items)
{
    return opengpu_compute_shader_validate_words_fpu(
        words, word_count, kernarg_size, local_items, false);
}

#endif /* OPENGPU_SHADER_VALIDATOR_H */
