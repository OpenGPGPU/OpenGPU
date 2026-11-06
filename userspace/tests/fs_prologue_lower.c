/* SPDX-License-Identifier: MIT */
/* Exercise the public TGSI/menu/emitter/runtime ABI path, then execute its
 * actual machine words with the existing independent CPU interpreter. */
#define main opengpu_prologue_existing_lower_main
#include "fs_alu_lower.c"
#undef main
#include "fs_word_vm.h"

static void coordinate_program(struct tgsi_token *tokens, unsigned mask,
                                int via_temp, int dead_input, int dot)
{
    struct tgsi_header *h;
    struct op_src constants[2] = {op_src_id(TGSI_FILE_CONSTANT, 0),
                                  op_src_id(TGSI_FILE_CONSTANT, 1)};
    unsigned c;

    begin_fragment(tokens, &h);
    add_decl(tokens, h, TGSI_FILE_CONSTANT, 0, 1, -1);
    add_decl(tokens, h, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    if (mask || dead_input)
        add_decl(tokens, h, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    if (via_temp || dead_input)
        add_decl(tokens, h, TGSI_FILE_TEMPORARY, 0, 0, -1);
    if (dead_input) {
        struct op_src x = op_src(TGSI_FILE_INPUT, 0, 0, 0, 0, 0, 0);
        add_op(tokens, h, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0, 1, 1, &x);
    }
    add_op(tokens, h, TGSI_OPCODE_MUL, TGSI_FILE_OUTPUT, 0, 15, 2, constants);
    if (dot) {
        struct op_src operands[2] = {op_src_id(TGSI_FILE_INPUT, 0),
                                     op_src_id(TGSI_FILE_CONSTANT, 1)};
        add_op(tokens, h, TGSI_OPCODE_DP2, TGSI_FILE_OUTPUT, 0, 15, 2, operands);
    }
    for (c = 0; !dot && c < 4; c++) {
        struct op_src product[2] = {
            op_src(TGSI_FILE_INPUT, 0, c, c, c, c, 0),
            op_src(TGSI_FILE_CONSTANT, 1, c, c, c, c, 0)};
        if (!(mask & (1u << c)))
            continue;
        if (via_temp) {
            add_op(tokens, h, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0,
                   1, 1, product);
            product[0] = op_src(TGSI_FILE_TEMPORARY, 0, 0, 0, 0, 0, 0);
        }
        add_op(tokens, h, TGSI_OPCODE_MUL, TGSI_FILE_OUTPUT, 0,
               1u << c, 2, product);
    }
    add_end(tokens, h);
}

static void check_coordinate_loads(const uint32_t *code, unsigned words,
                                   unsigned mask)
{
    unsigned i, loads = 0;
    for (i = 0; i + 1 < words; i++) {
        uint32_t w = code[i], next = code[i + 1];
        if ((w & 0x000fffffu) == 0x00028313u &&
            (next & 0xfffff07fu) == 0x02036007u) {
            unsigned offset = w >> 20;
            if (offset == 0 || offset == 32)
                loads |= 1u << (offset / 32);
        }
    }
    assert(loads == mask);
}

static void coordinate_flow_program(struct tgsi_token *tokens)
{
    struct tgsi_header *h;
    struct op_src constants[2] = {op_src_id(TGSI_FILE_CONSTANT, 0),
                                  op_src_id(TGSI_FILE_CONSTANT, 1)};
    struct op_src predicate = op_src(TGSI_FILE_CONSTANT, 0, 0, 0, 0, 0, 0);
    struct op_src product[2] = {op_src(TGSI_FILE_INPUT, 0, 0, 0, 0, 0, 0),
                                op_src(TGSI_FILE_CONSTANT, 1, 0, 0, 0, 0, 0)};

    begin_fragment(tokens, &h);
    add_decl(tokens, h, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, h, TGSI_FILE_CONSTANT, 0, 1, -1);
    add_decl(tokens, h, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_op(tokens, h, TGSI_OPCODE_MUL, TGSI_FILE_OUTPUT, 0, 15, 2, constants);
    add_op(tokens, h, TGSI_OPCODE_IF, -1, 0, 0, 1, &predicate);
    add_op(tokens, h, TGSI_OPCODE_MUL, TGSI_FILE_OUTPUT, 0, 1, 2, product);
    add_op(tokens, h, TGSI_OPCODE_ELSE, -1, 0, 0, 0, NULL);
    product[0] = op_src(TGSI_FILE_INPUT, 0, 1, 1, 1, 1, 0);
    add_op(tokens, h, TGSI_OPCODE_MUL, TGSI_FILE_OUTPUT, 0, 1, 2, product);
    add_op(tokens, h, TGSI_OPCODE_ENDIF, -1, 0, 0, 0, NULL);
    add_end(tokens, h);
}

static void execute_coordinate_case(unsigned mask, int via_temp, int dead_input,
                                     int dot)
{
    const float a[4] = {0.125f, 0.25f, 0.5f, 1.f};
    const float b[4] = {0.5f, 0.25f, 0.125f, 0.75f};
    const uint32_t profiles[] = {0, 0x01020204u, 0x02020810u};
    struct tgsi_token tokens[MAX_TOKENS];
    struct opengpu_fs_alu direct;
    uint32_t original[256], runtime[OPENGPU_ABI_TARGET_WORDS];
    unsigned words = 0, p;

    coordinate_program(tokens, mask, via_temp, dead_input, dot);
    assert(!opengpu_fragment_lower_kind(tokens, original, 256, &words));
    assert(!opengpu_fs_emit(tokens, &direct));
    assert(direct.words == words && !memcmp(original, direct.code, words * 4u));
    check_coordinate_loads(original, words, mask);
    for (p = 0; p < sizeof(profiles) / sizeof(profiles[0]); p++) {
        uint32_t config = profiles[p];
        unsigned batch = opengpu_shader_resource_batch(config);
        unsigned lanes = config ? opengpu_shader_resource_lanes(config) : 4;
        unsigned stride = batch * 4u, prefix = 128, uniform = prefix + 9u * stride;
        uint8_t memory[8192], before[8192];
        struct fs_word_vm seed = {0};
        unsigned i, warp;
        size_t lowered = 0;

        assert(!opengpu_shader_lower_runtime_abi(original, words, false, runtime,
                                                OPENGPU_ABI_TARGET_WORDS, &lowered));
        assert(opengpu_shader_validate_words_profile_runtime(runtime, (uint32_t)lowered,
            opengpu_shader_fragment_bank_stride(config), batch, 6, 9, true,
            false, false, true, batch));
        memset(memory, 0xa7, sizeof(memory));
        seed.memory = memory; seed.bytes = sizeof(memory);
        for (i = 0; i < 8; i++)
            vm_store(&seed, uniform + i * 4u, vm_bits(i < 4 ? a[i] : b[i - 4]));
        for (i = 0; i < batch; i++) {
            vm_store(&seed, prefix + i * 4u, i % 2);
            vm_store(&seed, prefix + stride + i * 4u, (i / 2) % 2);
        }
        memcpy(before, memory, sizeof(memory));
        for (warp = 0; warp < batch / lanes; warp++) {
            struct fs_word_vm m = {0};
            m.memory = memory; m.bytes = sizeof(memory); m.lanes = lanes;
            m.x[1] = prefix; m.x[8] = warp * lanes; m.x[9] = batch;
            vm_execute(&m, runtime, (unsigned)lowered, 1);
            assert(m.stores == 1);
        }
        for (i = 0; i < sizeof(memory); i++) {
            uint8_t expected = before[i];
            if (i >= prefix + 6u * stride && i < prefix + 7u * stride) {
                unsigned item = (i - prefix - 6u * stride) / 4u, c;
                uint32_t color = 0;
                for (c = 0; c < 4; c++) {
                    float lhs = mask & (1u << c) ?
                        (float)(c == 0 ? item % 2 : (item / 2) % 2) : a[c];
                    volatile float product = lhs * b[c];
                    volatile float scaled;
                    if (dot) {
                        volatile float x = (float)(item % 2) * b[0];
                        volatile float y = (float)((item / 2) % 2) * b[1];
                        product = x + y;
                    }
                    scaled = product * 255.f;
                    color |= (uint32_t)lrintf(scaled) << (24u - 8u * c);
                }
                expected = (uint8_t)(color >> (8u * ((i - prefix) % 4u)));
            }
            assert(memory[i] == expected);
        }
    }
    printf("FS coordinate demand mask=%u via_temp=%d dead_input=%d dot=%d words=%u: PASS\n",
           mask, via_temp, dead_input, dot, words);
}

int main(void)
{
    struct tgsi_token tokens[MAX_TOKENS];
    struct opengpu_fs_alu out;

    execute_coordinate_case(0, 0, 0, 0);
    execute_coordinate_case(1, 0, 0, 0);
    execute_coordinate_case(2, 0, 0, 0);
    execute_coordinate_case(3, 0, 0, 0);
    execute_coordinate_case(2, 1, 0, 0);
    execute_coordinate_case(0, 0, 1, 0);
    execute_coordinate_case(3, 0, 0, 1);
    /* The existing position Z/W admission remains unsupported. */
    coordinate_program(tokens, 4, 0, 0, 0);
    assert(opengpu_fs_emit(tokens, &out) && out.words == 0);
    coordinate_program(tokens, 8, 0, 0, 0);
    assert(opengpu_fs_emit(tokens, &out) && out.words == 0);
    /* Discard's transitive input and the conservative nonzero-index texture
     * origin path must retain their required coordinate loads. */
    fragcoord_kill(tokens);
    assert(!opengpu_fs_emit(tokens, &out));
    check_coordinate_loads(out.code, out.words, 2);
    uniform_if(tokens);
    assert(!opengpu_fs_emit(tokens, &out));
    check_coordinate_loads(out.code, out.words, 0);
    coordinate_flow_program(tokens);
    assert(!opengpu_fs_emit(tokens, &out));
    check_coordinate_loads(out.code, out.words, 3);
    check_runtime(out.code, out.words, 0, 0x02020810u);
    sample_miss(tokens);
    assert(!opengpu_fs_emit(tokens, &out) && out.samples);
    check_coordinate_loads(out.code, out.words, 3);
    puts("FS production prologue: public lowering, final words, exact output/guards at full128items: PASS");
    return 0;
}
