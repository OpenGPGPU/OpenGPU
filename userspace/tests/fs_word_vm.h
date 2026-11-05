/* SPDX-License-Identifier: MIT */
/* Small, fail-closed CPU executor for the actual fragment words under test.
 * It decodes RV32/RVV encodings independently of the emitter IR. This is a
 * compiler oracle, not a GPU timing or hardware execution model.
 */
#ifndef OPENGPU_TEST_FS_WORD_VM_H
#define OPENGPU_TEST_FS_WORD_VM_H

#include <assert.h>
#include <fenv.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct fs_word_vm {
    uint32_t x[32], f[32], v[32][16];
    uint8_t *memory;
    size_t bytes;
    unsigned lanes, vl;
    unsigned stores;
};

static float vm_float(uint32_t bits)
{
    float value;
    memcpy(&value, &bits, 4);
    return value;
}

static uint32_t vm_bits(float value)
{
    uint32_t bits;
    memcpy(&bits, &value, 4);
    return bits;
}

static uint32_t vm_load(const struct fs_word_vm *m, uint32_t address)
{
    assert(address <= m->bytes && m->bytes - address >= 4);
    return (uint32_t)m->memory[address] | (uint32_t)m->memory[address + 1] << 8 |
           (uint32_t)m->memory[address + 2] << 16 | (uint32_t)m->memory[address + 3] << 24;
}

static void vm_store(struct fs_word_vm *m, uint32_t address, uint32_t value)
{
    unsigned i;
    assert(address <= m->bytes && m->bytes - address >= 4);
    for (i = 0; i < 4; i++)
        m->memory[address + i] = (uint8_t)(value >> (8 * i));
}

static uint32_t vm_fp_binary(uint32_t a, uint32_t b, unsigned operation)
{
    volatile float lhs = vm_float(a), rhs = vm_float(b), value;

    if (operation == 0x00)
        value = lhs + rhs;
    else if (operation == 0x24)
        value = lhs * rhs;
    else
        abort();
    return isnan(value) ? 0x7fc00000u : vm_bits(value);
}

static uint32_t vm_minmax(uint32_t a, uint32_t b, int maximum)
{
    float lhs = vm_float(a), rhs = vm_float(b);
    if (isnan(lhs)) return isnan(rhs) ? 0x7fc00000u : b;
    if (isnan(rhs)) return a;
    if (lhs == 0 && rhs == 0)
        return maximum ? a & b : a | b;
    return maximum ? (lhs > rhs ? a : b) : (lhs < rhs ? a : b);
}

static uint32_t vm_float_to_int(uint32_t value)
{
    float f = vm_float(value);
    if (isnan(f) || (double)f >= INT32_MAX) return INT32_MAX;
    if ((double)f <= INT32_MIN) return (uint32_t)INT32_MIN;
    return (uint32_t)(int32_t)lrintf(f);
}

static void vm_execute(struct fs_word_vm *m, const uint32_t *code, unsigned words,
                        int require_return)
{
    unsigned pc, steps = 0;

    assert(fegetround() == FE_TONEAREST && m->lanes && m->lanes <= 16);
    for (pc = 0; pc < words; pc++) {
        uint32_t w = code[pc];
        unsigned op = w & 127u, rd = w >> 7 & 31u, f3 = w >> 12 & 7u;
        unsigned a = w >> 15 & 31u, b = w >> 20 & 31u, f6 = w >> 26;
        int32_t immediate = (int32_t)w >> 20;
        unsigned lane;

        assert(++steps < 4096);
        if (w == 0x30500073u) {
            assert(require_return && pc + 1 == words);
            return;
        }
        if (op == 0x37) {
            m->x[rd] = w & 0xfffff000u;
        } else if (op == 0x13 && f3 == 0) {
            m->x[rd] = m->x[a] + (uint32_t)immediate;
        } else if (op == 0x13 && f3 == 1 && !(w >> 25)) {
            m->x[rd] = m->x[a] << b;
        } else if (op == 0x33 && f3 == 0 && !(w >> 25)) {
            m->x[rd] = m->x[a] + m->x[b];
        } else if (op == 0x33 && f3 == 0 && (w >> 25) == 1) {
            m->x[rd] = m->x[a] * m->x[b];
        } else if (op == 0x07 && f3 == 2) {
            m->f[rd] = vm_load(m, m->x[a] + (uint32_t)immediate);
        } else if (op == 0x53 && (w >> 27) == 0x1a && b == 0) {
            m->f[rd] = vm_bits((float)(int32_t)m->x[a]);
        } else if (op == 0x53 && (w >> 27) == 0x1e && b == 0 && f3 == 0) {
            m->f[rd] = m->x[a];
        } else if (op == 0x53 && (w >> 27) == 0x04 && f3 == 1) {
            m->f[rd] = (m->f[a] & 0x7fffffffu) | (~m->f[b] & 0x80000000u);
        } else if (op == 0x57 && f3 == 7) {
            assert(w == 0x01007357u); /* actual runtime VLMAX form */
            m->vl = m->lanes;
            m->x[rd] = m->vl;
        } else if ((op == 0x07 || op == 0x27) && f3 == 6) {
            assert((w & 0xfff00000u) == 0x02000000u && m->vl);
            for (lane = 0; lane < m->vl; lane++) {
                if (op == 0x07)
                    m->v[rd][lane] = vm_load(m, m->x[a] + 4u * lane);
                else
                    vm_store(m, m->x[a] + 4u * lane, m->v[rd][lane]);
            }
            if (op == 0x27) m->stores++;
        } else if (op == 0x57 && (w & (1u << 25)) && m->vl) {
            for (lane = 0; lane < m->vl; lane++) {
                uint32_t lhs = m->v[b][lane];
                uint32_t rhs = f3 == 5 ? m->f[a] : m->v[a][lane];
                uint32_t value;

                if (f6 == 0x17 && (f3 == 0 || f3 == 5))
                    value = f3 == 0 ? m->v[a][lane] : m->f[a];
                else if ((f3 == 1 || f3 == 5) && (f6 == 0x00 || f6 == 0x24))
                    value = vm_fp_binary(lhs, rhs, f6);
                else if ((f3 == 1 || f3 == 5) && f6 == 0x09)
                    value = (lhs & 0x7fffffffu) | (~rhs & 0x80000000u);
                else if ((f3 == 1 || f3 == 5) && (f6 == 0x04 || f6 == 0x06))
                    value = vm_minmax(lhs, rhs, f6 == 0x06);
                else if (f3 == 1 && f6 == 0x12 && a == 3)
                    value = vm_bits((float)(int32_t)lhs);
                else if (f3 == 1 && f6 == 0x12 && a == 1)
                    value = vm_float_to_int(lhs);
                else if (f3 == 3 && f6 == 0x25)
                    value = lhs << a;
                else if (f3 == 0 && f6 == 0x0a)
                    value = lhs | rhs;
                else {
                    fprintf(stderr, "unsupported CPU vector word %08x at %u\n", w, pc);
                    abort();
                }
                m->v[rd][lane] = value;
            }
        } else {
            fprintf(stderr, "unsupported CPU word %08x at %u\n", w, pc);
            abort();
        }
        m->x[0] = 0;
    }
    assert(!require_return);
}

#endif
