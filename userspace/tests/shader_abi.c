/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "opengpu_shaders.h"
#include "opengpu_shader_abi.h"
#include "opengpu_shader_resources.h"
#include "opengpu_shader_validator.h"

#define COUNT(a) (sizeof(a) / sizeof((a)[0]))
struct program { const char *name; const uint32_t *code; size_t words; bool vertex; };
#define PROGRAM(n, v) { #n, n, COUNT(n), v }
static const struct program programs[] = {
    PROGRAM(opengpu_fragment_color, false),
    PROGRAM(opengpu_fragment_uniform_color, false),
    PROGRAM(opengpu_fragment_sample, false),
    PROGRAM(opengpu_fragment_swizzle, false),
    PROGRAM(opengpu_fragment_modulate, false),
    PROGRAM(opengpu_fragment_shade, false),
    PROGRAM(opengpu_fragment_fade, false),
    PROGRAM(opengpu_fragment_wash, false),
    PROGRAM(opengpu_vertex_passthrough, true),
    PROGRAM(opengpu_vertex_mvp, true),
    PROGRAM(opengpu_vertex_mvp2, true),
};
struct access { uint32_t address; unsigned kind; };
struct execution { struct access accesses[512]; unsigned count, vl; };

/* Execute integer address calculations and branch control, recording every
 * memory operation. Vector/FP arithmetic cannot modify these pointer values.
 * This compares actual effective addresses rather than matching encodings. */
static struct execution execute(const uint32_t *code, size_t words,
                                unsigned warp_base, unsigned branch_flag,
                                unsigned lanes, unsigned capacity)
{
    struct execution result = {0};
    uint32_t x[32] = {0};
    unsigned steps = 0;
    size_t pc = 0;
    x[1] = 0x10000000u; x[8] = warp_base; x[7] = branch_flag; x[9] = capacity;
    while (pc < words && steps++ < 1024) {
        uint32_t w = code[pc++], op = w & 127u;
        unsigned rd = w >> 7 & 31u, f3 = w >> 12 & 7u;
        unsigned r1 = w >> 15 & 31u, r2 = w >> 20 & 31u;
        int imm = (int32_t)w >> 20;
        if (w == OPENGPU_SHADER_CEASE)
            return result;
        if (op == 0x13) {
            if (f3 == 0) x[rd] = x[r1] + (uint32_t)imm;
            else if (f3 == 1) x[rd] = x[r1] << (r2 & 31u);
            else if (f3 == 5) x[rd] = x[r1] >> (r2 & 31u);
            else if (f3 == 7) x[rd] = x[r1] & (uint32_t)imm;
            else assert(!"unexpected integer immediate in shader corpus");
        } else if (op == 0x33) {
            assert(f3 == 0);
            x[rd] = (w >> 25) == 1 ? x[r1] * x[r2] :
                (w >> 25) == 0x20 ? x[r1] - x[r2] : x[r1] + x[r2];
        } else if (op == 0x37) {
            x[rd] = w & 0xfffff000u;
        } else if (op == 0x57 && f3 == 7) {
            if (w >> 30 == 3) result.vl = r1 < lanes ? r1 : lanes;
            else {
                assert(!r1 && rd);
                result.vl = lanes;
                x[rd] = lanes;
            }
        } else if (op == 0x03 || op == 0x07 || op == 0x27) {
            unsigned kind = op == 0x27 ? 2u : 1u;
            uint32_t address = x[r1];
            if (f3 == 2) { address += (uint32_t)imm; kind = 3; }
            assert(result.count < COUNT(result.accesses));
            result.accesses[result.count++] = (struct access){address, kind};
            if (op == 0x03) x[rd] = 0x3f800000u;
        } else if (op == 0x63) {
            int delta = (int)((w >> 31) << 12 | (w >> 7 & 1) << 11 |
                (w >> 25 & 63) << 5 | (w >> 8 & 15) << 1);
            assert(f3 == 0);
            if (delta & 4096) delta -= 8192;
            assert(delta > 0 && !(delta & 3));
            if (x[r1] == x[r2]) pc = pc - 1 + (unsigned)delta / 4;
        } else {
            assert(op == 0x57 || op == 0x53 || op == 0x2b);
        }
        x[0] = 0;
    }
    assert(!"shader failed to cease within its bound");
    return result;
}

