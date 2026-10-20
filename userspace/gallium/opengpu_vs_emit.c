/* SPDX-License-Identifier: MIT */
/* Lower one vertex program the menu does not classify. The copy schedule
 * is the clang vertex_passthrough image. The two new words are lw of the
 * uniform at byte 512 and vadd.vx of that word onto position x. */
#include "opengpu_vs_emit.h"

#include "pipe/p_defines.h"
#include "tgsi/tgsi_parse.h"

#include <string.h>

/* lw x12, 512(x1). The runtime ABI lowerer rewrites this one word. */
#define VS_LW_X12_512 0x2000a603u
/* vadd.vx v8, v8, x12. The lowerer must leave this word in place. */
#define VS_VADD_VX 0x02864457u

static const uint32_t vs_passthrough[] = {
    0x00241513u, 0x00150533u, 0xc1027057u, 0x02056407u,
    0x10050593u, 0x0205e427u, 0x02050593u, 0x0205e407u,
    0x12050593u, 0x0205e427u, 0x04050593u, 0x0205e407u,
    0x14050593u, 0x0205e427u, 0x06050593u, 0x0205e407u,
    0x16050593u, 0x0205e427u, 0x08050593u, 0x0205e407u,
    0x18050593u, 0x0205e427u, 0x0a050593u, 0x0205e407u,
    0x1a050593u, 0x0205e427u, 0x0c050593u, 0x0205e407u,
    0x1c050593u, 0x0205e427u, 0x0e050593u, 0x0205e407u,
    0x1e050513u, 0x02056427u, 0x30500073u,
};

static int channel_x(const struct tgsi_src_register *src)
{
    return !src->Indirect && !src->Negate && !src->Absolute &&
           src->SwizzleX == TGSI_SWIZZLE_X;
}

static int plain(const struct tgsi_src_register *src)
{
    return !src->Indirect && !src->Negate && !src->Absolute &&
           src->SwizzleX == TGSI_SWIZZLE_X && src->SwizzleY == TGSI_SWIZZLE_Y &&
           src->SwizzleZ == TGSI_SWIZZLE_Z && src->SwizzleW == TGSI_SWIZZLE_W;
}

static int fill_words(struct opengpu_vs_alu *out)
{
    unsigned n = 0;
    unsigned i;

    if (n + 6 + (sizeof(vs_passthrough) / sizeof(vs_passthrough[0]) - 4) >
        sizeof(out->code) / sizeof(out->code[0]))
        return -1;
    out->code[n++] = vs_passthrough[0];
    out->code[n++] = vs_passthrough[1];
    out->code[n++] = VS_LW_X12_512;
    out->code[n++] = vs_passthrough[2];
    out->code[n++] = vs_passthrough[3];
    out->code[n++] = VS_VADD_VX;
    for (i = 4; i < sizeof(vs_passthrough) / sizeof(vs_passthrough[0]); i++)
        out->code[n++] = vs_passthrough[i];
    out->words = n;
    return 0;
}

