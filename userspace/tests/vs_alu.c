/* SPDX-License-Identifier: MIT */
/* The checked-in vertex ALU image passes the vertex validator, and the
 * runtime ABI lowerer accepts it. That lowerer rewrites the VL=4 vsetivli
 * and the uniform load, and leaves the vadd.vx word in place. */
#include <assert.h>
#include <stdio.h>

#include "opengpu_shader_abi.h"
#include "opengpu_shader_resources.h"
#include "opengpu_shader_validator.h"
#include "opengpu_vertex_alu.h"

#define VL4_VSETIVLI 0xc1027057u
#define VLMAX_VSETVLI 0x01007357u
#define VS_LW_UNIFORM 0x000fa603u

static int contains(const uint32_t *code, size_t words, uint32_t word)
{
    size_t i;

    for (i = 0; i < words; i++) {
        if (code[i] == word)
            return 1;
    }
    return 0;
}

static void check_runtime(uint32_t resources)
{
    uint32_t converted[OPENGPU_ABI_TARGET_WORDS];
    unsigned batch = opengpu_shader_resource_batch(resources);
    size_t lowered = 0;

    assert(!opengpu_shader_lower_runtime_abi(
        opengpu_vertex_alu, OPENGPU_VERTEX_ALU_WORDS, true, converted,
        OPENGPU_ABI_TARGET_WORDS, &lowered));
    assert(lowered && lowered <= OPENGPU_ABI_TARGET_WORDS);
    assert(contains(opengpu_vertex_alu, OPENGPU_VERTEX_ALU_WORDS, VL4_VSETIVLI));
    assert(contains(opengpu_vertex_alu, OPENGPU_VERTEX_ALU_WORDS, OPENGPU_VERTEX_ALU_LW));
    assert(!contains(converted, lowered, VL4_VSETIVLI));
    assert(!contains(converted, lowered, OPENGPU_VERTEX_ALU_LW));
    assert(contains(converted, lowered, VLMAX_VSETVLI));
    assert(contains(converted, lowered, VS_LW_UNIFORM));
    assert(contains(converted, lowered, OPENGPU_VERTEX_ALU_VADD));
    assert(opengpu_shader_validate_words_profile_runtime(
        converted, (opengpu_shader_u32)lowered,
        opengpu_shader_vertex_kernarg_bytes(resources), batch, 8, 16, false,
        false, false, true, batch));
}

int main(void)
{
    const uint32_t resources[] = { 0, 0x01020204u };
    unsigned i;

    assert(OPENGPU_VERTEX_ALU_WORDS == 37);
    assert(contains(opengpu_vertex_alu, OPENGPU_VERTEX_ALU_WORDS,
                    OPENGPU_VERTEX_ALU_VADD));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++) {
        assert(opengpu_vertex_shader_validate_words(
            opengpu_vertex_alu, OPENGPU_VERTEX_ALU_WORDS,
            opengpu_shader_vertex_kernarg_bytes(resources[i]), 8, true));
        check_runtime(resources[i]);
    }
    printf("vertex ALU image: PASS\n");
    return 0;
}
