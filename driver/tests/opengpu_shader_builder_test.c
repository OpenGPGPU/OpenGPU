/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "opengpu_shader_builder.h"

static void check_copy(uint32_t batch, uint32_t lanes, bool runtime)
{
    uint32_t program[16] = {0};
    uint32_t count = runtime ? opengpu_fragment_runtime_passthrough_shader(program, 16) :
        opengpu_fragment_passthrough_shader(program, 16, batch, lanes);
    uint32_t memory[9 * 252 + 16], before[9 * 252 + 16];
    const uint32_t base = 0x8000;
    unsigned i, warp;

    assert(count && count <= (runtime ? 13u : 11u));
    assert(opengpu_shader_validate_words_profile_runtime(program, count,
        9u * batch * 4, batch, 6, 9, true, false, false, false,
        runtime ? batch : 0));
    for (i = 0; i < sizeof(memory) / sizeof(*memory); i++)
        memory[i] = 0x12340000u + i;
    memcpy(before, memory, sizeof(memory));
    /* Interpret the small RV32 subset independently. Check every physical
     * warp's effective vector addresses, rather than matching generated words. */
    for (warp = 0; warp < batch; warp += lanes) {
        uint32_t x[32] = {0}, vector[32] = {0}, vl = 0;
        x[1] = base;
        x[8] = warp;
        x[9] = batch;
        for (i = 0; i < count; i++) {
            uint32_t insn = program[i], op = insn & 127u;
            uint32_t rd = insn >> 7 & 31u, rs1 = insn >> 15 & 31u;
            uint32_t rs2 = insn >> 20 & 31u;
            int32_t imm = (int32_t)insn >> 20;
            unsigned k;
            if (insn == OPENGPU_SHADER_CEASE)
                break;
            if (op == 0x13)
                x[rd] = (insn >> 12 & 7u) == 1u ? x[rs1] << rs2 : x[rs1] + imm;
            else if (op == 0x33)
                x[rd] = insn >> 25 == 1 ? x[rs1] * x[rs2] : x[rs1] + x[rs2];
            else if (op == 0x57) {
                vl = runtime ? lanes : rs1;
                if (runtime) {
                    assert(insn == 0x01007357u);
                    x[rd] = lanes;
                }
                assert(vl == lanes);
            } else {
                assert(op == 0x07 || op == 0x27);
                assert(vl && x[rs1] >= base && !(x[rs1] & 3u));
                for (k = 0; k < vl; k++) {
                    uint32_t offset = (x[rs1] - base) / 4 + k;
                    assert(offset < 9 * batch);
                    if (op == 0x07) {
                        assert(offset == 3 * batch + warp + k);
                        vector[k] = memory[offset];
                    } else {
                        assert(offset == 6 * batch + warp + k);
                        memory[offset] = vector[k];
                    }
                }
            }
            x[0] = 0;
        }
    }
    for (i = 0; i < sizeof(memory) / sizeof(*memory); i++)
        assert(memory[i] == (i >= 6 * batch && i < 7 * batch ? before[i - 3 * batch] : before[i]));
}

int main(void)
{
    uint32_t program[16];
    check_copy(8, 4, false);
    check_copy(32, 16, false);
    check_copy(128, 16, false);
    check_copy(252, 4, false);
    check_copy(8, 4, true);
    check_copy(32, 8, true);
    check_copy(128, 16, true);
    check_copy(64, 32, true);
    assert(!opengpu_fragment_runtime_passthrough_shader(program, 12));
    assert(!opengpu_fragment_runtime_passthrough_shader(NULL, 16));
    assert(!opengpu_fragment_passthrough_shader(program, 15, 8, 4));
    assert(!opengpu_fragment_passthrough_shader(program, 16, 0, 4));
    assert(!opengpu_fragment_passthrough_shader(program, 16, 128, 0));
    assert(!opengpu_fragment_passthrough_shader(program, 16, 128, 32));
    assert(!opengpu_fragment_passthrough_shader(program, 16, 127, 16));
    assert(!opengpu_fragment_passthrough_shader(program, 16, 256, 16));
    puts("wide fragment shader builder, bounds and effective vector addresses passed");
    return 0;
}
