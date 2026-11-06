/* SPDX-License-Identifier: MIT */
/* Bounded uint32 TGSI -> checked resources and canonical forward SIMT scopes. */
#include "opengpu_cs_emit.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_parse.h"
#include "opengpu_buffer_views.h"
#include <stdlib.h>
#include <string.h>

#define CS_TEMPS 32u
#define CS_IMMS 16u
#define CS_SYSTEMS 8u
#define CS_TOKENS 4096u

struct build {
    struct opengpu_cs_program p;
    unsigned declared[CS_TEMPS], reg[CS_TEMPS][4], next_reg;
    unsigned sys_mask[CS_SYSTEMS], sys_name[CS_SYSTEMS], buffers;
    unsigned const_mask[13], const_first[13], const_last[13];
    unsigned address_declared, address_defined;
    uint32_t samplers, sampler_views;
    unsigned images, image_writable;
    unsigned cf, planned[CS_TEMPS][4];
    uint32_t imm[CS_IMMS][4];
    unsigned nimm;
    int failed;
};

static void word(struct build *b, uint32_t insn)
{
    if (b->p.words == 512) { b->failed = 1; return; }
    b->p.code[b->p.words++] = insn;
}

static uint32_t vec(unsigned fn, unsigned f3, unsigned rd, unsigned a, unsigned z)
{
    return fn << 26 | 1u << 25 | z << 20 | a << 15 | f3 << 12 | rd << 7 | 0x57u;
}

static void copy(struct build *b, unsigned to, unsigned from)
{
    word(b, vec(0, 4, to, 0, from)); /* vadd.vx to,from,x0 */
}

static void scalar(struct build *b, uint32_t value)
{
    uint32_t high = (value + 0x800u) & 0xfffff000u;
    uint32_t low = value & 0xfffu;
    if (high) word(b, high | 10u << 7 | 0x37u);
    if (low || !high)
        word(b, low << 20 | (high ? 10u : 0u) << 15 | 10u << 7 | 0x13u);
}

static void splat(struct build *b, unsigned reg, uint32_t value)
{
    scalar(b, value);
    word(b, vec(0x17, 4, reg, 10, 0));
}

/* Validate packet extents before the real Mesa parser touches optional fields.
 * Only canonical CONST + ADDR0.x indirection and static CB dimensions reach
 * its extension-token readers. Other shapes fail before parsing extensions. */