int opengpu_vs_emit(const void *tokens, struct opengpu_vs_alu *out)
{
    struct tgsi_parse_context parse;
    int saw_add = 0, saw_yzw = 0, saw_pos = 0, saw_color = 0, saw_end = 0;
    int pos_src = -1, color_src = -1, color_sem = -1, color_index = 0;

    if (!tokens || !out)
        return -1;
    memset(out, 0, sizeof(*out));
    out->pos_src = -1;
    out->color_src = -1;
    out->color_sem = -1;
    if (tgsi_parse_init(&parse, tokens) != TGSI_PARSE_OK)
        return -1;
    if (parse.FullHeader.Processor.Processor != PIPE_SHADER_VERTEX)
        goto fail;
    while (!tgsi_parse_end_of_tokens(&parse)) {
        struct tgsi_full_instruction *insn;
        struct tgsi_full_declaration *decl;
        unsigned opcode;

        tgsi_parse_token(&parse);
        switch (parse.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_DECLARATION:
            decl = &parse.FullToken.FullDeclaration;
            if (!decl->Declaration.Semantic)
                break;
            if (decl->Declaration.File == TGSI_FILE_INPUT &&
                decl->Semantic.Name == TGSI_SEMANTIC_POSITION &&
                decl->Range.First == decl->Range.Last)
                pos_src = (int)decl->Range.First;
            if (decl->Declaration.File == TGSI_FILE_INPUT &&
                decl->Semantic.Name == TGSI_SEMANTIC_COLOR &&
                decl->Range.First == decl->Range.Last) {
                color_src = (int)decl->Range.First;
                color_sem = TGSI_SEMANTIC_COLOR;
                color_index = (int)decl->Semantic.Index;
            }
            break;
        case TGSI_TOKEN_TYPE_PROPERTY:
            break;
        case TGSI_TOKEN_TYPE_INSTRUCTION:
            insn = &parse.FullToken.FullInstruction;
            opcode = insn->Instruction.Opcode;
            if (opcode == TGSI_OPCODE_END) {
                saw_end = 1;
                break;
            }
            if (insn->Instruction.Saturate || insn->Instruction.NumDstRegs != 1)
                goto fail;
            if (opcode == TGSI_OPCODE_ADD) {
                const struct tgsi_dst_register *dst = &insn->Dst[0].Register;
                const struct tgsi_src_register *a = &insn->Src[0].Register;
                const struct tgsi_src_register *b = &insn->Src[1].Register;

                if (saw_add || insn->Instruction.NumSrcRegs != 2)
                    goto fail;
                if (dst->File != TGSI_FILE_TEMPORARY || dst->Index != 0 ||
                    dst->WriteMask != TGSI_WRITEMASK_X || dst->Indirect)
                    goto fail;
                if (a->File != TGSI_FILE_INPUT || a->Index != 0 || !channel_x(a))
                    goto fail;
                if (b->File != TGSI_FILE_CONSTANT || b->Index != 0 || !channel_x(b))
                    goto fail;
                saw_add = 1;
                break;
            }
            if (opcode != TGSI_OPCODE_MOV || insn->Instruction.NumSrcRegs != 1)
                goto fail;
            {
                const struct tgsi_dst_register *dst = &insn->Dst[0].Register;
                const struct tgsi_src_register *src = &insn->Src[0].Register;

                if (dst->Indirect)
                    goto fail;
                if (dst->File == TGSI_FILE_TEMPORARY && dst->Index == 0 &&
                    dst->WriteMask == TGSI_WRITEMASK_YZW &&
                    src->File == TGSI_FILE_INPUT && src->Index == 0 && plain(src)) {
                    if (saw_yzw)
                        goto fail;
                    saw_yzw = 1;
                    break;
                }
                if (dst->File == TGSI_FILE_OUTPUT && dst->Index == 0 &&
                    dst->WriteMask == TGSI_WRITEMASK_XYZW &&
                    src->File == TGSI_FILE_TEMPORARY && src->Index == 0 &&
                    plain(src)) {
                    if (saw_pos)
                        goto fail;
                    saw_pos = 1;
                    break;
                }
                if (dst->File == TGSI_FILE_OUTPUT && dst->Index == 1 &&
                    dst->WriteMask == TGSI_WRITEMASK_XYZW &&
                    src->File == TGSI_FILE_INPUT && src->Index == 1 && plain(src)) {
                    if (saw_color)
                        goto fail;
                    saw_color = 1;
                    break;
                }
            }
            goto fail;
        default:
            goto fail;
        }
    }
    tgsi_parse_free(&parse);
    if (!saw_end || !saw_add || !saw_yzw || !saw_pos || !saw_color ||
        pos_src != 0 || color_src != 1 || color_sem != TGSI_SEMANTIC_COLOR)
        return -1;
    out->pos_src = pos_src;
    out->color_src = color_src;
    out->color_sem = color_sem;
    out->color_sem_index = color_index;
    return fill_words(out);
fail:
    tgsi_parse_free(&parse);
    return -1;
}
