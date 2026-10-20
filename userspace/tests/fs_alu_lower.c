/* SPDX-License-Identifier: MIT */
/* A fragment program the Gallium menu cannot classify must come back from
 * create_fs_state's lowerer with the ALU emitter words. A colour copy stays
 * on the menu. The emitter words then have to pass the fragment validator
 * and the runtime ABI lowerer, which rewrites only the VL=4 vsetivli. */
#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "pipe/p_defines.h"
#include "pipe/p_shader_tokens.h"
#include "tgsi/tgsi_build.h"
#include "opengpu_fragment_alu.h"
#include "opengpu_fragment_min.h"
#include "opengpu_fs_emit.h"
#include "opengpu_vertex_alu.h"
#include "opengpu_vs_emit.h"
#include "opengpu_shader_abi.h"
#include "opengpu_shader_resources.h"
#include "opengpu_shader_validator.h"

#define MAX_TOKENS 128u
#define VL4_VSETIVLI 0xc1027057u
#define VLMAX_VSETVLI 0x01007357u

static struct tgsi_token *cursor(struct tgsi_token *tokens,
                                 const struct tgsi_header *header)
{
    return tokens + header->HeaderSize + header->BodySize;
}

static unsigned room(const struct tgsi_header *header)
{
    return MAX_TOKENS - header->HeaderSize - header->BodySize;
}

static void begin_shader(struct tgsi_token *tokens, struct tgsi_header **header,
                         unsigned processor)
{
    struct tgsi_processor *proc;

    memset(tokens, 0, MAX_TOKENS * sizeof(*tokens));
    *header = (struct tgsi_header *)tokens;
    **header = tgsi_build_header();
    proc = (struct tgsi_processor *)(tokens + 1);
    *proc = tgsi_build_processor(processor, *header);
}

static void begin_fragment(struct tgsi_token *tokens, struct tgsi_header **header)
{
    begin_shader(tokens, header, PIPE_SHADER_FRAGMENT);
}

static void add_decl(struct tgsi_token *tokens, struct tgsi_header *header,
                     unsigned file, unsigned first, unsigned last, int semantic)
{
    struct tgsi_full_declaration decl = tgsi_default_full_declaration();
    unsigned wrote;

    decl.Declaration.File = file;
    decl.Range.First = first;
    decl.Range.Last = last;
    if (semantic >= 0) {
        decl.Declaration.Semantic = 1;
        decl.Semantic.Name = (unsigned)semantic;
    }
    wrote = tgsi_build_full_declaration(&decl, cursor(tokens, header), header,
                                        room(header));
    assert(wrote);
}

static void add_insn_mask(struct tgsi_token *tokens, struct tgsi_header *header,
                          unsigned opcode, unsigned dst_file, unsigned dst_index,
                          unsigned writemask, unsigned nsrc,
                          const unsigned *src_file, const unsigned *src_index)
{
    struct tgsi_full_instruction inst = tgsi_default_full_instruction();
    unsigned i, wrote;

    inst.Instruction.Opcode = opcode;
    inst.Instruction.NumDstRegs = 1;
    inst.Instruction.NumSrcRegs = nsrc;
    inst.Dst[0].Register.File = dst_file;
    inst.Dst[0].Register.Index = (int)dst_index;
    inst.Dst[0].Register.WriteMask = writemask;
    for (i = 0; i < nsrc; i++) {
        inst.Src[i].Register.File = src_file[i];
        inst.Src[i].Register.Index = (int)src_index[i];
    }
    wrote = tgsi_build_full_instruction(&inst, cursor(tokens, header), header,
                                        room(header));
    assert(wrote);
}

static void add_insn(struct tgsi_token *tokens, struct tgsi_header *header,
                     unsigned opcode, unsigned dst_file, unsigned dst_index,
                     unsigned nsrc, const unsigned *src_file,
                     const unsigned *src_index)
{
    add_insn_mask(tokens, header, opcode, dst_file, dst_index,
                  TGSI_WRITEMASK_XYZW, nsrc, src_file, src_index);
}