static int packets(const struct tgsi_token *t)
{
    struct tgsi_header h;
    struct tgsi_processor processor;
    unsigned pos, end;
    memcpy(&h, t, 4);
    if (h.HeaderSize != 2 || !h.BodySize || h.BodySize > CS_TOKENS) return -1;
    memcpy(&processor, t + 1, 4);
    if (processor.Processor != PIPE_SHADER_COMPUTE || processor.Padding) return -1;
    end = 2 + h.BodySize;
    for (pos = 2; pos < end;) {
        struct tgsi_token token;
        unsigned n, q, i;
        memcpy(&token, t + pos, 4);
        n = token.NrTokens;
        if (token.Type == TGSI_TOKEN_TYPE_IMMEDIATE) {
            struct tgsi_immediate imm;
            memcpy(&imm, t + pos, 4);
            n = imm.NrTokens;
            if (n != 5 || imm.Padding ||
                (imm.DataType != TGSI_IMM_UINT32 && imm.DataType != TGSI_IMM_INT32)) return -1;
        } else if (token.Type == TGSI_TOKEN_TYPE_DECLARATION) {
            struct tgsi_declaration d;
            memcpy(&d, t + pos, 4);
            if (d.Interpolate || d.Array || d.Atomic || d.MemType || d.Padding) return -1;
            if (d.File == TGSI_FILE_CONSTANT) {
                struct tgsi_declaration_dimension dim;
                if (!d.Dimension || d.Semantic || n != 3 || n > end - pos) return -1;
                memcpy(&dim, t + pos + 2, 4);
                if (dim.Padding || dim.Index2D > 12) return -1;
            } else if (d.File == TGSI_FILE_SAMPLER_VIEW || d.File == TGSI_FILE_IMAGE) {
                if (d.Dimension || d.Semantic || n != 3 || n > end - pos) return -1;
            } else if ((d.File != TGSI_FILE_TEMPORARY && d.File != TGSI_FILE_BUFFER &&
                        d.File != TGSI_FILE_SAMPLER &&
                        d.File != TGSI_FILE_SYSTEM_VALUE && d.File != TGSI_FILE_ADDRESS) || d.Dimension ||
                       n != 2u + d.Semantic) return -1;
        } else if (token.Type == TGSI_TOKEN_TYPE_PROPERTY) {
            struct tgsi_property p;
            memcpy(&p, t + pos, 4);
            if (p.NrTokens != 2 || p.Padding) return -1;
        } else if (token.Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            struct tgsi_instruction insn;
            memcpy(&insn, t + pos, 4);
            /* TGSI instruction NrTokens counts its following tokens, unlike
             * declaration/immediate/property lengths, which include the head. */
            n = 1u + insn.NrTokens;
            if ((insn.Label && insn.Opcode != TGSI_OPCODE_UIF && insn.Opcode != TGSI_OPCODE_ELSE) ||
                insn.Padding || insn.Saturate || (insn.Memory && insn.Texture) ||
                insn.NumDstRegs > 1 ||
                insn.NumSrcRegs > (insn.Opcode == TGSI_OPCODE_UCMP ? 3u : 2u)) return -1;
            if (n > end - pos) return -1;
            q = pos + 1;
            if (insn.Label) {
                struct tgsi_instruction_label label;
                if (q >= pos + n) return -1;
                memcpy(&label, t + q++, 4);
                if (label.Padding) return -1;
            }
            if (insn.Texture) {
                struct tgsi_instruction_texture tex;
                if (q >= pos + n) return -1;
                memcpy(&tex, t + q++, 4);
                if (tex.Texture != TGSI_TEXTURE_BUFFER || tex.NumOffsets || tex.Padding ||
                    (insn.Opcode != TGSI_OPCODE_TXF && insn.Opcode != TGSI_OPCODE_TXF_LZ &&
                     insn.Opcode != TGSI_OPCODE_TXQ)) return -1;
            }
            if (insn.Memory && q >= pos + n) return -1;
            q += insn.Memory;
            for (i = 0; i < insn.NumDstRegs; i++, q++) {
                struct tgsi_dst_register dst;
                if (q >= pos + n) return -1;
                memcpy(&dst, t + q, 4);
                if (dst.Indirect || dst.Dimension || dst.Padding) return -1;
            }
            for (i = 0; i < insn.NumSrcRegs; i++, q++) {
                struct tgsi_src_register src;
                if (q >= pos + n) return -1;
                memcpy(&src, t + q, 4);
                if (src.Absolute || src.Negate) return -1;
                if (src.Indirect) {
                    struct tgsi_ind_register ind;
                    if (src.File != TGSI_FILE_CONSTANT || ++q >= pos + n) return -1;
                    memcpy(&ind, t + q, 4);
                    if (ind.File != TGSI_FILE_ADDRESS || ind.Index || ind.Swizzle || ind.ArrayID) return -1;
                }
                if (src.File == TGSI_FILE_CONSTANT) {
                    struct tgsi_dimension dim;
                    if (!src.Dimension || ++q >= pos + n) return -1;
                    memcpy(&dim, t + q, 4);
                    if (dim.Indirect || dim.Dimension || dim.Padding ||
                        dim.Index < 0 || dim.Index > 12) return -1;
                } else if (src.Dimension) return -1;
            }
            if (q != pos + n) return -1;
        } else return -1;
        if (!n || n > end - pos) return -1;
        pos += n;
    }
    return 0;
}

