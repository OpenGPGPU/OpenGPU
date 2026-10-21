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
#include "opengpu_fragment_cover.h"
#include "opengpu_fragment_min.h"
#include "opengpu_fs_emit.h"
#include "opengpu_vertex_alu.h"
#include "opengpu_vs_emit.h"
#include "opengpu_shader_abi.h"
#include "opengpu_shader_resources.h"
#include "opengpu_shader_validator.h"

#define MAX_TOKENS 1024u
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

struct op_src {
    unsigned file;
    int index;
    unsigned swiz[4];
    int neg;
};

static struct op_src op_src(unsigned file, int index, unsigned x, unsigned y,
                            unsigned z, unsigned w, int neg)
{
    struct op_src s;

    s.file = file;
    s.index = index;
    s.swiz[0] = x;
    s.swiz[1] = y;
    s.swiz[2] = z;
    s.swiz[3] = w;
    s.neg = neg;
    return s;
}

static struct op_src op_src_id(unsigned file, int index)
{
    return op_src(file, index, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Z,
                  TGSI_SWIZZLE_W, 0);
}

static int op_saturate;

static void add_op(struct tgsi_token *tokens, struct tgsi_header *header,
                   unsigned opcode, int dst_file, unsigned dst_index,
                   unsigned writemask, unsigned nsrc, const struct op_src *src)
{
    struct tgsi_full_instruction inst = tgsi_default_full_instruction();
    unsigned i, wrote;

    inst.Instruction.Opcode = opcode;
    inst.Instruction.Saturate = op_saturate ? 1u : 0u;
    op_saturate = 0;
    inst.Instruction.NumDstRegs = dst_file < 0 ? 0u : 1u;
    inst.Instruction.NumSrcRegs = nsrc;
    if (dst_file >= 0) {
        inst.Dst[0].Register.File = (unsigned)dst_file;
        inst.Dst[0].Register.Index = (int)dst_index;
        inst.Dst[0].Register.WriteMask = writemask;
    }
    for (i = 0; i < nsrc; i++) {
        inst.Src[i].Register.File = src[i].file;
        inst.Src[i].Register.Index = src[i].index;
        inst.Src[i].Register.SwizzleX = src[i].swiz[0];
        inst.Src[i].Register.SwizzleY = src[i].swiz[1];
        inst.Src[i].Register.SwizzleZ = src[i].swiz[2];
        inst.Src[i].Register.SwizzleW = src[i].swiz[3];
        inst.Src[i].Register.Negate = src[i].neg ? 1u : 0u;
    }
    wrote = tgsi_build_full_instruction(&inst, cursor(tokens, header), header,
                                        room(header));
    assert(wrote);
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

/* gl_FragColor = sqrt(CONST[0][0]). sqrt(0.25) and sqrt(1) are exact. */
static void constant_sqrt(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src src = op_src_id(TGSI_FILE_CONSTANT, 0);
    struct op_src tmp = op_src_id(TGSI_FILE_TEMPORARY, 0);

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_op(tokens, header, TGSI_OPCODE_SQRT, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XYZW, 1, &src);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 1, &tmp);
    add_end(tokens, header);
}

/* gl_FragColor = min(max(CONST[0], 0), 1). Low is CONST[1].xxxx and high
 * is CONST[1].yyyy, so both bounds are real MIN and MAX sources. */
static void constant_clamp(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src value = op_src_id(TGSI_FILE_CONSTANT, 0);
    struct op_src low = op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_X,
                               TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src high = op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_Y,
                                TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src pair[2];
    struct op_src tmp = op_src_id(TGSI_FILE_TEMPORARY, 0);

    pair[0] = value;
    pair[1] = low;
    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 1, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_op(tokens, header, TGSI_OPCODE_MAX, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XYZW, 2, pair);
    pair[0] = tmp;
    pair[1] = high;
    add_op(tokens, header, TGSI_OPCODE_MIN, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XYZW, 2, pair);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 1, &tmp);
    add_end(tokens, header);
}

/* gl_FragColor.x is (gl_FragCoord.y < 32). .yzw is CONST (0, 1, 1), so
 * y<32 packs 0xff00ffff and y>=32 packs 0x0000ffff. Channel y is the
 * only input the previous images never read. */