static void add_end(struct tgsi_token *tokens, struct tgsi_header *header)
{
    struct tgsi_full_instruction inst = tgsi_default_full_instruction();
    unsigned wrote;

    inst.Instruction.Opcode = TGSI_OPCODE_END;
    inst.Instruction.NumDstRegs = 0;
    inst.Instruction.NumSrcRegs = 0;
    wrote = tgsi_build_full_instruction(&inst, cursor(tokens, header), header,
                                        room(header));
    assert(wrote);
}

static void colour_copy(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    unsigned src_file = TGSI_FILE_INPUT, src_index = 0;

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_insn(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, 1,
             &src_file, &src_index);
    add_end(tokens, header);
}

/* gl_FragColor = CONST[0][0] * CONST[0][1]. The menu matches a colour copy,
 * a uniform copy, and a sample times a colour. Two constants multiplied
 * together are none of those. */
static void constant_product(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    unsigned mul_file[2] = { TGSI_FILE_CONSTANT, TGSI_FILE_CONSTANT };
    unsigned mul_index[2] = { 0, 1 };
    unsigned mov_file = TGSI_FILE_TEMPORARY, mov_index = 0;

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 1, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_insn(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_TEMPORARY, 0, 2,
             mul_file, mul_index);
    add_insn(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, 1,
             &mov_file, &mov_index);
    add_end(tokens, header);
}

/* gl_FragColor = min(CONST[0][0], CONST[0][1]). clamp() lowers to MIN
 * and MAX. MAX is already emitted. */
static void constant_min(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    unsigned min_file[2] = { TGSI_FILE_CONSTANT, TGSI_FILE_CONSTANT };
    unsigned min_index[2] = { 0, 1 };
    unsigned mov_file = TGSI_FILE_TEMPORARY, mov_index = 0;

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 1, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_insn(tokens, header, TGSI_OPCODE_MIN, TGSI_FILE_TEMPORARY, 0, 2,
             min_file, min_index);
    add_insn(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, 1,
             &mov_file, &mov_index);
    add_end(tokens, header);
}

/* Position x is IN[0].x + CONST[0][0].x. That is not a mat4, so the
 * vertex menu misses and the emitter copies the other slices. */
static void position_xadd(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    unsigned add_file[2] = { TGSI_FILE_INPUT, TGSI_FILE_CONSTANT };
    unsigned add_index[2] = { 0, 0 };
    unsigned yzw_file = TGSI_FILE_INPUT, yzw_index = 0;
    unsigned pos_file = TGSI_FILE_TEMPORARY, pos_index = 0;
    unsigned color_file = TGSI_FILE_INPUT, color_index = 1;

    begin_shader(tokens, &header, PIPE_SHADER_VERTEX);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_INPUT, 1, 1, TGSI_SEMANTIC_COLOR);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 1, 1, TGSI_SEMANTIC_COLOR);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_insn_mask(tokens, header, TGSI_OPCODE_ADD, TGSI_FILE_TEMPORARY, 0,
                  TGSI_WRITEMASK_X, 2, add_file, add_index);
    add_insn_mask(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0,
                  TGSI_WRITEMASK_YZW, 1, &yzw_file, &yzw_index);
    add_insn(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, 1,
             &pos_file, &pos_index);
    add_insn(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 1, 1,
             &color_file, &color_index);
    add_end(tokens, header);
}

static void position_copy(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    unsigned src_file = TGSI_FILE_INPUT, src_index = 0;

    begin_shader(tokens, &header, PIPE_SHADER_VERTEX);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_insn(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, 1,
             &src_file, &src_index);
    add_end(tokens, header);
}