static int declaration(struct build *b, const struct tgsi_full_declaration *d)
{
    unsigned first = d->Range.First, last = d->Range.Last, i;
    unsigned mask = d->Declaration.UsageMask;
    if (!mask || first > last) return -1;
    switch (d->Declaration.File) {
    case TGSI_FILE_SAMPLER:
        if (last >= 32 || d->Declaration.Semantic) return -1;
        for (i = first; i <= last; i++) {
            if (b->samplers & (UINT32_C(1) << i)) return -1;
            b->samplers |= UINT32_C(1) << i;
        }
        return 0;
    case TGSI_FILE_SAMPLER_VIEW: {
        unsigned type = d->SamplerView.ReturnTypeX, kind;
        if (last >= 32 || d->SamplerView.Resource != TGSI_TEXTURE_BUFFER ||
            type != d->SamplerView.ReturnTypeY || type != d->SamplerView.ReturnTypeZ ||
            type != d->SamplerView.ReturnTypeW) return -1;
        if (type == TGSI_RETURN_TYPE_FLOAT) kind = 0;
        else if (type == TGSI_RETURN_TYPE_UINT) kind = 1;
        else if (type == TGSI_RETURN_TYPE_SINT) kind = 2;
        else return -1;
        for (i = first; i <= last; i++) {
            if (b->sampler_views & (UINT32_C(1) << i)) return -1;
            b->sampler_views |= UINT32_C(1) << i;
            b->p.texture_kind[i] = kind;
        }
        return 0;
    }
    case TGSI_FILE_IMAGE: {
        int format = opengpu_buffer_image_format(d->Image.Format);
        if (last >= 4 || d->Image.Resource != TGSI_TEXTURE_BUFFER ||
            d->Image.Raw || d->Image.Padding || format < 0) return -1;
        for (i = first; i <= last; i++) {
            if (b->images & (1u << i)) return -1;
            b->images |= 1u << i;
            if (d->Image.Writable) b->image_writable |= 1u << i;
            b->p.image_format[i] = (unsigned)format;
        }
        return 0;
    }
    case TGSI_FILE_ADDRESS:
        if (first || last || !(mask & 1) || d->Declaration.Semantic || b->address_declared) return -1;
        b->address_declared = 1;
        return 0;
    case TGSI_FILE_CONSTANT:
        i = d->Dim.Index2D;
        if (i > 12 || last >= (i ? 1024u : 256u) || b->const_mask[i]) return -1;
        b->const_mask[i] = mask;
        b->const_first[i] = first;
        b->const_last[i] = last;
        return 0;
    case TGSI_FILE_TEMPORARY:
        if (last >= CS_TEMPS || d->Declaration.Semantic) return -1;
        for (i = first; i <= last; i++) {
            if (b->declared[i]) return -1;
            b->declared[i] = mask;
        }
        return 0;
    case TGSI_FILE_BUFFER:
        if (last >= 4 || d->Declaration.Semantic) return -1;
        for (i = first; i <= last; i++) {
            if (b->buffers & (1u << i)) return -1;
            b->buffers |= 1u << i;
        }
        return 0;
    case TGSI_FILE_SYSTEM_VALUE:
        if (!d->Declaration.Semantic || first != last || last >= CS_SYSTEMS ||
            d->Semantic.Index || d->Semantic.StreamX || d->Semantic.StreamY ||
            d->Semantic.StreamZ || d->Semantic.StreamW || b->sys_mask[first]) return -1;
        if (d->Semantic.Name != TGSI_SEMANTIC_THREAD_ID &&
            d->Semantic.Name != TGSI_SEMANTIC_BLOCK_ID &&
            d->Semantic.Name != TGSI_SEMANTIC_BLOCK_SIZE &&
            d->Semantic.Name != TGSI_SEMANTIC_GRID_SIZE) return -1;
        b->sys_mask[first] = mask;
        b->sys_name[first] = d->Semantic.Name;
        return 0;
    default: return -1;
    }
}

static unsigned swizzle(const struct tgsi_src_register *s, unsigned c)
{
    unsigned v[] = {s->SwizzleX, s->SwizzleY, s->SwizzleZ, s->SwizzleW};
    return v[c];
}

static int source(struct build *b, const struct tgsi_full_src_register *full,
                  unsigned component, unsigned to)
{
    const struct tgsi_src_register *s = &full->Register;
    unsigned c = swizzle(s, component), i = (unsigned)s->Index;
    if (s->Index < 0) return -1;
    switch (s->File) {
    case TGSI_FILE_CONSTANT: {
        unsigned slot = (unsigned)full->Dimension.Index, bytes = i * 16 + c * 4 + 4;
        if (slot > 12 || !(b->const_mask[slot] & (1u << c)) ||
            i < b->const_first[slot] || i > b->const_last[slot]) return -1;
        if (s->Indirect) {
            if (!b->address_defined) return -1;
            /* v30 retains the complete UARL value across other sources and
             * TEMP rewrites. Check it before <<4 or base addition; invalid
             * lanes use an aligned offset beyond every supported CB view. */
            scalar(b, 4);
            word(b, vec(0x25, 4, to, 10, 30));
            scalar(b, bytes - 4);
            word(b, vec(0, 4, to, 10, to));
            splat(b, 31, 0x4000u);
            scalar(b, b->const_last[slot] - i);
            word(b, vec(0x1c, 4, 0, 10, 30));
            word(b, vec(0x17, 0, 31, to, 31) & ~(1u << 25));
            bytes = b->const_last[slot] * 16 + c * 4 + 4;
        } else splat(b, 31, bytes - 4);
        /* Reuse the load destination for its selector after the byte offset
         * is ready; persistent ADDR0(v30) and all other operands stay intact. */
        splat(b, to, slot ? slot - 1 : 31);
        word(b, 7u << 26 | 1u << 25 | 31u << 20 | to << 15 | to << 7 | 0x2bu);
        if (b->p.constant_bytes[slot] < bytes) b->p.constant_bytes[slot] = bytes;
        return 0;
    }
    case TGSI_FILE_TEMPORARY:
        if (i >= CS_TEMPS || !b->reg[i][c]) return -1;
        copy(b, to, b->reg[i][c]);
        return 0;
    case TGSI_FILE_IMMEDIATE:
        if (i >= b->nimm) return -1;
        splat(b, to, b->imm[i][c]);
        return 0;
    case TGSI_FILE_SYSTEM_VALUE:
        if (i >= CS_SYSTEMS || c >= 3 || !(b->sys_mask[i] & (1u << c))) return -1;
        switch (b->sys_name[i]) {
        case TGSI_SEMANTIC_BLOCK_ID:
            word(b, vec(0x17, 4, to, 2 + c, 0)); break;
        case TGSI_SEMANTIC_BLOCK_SIZE:
            word(b, vec(0x17, 4, to, 5 + c, 0)); break;
        case TGSI_SEMANTIC_THREAD_ID:
            if (!c) word(b, vec(0x22, 6, to, 5, 1));
            else {
                word(b, vec(0x20, 6, to, 5, 1));
                word(b, vec(c == 1 ? 0x22 : 0x20, 6, to, 6, to));
            }
            break;
        case TGSI_SEMANTIC_GRID_SIZE:
            word(b, (4u * c) << 20 | 1u << 15 | 2u << 12 | 10u << 7 | 0x03u);
            word(b, vec(0x17, 4, to, 10, 0)); break;
        default: return -1;
        }
        return 0;
    default: return -1;
    }
}

