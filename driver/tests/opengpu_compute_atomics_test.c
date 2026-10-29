// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stdint.h>
#include "../opengpu_shader_validator.h"

static uint32_t addi(unsigned rd, unsigned rs1, int immediate)
{
    return ((uint32_t)immediate & 0xfffu) << 20 | rs1 << 15 | rd << 7 | 0x13u;
}

static uint32_t atomic(unsigned operation, unsigned width, unsigned ordering,
                       unsigned rd, unsigned address, unsigned replacement)
{
    return operation << 27 | ordering << 25 | replacement << 20 |
           address << 15 | width << 12 | rd << 7 | 0x2fu;
}

static bool supported(unsigned operation)
{
    return operation == 0 || operation == 1 || operation == 4 ||
           operation == 5 || operation == 8 || operation == 12 ||
           operation == 16 || operation == 20 || operation == 24 ||
           operation == 28;
}

static bool valid(const uint32_t *code, unsigned count, uint64_t size, bool feature)
{
    return opengpu_compute_shader_validate_words_features(
        code, count, size, 4, false, feature);
}

int main(void)
{
    uint32_t code[] = {addi(10, 0, 7), addi(11, 1, 0), addi(12, 0, 42),
                      0, OPENGPU_SHADER_CEASE};

    /* Every funct5/width/aq/rl encoding, including LR/SC and wider CAS. */
    for (unsigned op = 0; op < 32; op++)
        for (unsigned width = 0; width < 8; width++)
            for (unsigned order = 0; order < 4; order++) {
                code[3] = atomic(op, width, order, 10, 11, 12);
                assert(valid(code, 5, 64, true) ==
                       (supported(op) && width == 2 && order == 0));
                assert(!valid(code, 5, 64, false));
                assert(!opengpu_compute_shader_validate_words(code, 5, 64, 4));
                assert(!opengpu_compute_shader_validate_words_fpu(code, 5, 64, 4, true));
            }

    for (unsigned op = 0; op < 32; op++) {
        if (!supported(op))
            continue;
        code[3] = atomic(op, 2, 0, 10, 11, 12);
        for (int offset = -8; offset <= 72; offset++) {
            code[1] = addi(11, 1, offset);
            assert(valid(code, 5, 64, true) ==
                   (offset >= 0 && offset <= 60 && offset % 4 == 0));
        }
        code[1] = addi(11, 1, 0);
        for (unsigned size = 0; size <= 8; size++)
            assert(valid(code, 5, size, true) == (size >= 4));

        /* Bounds alone do not grant graphics atomics, even at output offset. */
        code[1] = addi(11, 1, 192);
        assert(!opengpu_shader_validate_words(code, 5, 288, 8, false));
        assert(!opengpu_vertex_shader_validate_words(code, 5, 512, 8, false));
        assert(!opengpu_shader_validate_words_profile_runtime_features(
            code, 5, 288, 8, 6, 9, true, false, false, false, 0, 0, 0, true));

        code[1] = addi(11, 1, 0);
        code[3] = atomic(op, 2, 0, 1, 11, 12); /* immutable argument root */
        assert(!valid(code, 5, 64, true));
        code[3] = atomic(op, 2, 0, 10, 11, 31); /* undefined replacement */
        assert(!valid(code, 5, 64, true));
        code[3] = atomic(op, 2, 0, 10, 31, 12); /* undefined address */
        assert(!valid(code, 5, 64, true));
        code[3] = atomic(op, 2, 0, 10, 11, 12);
        code[1] = addi(11, 0, 0); /* a constant is not an authorized pointer */
        assert(!valid(code, 5, 64, true));
        code[1] = addi(11, 8, 0); /* warp-relative values are not uniform bounds */
        assert(!valid(code, 5, 64, true));
    }

    code[1] = addi(11, 1, 0);
    code[3] = atomic(5, 2, 0, 31, 11, 12); /* original rd is a CAS input */
    assert(!valid(code, 5, 64, true));
    code[3] = atomic(0, 2, 0, 31, 11, 12); /* ordinary AMO defines fresh rd */
    assert(valid(code, 5, 64, true));
    code[3] = atomic(5, 2, 0, 0, 11, 0); /* x0 comparison and replacement */
    assert(valid(code, 5, 64, true));
    code[3] = atomic(5, 2, 0, 11, 11, 11); /* capture old aliased operands */
    assert(valid(code, 5, 64, true));

    /* AMO result must not inherit the old rd's proven pointer or constant. */
    uint32_t reuse[] = {addi(11, 1, 0), atomic(1, 2, 0, 11, 11, 0),
                        atomic(1, 2, 0, 10, 11, 0), OPENGPU_SHADER_CEASE};
    assert(!valid(reuse, 4, 64, true));
    reuse[2] = atomic(1, 2, 0, 10, 1, 0);
    assert(valid(reuse, 4, 64, true));

    uint32_t conditional[] = {addi(11, 1, 0), 0x00000463u, /* beq x0,x0,+8 */
        addi(10, 0, 7), atomic(5, 2, 0, 10, 11, 0), OPENGPU_SHADER_CEASE};
    assert(!valid(conditional, 5, 64, true)); /* comparison undefined on one path */
    conditional[3] = atomic(0, 2, 0, 10, 11, 0);
    assert(valid(conditional, 5, 64, true));

    /* No atomics feature is needed for the previously admitted scalar path. */
    uint32_t legacy[] = {addi(10, 0, 1), OPENGPU_SHADER_CEASE};
    assert(valid(legacy, 2, 64, false));
    assert(valid(legacy, 2, 64, true));
    return 0;
}