static void fragcoord_y(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src cmp[2];
    struct op_src rest = op_src_id(TGSI_FILE_CONSTANT, 0);
    struct op_src tmp = op_src_id(TGSI_FILE_TEMPORARY, 0);

    cmp[0] = op_src(TGSI_FILE_INPUT, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y,
                    TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    cmp[1] = op_src_id(TGSI_FILE_CONSTANT, 0);
    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_op(tokens, header, TGSI_OPCODE_SLT, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_X, 2, cmp);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_YZW, 1, &rest);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 1, &tmp);
    add_end(tokens, header);
}

/* if (CONST[0].x) colour (1,0,0,1) else colour (0,1,0,1). The two colours
 * are swizzles of the same vec4, so one uniform selects the side. */
static void uniform_if(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src cond = op_src_id(TGSI_FILE_CONSTANT, 0);
    struct op_src then_c = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_Y,
                                  TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W, 0);
    struct op_src else_c = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_Z,
                                  TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W, 0);
    struct op_src tmp = op_src_id(TGSI_FILE_TEMPORARY, 0);

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_op(tokens, header, TGSI_OPCODE_IF, -1, 0, 0, 1, &cond);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XYZW, 1, &then_c);
    add_op(tokens, header, TGSI_OPCODE_ELSE, -1, 0, 0, 0, NULL);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XYZW, 1, &else_c);
    add_op(tokens, header, TGSI_OPCODE_ENDIF, -1, 0, 0, 0, NULL);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 1, &tmp);
    add_end(tokens, header);
}

/* Discard lanes whose gl_FragCoord.y is below 32. Survivors are
 * (1, 0, 0, 1), taken from CONST[0].yzzw. */
static void fragcoord_kill(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src add[2];
    struct op_src pred = op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_X,
                                TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src colour = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_Y,
                                  TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W, 0);

    add[0] = op_src(TGSI_FILE_INPUT, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y,
                    TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    add[1] = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                    TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 1);
    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_op(tokens, header, TGSI_OPCODE_ADD, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_X, 2, add);
    add_op(tokens, header, TGSI_OPCODE_KILL_IF, -1, 0, 0, 1, &pred);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 1, &colour);
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