static int destination(struct build *b, const struct tgsi_dst_register *d)
{
    unsigned c, i = (unsigned)d->Index;
    if (d->Index < 0 || i >= CS_TEMPS || d->File != TGSI_FILE_TEMPORARY ||
        !d->WriteMask || (d->WriteMask & ~b->declared[i])) return -1;
    /* Results are staged in v26..29 before any temp is overwritten, preserving
     * parallel TGSI writemasks/swizzles even when destination aliases a source. */
    for (c = 0; c < 4; c++) if (d->WriteMask & (1u << c)) {
        if (!b->reg[i][c]) {
            if (b->cf) {
                if (!b->planned[i][c]) return -1;
                b->reg[i][c] = b->planned[i][c];
            } else {
                if (b->next_reg > 21) return -1;
                b->reg[i][c] = b->next_reg++;
            }
        }
        copy(b, b->reg[i][c], 26 + c);
    }
    return 0;
}

static void storage(struct build *b, unsigned fn, unsigned rd, unsigned a, unsigned z)
{
    word(b, fn << 26 | 1u << 25 | z << 20 | a << 15 | rd << 7 | 0x2bu);
}

static void resource_word(struct build *b, unsigned fn, unsigned f3,
                          unsigned rd, unsigned a, unsigned z)
{
    word(b, fn << 26 | 1u << 25 | z << 20 | a << 15 | f3 << 12 | rd << 7 | 0x2bu);
}

/* Only statically declared buffer resources. The existing SIMT arm validator
 * does not admit these owners; no resource operation may hide in a CF program. */
static int texture(struct build *b, const struct tgsi_full_instruction *in)
{
    const struct tgsi_instruction *i = &in->Instruction;
    const struct tgsi_dst_register *d = &in->Dst[0].Register;
    const struct tgsi_src_register *unit = &in->Src[1].Register;
    unsigned op = i->Opcode, index = (unsigned)unit->Index;
    if (b->cf || !i->Texture || i->Memory || i->NumDstRegs != 1 || i->NumSrcRegs != 2 ||
        unit->File != TGSI_FILE_SAMPLER || index >= 32 ||
        !(b->samplers & b->sampler_views & (UINT32_C(1) << index))) return -1;
    /* TGSI size has an integer return type independently of the sampled type;
     * the declaration still retains the sampler's return-class contract. */
    if (op != TGSI_OPCODE_TXQ && in->Texture.ReturnType !=
        (b->p.texture_kind[index] == 1 ? TGSI_RETURN_TYPE_UINT :
         b->p.texture_kind[index] == 2 ? TGSI_RETURN_TYPE_SINT : TGSI_RETURN_TYPE_FLOAT)) return -1;
    if (op == TGSI_OPCODE_TXQ) {
        /* Buffer textures have no LOD. Require the actual query's scalar zero;
         * fetch's unused coordinate channels are not interpreted as a LOD. */
        const struct tgsi_src_register *s = &in->Src[0].Register;
        if (s->File != TGSI_FILE_IMMEDIATE || s->Index < 0 || (unsigned)s->Index >= b->nimm ||
            b->imm[s->Index][swizzle(s, 0)]) return -1;
        splat(b, 24, index); splat(b, 23, 0);
        /* Even a levels-only query validates the selected descriptor. */
        resource_word(b, 6, 0, 26, 24, 23);
        if (d->WriteMask & 2) splat(b, 27, 0);
        if (d->WriteMask & 4) splat(b, 28, 0);
        if (d->WriteMask & 8) splat(b, 29, 1);
    } else {
        if (source(b, &in->Src[0], 0, 22)) return -1;
        splat(b, 23, 0);
        resource_word(b, 3, 0, 0, 22, 23);
        for (unsigned pair = 1; pair < 8; pair++) resource_word(b, 3, pair, 0, 23, 23);
        splat(b, 24, index);
        for (unsigned c = 0; c < 4; c++) if (d->WriteMask & (1u << c))
            resource_word(b, 5, c, 26 + c, 24, 23);
    }
    b->p.textures |= UINT32_C(1) << index;
    return destination(b, d);
}

