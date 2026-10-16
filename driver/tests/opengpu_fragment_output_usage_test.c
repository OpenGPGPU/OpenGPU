/* SPDX-License-Identifier: MIT */
#include "opengpu_shader_validator.h"
#include <assert.h>
#include <stdio.h>

#define COUNT(a) (sizeof(a) / sizeof((a)[0]))
static uint32_t addi(unsigned rd, unsigned rs1, int imm)
{
    return ((uint32_t)imm & 0xfff) << 20 | rs1 << 15 | rd << 7 | 0x13;
}
static uint32_t add(unsigned rd, unsigned rs1, unsigned rs2)
{
    return rs2 << 20 | rs1 << 15 | rd << 7 | 0x33;
}
static uint32_t load(unsigned rd, unsigned offset, unsigned funct3, unsigned op)
{
    return offset << 20 | 1u << 15 | funct3 << 12 | rd << 7 | op;
}
static uint32_t store(unsigned reg, unsigned offset, unsigned funct3, unsigned op)
{
    return (offset >> 5) << 25 | reg << 20 | 1u << 15 |
           funct3 << 12 | (offset & 31) << 7 | op;
}
static void check(const uint32_t *words, unsigned count, unsigned capacity,
                  unsigned expected_reads, unsigned expected_writes)
{
    uint32_t reads = 0, writes = 0, inputs = 0;
    uint64_t bytes = 10ull * 4 * capacity + 256;

    assert(opengpu_shader_validate_words_profile_runtime_io_usage(
        words, count, bytes, capacity, 6, 9, true, true, false, true,
        capacity, &reads, &writes));
    assert(reads == expected_reads && writes == expected_writes);
    assert(opengpu_shader_validate_words_profile_runtime_usage(
        words, count, bytes, capacity, 6, 9, true, true, false, true,
        capacity, &inputs));
    assert(inputs == (expected_reads & 63));
}

/* Build addresses from launch x9, independently of the scalar/vector width.
 * The same program works at every tested capacity, including non-power-of-two
 * and the largest complete-quad capacity accepted by the validator. */
static unsigned pointer(uint32_t *words, unsigned used, unsigned slice)
{
    words[used++] = addi(11, 1, 0);
    for (unsigned i = 0; i < slice; i++)
        words[used++] = add(11, 11, 10);
    return used;
}

int main(void)
{
    const unsigned capacities[] = {8, 16, 112, 128, 252};
    uint32_t code[40], reads, writes;
    unsigned count, vector_cases = 0;

    for (unsigned c = 0; c < COUNT(capacities); c++) {
        for (unsigned source = 0; source < 10; source++) {
            for (unsigned target = 6; target < 9; target++) {
                count = 0;
                code[count++] = 0x010072d7u; /* vsetvli x5,x0,e32,m1 */
                code[count++] = 2u << 20 | 9u << 15 | 1u << 12 |
                                10u << 7 | 0x13; /* stride = x9 << 2 */
                count = pointer(code, count, source);
                code[count++] = 0x02006007u | 11u << 15 | 2u << 7;
                count = pointer(code, count, target);
                code[count++] = 0x02006027u | 11u << 15 | 2u << 7;
                code[count++] = OPENGPU_SHADER_CEASE;
                check(code, count, capacities[c],
                      source < 9 ? 1u << source : 0, 1u << target);
                vector_cases++;
            }
        }
        for (unsigned boundary = 7; boundary < 9; boundary++) {
            count = 0;
            code[count++] = 0x010072d7u;
            code[count++] = 2u << 20 | 9u << 15 | 1u << 12 | 10u << 7 | 0x13;
            count = pointer(code, count, boundary);
            code[count++] = addi(11, 11, -4);
            code[count++] = 0x02006007u | 11u << 15 | 2u << 7;
            code[count++] = 0x02006027u | 11u << 15 | 2u << 7;
            code[count++] = OPENGPU_SHADER_CEASE;
            unsigned mask = (1u << boundary) | (1u << (boundary - 1));
            check(code, count, capacities[c], mask, mask);
            vector_cases++;
        }
    }
    code[0] = OPENGPU_SHADER_CEASE;
    check(code, 1, 8, 0, 0);
    for (unsigned slice = 6; slice < 9; slice++) {
        for (unsigned size = 0; size < 3; size++) {
            code[0] = addi(10, 0, 1);
            code[1] = store(10, slice * 32, size, 0x23);
            code[2] = OPENGPU_SHADER_CEASE;
            check(code, 3, 8, 0, 1u << slice);
        }
        code[0] = load(1, slice * 32, 2, 0x07);
        code[1] = store(1, slice * 32, 2, 0x27);
        code[2] = OPENGPU_SHADER_CEASE;
        check(code, 3, 8, 1u << slice, 1u << slice);
    }
    code[0] = 0x010072d7u;
    code[1] = addi(11, 1, 6 * 32);
    code[2] = addi(12, 0, 4);
    code[3] = 0x0a006027u | 12u << 20 | 11u << 15 | 1u << 7;
    code[4] = OPENGPU_SHADER_CEASE;
    check(code, 5, 8, 0, 0x1ff); /* Conservative strided write. */
    code[2] = 0x25u << 26 | 1u << 25 | 1u << 20 | 2u << 15 |
              3u << 12 | 2u << 7 | 0x57; /* vsll.vi v2,v1,2 */
    code[3] = 0x0e006027u | 2u << 20 | 11u << 15 | 1u << 7;
    check(code, 5, 8, 0, 0x1ff); /* Conservative indexed write. */
    code[0] = load(10, 7 * 32, 2, 0x03);
    code[1] = store(10, 8 * 32, 2, 0x23);
    code[2] = OPENGPU_SHADER_CEASE;
    check(code, 3, 8, 1u << 7, 1u << 8);
    code[0] = addi(10, 0, 1);
    code[1] = 0x00700463u; /* beq x0,x7,+8: conditional depth write. */
    code[2] = store(10, 7 * 32, 0, 0x23);
    code[3] = store(10, 8 * 32, 0, 0x23);
    code[4] = OPENGPU_SHADER_CEASE;
    check(code, 5, 8, 0, (1u << 7) | (1u << 8));
    code[4] = 0;
    reads = writes = 0;
    assert(!opengpu_shader_validate_words_profile_runtime_io_usage(
        code, 5, 544, 8, 6, 9, true, true, false, true, 8, &reads, &writes));
    assert(reads == 0x1ff && writes == 0x1ff);
    printf("fragment output usage: %u runtime-capacity vector cases, scalar/FP, conditional stores and conservative fallback PASS\n", vector_cases);
    return 0;
}
