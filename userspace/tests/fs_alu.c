/* SPDX-License-Identifier: MIT */
/* The checked-in fragment ALU image passes the fragment validator, and the
 * runtime ABI lowerer accepts it. That lowerer rewrites the VL=4 vsetivli
 * and leaves every other opcode in place. */
#include <assert.h>
#include <stdio.h>

#include "opengpu_fragment_alu.h"
#include "opengpu_fragment_cover.h"
#include "opengpu_fragment_min.h"
#include "opengpu_shader_abi.h"
#include "opengpu_shader_resources.h"
#include "opengpu_shader_validator.h"

#define VL4_VSETIVLI 0xc1027057u
#define VLMAX_VSETVLI 0x01007357u

static int contains(const uint32_t *code, size_t words, uint32_t word)
{
    size_t i;

    for (i = 0; i < words; i++) {
        if (code[i] == word)
            return 1;
    }
    return 0;
}

static void check_runtime(const uint32_t *code, size_t words, uint32_t resources)
{
    uint32_t converted[OPENGPU_ABI_TARGET_WORDS];
    unsigned batch = opengpu_shader_resource_batch(resources);
    size_t lowered = 0;

    assert(!opengpu_shader_lower_runtime_abi(
        code, words, false, converted, OPENGPU_ABI_TARGET_WORDS, &lowered));
    assert(lowered && lowered <= OPENGPU_ABI_TARGET_WORDS);
    assert(contains(code, words, VL4_VSETIVLI));
    assert(!contains(converted, lowered, VL4_VSETIVLI));
    assert(contains(converted, lowered, VLMAX_VSETVLI));
    assert(opengpu_shader_validate_words_profile_runtime(
        converted, (opengpu_shader_u32)lowered,
        opengpu_shader_fragment_bank_stride(resources), batch, 6, 9, true,
        false, false, true, batch));
}

int main(void)
{
    const uint32_t resources[] = { 0, 0x01020204u };
    unsigned i;

    assert(OPENGPU_FRAGMENT_ALU_WORDS == 56);
    assert(OPENGPU_FRAGMENT_MIN_WORDS == 52);
    assert(OPENGPU_FRAGMENT_SQRT_WORDS == 48);
    assert(OPENGPU_FRAGMENT_CLAMP_WORDS == 60);
    assert(OPENGPU_FRAGMENT_COORD_WORDS == 50);
    assert(OPENGPU_FRAGMENT_IF_WORDS == 59);
    assert(OPENGPU_FRAGMENT_KILL_WORDS == 59);
    assert(contains(opengpu_fragment_sqrt, OPENGPU_FRAGMENT_SQRT_WORDS,
                    OPENGPU_FRAGMENT_SQRT_SCALAR));
    assert(contains(opengpu_fragment_coord, OPENGPU_FRAGMENT_COORD_WORDS,
                    OPENGPU_FRAGMENT_COORD_YCVT));
    assert(contains(opengpu_fragment_if, OPENGPU_FRAGMENT_IF_WORDS,
                    OPENGPU_FRAGMENT_IF_BNE));
    assert(contains(opengpu_fragment_kill, OPENGPU_FRAGMENT_KILL_WORDS,
                    OPENGPU_FRAGMENT_KILL_VALIDITY));
    assert(opengpu_shader_validate_words(opengpu_fragment_alu,
                                          OPENGPU_FRAGMENT_ALU_WORDS, 512, 8,
                                          true));
    assert(opengpu_shader_validate_words(opengpu_fragment_min,
                                          OPENGPU_FRAGMENT_MIN_WORDS, 512, 8,
                                          true));
    assert(opengpu_shader_validate_words(opengpu_fragment_sqrt,
                                          OPENGPU_FRAGMENT_SQRT_WORDS, 512, 8,
                                          true));
    assert(opengpu_shader_validate_words(opengpu_fragment_clamp,
                                          OPENGPU_FRAGMENT_CLAMP_WORDS, 512, 8,
                                          true));
    assert(opengpu_shader_validate_words(opengpu_fragment_coord,
                                          OPENGPU_FRAGMENT_COORD_WORDS, 512, 8,
                                          true));
    assert(opengpu_shader_validate_words(opengpu_fragment_if,
                                          OPENGPU_FRAGMENT_IF_WORDS, 512, 8,
                                          true));
    assert(opengpu_shader_validate_words(opengpu_fragment_kill,
                                          OPENGPU_FRAGMENT_KILL_WORDS, 512, 8,
                                          true));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++) {
        check_runtime(opengpu_fragment_alu, OPENGPU_FRAGMENT_ALU_WORDS,
                      resources[i]);
        check_runtime(opengpu_fragment_min, OPENGPU_FRAGMENT_MIN_WORDS,
                      resources[i]);
        check_runtime(opengpu_fragment_sqrt, OPENGPU_FRAGMENT_SQRT_WORDS,
                      resources[i]);
        check_runtime(opengpu_fragment_clamp, OPENGPU_FRAGMENT_CLAMP_WORDS,
                      resources[i]);
        check_runtime(opengpu_fragment_coord, OPENGPU_FRAGMENT_COORD_WORDS,
                      resources[i]);
        check_runtime(opengpu_fragment_if, OPENGPU_FRAGMENT_IF_WORDS,
                      resources[i]);
        check_runtime(opengpu_fragment_kill, OPENGPU_FRAGMENT_KILL_WORDS,
                      resources[i]);
    }
    printf("fragment ALU image: PASS\n");
    return 0;
}
