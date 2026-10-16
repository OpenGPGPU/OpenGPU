/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GALLIUM_SHADER_ABI_H
#define OPENGPU_GALLIUM_SHADER_ABI_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define OPENGPU_ABI_SOURCE_WORDS 256u
#define OPENGPU_ABI_TARGET_WORDS 512u

struct opengpu_abi_builder {
    uint32_t *code;
    size_t words, capacity;
};

static inline int opengpu_abi_put(struct opengpu_abi_builder *b, uint32_t word)
{
    if (b->words >= b->capacity)
        return -1;
    b->code[b->words++] = word;
    return 0;
}

static inline uint32_t opengpu_abi_addi(unsigned rd, unsigned rs, int offset)
{
    return ((uint32_t)offset & 0xfffu) << 20 | rs << 15 | rd << 7 | 0x13u;
}

static inline uint32_t opengpu_abi_add(unsigned rd, unsigned rs1, unsigned rs2)
{
    return rs2 << 20 | rs1 << 15 | rd << 7 | 0x33u;
}

/* x30 is the runtime staging stride. Slice numbers are semantic ABI fields,
 * independent of VLEN, warp count and the number of CUs. */
static inline int opengpu_abi_slice(struct opengpu_abi_builder *b,
                                    unsigned rd, unsigned base, unsigned slice)
{
    unsigned shift = 0;
    if (!slice)
        return opengpu_abi_put(b, opengpu_abi_addi(rd, base, 0));
    if (!(slice & (slice - 1u))) {
        while ((1u << shift) != slice) shift++;
        if (opengpu_abi_put(b, shift << 20 | 30u << 15 | 1u << 12 | rd << 7 | 0x13u))
            return -1;
    } else {
        if (opengpu_abi_put(b, opengpu_abi_addi(rd, 0, (int)slice)) ||
            opengpu_abi_put(b, 1u << 25 | 30u << 20 | rd << 15 | rd << 7 | 0x33u))
            return -1;
    }
    return opengpu_abi_put(b, opengpu_abi_add(rd, base, rd));
}

/* Integer x30/x31 are reserved by this lowering pass. The FP/vector register
 * files are separate and their registers 30/31 remain available. */
static inline bool opengpu_abi_uses_register(uint32_t w, unsigned reg)
{
    unsigned op = w & 127u, rd = w >> 7 & 31u;
    unsigned f3 = w >> 12 & 7u, rs1 = w >> 15 & 31u, rs2 = w >> 20 & 31u;
    switch (op) {
    case 0x03: case 0x13: case 0x67:
        return rd == reg || rs1 == reg;
    case 0x33:
        return rd == reg || rs1 == reg || rs2 == reg;
    case 0x37: case 0x17: case 0x6f:
        return rd == reg;
    case 0x63: case 0x23:
        return rs1 == reg || rs2 == reg;
    case 0x07: case 0x27:
        return rs1 == reg;
    case 0x57:
        return (f3 == 7 && rd == reg) || ((f3 == 4 || f3 == 6) && rs1 == reg);
    case 0x53:
        return (((w >> 27) == 0x18 || (w >> 27) == 0x1c || (w >> 27) == 0x14) && rd == reg) ||
               (((w >> 27) == 0x1a || (w >> 27) == 0x1e) && rs1 == reg);
    case 0x73:
        return rd == reg || ((f3 == 1 || f3 == 2 || f3 == 3) && rs1 == reg);
    default:
        return false;
    }
}

/* Integer-register writes invalidate cached addresses. Other register banks
 * do not alias GPRs. Unrecognized instructions conservatively kill all facts. */
static inline uint32_t opengpu_abi_gpr_writes(uint32_t w)
{
    unsigned op = w & 127u, rd = w >> 7 & 31u, f3 = w >> 12 & 7u;
    switch (op) {
    case 0x03: case 0x13: case 0x33: case 0x37: case 0x17:
    case 0x6f: case 0x67: case 0x73:
        return 1u << rd;
    case 0x57:
        return (f3 == 7 || (f3 == 2 && w >> 26 == 0x10)) ? 1u << rd : 0;
    case 0x53:
        return ((w >> 27) == 0x18 || (w >> 27) == 0x1c ||
                (w >> 27) == 0x14) ? 1u << rd : 0;
    case 0x23: case 0x07: case 0x27: case 0x63: case 0x2b:
        return 0;
    default:
        return UINT32_MAX;
    }
}

/* Lower Gallium's canonical compiler output to one runtime shader ABI.
 * This pass has no hardware-resource argument: identical input always emits
 * identical code. x9 supplies physical CU capacity at launch; vsetvli selects
 * VLMAX. Raw DRM/pipe programs retain their caller-supplied machine code.
 * Canonical x6/x7=x5+slice*32 denotes a semantic staging slice, while
 * x1-relative scalar accesses after the canonical uniform boundary denote
 * uniform fields. Relocate forward branches after instruction expansion.
 */
