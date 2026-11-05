/* SPDX-License-Identifier: MIT */
/* Independent CPU decoder for the emitted integer/storage subset, not RTL. */
#ifndef OPENGPU_CS_WORD_VM_H
#define OPENGPU_CS_WORD_VM_H
#include <assert.h>
#include <stdint.h>
#include <string.h>

struct cs_vm_view { uint32_t *data; uint64_t bytes; unsigned access; };

static void cs_vm_group(const uint32_t *code, unsigned words,
    const uint32_t grid[3], const uint32_t group[3], const uint32_t local[3],
    struct cs_vm_view memory[4])
{
    unsigned total = local[0] * local[1] * local[2];
    for (unsigned base = 0; base < total; base += 16) {
        uint32_t x[32] = {0}, v[32][16] = {{0}}, stored[16] = {0};
        unsigned active = total - base < 16 ? total - base : 16;
        unsigned ended = 0, captured = 0, fences = 0;
        for (unsigned i = 0; i < 3; i++) { x[2+i] = group[i]; x[5+i] = local[i]; }
        x[8] = base; x[9] = 128;
        for (unsigned lane = 0; lane < 16; lane++) v[1][lane] = base + lane;
        for (unsigned pc = 0; pc < words; pc++) {
            uint32_t w = code[pc], opcode = w & 127, rd = w >> 7 & 31;
            unsigned a = w >> 15 & 31, z = w >> 20 & 31, f = w >> 12 & 7, fn = w >> 26;
            int32_t imm = (int32_t)w >> 20;
            if (w == 0x30500073u) { ended = 1; break; }
            if (w == 0x0330000fu) { fences++; continue; }
            if (opcode == 0x37) { x[rd] = w & 0xfffff000u; continue; }
            if (opcode == 0x13) { assert(f == 0); if (rd) x[rd] = x[a] + (uint32_t)imm; continue; }
            if (opcode == 3) {
                assert(f == 2 && a == 1 && imm >= 0 && imm < 12 && !(imm & 3));
                x[rd] = grid[(unsigned)imm/4]; continue;
            }
            if (opcode == 0x57) {
                if (f == 7) { assert(w == 0x01007557u); x[10] = 16; continue; }
                if (!(w & (1u << 25))) {
                    /* RVV merge selects full lane values using packed v0.
                     * Inactive lanes retain the old destination. */
                    assert(fn == 0x17 && (f == 0 || f == 3) && rd);
                    uint32_t mask = v[0][0], result[16];
                    int32_t immediate = (int32_t)a;
                    if (a & 16) immediate -= 32;
                    for (unsigned lane = 0; lane < active; lane++)
                        result[lane] = mask & (1u << lane) ?
                            (f == 3 ? (uint32_t)immediate : v[a][lane]) : v[z][lane];
                    for (unsigned lane = 0; lane < active; lane++) v[rd][lane] = result[lane];
                    continue;
                }
                assert(w & (1u << 25));
                if (fn == 0x18 || fn == 0x19 || fn == 0x1a || fn == 0x1c) {
                    assert((f == 0 || f == 4) && rd == 0);
                    uint32_t mask = v[0][0];
                    for (unsigned lane = 0; lane < active; lane++) {
                        uint32_t left = v[z][lane], right = f == 4 ? x[a] : v[a][lane];
                        int predicate = fn == 0x18 ? left == right : fn == 0x19 ? left != right :
                            fn == 0x1a ? left < right : left <= right;
                        mask = (mask & ~(1u << lane)) | ((uint32_t)predicate << lane);
                    }
                    v[0][0] = mask;
                    continue;
                }
                for (unsigned lane = 0; lane < active; lane++) {
                    uint32_t left = v[z][lane], right = f == 4 || f == 6 ? x[a] : v[a][lane], result;
                    if (fn == 0x17 && f == 4) { assert(z == 0); result = x[a]; }
                    else if (fn == 0 && (f == 0 || f == 4)) result = left + right;
                    else if (fn == 0x20 && f == 6) { assert(right); result = left / right; }
                    else if (fn == 0x22 && f == 6) { assert(right); result = left % right; }
                    else if (fn == 0x25 && f == 2) result = left * right;
                    else if (fn == 9 && f == 0) result = left & right;
                    else if (fn == 10 && f == 0) result = left | right;
                    else if (fn == 11 && f == 0) result = left ^ right;
                    else if (fn == 0x25 && f == 0) result = left << (right & 31);
                    else if (fn == 0x28 && f == 0) result = left >> (right & 31);
                    else { assert(!"unexpected emitted vector opcode"); result = 0; }
                    v[rd][lane] = result;
                }
                continue;
            }
            assert(opcode == 0x2b && !f && (w & (1u << 25)));
            if (fn == 14) {
                assert(!rd); for (unsigned lane = 0; lane < active; lane++) stored[lane] = v[a][lane];
                captured = 1; continue;
            }
            assert(fn == 15 || fn == 16);
            for (unsigned lane = 0; lane < active; lane++) {
                unsigned binding = v[a][lane]; uint32_t offset = v[z][lane];
                assert(binding < 4 && memory[binding].data && !(offset & 3));
                assert(offset <= memory[binding].bytes && 4 <= memory[binding].bytes - offset);
                assert(memory[binding].access & (fn == 15 ? 1 : 2));
                if (fn == 15) v[rd][lane] = memory[binding].data[offset/4];
                else { assert(captured && !rd); memory[binding].data[offset/4] = stored[lane]; }
            }
        }
        assert(ended && fences);
    }
}
#endif