/* gl_Position = CONST[0][0..3] * CONST[0][4]. No vertex attribute. */
static void constant_position(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src mul[2], mad[3];
    unsigned ch;

    begin_shader(tokens, &header, PIPE_SHADER_VERTEX);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 4, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    mul[0] = op_src(TGSI_FILE_CONSTANT, 4, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                    TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    mul[1] = op_src_id(TGSI_FILE_CONSTANT, 0);
    add_op(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XYZW, 2, mul);
    for (ch = 1; ch < 4; ch++) {
        mad[0] = op_src(TGSI_FILE_CONSTANT, 4, ch, ch, ch, ch, 0);
        mad[1] = op_src_id(TGSI_FILE_CONSTANT, ch);
        mad[2] = op_src_id(TGSI_FILE_TEMPORARY, 0);
        add_op(tokens, header, TGSI_OPCODE_MAD,
               ch == 3 ? TGSI_FILE_OUTPUT : TGSI_FILE_TEMPORARY, 0,
               TGSI_WRITEMASK_XYZW, 3, mad);
    }
    add_end(tokens, header);
}

/* The same product with an attribute stays on the ordinary MVP menu. */
static void attribute_position(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src mul[2], mad[3];
    unsigned ch;

    begin_shader(tokens, &header, PIPE_SHADER_VERTEX);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 3, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    mul[0] = op_src(TGSI_FILE_INPUT, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                    TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    mul[1] = op_src_id(TGSI_FILE_CONSTANT, 0);
    add_op(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XYZW, 2, mul);
    for (ch = 1; ch < 4; ch++) {
        mad[0] = op_src(TGSI_FILE_INPUT, 0, ch, ch, ch, ch, 0);
        mad[1] = op_src_id(TGSI_FILE_CONSTANT, ch);
        mad[2] = op_src_id(TGSI_FILE_TEMPORARY, 0);
        add_op(tokens, header, TGSI_OPCODE_MAD,
               ch == 3 ? TGSI_FILE_OUTPUT : TGSI_FILE_TEMPORARY, 0,
               TGSI_WRITEMASK_XYZW, 3, mad);
    }
    add_end(tokens, header);
}

static void add_imm(struct tgsi_token *tokens, struct tgsi_header *header,
                    float x, float y, float z, float w);

/* GtkGLArea: texture2D, then SEQ against 1, then CMP that swaps R and B. */
static void gtk_swizzle(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src uv = op_src(TGSI_FILE_INPUT, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                              TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src seq[2];
    struct op_src cmp[3];
    struct tgsi_full_instruction inst;
    unsigned wrote;

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_GENERIC);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 1, -1);
    add_imm(tokens, header, 1.f, 0.f, 0.f, 0.f);
    inst = tgsi_default_full_instruction();
    inst.Instruction.Opcode = TGSI_OPCODE_TEX;
    inst.Instruction.NumDstRegs = 1;
    inst.Instruction.NumSrcRegs = 2;
    inst.Instruction.Texture = 1;
    inst.Texture.Texture = TGSI_TEXTURE_2D;
    inst.Dst[0].Register.File = TGSI_FILE_TEMPORARY;
    inst.Dst[0].Register.Index = 0;
    inst.Dst[0].Register.WriteMask = TGSI_WRITEMASK_XYZW;
    inst.Src[0].Register.File = uv.file;
    inst.Src[0].Register.Index = uv.index;
    inst.Src[0].Register.SwizzleX = uv.swiz[0];
    inst.Src[0].Register.SwizzleY = uv.swiz[1];
    inst.Src[0].Register.SwizzleZ = uv.swiz[2];
    inst.Src[0].Register.SwizzleW = uv.swiz[3];
    inst.Src[1].Register.File = TGSI_FILE_SAMPLER;
    inst.Src[1].Register.Index = 0;
    wrote = tgsi_build_full_instruction(&inst, cursor(tokens, header), header,
                                        room(header));
    assert(wrote);
    seq[0] = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                    TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    seq[1] = op_src(TGSI_FILE_IMMEDIATE, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                    TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    add_op(tokens, header, TGSI_OPCODE_SEQ, TGSI_FILE_TEMPORARY, 1,
           TGSI_WRITEMASK_X, 2, seq);
    cmp[0] = op_src(TGSI_FILE_TEMPORARY, 1, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                    TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 1);
    cmp[1] = op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Y,
                    TGSI_SWIZZLE_X, TGSI_SWIZZLE_W, 0);
    cmp[2] = op_src_id(TGSI_FILE_TEMPORARY, 0);
    add_op(tokens, header, TGSI_OPCODE_CMP, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 3, cmp);
    add_end(tokens, header);
}

/* The colour fragment bound with the constant-position vertex shader. */
static void uniform_color(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src color = op_src_id(TGSI_FILE_CONSTANT, 0);

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 0, -1);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 1, &color);
    add_end(tokens, header);
}

/* Position xy from the attribute, zw from (0, 1), plus a vec2 varying. */
static void position_zw_imm(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src xy = op_src(TGSI_FILE_INPUT, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                              TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src zw = op_src(TGSI_FILE_IMMEDIATE, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                              TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y, 0);
    struct op_src uv = op_src(TGSI_FILE_INPUT, 1, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                              TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);

    begin_shader(tokens, &header, PIPE_SHADER_VERTEX);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_INPUT, 1, 1, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 1, 1, TGSI_SEMANTIC_GENERIC);
    add_imm(tokens, header, 0.f, 1.f, 0.f, 0.f);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XY, 1, &xy);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_ZW, 1, &zw);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 1,
           TGSI_WRITEMASK_XY, 1, &uv);
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

static int has_vfsqrt(const uint32_t *code, unsigned words)
{
    unsigned i;

    for (i = 0; i < words; i++) {
        uint32_t w = code[i];

        if ((w & 0x7fu) == 0x57u && (w >> 26) == 0x13u &&
            ((w >> 15) & 31u) == 0u)
            return 1;
    }
    return 0;
}

static int has_vfrsqrt7(const uint32_t *code, unsigned words)
{
    unsigned i;

    for (i = 0; i < words; i++) {
        if ((code[i] & 0xfe0ff07fu) == 0x4e021057u)
            return 1;
    }
    return 0;
}

static int has_branch(const uint32_t *code, unsigned words)
{
    unsigned i;

    for (i = 0; i < words; i++) {
        if ((code[i] & 0x7fu) == 0x63u)
            return 1;
    }
    return 0;
}

