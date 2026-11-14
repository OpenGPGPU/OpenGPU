/* SPDX-License-Identifier: MIT */
/* Independent CPU decoder for the emitted integer/storage subset, not RTL. */
#ifndef OPENGPU_CS_WORD_VM_H
#define OPENGPU_CS_WORD_VM_H
#include <assert.h>
#include <stdint.h>
#include <string.h>

struct cs_vm_view { uint32_t *data; uint64_t bytes; unsigned access; };
struct cs_vm_uniform_trace { unsigned reads, fault_warps; uint32_t fault_mask[8]; };
struct cs_vm_resources {
    void *opaque;
    uint32_t (*texture)(void *, unsigned unit, uint32_t index, unsigned component, int query);
    void (*image)(void *, unsigned unit, unsigned controls, uint32_t index,
                  uint32_t rgba[4], int write, int query);
};

static void cs_vm_group_resources_counters_checked(const uint32_t *code, unsigned words,
    const uint32_t grid[3], const uint32_t group[3], const uint32_t local[3],
    struct cs_vm_view memory[4], const struct cs_vm_view uniforms[13],
    struct cs_vm_uniform_trace *trace, const struct cs_vm_resources *resources,
    const struct cs_vm_view *counters)
{
    unsigned total = local[0] * local[1] * local[2];
    for (unsigned base = 0; base < total; base += 16) {
        uint32_t x[32] = {0}, v[32][16] = {{0}}, stored[16] = {0};
        unsigned active = total - base < 16 ? total - base : 16;
        uint32_t control = (1u << active) - 1u;
        struct { unsigned pc; uint32_t mask; } stack[8];
        unsigned depth = 0, steps = 0;
        unsigned ended = 0, captured = 0, fences = 0;
        uint32_t atomic_operand[16]={0},atomic_compare[16]={0},atomic_valid=0;
        uint32_t texture[16][16] = {{0}}, image[16][8] = {{0}}, image_loaded[16][4] = {{0}};
        unsigned texture_valid[16] = {0}, image_valid[16] = {0}, image_read[16] = {0};
        for (unsigned i = 0; i < 3; i++) { x[2+i] = group[i]; x[5+i] = local[i]; }
        x[8] = base; x[9] = 128;
        for (unsigned lane = 0; lane < 16; lane++) v[1][lane] = base + lane;
        for (unsigned pc = 0; pc < words; pc++) {
            assert(++steps <= words * 4u);
            uint32_t w = code[pc], opcode = w & 127, rd = w >> 7 & 31;
            unsigned a = w >> 15 & 31, z = w >> 20 & 31, f = w >> 12 & 7, fn = w >> 26;
            int32_t imm = (int32_t)w >> 20;
            if (w == 0x30500073u) { assert(!depth); ended = 1; break; }
            if (opcode == 0x5b) {
                uint32_t displacement = (w >> 31) << 12 | (w >> 7 & 1) << 11 |
                    (w >> 25 & 63) << 5 | (w >> 8 & 15) << 1;
                /* Only the forward compiler dialect is modeled. The stack
                 * follows the ISA's marker/alternate PCs and taken-first
                 * mask restoration; it is an arithmetic oracle, not a model
                 * of hardware scheduling, traps, cancellation or drain. */
                if (f == 3) {
                    assert(!(displacement & 3) && !(displacement & 4096) && depth < 8);
                    stack[depth].pc = pc + displacement / 4 + 1;
                    stack[depth++].mask = control;
                } else if (f <= 1) {
                    uint32_t take = 0;
                    assert(depth && displacement && !(displacement & 3) && !(displacement & 4096));
                    for (unsigned lane = 0; lane < active; lane++)
                        if ((control & (1u << lane)) && ((v[a][lane] == v[z][lane]) == (f == 0)))
                            take |= 1u << lane;
                    uint32_t fall = control & ~take;
                    if (take && fall) {
                        assert(depth < 8);
                        stack[depth].pc = pc + 1;
                        stack[depth++].mask = fall;
                    }
                    if (take) { control = take; pc += displacement / 4 - 1; }
                    else control = fall;
                } else {
                    assert(w == 0x205bu && depth);
                    --depth; control = stack[depth].mask; pc = stack[depth].pc - 1;
                }
                assert(control); captured = 0;
                continue;
            }
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
                    for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane))
                        result[lane] = mask & (1u << lane) ?
                            (f == 3 ? (uint32_t)immediate : v[a][lane]) : v[z][lane];
                    for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane))
                        v[rd][lane] = result[lane];
                    continue;
                }
                assert(w & (1u << 25));
                if (fn == 0x18 || fn == 0x19 || fn == 0x1a || fn == 0x1c) {
                    assert((f == 0 || f == 4) && rd == 0);
                    uint32_t mask = v[0][0];
                    for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane)) {
                        uint32_t left = v[z][lane], right = f == 4 ? x[a] : v[a][lane];
                        int predicate = fn == 0x18 ? left == right : fn == 0x19 ? left != right :
                            fn == 0x1a ? left < right : left <= right;
                        mask = (mask & ~(1u << lane)) | ((uint32_t)predicate << lane);
                    }
                    v[0][0] = mask;
                    continue;
                }
                for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane)) {
                    uint32_t left = v[z][lane], right = f == 3 ? a : f == 4 || f == 6 ? x[a] : v[a][lane], result;
                    if (fn == 0x17 && f == 4) { assert(z == 0); result = x[a]; }
                    else if (fn == 0 && (f == 0 || f == 4)) result = left + right;
                    else if (fn == 0x20 && f == 6) { assert(right); result = left / right; }
                    else if (fn == 0x22 && f == 6) { assert(right); result = left % right; }
                    else if (fn == 0x25 && f == 2) result = left * right;
                    else if (fn == 9 && f == 0) result = left & right;
                    else if (fn == 10 && f == 0) result = left | right;
                    else if (fn == 11 && f == 0) result = left ^ right;
                    else if (fn == 0x25 && (f == 0 || f == 3 || f == 4)) result = left << (right & 31);
                    else if (fn == 0x28 && f == 0) result = left >> (right & 31);
                    else { assert(!"unexpected emitted vector opcode"); result = 0; }
                    v[rd][lane] = result;
                }
                continue;
            }
            /* Independent scalar-size decoder for the compiler's straight-line
             * static unit/LOD0 dialect. This does not model hardware traps/drain. */
            if (opcode == 0x0b && (w >> 25) == 6) {
                assert(control && !depth && resources && resources->texture);
                uint32_t unit = x[a], lod = x[z]; /* Capture before rd may alias rs1. */
                assert(rd && !z && !lod && !f && unit < 32);
                uint32_t value = resources->texture(resources->opaque, unit, 0, 0, 1);
                x[rd] = value;
                continue;
            }
            assert(opcode == 0x2b && (w & (1u << 25)));
            if (fn >= 17 && fn <= 19) {
                assert(!f && !z && rd && counters && counters->data && (counters->access & 1));
                if (fn != 17) assert(counters->access & 2);
                for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane)) {
                    uint32_t index = v[a][lane];
                    assert(index < 8 && (uint64_t)index * 4 + 4 <= counters->bytes);
                    uint32_t old = counters->data[index], next = old;
                    if (fn == 18) next++; else if (fn == 19) next--;
                    if (fn != 17) counters->data[index] = next;
                    v[rd][lane] = fn == 19 ? next : old;
                }
                continue;
            }
            if(fn>=24 && fn<=26) {
                assert(!depth);
                unsigned operation=fn==26 ? 8+f : f;
                for(unsigned lane=0;lane<active;lane++)if(control&(1u<<lane)) {
                    if(fn==24) {
                        assert(!f&&!rd);atomic_operand[lane]=v[a][lane];
                        atomic_compare[lane]=v[z][lane];atomic_valid|=1u<<lane;
                    } else {
                        unsigned binding=v[a][lane];uint32_t offset=v[z][lane];
                        assert(operation<=9 && (atomic_valid&(1u<<lane)) && binding<4 &&
                            memory[binding].data && !(offset&3) && (memory[binding].access&3)==3 &&
                            offset<=memory[binding].bytes && 4<=memory[binding].bytes-offset);
                        uint32_t old=memory[binding].data[offset/4],value=atomic_operand[lane],next=old;
                        switch(operation) {
                        case 0: next=value;break;
                        case 1: next=old+value;break;
                        case 2: next=old^value;break;
                        case 3: next=old|value;break;
                        case 4: next=old&value;break;
                        case 5: if((int32_t)value<(int32_t)old)next=value;break;
                        case 6: if((int32_t)value>(int32_t)old)next=value;break;
                        case 7: if(value<old)next=value;break;
                        case 8: if(value>old)next=value;break;
                        case 9: if(old==atomic_compare[lane])next=value;break;
                        }
                        memory[binding].data[offset/4]=next;v[rd][lane]=old;
                    }
                }
                continue;
            }
            if (fn == 3 || fn == 5 || fn == 6 || (fn >= 8 && fn <= 11) || fn == 27) {
                assert(resources);
                for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane)) {
                    uint32_t left = v[a][lane], right = v[z][lane];
                    if (fn == 3) {
                        assert(f < 8 && !rd);
                        if (!f) texture_valid[lane] = 0;
                        texture[lane][f * 2] = left; texture[lane][f * 2 + 1] = right;
                        texture_valid[lane] |= 3u << (f * 2);
                    } else if (fn == 5 || fn == 6) {
                        assert(rd && !right && f < (fn == 5 ? 4u : 3u));
                        if (fn == 5) {
                            assert(texture_valid[lane] == 65535);
                            for (unsigned k = 1; k < 16; k++) assert(!texture[lane][k]);
                        }
                        v[rd][lane] = resources->texture(resources->opaque, left,
                            fn == 5 ? texture[lane][0] : 0, f, fn == 6);
                    } else if (fn == 8) {
                        assert(f < 4 && !rd);
                        if (!f) { image_valid[lane] = 0; image_read[lane] = 0; }
                        image[lane][f * 2] = left; image[lane][f * 2 + 1] = right;
                        image_valid[lane] |= 1u << f;
                    } else if (fn == 11) {
                        assert(f && f < 4 && !a && !z && rd && image_read[lane]);
                        v[rd][lane] = image_loaded[lane][f];
                    } else {
                        uint32_t rgba[4] = {0};
                        assert(!f);
                        if (fn == 27) {
                            resources->image(resources->opaque, left, right, 0, rgba, 0, 1);
                            v[rd][lane] = rgba[0];
                        } else {
                            assert(!right && (image_valid[lane] & 3) == 3 && !image[lane][2] && !image[lane][3]);
                            if (fn == 10) {
                                assert(image_valid[lane] == 15 && !rd);
                                memcpy(rgba, &image[lane][4], sizeof(rgba));
                            }
                            resources->image(resources->opaque, image[lane][0], image[lane][1], left,
                                rgba, fn == 10, 0);
                            if (fn == 9) {
                                memcpy(image_loaded[lane], rgba, sizeof(rgba)); image_read[lane] = 1;
                                v[rd][lane] = rgba[0];
                            }
                        }
                    }
                }
                continue;
            }
            assert(!f);
            if (fn == 14) {
                assert(!rd); for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane))
                    stored[lane] = v[a][lane];
                captured = 1; continue;
            }
            if (fn == 7) {
                assert(uniforms && rd);
                uint32_t fault_mask = 0;
                for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane)) {
                    unsigned selector = v[a][lane], slot = selector == 31 ? 0 : selector + 1;
                    uint32_t offset = v[z][lane];
                    if ((selector >= 12 && selector != 31) || slot >= 13 ||
                        (offset & 3) || !uniforms[slot].data ||
                        offset > uniforms[slot].bytes || 4 > uniforms[slot].bytes - offset)
                        fault_mask |= 1u << lane;
                }
                if (fault_mask) {
                    assert(trace); trace->fault_warps++; trace->fault_mask[base/16] |= fault_mask;
                    /* Arithmetic-only fault probe: no invalid address is
                     * dereferenced. This whole-warp precheck/next-warp walk
                     * does not establish actual job trap/drain ordering. */
                    goto next_warp;
                }
                for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane)) {
                    unsigned selector = v[a][lane], slot = selector == 31 ? 0 : selector + 1;
                    uint32_t offset = v[z][lane];
                    assert((selector < 12 || selector == 31) && slot < 13 && !(offset & 3));
                    assert(uniforms[slot].data && offset <= uniforms[slot].bytes &&
                        4 <= uniforms[slot].bytes - offset);
                    v[rd][lane] = uniforms[slot].data[offset / 4];
                    if (trace) trace->reads++;
                }
                continue;
            }
            assert(fn == 15 || fn == 16);
            for (unsigned lane = 0; lane < active; lane++) if (control & (1u << lane)) {
                unsigned binding = v[a][lane]; uint32_t offset = v[z][lane];
                assert(binding < 4 && memory[binding].data && !(offset & 3));
                assert(offset <= memory[binding].bytes && 4 <= memory[binding].bytes - offset);
                assert(memory[binding].access & (fn == 15 ? 1 : 2));
                if (fn == 15) v[rd][lane] = memory[binding].data[offset/4];
                else { assert(captured && !rd); memory[binding].data[offset/4] = stored[lane]; }
            }
        }
        assert(ended && fences);