static int image(struct build *b, const struct tgsi_full_instruction *in)
{
    const struct tgsi_instruction *i = &in->Instruction;
    const struct tgsi_dst_register *d = &in->Dst[0].Register;
    unsigned op = i->Opcode, store = op == TGSI_OPCODE_STORE, query = op == TGSI_OPCODE_RESQ;
    unsigned index = (unsigned)(store ? d->Index : in->Src[0].Register.Index);
    if (b->cf || !i->Memory || i->Texture || i->NumDstRegs != 1 ||
        i->NumSrcRegs != (query ? 1u : 2u) || index >= 4 || !(b->images & (1u << index)) ||
        in->Memory.Qualifier || in->Memory.Padding || in->Memory.Texture != TGSI_TEXTURE_BUFFER ||
        opengpu_buffer_image_format(in->Memory.Format) != (int)b->p.image_format[index] ||
        (store && (!(b->image_writable & (1u << index)) || d->WriteMask != 15))) return -1;
    unsigned format = b->p.image_format[index];
    unsigned controls = 5u | opengpu_buffer_native_kind(format) << 3 | 1u << 5 | format << 6;
    if (query) {
        if (d->WriteMask != 1) return -1; /* imageBuffer size is scalar */
        splat(b, 24, index); splat(b, 23, controls);
        resource_word(b, 27, 0, 26, 24, 23);
        b->p.image_queries |= 1u << index;
        return destination(b, d);
    }
    /* Capture the coordinate before loading other sources; source() may use
     * scratch v22..25/v31 but cannot overwrite this reserved staged v26. */
    if (source(b, &in->Src[store ? 0 : 1], 0, 26)) return -1;
    splat(b, 24, index); splat(b, 23, controls);
    resource_word(b, 8, 0, 0, 24, 23);
    splat(b, 24, 0); resource_word(b, 8, 1, 0, 24, 24);
    if (store) {
        /* Capture each pair immediately. No output-register copy can clobber
         * an aliased TGSI source before all source components are consumed. */
        for (unsigned pair = 0; pair < 2; pair++) {
            if (source(b, &in->Src[1], pair * 2, 22) ||
                source(b, &in->Src[1], pair * 2 + 1, 23)) return -1;
            resource_word(b, 8, pair + 2, 0, 22, 23);
        }
        splat(b, 24, 0); resource_word(b, 10, 0, 0, 26, 24);
        b->p.image_writes |= 1u << index;
        word(b, 0x0330000fu);
        return 0;
    }
    resource_word(b, 9, 0, 26, 26, 24);
    for (unsigned c = 1; c < 4; c++) if (d->WriteMask & (1u << c))
        resource_word(b, 11, c, 26 + c, 0, 0);
    b->p.image_reads |= 1u << index;
    return destination(b, d);
}