static int has_vtex(const uint32_t *code, unsigned words)
{
    unsigned i;

    for (i = 0; i < words; i++) {
        if ((code[i] & 0xfe00707fu) == 0x0600002bu)
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

static void check_runtime_texture(const uint32_t *code, unsigned words,
                                  uint32_t resources)
{
    uint32_t converted[OPENGPU_ABI_TARGET_WORDS];
    unsigned batch = opengpu_shader_resource_batch(resources);
    size_t lowered = 0;

    assert(!opengpu_shader_lower_runtime_abi(code, words, 0, converted,
                                              OPENGPU_ABI_TARGET_WORDS, &lowered));
    assert(lowered && lowered <= OPENGPU_ABI_TARGET_WORDS);
    assert(opengpu_shader_validate_words_profile_runtime(
        converted, (opengpu_shader_u32)lowered,
        opengpu_shader_fragment_bank_stride(resources), batch, 6, 9, true,
        true, false, true, batch));
}

static void add_imm(struct tgsi_token *tokens, struct tgsi_header *header,
                    float x, float y, float z, float w)
{
    struct tgsi_full_immediate imm = tgsi_default_full_immediate();
    unsigned wrote;

    imm.Immediate.DataType = TGSI_IMM_FLOAT32;
    imm.Immediate.NrTokens = 5;
    imm.u[0].Float = x;
    imm.u[1].Float = y;
    imm.u[2].Float = z;
    imm.u[3].Float = w;
    wrote = tgsi_build_full_immediate(&imm, cursor(tokens, header), header,
                                      room(header));
    assert(wrote);
}

/* Mesa's ellipsis_coverage TGSI: Y-flip MAD, uniform radius test, DP2,
 * DIV, RSQ+RCP, and a saturating add. CONST[0][2] is gl_FbWposYTransform
 * and does not fit in the eight-float uniform tail. */
static void gtk_coverage(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src in_yyxx = op_src(TGSI_FILE_INPUT, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y,
                                   TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src in_xyyy = op_src(TGSI_FILE_INPUT, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                                   TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src c2xxxx = op_src(TGSI_FILE_CONSTANT, 2, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                                  TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src c2yyxx = op_src(TGSI_FILE_CONSTANT, 2, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y,
                                  TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src neg_c1 = op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                                  TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W, 1);
    struct op_src c1zwzw = op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W,
                                  TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W, 0);
    struct op_src c1zwyy = op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W,
                                  TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src imm0x = op_src(TGSI_FILE_IMMEDIATE, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                                 TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src imm0y = op_src(TGSI_FILE_IMMEDIATE, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y,
                                 TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src imm0z = op_src(TGSI_FILE_IMMEDIATE, 0, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Z,
                                 TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Z, 0);
    struct op_src imm0w = op_src(TGSI_FILE_IMMEDIATE, 0, TGSI_SWIZZLE_W, TGSI_SWIZZLE_W,
                                 TGSI_SWIZZLE_W, TGSI_SWIZZLE_W, 0);
    struct op_src half = op_src(TGSI_FILE_IMMEDIATE, 1, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                                TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src t0xyxx = op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                                  TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src t0xyyy = op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                                  TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src t0x = op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                               TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src t0neg = op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                                 TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 1);
    struct op_src t1x = op_src(TGSI_FILE_TEMPORARY, 1, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                               TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src t1yxxx = op_src(TGSI_FILE_TEMPORARY, 1, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_X,
                                  TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src t2xyxx = op_src(TGSI_FILE_TEMPORARY, 2, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                                  TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src t2xyyy = op_src(TGSI_FILE_TEMPORARY, 2, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                                  TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src t2x = op_src(TGSI_FILE_TEMPORARY, 2, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                               TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
    struct op_src color = op_src_id(TGSI_FILE_CONSTANT, 0);
    struct op_src mad[3];
    struct op_src pair[2];

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 2, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 2, -1);
    add_imm(tokens, header, 0.f, 2.f, -1.f, 0.00000001f);
    add_imm(tokens, header, 0.5f, 0.f, 0.f, 0.f);
    mad[0] = in_yyxx;
    mad[1] = c2xxxx;
    mad[2] = c2yyxx;
    add_op(tokens, header, TGSI_OPCODE_MAD, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_Y, 3, mad);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_X, 1, &in_xyyy);
    pair[0] = t0xyxx;
    pair[1] = neg_c1;
    add_op(tokens, header, TGSI_OPCODE_ADD, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XY, 2, pair);
    pair[0] = c1zwzw;
    pair[1] = imm0x;
    add_op(tokens, header, TGSI_OPCODE_SEQ, TGSI_FILE_TEMPORARY, 1,
           TGSI_WRITEMASK_XY, 2, pair);
    pair[0] = t1x;
    pair[1] = t1yxxx;
    add_op(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_TEMPORARY, 1,
           TGSI_WRITEMASK_X, 2, pair);
    add_op(tokens, header, TGSI_OPCODE_IF, -1, 0, 0, 1, &t1x);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 1,
           TGSI_WRITEMASK_X, 1, &imm0x);
    add_op(tokens, header, TGSI_OPCODE_ELSE, -1, 0, 0, 0, NULL);
    pair[0] = t0xyyy;
    pair[1] = c1zwyy;
    add_op(tokens, header, TGSI_OPCODE_DIV, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XY, 2, pair);
    pair[0] = imm0y;
    pair[1] = t0xyyy;
    add_op(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_TEMPORARY, 2,
           TGSI_WRITEMASK_XY, 2, pair);
    pair[0] = t2xyyy;
    pair[1] = c1zwyy;
    add_op(tokens, header, TGSI_OPCODE_DIV, TGSI_FILE_TEMPORARY, 2,
           TGSI_WRITEMASK_XY, 2, pair);
    pair[0] = t0xyxx;
    pair[1] = t0xyxx;
    add_op(tokens, header, TGSI_OPCODE_DP2, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_X, 2, pair);
    pair[0] = t0x;
    pair[1] = imm0z;
    add_op(tokens, header, TGSI_OPCODE_ADD, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_X, 2, pair);
    pair[0] = t2xyxx;
    pair[1] = t2xyxx;
    add_op(tokens, header, TGSI_OPCODE_DP2, TGSI_FILE_TEMPORARY, 2,
           TGSI_WRITEMASK_X, 2, pair);
    pair[0] = t2x;
    pair[1] = imm0w;
    add_op(tokens, header, TGSI_OPCODE_ADD, TGSI_FILE_TEMPORARY, 2,
           TGSI_WRITEMASK_X, 2, pair);
    add_op(tokens, header, TGSI_OPCODE_RSQ, TGSI_FILE_TEMPORARY, 2,
           TGSI_WRITEMASK_X, 1, &t2x);
    add_op(tokens, header, TGSI_OPCODE_RCP, TGSI_FILE_TEMPORARY, 2,
           TGSI_WRITEMASK_X, 1, &t2x);
    pair[0] = t0x;
    pair[1] = t2x;
    add_op(tokens, header, TGSI_OPCODE_DIV, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_X, 2, pair);
    pair[0] = half;
    pair[1] = t0neg;
    op_saturate = 1;
    add_op(tokens, header, TGSI_OPCODE_ADD, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_X, 2, pair);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 1,
           TGSI_WRITEMASK_X, 1, &t0x);
    add_op(tokens, header, TGSI_OPCODE_ENDIF, -1, 0, 0, 0, NULL);
    pair[0] = color;
    pair[1] = t1x;
    add_op(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 2, pair);
    add_end(tokens, header);
}

/* texture2D of a temporary coordinate misses the clang sample menu. */
static void sample_miss(struct tgsi_token *tokens)
{
    struct tgsi_header *header;
    struct op_src uv = op_src(TGSI_FILE_INPUT, 1, TGSI_SWIZZLE_X, TGSI_SWIZZLE_Y,
                              TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
    struct op_src coord = op_src_id(TGSI_FILE_TEMPORARY, 0);
    struct tgsi_full_instruction inst;
    unsigned wrote;

    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_INPUT, 1, 1, TGSI_SEMANTIC_TEXCOORD);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 1, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0,
           TGSI_WRITEMASK_XY, 1, &uv);
    inst = tgsi_default_full_instruction();
    inst.Instruction.Opcode = TGSI_OPCODE_TEX;
    inst.Instruction.NumDstRegs = 1;
    inst.Instruction.NumSrcRegs = 1;
    inst.Instruction.Texture = 1;
    inst.Texture.Texture = TGSI_TEXTURE_2D;
    inst.Dst[0].Register.File = TGSI_FILE_TEMPORARY;
    inst.Dst[0].Register.Index = 1;
    inst.Dst[0].Register.WriteMask = TGSI_WRITEMASK_XYZW;
    inst.Src[0].Register.File = coord.file;
    inst.Src[0].Register.Index = coord.index;
    inst.Src[0].Register.SwizzleX = TGSI_SWIZZLE_X;
    inst.Src[0].Register.SwizzleY = TGSI_SWIZZLE_Y;
    inst.Src[0].Register.SwizzleZ = TGSI_SWIZZLE_Z;
    inst.Src[0].Register.SwizzleW = TGSI_SWIZZLE_W;
    wrote = tgsi_build_full_instruction(&inst, cursor(tokens, header), header,
                                        room(header));
    assert(wrote);
    coord = op_src_id(TGSI_FILE_TEMPORARY, 1);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
           TGSI_WRITEMASK_XYZW, 1, &coord);
    add_end(tokens, header);
}

int main(void)
{
    struct tgsi_token copy_tokens[MAX_TOKENS];
    struct tgsi_token product_tokens[MAX_TOKENS];
    struct tgsi_token min_tokens[MAX_TOKENS];
    struct tgsi_token sqrt_tokens[MAX_TOKENS];
    struct tgsi_token clamp_tokens[MAX_TOKENS];
    struct tgsi_token coord_tokens[MAX_TOKENS];
    struct tgsi_token if_tokens[MAX_TOKENS];
    struct tgsi_token kill_tokens[MAX_TOKENS];
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

    constant_sqrt(sqrt_tokens);
    words = 0;
    assert(!opengpu_fragment_lower_kind(sqrt_tokens, code, 256, &words));
    assert(words == OPENGPU_FRAGMENT_SQRT_WORDS);
    assert(!memcmp(code, opengpu_fragment_sqrt, words * sizeof(uint32_t)));
    assert(contains(code, words, OPENGPU_FRAGMENT_SQRT_SCALAR));
    assert(opengpu_shader_validate_words(code, words, 512, 8, true));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
        check_runtime(code, words, 0, resources[i]);

    constant_clamp(clamp_tokens);
    words = 0;
    assert(!opengpu_fragment_lower_kind(clamp_tokens, code, 256, &words));
    assert(words == OPENGPU_FRAGMENT_CLAMP_WORDS);
    assert(!memcmp(code, opengpu_fragment_clamp, words * sizeof(uint32_t)));
    assert(opengpu_shader_validate_words(code, words, 512, 8, true));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
        check_runtime(code, words, 0, resources[i]);

    fragcoord_y(coord_tokens);
    words = 0;
    assert(!opengpu_fragment_lower_kind(coord_tokens, code, 256, &words));
    assert(words == OPENGPU_FRAGMENT_COORD_WORDS);
    assert(!memcmp(code, opengpu_fragment_coord, words * sizeof(uint32_t)));
    assert(contains(code, words, OPENGPU_FRAGMENT_COORD_YCVT));
    assert(opengpu_shader_validate_words(code, words, 512, 8, true));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
        check_runtime(code, words, 0, resources[i]);

    uniform_if(if_tokens);
    words = 0;
    assert(!opengpu_fragment_lower_kind(if_tokens, code, 256, &words));
    assert(words == OPENGPU_FRAGMENT_IF_WORDS);
    assert(!memcmp(code, opengpu_fragment_if, words * sizeof(uint32_t)));
    assert(contains(code, words, OPENGPU_FRAGMENT_IF_BNE));
    assert(opengpu_shader_validate_words(code, words, 512, 8, true));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
        check_runtime(code, words, 0, resources[i]);

    fragcoord_kill(kill_tokens);
    words = 0;
    assert(!opengpu_fragment_lower_kind(kill_tokens, code, 256, &words));
    assert(words == OPENGPU_FRAGMENT_KILL_WORDS);
    assert(!memcmp(code, opengpu_fragment_kill, words * sizeof(uint32_t)));
    assert(contains(code, words, OPENGPU_FRAGMENT_KILL_VALIDITY));
    assert(opengpu_shader_validate_words(code, words, 512, 8, true));
    for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
        check_runtime(code, words, 0, resources[i]);

    {
        struct tgsi_token gtk_tokens[MAX_TOKENS];
        struct tgsi_token tex_tokens[MAX_TOKENS];
        struct opengpu_fs_alu gtk, tex;
        uint32_t wide[256];

        gtk_coverage(gtk_tokens);
        assert(!opengpu_fs_emit(gtk_tokens, &gtk));
        assert(gtk.words && gtk.words <= 256);
        assert(gtk.nconst == 3);
        assert(gtk.nimm == 0);
        assert(gtk.patch_n == 4);
        assert(gtk.patch_src[0] == 8);
        assert(contains(gtk.code, gtk.words, OPENGPU_FRAGMENT_COORD_YCVT));
        assert(has_vfrsqrt7(gtk.code, gtk.words));
        assert(has_branch(gtk.code, gtk.words));
        assert(!has_vfsqrt(gtk.code, gtk.words));
        opengpu_fs_patch_immediate(gtk.code, gtk.patch_at[0], -1.f);
        opengpu_fs_patch_immediate(gtk.code, gtk.patch_at[1], 480.f);
        assert(opengpu_shader_validate_words(gtk.code, gtk.words, 512, 8, true));
        for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
            check_runtime(gtk.code, gtk.words, 0, resources[i]);
        words = 0;
        assert(!opengpu_fragment_lower_kind(gtk_tokens, wide, 256, &words));
        assert(words == gtk.words);

        sample_miss(tex_tokens);
        assert(!opengpu_fs_emit(tex_tokens, &tex));
        assert(tex.samples == 1);
        assert(tex.sample_sem == TGSI_SEMANTIC_TEXCOORD);
        assert(tex.nimm == 0);
        assert(contains(tex.code, tex.words, 0x08028313u));
        assert(contains(tex.code, tex.words, 0x0a028313u));
        assert(has_vtex(tex.code, tex.words));
        for (i = 0; i < sizeof(resources) / sizeof(resources[0]); i++)
            check_runtime_texture(tex.code, tex.words, resources[i]);
        words = 0;
        assert(!opengpu_fragment_lower_kind(tex_tokens, wide, 256, &words));
        assert(words == tex.words);
        assert(has_vtex(wide, words));
    }

    position_copy(copy_vs);
    assert(opengpu_vertex_lower_kind(copy_vs, code, 256, &words) == 1);
    {
        struct tgsi_token const_vs[MAX_TOKENS];
        struct tgsi_token attr_vs[MAX_TOKENS];
        /* Column-major identity, then CONST[0][4] = (0.5, -2, 0, 1). */
        float uniform[20] = {
            1.f, 0.f, 0.f, 0.f,
            0.f, 1.f, 0.f, 0.f,
            0.f, 0.f, 1.f, 0.f,
            0.f, 0.f, 0.f, 1.f,
            0.5f, -2.f, 0.f, 1.f
        };
        float staged[4];
        int row, col;
        int32_t clip[4];

        constant_position(const_vs);
        assert(opengpu_vertex_lower_kind(const_vs, code, 256, &words) == 2);
        assert(opengpu_stage_const_position(uniform, 19, staged));
        assert(!opengpu_stage_const_position(uniform, 20, staged));
        assert(staged[0] == 0.5f && staged[1] == -2.f && staged[2] == 0.f &&
               staged[3] == 1.f);
        /* vertex_mvp converts each component*coefficient*65536, then adds
         * the four integer terms. These products are exact. */
        for (row = 0; row < 4; row++) {
            int32_t sum = 0;

            for (col = 0; col < 4; col++) {
                double term = (double)staged[col] * (double)uniform[col * 4 + row] *
                              65536.0;

                sum += (int32_t)term;
            }
            clip[row] = sum;
        }
        assert(clip[0] == 32768 && clip[1] == -131072 && clip[2] == 0 &&
               clip[3] == 65536);
        attribute_position(attr_vs);
        assert(opengpu_vertex_lower_kind(attr_vs, code, 256, &words) == 1);

        uniform_color(const_vs);
        assert(opengpu_fragment_lower_kind(const_vs, code, 256, &words) == 1);
        gtk_swizzle(const_vs);
        assert(opengpu_fragment_lower_kind(const_vs, code, 256, &words) == 1);
        position_zw_imm(attr_vs);
        assert(opengpu_vertex_lower_kind(attr_vs, code, 256, &words) == 1);
    }
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