next_warp:;
    }
}

static void cs_vm_group_resources_checked(const uint32_t *code, unsigned words,
    const uint32_t grid[3], const uint32_t group[3], const uint32_t local[3],
    struct cs_vm_view memory[4], const struct cs_vm_view uniforms[13],
    struct cs_vm_uniform_trace *trace, const struct cs_vm_resources *resources)
{
    cs_vm_group_resources_counters_checked(code, words, grid, group, local,
        memory, uniforms, trace, resources, NULL);
}

static void cs_vm_group_uniforms_checked(const uint32_t *code, unsigned words,
    const uint32_t grid[3], const uint32_t group[3], const uint32_t local[3],
    struct cs_vm_view memory[4], const struct cs_vm_view uniforms[13],
    struct cs_vm_uniform_trace *trace)
{
    cs_vm_group_resources_checked(code, words, grid, group, local, memory, uniforms, trace, NULL);
}

static void cs_vm_group_uniforms(const uint32_t *code, unsigned words,
    const uint32_t grid[3], const uint32_t group[3], const uint32_t local[3],
    struct cs_vm_view memory[4], const struct cs_vm_view uniforms[13])
{
    cs_vm_group_uniforms_checked(code,words,grid,group,local,memory,uniforms,NULL);
}

static void cs_vm_group(const uint32_t *code, unsigned words,
    const uint32_t grid[3], const uint32_t group[3], const uint32_t local[3],
    struct cs_vm_view memory[4])
{
    cs_vm_group_uniforms(code, words, grid, group, local, memory, NULL);
}
#endif