static void check_program(const struct program *p, uint32_t resources)
{
    uint32_t converted[512];
    unsigned batch = opengpu_shader_resource_batch(resources);
    unsigned lanes = resources ? opengpu_shader_resource_lanes(resources) : 4;
    unsigned uniform = p->vertex ? 512 : 288;
    unsigned target_uniform = p->vertex ? opengpu_shader_vertex_uniform_offset(resources) :
                                         opengpu_shader_fragment_uniform_offset(resources);
    unsigned stride = opengpu_shader_array_stride(resources), warp;
    uint32_t input_reads = 0x3fu, actual_input_reads = 0;
    uint32_t io_reads = 0, io_writes = 0, actual_reads = 0, actual_writes = 0;
    size_t words;
    assert(!opengpu_shader_lower_runtime_abi(p->code, p->words, p->vertex,
                                              converted, COUNT(converted), &words));
    assert(words <= 512);
    assert(opengpu_shader_validate_words_profile_runtime(converted, words,
        p->vertex ? opengpu_shader_vertex_kernarg_bytes(resources) :
                    opengpu_shader_fragment_bank_stride(resources), batch,
        p->vertex ? 8 : 6, p->vertex ? 16 : 9, !p->vertex, true, false, true, batch));
    if (!p->vertex)
        assert(opengpu_shader_validate_words_profile_runtime_usage(
            converted, words, opengpu_shader_fragment_bank_stride(resources),
            batch, 6, 9, true, true, false, true, batch, &input_reads));
    if (!p->vertex)
        assert(opengpu_shader_validate_words_profile_runtime_io_usage(
            converted, words, opengpu_shader_fragment_bank_stride(resources),
            batch, 6, 9, true, true, false, true, batch, &io_reads, &io_writes));
    /* The same binary must be refused when x9 has no launch-time guarantee. */
    assert(!opengpu_shader_validate_words_profile_runtime(converted, words,
        p->vertex ? opengpu_shader_vertex_kernarg_bytes(resources) :
                    opengpu_shader_fragment_bank_stride(resources), batch,
        p->vertex ? 8 : 6, p->vertex ? 16 : 9, !p->vertex, true, false, true, 0));
    for (warp = 0; warp < batch / lanes; warp++) {
        struct execution old = execute(p->code, p->words, 0, 0, 4, 8);
        struct execution wide = execute(converted, words, warp * lanes, 0, lanes, batch);
        unsigned i;
        assert(old.vl == 4 && wide.vl == lanes && old.count == wide.count);
        for (i = 0; i < old.count; i++) {
            uint32_t offset = old.accesses[i].address - 0x10000000u;
            uint32_t expected;
            if (old.accesses[i].kind == 3) {
                assert(offset >= uniform);
                expected = target_uniform + offset - uniform;
            } else {
                assert(offset < uniform);
                expected = offset / 32u * stride + offset % 32u + warp * lanes * 4u;
            }
            assert(wide.accesses[i].kind == old.accesses[i].kind);
            assert(wide.accesses[i].address == 0x10000000u + expected);
            if (!p->vertex) {
                unsigned bytes = wide.accesses[i].kind == 3 ? 4 : 4 * wide.vl;
                uint32_t start = wide.accesses[i].address - 0x10000000u;

                /* Union actual reads and stores across every warp, including
                 * output slices that the six-input API deliberately omits. */
                for (unsigned byte = 0; byte < bytes; byte++) {
                    unsigned slice = (start + byte) / stride;

                    if (slice < 9) {
                        if (wide.accesses[i].kind == 2)
                            actual_writes |= 1u << slice;
                        else
                            actual_reads |= 1u << slice;
                    }
                }
            }
            if (!p->vertex && wide.accesses[i].kind != 2) {
                unsigned byte, bytes = wide.accesses[i].kind == 3 ? 4 : 4 * wide.vl;
                uint32_t start = wide.accesses[i].address - 0x10000000u;

                /* Independently union every byte actually read by every warp.
                 * A missing bit would let staging omit a shader input. Stock
                 * programs also require an exact union, keeping the analysis
                 * useful across all resource configurations. */
                for (byte = 0; byte < bytes; byte++) {
                    unsigned slice = (start + byte) / stride;

                    if (slice < 6)
                        actual_input_reads |= 1u << slice;
                }
            }
        }
    }
    if (!p->vertex)
        assert(input_reads == actual_input_reads);
    if (!p->vertex) {
        assert(io_reads == actual_reads);
        assert(io_writes == actual_writes);
    }
    printf("%s resources=%08x: %zu -> %zu words PASS\n", p->name, resources, p->words, words);
}