static inline int opengpu_shader_lower_runtime_abi(const uint32_t *source,
    size_t words, bool vertex, uint32_t *target, size_t capacity,
    size_t *target_words)
{
    struct opengpu_abi_builder b = { target, 0, capacity };
    size_t map[OPENGPU_ABI_SOURCE_WORDS + 1], i;
    unsigned old_uniform = vertex ? 512u : 288u;
    bool uniforms = false;
    bool entry[OPENGPU_ABI_SOURCE_WORDS + 1] = {false};
    int cached_slice[2] = {-1, -1};

    if (!source || !target || !target_words || !words ||
        words > OPENGPU_ABI_SOURCE_WORDS || capacity > OPENGPU_ABI_TARGET_WORDS)
        return -1;
    for (i = 0; i < words; i++) {
        if (opengpu_abi_uses_register(source[i], 30) ||
            opengpu_abi_uses_register(source[i], 31))
            return -1;
    }
    /* A branch target is a control-flow join. Never carry a path-local
     * address fact into it, including a target that falls through normally. */
    for (i = 0; i < words; i++) {
        uint32_t w = source[i];
        if ((w & 127u) == 0x63u) {
            int delta = (int)((w >> 31) << 12 | (w >> 7 & 1) << 11 |
                (w >> 25 & 63) << 5 | (w >> 8 & 15) << 1);
            size_t destination;
            if (delta & 4096) delta -= 8192;
            if (delta <= 0 || (delta & 3)) return -1;
            destination = i + (unsigned)delta / 4u;
            if (destination >= words) return -1;
            entry[destination] = true;
        }
    }
    /* Save the launch capacity before the canonical body reuses x9. */
    if (opengpu_abi_put(&b, 2u << 20 | 9u << 15 | 1u << 12 | 30u << 7 | 0x13u))
        return -1;
    for (i = 0; i < words; i++) {
        uint32_t w = source[i];
        unsigned op = w & 127u, f3 = w >> 12 & 7u, rs1 = w >> 15 & 31u;
        int imm = (int32_t)w >> 20;
        if (rs1 == 1 && imm >= (int)old_uniform &&
            ((op == 0x03 && f3 == 2) || (op == 0x07 && f3 == 2) ||
             (op == 0x13 && f3 == 0)))
            uniforms = true;
    }
    if (uniforms && opengpu_abi_slice(&b, 31, 1, vertex ? 16u : 9u))
        return -1;
    for (i = 0; i < words; i++) {
        uint32_t w = source[i];
        unsigned op = w & 127u, rd = w >> 7 & 31u;
        unsigned f3 = w >> 12 & 7u, rs1 = w >> 15 & 31u;
        int imm = (int32_t)w >> 20;
        if (entry[i]) cached_slice[0] = cached_slice[1] = -1;
        map[i] = b.words;
        if (op == 0x57 && f3 == 7) {
            if (w != 0xc1027057u)
                return -1;
            w = 0x01007357u; /* vsetvli x6,x0,e32,m1: VLMAX, no literal VL */
        } else if (op == 0x13 && f3 == 0 && (rd == 6 || rd == 7) && rs1 == 5) {
            if (imm < 0 || imm >= (int)old_uniform || (imm & 31))
                return -1;
            unsigned slice = (unsigned)imm / 32u, slot = rd - 6u;
            unsigned k, reuse = 2;
            /* Prefer an exact address, otherwise advance one semantic field
             * using x30. No resource size, VLEN or shader identity is baked in. */
            for (k = 0; k < 2; k++)
                if (cached_slice[k] == (int)slice) { reuse = k; break; }
            if (reuse < 2) {
                if (opengpu_abi_put(&b, opengpu_abi_addi(rd, reuse + 6u, 0))) return -1;
            } else {
                for (k = 0; k < 2; k++)
                    if (cached_slice[k] >= 0 && cached_slice[k] + 1 == (int)slice) {
                        reuse = k; break;
                    }
                if (reuse < 2) {
                    if (opengpu_abi_put(&b, opengpu_abi_add(rd, reuse + 6u, 30))) return -1;
                } else if (opengpu_abi_slice(&b, rd, rs1, slice)) return -1;
            }
            cached_slice[slot] = (int)slice;
            continue;
        } else if (uniforms && rs1 == 1 && imm >= (int)old_uniform &&
                   ((op == 0x03 && f3 == 2) || (op == 0x07 && f3 == 2) ||
                    (op == 0x13 && f3 == 0))) {
            w = (w & 0x7fffu) | 31u << 15 | ((unsigned)imm - old_uniform) << 20;
        } else if (op == 0x6f || op == 0x67) {
            return -1;
        }
        if (opengpu_abi_put(&b, w))
            return -1;
        {
            uint32_t writes = opengpu_abi_gpr_writes(w);
            if (writes & (1u << 5)) cached_slice[0] = cached_slice[1] = -1;
            if (writes & (1u << 6)) cached_slice[0] = -1;
            if (writes & (1u << 7)) cached_slice[1] = -1;
            if (op == 0x63) cached_slice[0] = cached_slice[1] = -1;
        }
    }
    map[words] = b.words;
    for (i = 0; i < words; i++) {
        uint32_t w = source[i];
        if ((w & 127u) == 0x63u) {
            int old_delta = (int)((w >> 31) << 12 | (w >> 7 & 1) << 11 |
                (w >> 25 & 63) << 5 | (w >> 8 & 15) << 1);
            size_t old_target;
            unsigned delta;
            if (old_delta & 4096)
                old_delta -= 8192;
            if (old_delta <= 0 || (old_delta & 3))
                return -1;
            old_target = i + (unsigned)old_delta / 4u;
            if (old_target >= words)
                return -1;
            delta = (unsigned)(map[old_target] - map[i]) * 4u;
            if (delta > 4094u)
                return -1;
            target[map[i]] = (w & ~0xfe000f80u) | (delta >> 12 & 1) << 31 |
                (delta >> 5 & 63) << 25 | (delta >> 1 & 15) << 8 | (delta >> 11 & 1) << 7;
        }
    }
    *target_words = b.words;
    return 0;
}

#endif