static int instruction(struct build *b, const struct tgsi_full_instruction *in)
{
    const struct tgsi_instruction *i = &in->Instruction;
    const struct tgsi_dst_register *d = &in->Dst[0].Register;
    unsigned c, op = i->Opcode, fn = 0, f3 = 0, binding;
    int mem = op == TGSI_OPCODE_LOAD || op == TGSI_OPCODE_STORE;
    if (op == TGSI_OPCODE_END)
        return !i->NumSrcRegs && !i->NumDstRegs && !i->Memory ? 1 : -1;
    if (i->NumDstRegs != 1 || !d->WriteMask) return -1;
    if (op == TGSI_OPCODE_TXF || op == TGSI_OPCODE_TXF_LZ || op == TGSI_OPCODE_TXQ)
        return texture(b, in);
    if ((mem || op == TGSI_OPCODE_RESQ) &&
        (op == TGSI_OPCODE_STORE ? d->File : in->Src[0].Register.File) == TGSI_FILE_IMAGE)
        return image(b, in);
    if (i->Texture) return -1;
    if (mem) {
        const struct tgsi_full_src_register *a = &in->Src[op == TGSI_OPCODE_LOAD ? 1 : 0];
        if (i->NumSrcRegs != 2 || (i->Memory &&
            (in->Memory.Qualifier || in->Memory.Format || in->Memory.Padding ||
             in->Memory.Texture != TGSI_TEXTURE_BUFFER))) return -1;
        binding = (unsigned)(op == TGSI_OPCODE_LOAD ? in->Src[0].Register.Index : d->Index);
        if ((op == TGSI_OPCODE_LOAD ? in->Src[0].Register.File : d->File) != TGSI_FILE_BUFFER ||
            binding >= 4 || !(b->buffers & (1u << binding))) return -1;
        if (source(b, a, 0, 25)) return -1;
        splat(b, 24, binding);
        for (c = 0; c < 4; c++) if (d->WriteMask & (1u << c)) {
            scalar(b, 4 * c);
            word(b, vec(0, 4, 23, 10, 25));
            if (op == TGSI_OPCODE_LOAD) storage(b, 15, 26 + c, 24, 23);
            else {
                if (source(b, &in->Src[1], c, 22)) return -1;
                storage(b, 14, 0, 22, 0);
                storage(b, 16, 0, 24, 23);
            }
        }
        if (op == TGSI_OPCODE_LOAD) {
            b->p.reads |= 1u << binding;
            return destination(b, d);
        }
        b->p.writes |= 1u << binding;
        word(b, 0x0330000fu); /* complete preceding stores before later reads */
        return 0;
    }
    if (i->Memory) return -1;
    if (op == TGSI_OPCODE_UARL) {
        if (!b->address_declared || i->NumSrcRegs != 1 ||
            d->File != TGSI_FILE_ADDRESS || d->Index || d->WriteMask != 1 ||
            source(b, &in->Src[0], 0, 22)) return -1;
        copy(b, 30, 22);
        b->address_defined = 1;
        return 0;
    }
    switch (op) {
    case TGSI_OPCODE_MOV: break;
    case TGSI_OPCODE_UADD: fn = 0; break;
    case TGSI_OPCODE_UMUL: fn = 0x25; f3 = 2; break;
    case TGSI_OPCODE_AND: fn = 9; break;
    case TGSI_OPCODE_OR: fn = 10; break;
    case TGSI_OPCODE_XOR: fn = 11; break;
    case TGSI_OPCODE_SHL: fn = 0x25; break;
    case TGSI_OPCODE_USHR: fn = 0x28; break;
    case TGSI_OPCODE_USEQ: fn = 0x18; break;
    case TGSI_OPCODE_USNE: fn = 0x19; break;
    case TGSI_OPCODE_USLT: fn = 0x1a; break;
    case TGSI_OPCODE_USGE: fn = 0x1c; break;
    case TGSI_OPCODE_UCMP: break;
    default: return -1;
    }
    if (i->NumSrcRegs != (op == TGSI_OPCODE_MOV ? 1u : op == TGSI_OPCODE_UCMP ? 3u : 2u)) return -1;
    for (c = 0; c < 4; c++) if (d->WriteMask & (1u << c)) {
        if (source(b, &in->Src[0], c, 22)) return -1;
        if (op == TGSI_OPCODE_MOV) copy(b, 26 + c, 22);
        else if (op == TGSI_OPCODE_UCMP) {
            if (source(b, &in->Src[1], c, 23) ||
                source(b, &in->Src[2], c, 24)) return -1;
            /* v0 is a packed lane mask. Any nonzero bit pattern is true.
             * Define old vd before masked merge, as the sandbox requires. */
            copy(b, 26 + c, 24);
            word(b, vec(0x19, 4, 0, 0, 22)); /* vmsne.vx v0,v22,x0 */
            word(b, vec(0x17, 0, 26 + c, 23, 24) & ~(1u << 25));
        }
        else {
            if (source(b, &in->Src[1], c, 23)) return -1;
            if (op == TGSI_OPCODE_USEQ || op == TGSI_OPCODE_USNE ||
                op == TGSI_OPCODE_USLT || op == TGSI_OPCODE_USGE) {
                /* USGE(a,b) is b<=a. Materialize TGSI's per-component
                 * all-ones/zero result from the native packed predicate. */
                word(b, vec(fn, 0, 0, op == TGSI_OPCODE_USGE ? 22 : 23,
                    op == TGSI_OPCODE_USGE ? 23 : 22));
                splat(b, 26 + c, 0);
                word(b, vec(0x17, 3, 26 + c, 31, 26 + c) & ~(1u << 25));
            } else word(b, vec(fn, f3, 26 + c, 23, 22));
        }
    }
    return destination(b, d);
}

#define CS_IF_DEPTH 4u /* two physical stack entries/scope, native depth eight */
struct cf_instruction {
    struct tgsi_full_instruction in;
    unsigned alternative, end;
};

static uint32_t branch_word(unsigned f, unsigned a, unsigned z, unsigned bytes)
{
    return ((bytes >> 12) & 1u) << 31 | ((bytes >> 5) & 63u) << 25 |
        z << 20 | a << 15 | f << 12 | ((bytes >> 1) & 15u) << 8 |
        ((bytes >> 11) & 1u) << 7 | 0x5bu;
}