static void check_pointer_clobbers(uint32_t resources)
{
    const uint32_t clobbers[] = {
        0x00000313u, /* addi x6,x0,0 */
        0x00000333u, /* add x6,x0,x0 */
        0x1200a303u, /* lw x6,288(x1): relocated uniform access */
        0x00428293u, /* addi x5,x5,4: update the common staging base */
    };
    size_t i;

    for (i = 0; i < COUNT(clobbers); i++) {
        uint32_t code[] = {
            0x00241293u, 0x005082b3u, 0xc1027057u,
            0x08028313u, 0x02036087u, /* x6 = slice4; vector load */
            clobbers[i],
            0x0a028313u, 0x02036107u, /* x6 = slice5; vector load */
            OPENGPU_SHADER_CEASE,
        };
        struct program p = { "pointer-clobber", code, COUNT(code), false };
        check_program(&p, resources);
    }
}

static void check_pointer_join(uint32_t resources)
{
    const uint32_t code[] = {
        0x00241293u, 0x005082b3u, 0xc1027057u,
        0x08028313u, 0x02036087u, /* slice4 */
        0x00700663u,             /* beq x0,x7,+12: skip slice5 */
        0x0a028313u, 0x02036107u,
        0x0c028313u, 0x02036187u, /* join: slice6 */
        OPENGPU_SHADER_CEASE,
    };
    uint32_t converted[512];
    unsigned lanes = resources ? opengpu_shader_resource_lanes(resources) : 4;
    unsigned batch = opengpu_shader_resource_batch(resources);
    unsigned stride = opengpu_shader_array_stride(resources);
    size_t words;

    assert(!opengpu_shader_lower_runtime_abi(code, COUNT(code), false,
                                            converted, COUNT(converted), &words));
    for (unsigned flag = 0; flag < 2; flag++)
        for (unsigned warp = 0; warp < batch / lanes; warp++) {
            struct execution before = execute(code, COUNT(code), 0, flag, 4, 8);
            struct execution after = execute(converted, words, warp * lanes,
                                               flag, lanes, batch);
            assert(before.count == 2 + flag && before.count == after.count);
            for (unsigned i = 0; i < before.count; i++) {
                uint32_t offset = before.accesses[i].address - 0x10000000u;
                uint32_t expected = offset / 32u * stride + offset % 32u +
                                    warp * lanes * 4u;
                assert(after.accesses[i].address == 0x10000000u + expected);
                assert(after.accesses[i].kind == before.accesses[i].kind);
            }
        }
}

int main(void)
{
    const uint32_t resources[] = {0, 0x01020204u, 0x02040408u, 0x04080810u, 0x01020810u, 0x02040810u, 0x01020220u};
    uint32_t out[512], guarded[514], branch_code[] = {
        0x00241293u, 0x005082b3u, 0xc1027057u, 0x1200a483u,
        0x00700663u, /* beq x0,x7,+12: skip an expanding address and store */
        0x0c028313u, 0x020360a7u, OPENGPU_SHADER_CEASE,
    };
    size_t i, j, words;
    for (i = 0; i < COUNT(programs); i++)
        for (j = 0; j < COUNT(resources); j++)
            check_program(&programs[i], resources[j]);
    for (j = 0; j < COUNT(resources); j++) {
        check_pointer_clobbers(resources[j]);
        check_pointer_join(resources[j]);
    }
    assert(!opengpu_shader_lower_runtime_abi(branch_code, COUNT(branch_code),
                                          false, out, COUNT(out), &words));
    for (i = 0; i < 2; i++) {
        struct execution before = execute(branch_code, COUNT(branch_code), 0, i, 4, 8);
        struct execution after = execute(out, words, 0, i, 16, 128);
        assert(before.count == after.count && before.count == 1 + i);
        if (i) assert(after.accesses[1].address == 0x10000000u + 6u * 512u);
    }
    memset(guarded, 0x5a, sizeof(guarded));
    assert(opengpu_shader_lower_runtime_abi(opengpu_vertex_mvp2, COUNT(opengpu_vertex_mvp2),
        true, guarded + 1, 8, &words));
    assert(guarded[0] == 0x5a5a5a5au && guarded[9] == 0x5a5a5a5au);
    branch_code[0] = opengpu_abi_addi(31, 0, 1);
    assert(opengpu_shader_lower_runtime_abi(branch_code, COUNT(branch_code),
                                         false, out, COUNT(out), &words));
    puts("runtime shader ABI effective addresses, all warps, branch relocation and bounds: PASS");
    return 0;
}