static int contains(const uint32_t *code, unsigned words, uint32_t word)
{
    unsigned i;

    for (i = 0; i < words; i++) {
        if (code[i] == word)
            return 1;
    }
    return 0;
}

static void check_runtime(const uint32_t *code, unsigned words, int vertex,
                          uint32_t resources)
{
    uint32_t converted[OPENGPU_ABI_TARGET_WORDS];
    unsigned batch = opengpu_shader_resource_batch(resources);
    size_t lowered = 0;

    assert(!opengpu_shader_lower_runtime_abi(code, words, vertex, converted,
                                              OPENGPU_ABI_TARGET_WORDS, &lowered));
    assert(lowered && lowered <= OPENGPU_ABI_TARGET_WORDS);
    assert(!contains(converted, (unsigned)lowered, VL4_VSETIVLI));
    assert(contains(converted, (unsigned)lowered, VLMAX_VSETVLI));
    if (vertex)
        assert(opengpu_shader_validate_words_profile_runtime(
            converted, (opengpu_shader_u32)lowered,
            opengpu_shader_vertex_kernarg_bytes(resources), batch, 8, 16, false,
            false, false, true, batch));
    else
        assert(opengpu_shader_validate_words_profile_runtime(
            converted, (opengpu_shader_u32)lowered,
            opengpu_shader_fragment_bank_stride(resources), batch, 6, 9, true,
            false, false, true, batch));
}

int main(void)
{
    struct tgsi_token copy_tokens[MAX_TOKENS];
    struct tgsi_token product_tokens[MAX_TOKENS];
    struct tgsi_token min_tokens[MAX_TOKENS];
    struct tgsi_token copy_vs[MAX_TOKENS];
    struct tgsi_token add_vs[MAX_TOKENS];
    uint32_t code[256];
    unsigned words = 0;
    const uint32_t resources[] = { 0, 0x01020204u };
    unsigned i;

    colour_copy(copy_tokens);
    assert(opengpu_fragment_lower_kind(copy_tokens, code, 256, &words) == 1);

    constant_product(product_tokens);
    words = 0;
    assert(!opengpu_fragment_lower_kind(product_tokens, code, 256, &words));
    assert(words && words <= 256);
    assert(words == OPENGPU_FRAGMENT_ALU_WORDS);
    assert(!memcmp(code, opengpu_fragment_alu, words * sizeof(uint32_t)));
    assert(contains(code, words, VL4_VSETIVLI));
    assert(opengpu_shader_validate_words(code, words, 512, 8, true));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
        check_runtime(code, words, 0, resources[i]);

    constant_min(min_tokens);
    words = 0;
    assert(!opengpu_fragment_lower_kind(min_tokens, code, 256, &words));
    assert(words == OPENGPU_FRAGMENT_MIN_WORDS);
    assert(!memcmp(code, opengpu_fragment_min, words * sizeof(uint32_t)));
    assert(contains(code, words, VL4_VSETIVLI));
    assert(opengpu_shader_validate_words(code, words, 512, 8, true));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
        check_runtime(code, words, 0, resources[i]);

    position_copy(copy_vs);
    assert(opengpu_vertex_lower_kind(copy_vs, code, 256, &words) == 1);
    position_xadd(add_vs);
    words = 0;
    assert(!opengpu_vertex_lower_kind(add_vs, code, 256, &words));
    assert(words == OPENGPU_VERTEX_ALU_WORDS);
    assert(!memcmp(code, opengpu_vertex_alu, words * sizeof(uint32_t)));
    assert(contains(code, words, VL4_VSETIVLI));
    assert(contains(code, words, OPENGPU_VERTEX_ALU_VADD));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++) {
        assert(opengpu_vertex_shader_validate_words(
            code, words, opengpu_shader_vertex_kernarg_bytes(resources[i]), 8,
            true));
        check_runtime(code, words, 1, resources[i]);
    }

    printf("fragment ALU lower: PASS\n");
    return 0;
}