static int span(struct build *b, const struct cf_instruction *ir, unsigned first, unsigned last)
{
    for (unsigned i = first; i < last; i++) {
        const struct cf_instruction *node = &ir[i];
        if (node->in.Instruction.Opcode != TGSI_OPCODE_UIF) {
            if (instruction(b, &node->in)) return -1;
            continue;
        }
        unsigned before[CS_TEMPS][4], yes[CS_TEMPS][4];
        unsigned address_before = b->address_defined, address_yes;
        if (source(b, &node->in.Src[0], 0, 22)) return -1;
        splat(b, 23, 0);
        unsigned sync = b->p.words; word(b, 0); word(b, 0);
        memcpy(before, b->reg, sizeof(before));
        unsigned yes_end = node->alternative < node->end ? node->alternative : node->end;
        if (span(b, ir, i + 1, yes_end)) return -1;
        word(b, 0x205bu);
        unsigned taken = b->p.words;
        memcpy(yes, b->reg, sizeof(yes)); address_yes = b->address_defined;
        memcpy(b->reg, before, sizeof(before)); b->address_defined = address_before;
        if (node->alternative < node->end && span(b, ir, node->alternative + 1, node->end)) return -1;
        word(b, 0x205bu);
        unsigned marker = b->p.words; word(b, 0x205bu);
        if (b->failed || sync >= 512 || sync + 1 >= 512) return -1;
        b->p.code[sync] = branch_word(3, 0, 0, (marker - sync) * 4);
        /* Zero condition takes the textual else; neither VL nor v0 qualifies
         * SIMT comparison. Hardware retains each path's warp active mask. */
        b->p.code[sync + 1] = branch_word(0, 22, 23, (taken - sync - 1) * 4);
        for (unsigned t = 0; t < CS_TEMPS; t++) for (unsigned c = 0; c < 4; c++)
            if (!yes[t][c]) b->reg[t][c] = 0;
        b->address_defined &= address_yes;
        i = node->end;
    }
    return b->failed ? -1 : 0;
}

static int control_program(const void *tokens, struct opengpu_cs_program *out)
{
    struct build b;
    struct tgsi_parse_context p;
    struct cf_instruction *ir = calloc(512, sizeof(*ir));
    unsigned count = 0, depth = 0, stack[CS_IF_DEPTH], ended = 0;
    int result = -1;
    if (!ir) return -1;
    memset(&b, 0, sizeof(b)); b.cf = 1; b.next_reg = 2;
    if (tgsi_parse_init(&p, tokens) != TGSI_PARSE_OK) goto done;
    while (!tgsi_parse_end_of_tokens(&p)) {
        tgsi_parse_token(&p);
        if (ended) goto parsed;
        switch (p.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_DECLARATION:
            if (count || declaration(&b, &p.FullToken.FullDeclaration)) goto parsed;
            break;
        case TGSI_TOKEN_TYPE_IMMEDIATE:
            if (count || b.nimm == CS_IMMS) goto parsed;
            for (unsigned c = 0; c < 4; c++) b.imm[b.nimm][c] = p.FullToken.FullImmediate.u[c].Uint;
            b.nimm++;
            break;
        case TGSI_TOKEN_TYPE_PROPERTY: {
            const struct tgsi_full_property *v = &p.FullToken.FullProperty;
            unsigned n = v->Property.PropertyName;
            if (count || n < TGSI_PROPERTY_CS_FIXED_BLOCK_WIDTH || n > TGSI_PROPERTY_CS_FIXED_BLOCK_DEPTH ||
                !v->u[0].Data || b.p.fixed_block[n - TGSI_PROPERTY_CS_FIXED_BLOCK_WIDTH]) goto parsed;
            b.p.fixed_block[n - TGSI_PROPERTY_CS_FIXED_BLOCK_WIDTH] = v->u[0].Data;
            break;
        }
        case TGSI_TOKEN_TYPE_INSTRUCTION: {
            if (count == 512) goto parsed;
            struct cf_instruction *node = &ir[count];
            node->in = p.FullToken.FullInstruction; node->alternative = node->end = 512;
            const struct tgsi_instruction *in = &node->in.Instruction;
            unsigned op = in->Opcode;
            if (op == TGSI_OPCODE_UIF || op == TGSI_OPCODE_ELSE || op == TGSI_OPCODE_ENDIF) {
                if (in->Memory || in->NumDstRegs || in->NumSrcRegs != (op == TGSI_OPCODE_UIF ? 1u : 0u)) goto parsed;
                if (op == TGSI_OPCODE_UIF) {
                    if (depth == CS_IF_DEPTH) goto parsed;
                    stack[depth++] = count;
                } else {
                    if (!depth) goto parsed;
                    struct cf_instruction *owner = &ir[stack[depth - 1]];
                    if (op == TGSI_OPCODE_ELSE) {
                        if (owner->alternative != 512) goto parsed;
                        owner->alternative = count;
                    } else {
                        owner->end = count; depth--;
                        unsigned target = owner->alternative < count ? owner->alternative : count;
                        if (owner->in.Instruction.Label && owner->in.Label.Label != target) goto parsed;
                        if (owner->alternative < count) {
                            struct cf_instruction *alt = &ir[owner->alternative];
                            if (alt->in.Instruction.Label && alt->in.Label.Label != count) goto parsed;
                        }
                    }
                }
            } else if (op == TGSI_OPCODE_END) {
                if (depth || in->NumDstRegs || in->NumSrcRegs || in->Memory) goto parsed;
                ended = 1;
            } else if (in->NumDstRegs && node->in.Dst[0].Register.File == TGSI_FILE_TEMPORARY) {
                const struct tgsi_dst_register *d = &node->in.Dst[0].Register;
                if (d->Index < 0 || (unsigned)d->Index >= CS_TEMPS || !d->WriteMask ||
                    (d->WriteMask & ~b.declared[d->Index])) goto parsed;
                for (unsigned c = 0; c < 4; c++) if ((d->WriteMask & (1u << c)) && !b.planned[d->Index][c]) {
                    if (b.next_reg > 21) goto parsed;
                    b.planned[d->Index][c] = b.next_reg++;
                }
            }
            count++;
            break;
        }
        default: goto parsed;
        }
    }
    if (!ended || !count || depth) goto parsed;
    word(&b, 0x01007557u);
    if (span(&b, ir, 0, count - 1)) goto parsed;
    word(&b, 0x0330000fu); word(&b, 0x30500073u);
    if (!b.failed) { *out = b.p; result = 0; }
parsed:
    tgsi_parse_free(&p);
done:
    free(ir);
    return result;
}

int opengpu_cs_emit(const void *tokens, struct opengpu_cs_program *out)
{
    struct build b;
    struct tgsi_parse_context p;
    int ended = 0;
    if (!tokens || !out || packets(tokens)) return -1;
    if (tgsi_parse_init(&p, tokens) != TGSI_PARSE_OK) return -1;
    while (!tgsi_parse_end_of_tokens(&p)) {
        tgsi_parse_token(&p);
        if (p.FullToken.Token.Type == TGSI_TOKEN_TYPE_INSTRUCTION &&
            (p.FullToken.FullInstruction.Instruction.Opcode == TGSI_OPCODE_UIF ||
             p.FullToken.FullInstruction.Instruction.Opcode == TGSI_OPCODE_ELSE ||
             p.FullToken.FullInstruction.Instruction.Opcode == TGSI_OPCODE_ENDIF)) {
            tgsi_parse_free(&p);
            return control_program(tokens, out);
        }
    }
    tgsi_parse_free(&p);
    memset(&b, 0, sizeof(b));
    b.next_reg = 2;
    if (tgsi_parse_init(&p, tokens) != TGSI_PARSE_OK) return -1;
    word(&b, 0x01007557u); /* VLMAX e32,m1,tu,mu -> scratch x10; preserve x5..7 */
    while (!tgsi_parse_end_of_tokens(&p)) {
        int ret = 0;
        if (ended) { b.failed = 1; break; }
        tgsi_parse_token(&p);
        switch (p.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_DECLARATION:
            ret = declaration(&b, &p.FullToken.FullDeclaration); break;
        case TGSI_TOKEN_TYPE_IMMEDIATE: {
            unsigned c;
            if (b.nimm == CS_IMMS) { ret = -1; break; }
            for (c = 0; c < 4; c++) b.imm[b.nimm][c] = p.FullToken.FullImmediate.u[c].Uint;
            b.nimm++;
            break;
        }
        case TGSI_TOKEN_TYPE_PROPERTY: {
            const struct tgsi_full_property *v = &p.FullToken.FullProperty;
            unsigned n = v->Property.PropertyName;
            if (n < TGSI_PROPERTY_CS_FIXED_BLOCK_WIDTH || n > TGSI_PROPERTY_CS_FIXED_BLOCK_DEPTH ||
                !v->u[0].Data || b.p.fixed_block[n - TGSI_PROPERTY_CS_FIXED_BLOCK_WIDTH]) ret = -1;
            else b.p.fixed_block[n - TGSI_PROPERTY_CS_FIXED_BLOCK_WIDTH] = v->u[0].Data;
            break;
        }
        case TGSI_TOKEN_TYPE_INSTRUCTION:
            ret = instruction(&b, &p.FullToken.FullInstruction);
            if (ret == 1) { ended = 1; ret = 0; }
            break;
        default: ret = -1;
        }
        if (ret < 0) { b.failed = 1; break; }
    }
    tgsi_parse_free(&p);
    word(&b, 0x0330000fu);
    word(&b, 0x30500073u);
    if (!ended || b.failed) return -1;
    *out = b.p;
    return 0;
}
