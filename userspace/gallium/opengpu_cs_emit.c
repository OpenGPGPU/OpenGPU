/* SPDX-License-Identifier: MIT */
/* Bounded uint32 TGSI -> checked resources and canonical forward SIMT scopes. */
#include "opengpu_cs_emit.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_parse.h"
#include "opengpu_buffer_views.h"
#include <stdlib.h>
#include <string.h>

#define CS_TEMPS 32u
#define CS_IMMS 32u
#define CS_LEGACY_IMMS 16u
#define CS_SYSTEMS 8u
#define CS_TOKENS 4096u

enum shared_expr_kind { EXPR_UNKNOWN, EXPR_CONSTANT, EXPR_LOCAL, EXPR_PEER };
struct shared_expr { unsigned kind, scale; uint32_t offset; };

struct build {
    struct opengpu_cs_program p;
    unsigned declared[CS_TEMPS], reg[CS_TEMPS][4], next_reg;
    unsigned sys_mask[CS_SYSTEMS], sys_name[CS_SYSTEMS], buffers;
    unsigned const_mask[13], const_first[13], const_last[13];
    unsigned address_declared, address_defined, counter_address;
    unsigned counters;
    uint32_t samplers, sampler_views;
    const unsigned *texture_observed;
    unsigned texture_position;
    unsigned images, image_writable;
    unsigned cf, planned[CS_TEMPS][4];
    uint32_t imm[CS_IMMS][4];
    unsigned nimm;
    const struct opengpu_cs_shared *shared;
    struct shared_expr expr[CS_TEMPS][4];
    unsigned shared_declared, shared_used, shared_written, shared_visible;
    unsigned shared_address_valid[2], shared_address_offset[2];
    int failed;
};

/* Existing checked storage word RMW operation IDs, not global pointer AMOs. */
static int storage_atomic_operation(unsigned op)
{
    switch(op) {
    case TGSI_OPCODE_ATOMXCHG: return 0;
    case TGSI_OPCODE_ATOMUADD: return 1;
    case TGSI_OPCODE_ATOMXOR: return 2;
    case TGSI_OPCODE_ATOMOR: return 3;
    case TGSI_OPCODE_ATOMAND: return 4;
    case TGSI_OPCODE_ATOMIMIN: return 5;
    case TGSI_OPCODE_ATOMIMAX: return 6;
    case TGSI_OPCODE_ATOMUMIN: return 7;
    case TGSI_OPCODE_ATOMUMAX: return 8;
    case TGSI_OPCODE_ATOMCAS: return 9;
    default: return -1;
    }
}

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
 * Only canonical CONST + ADDR0.x and HW_ATOMIC + ADDR2.x indirection reach
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
            if (d.Interpolate || d.Array || d.Atomic || d.Padding ||
                (d.File == TGSI_FILE_MEMORY ? d.MemType != TGSI_MEMORY_TYPE_SHARED : d.MemType != 0)) return -1;
            if (d.File == TGSI_FILE_CONSTANT || d.File == TGSI_FILE_HW_ATOMIC) {
                struct tgsi_declaration_dimension dim;
                if (!d.Dimension || d.Semantic || n != 3 || n > end - pos) return -1;
                memcpy(&dim, t + pos + 2, 4);
                if (dim.Padding || dim.Index2D > (d.File == TGSI_FILE_CONSTANT ? 12u : 0u)) return -1;
            } else if (d.File == TGSI_FILE_SAMPLER_VIEW || d.File == TGSI_FILE_IMAGE) {
                if (d.Dimension || d.Semantic || n != 3 || n > end - pos) return -1;
            } else if ((d.File != TGSI_FILE_TEMPORARY && d.File != TGSI_FILE_BUFFER &&
                        d.File != TGSI_FILE_SAMPLER && d.File != TGSI_FILE_MEMORY &&
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
                insn.NumSrcRegs > (insn.Opcode == TGSI_OPCODE_ATOMCAS ? 4u :
                    storage_atomic_operation(insn.Opcode) >= 0 || insn.Opcode == TGSI_OPCODE_UCMP ? 3u : 2u)) return -1;
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
                    if ((src.File != TGSI_FILE_CONSTANT && src.File != TGSI_FILE_HW_ATOMIC) || ++q >= pos + n) return -1;
                    memcpy(&ind, t + q, 4);
                    if (ind.File != TGSI_FILE_ADDRESS || ind.Index != (src.File == TGSI_FILE_HW_ATOMIC ? 2 : 0) ||
                        ind.Swizzle || ind.ArrayID) return -1;
                }
                if (src.File == TGSI_FILE_CONSTANT || src.File == TGSI_FILE_HW_ATOMIC) {
                    struct tgsi_dimension dim;
                    if (!src.Dimension || ++q >= pos + n) return -1;
                    memcpy(&dim, t + q, 4);
                    if (dim.Indirect || dim.Dimension || dim.Padding ||
                        dim.Index < 0 || dim.Index > (src.File == TGSI_FILE_CONSTANT ? 12 : 0)) return -1;
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
    case TGSI_FILE_HW_ATOMIC:
        if (last >= 8 || mask != 15 || !d->Declaration.Dimension || d->Dim.Index2D ||
            d->Declaration.Semantic || b->cf) return -1;
        for (i = first; i <= last; i++) {
            if (b->counters & (1u << i)) return -1;
            b->counters |= 1u << i;
        }
        if (b->p.counter_bytes < (last + 1) * 4) b->p.counter_bytes = (last + 1) * 4;
        return 0;
    case TGSI_FILE_MEMORY:
        if (!b->shared || b->cf || first != TGSI_MEMORY_TYPE_SHARED || last != first || d->Declaration.Semantic ||
            d->Declaration.MemType != TGSI_MEMORY_TYPE_SHARED || b->shared_declared) return -1;
        b->shared_declared = 1;
        return 0;
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
        if (first || (last != 0 && last != 2) || !(mask & 1) ||
            d->Declaration.Semantic || b->address_declared || (last == 2 && !b->counters)) return -1;
        b->address_declared = last == 2 ? 5 : 1;
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

/* Only affine local-ID stripes and the validator's exact adjacent-warp XOR
 * earn a scalar shared address. Unknown, masked or data-derived addresses
 * never acquire provenance merely because their numeric range looks small. */
static struct shared_expr expression(const struct build *b,
    const struct tgsi_full_src_register *full, unsigned component)
{
    const struct tgsi_src_register *s = &full->Register;
    unsigned c = swizzle(s, component), index = (unsigned)s->Index;
    struct shared_expr unknown = {0};
    if (!b->shared || s->Index < 0) return unknown;
    if (s->File == TGSI_FILE_TEMPORARY && index < CS_TEMPS) return b->expr[index][c];
    if (s->File == TGSI_FILE_IMMEDIATE && index < b->nimm)
        return (struct shared_expr){EXPR_CONSTANT, 0, b->imm[index][c]};
    if (s->File != TGSI_FILE_SYSTEM_VALUE || index >= CS_SYSTEMS || c >= 3 ||
        !(b->sys_mask[index] & (1u << c))) return unknown;
    if (b->sys_name[index] == TGSI_SEMANTIC_BLOCK_SIZE && b->p.fixed_block[c])
        return (struct shared_expr){EXPR_CONSTANT, 0, b->p.fixed_block[c]};
    if (b->sys_name[index] == TGSI_SEMANTIC_THREAD_ID &&
        b->p.fixed_block[0] && b->p.fixed_block[1] == 1 && b->p.fixed_block[2] == 1)
        return c ? (struct shared_expr){EXPR_CONSTANT, 0, 0} :
                   (struct shared_expr){EXPR_LOCAL, 1, 0};
    return unknown;
}

static struct shared_expr expression_op(unsigned op, struct shared_expr a,
                                        struct shared_expr z, unsigned items, unsigned lanes)
{
    struct shared_expr unknown = {0};
    if (op == TGSI_OPCODE_MOV) return a;
    if (!a.kind || !z.kind) return unknown;
    if (a.kind == EXPR_CONSTANT && z.kind == EXPR_CONSTANT) {
        uint32_t value;
        switch (op) {
        case TGSI_OPCODE_UADD: value = a.offset + z.offset; break;
        case TGSI_OPCODE_UMUL: value = a.offset * z.offset; break;
        case TGSI_OPCODE_SHL: value = a.offset << (z.offset & 31); break;
        case TGSI_OPCODE_USHR: value = a.offset >> (z.offset & 31); break;
        case TGSI_OPCODE_AND: value = a.offset & z.offset; break;
        case TGSI_OPCODE_OR: value = a.offset | z.offset; break;
        case TGSI_OPCODE_XOR: value = a.offset ^ z.offset; break;
        default: return unknown;
        }
        return (struct shared_expr){EXPR_CONSTANT, 0, value};
    }
    if ((op == TGSI_OPCODE_UADD || op == TGSI_OPCODE_UMUL || op == TGSI_OPCODE_XOR) &&
        a.kind == EXPR_CONSTANT) { struct shared_expr tmp = a; a = z; z = tmp; }
    if ((a.kind != EXPR_LOCAL && a.kind != EXPR_PEER) || z.kind != EXPR_CONSTANT) return unknown;
    if (op == TGSI_OPCODE_UADD) {
        uint64_t sum = (uint64_t)a.offset + z.offset;
        if (sum <= UINT32_MAX) { a.offset = (uint32_t)sum; return a; }
    } else if ((op == TGSI_OPCODE_SHL && z.offset == 2) ||
               (op == TGSI_OPCODE_UMUL && z.offset == 4)) {
        if (a.scale == 1 && a.offset <= UINT32_MAX / 4) {
            a.scale = 4; a.offset *= 4; return a;
        }
    } else if (op == TGSI_OPCODE_XOR && a.kind == EXPR_LOCAL && !a.offset &&
               lanes == 16 && items == 128 && z.offset == a.scale * lanes) {
        a.kind = EXPR_PEER; return a;
    }
    return unknown;
}

static void materialize(struct build *b, struct shared_expr e, unsigned to)
{
    if (e.kind == EXPR_CONSTANT) { splat(b, to, e.offset); return; }
    copy(b, to, 1);
    if (e.kind == EXPR_PEER) {
        scalar(b, b->shared->lanes);
        word(b, vec(11, 4, to, 10, to));
    }
    if (e.scale == 4) word(b, vec(0x25, 3, to, 2, to));
    if (e.offset) { scalar(b, e.offset); word(b, vec(0, 4, to, 10, to)); }
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
            if (!(b->address_defined & 1)) return -1;
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
        if (i < CS_TEMPS && b->shared && b->expr[i][c].kind) {
            materialize(b, b->expr[i][c], to); return 0;
        }
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

static int source_register(struct build *b, const struct tgsi_full_src_register *full,
                           unsigned component, unsigned scratch, unsigned *reg)
{
    const struct tgsi_src_register *s = &full->Register;
    unsigned index = (unsigned)s->Index, c = swizzle(s, component);
    struct shared_expr e = expression(b, full, component);
    if (b->shared && e.kind == EXPR_LOCAL && e.scale == 1 && !e.offset) {
        *reg = 1; return 0;
    }
    if (b->shared && !e.kind && s->File == TGSI_FILE_TEMPORARY &&
        s->Index >= 0 && index < CS_TEMPS && b->reg[index][c]) {
        *reg = b->reg[index][c]; return 0;
    }
    *reg = scratch;
    return source(b, full, component, scratch);
}

static int destination(struct build *b, const struct tgsi_dst_register *d)
{
    unsigned c, i = (unsigned)d->Index;
    if (d->Index < 0 || i >= CS_TEMPS || d->File != TGSI_FILE_TEMPORARY ||
        !d->WriteMask || (d->WriteMask & ~b->declared[i])) return -1;
    /* Results are staged in v26..29 before any temp is overwritten, preserving
     * parallel TGSI writemasks/swizzles even when destination aliases a source. */
    for (c = 0; c < 4; c++) if (d->WriteMask & (1u << c)) {
        b->expr[i][c] = (struct shared_expr){0};
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

static int shared_memory(struct build *b, const struct tgsi_full_instruction *in)
{
    const struct tgsi_instruction *i = &in->Instruction;
    const struct tgsi_dst_register *d = &in->Dst[0].Register;
    unsigned store = i->Opcode == TGSI_OPCODE_STORE;
    unsigned value_reg = 22, load_reg = 26, temp = (unsigned)d->Index;
    unsigned binding = (unsigned)(store ? d->Index : in->Src[0].Register.Index);
    struct shared_expr address = expression(b, &in->Src[store ? 0 : 1], 0);
    uint64_t end = (uint64_t)address.offset + 4ull * b->p.fixed_block[0];
    if (!b->shared || !b->shared_declared || b->cf || !i->Memory || i->Texture ||
        i->NumDstRegs != 1 || i->NumSrcRegs != 2 || binding != TGSI_MEMORY_TYPE_SHARED || d->WriteMask != 1 ||
        in->Memory.Qualifier || in->Memory.Format || in->Memory.Padding ||
        in->Memory.Texture != TGSI_TEXTURE_BUFFER ||
        (address.kind != EXPR_LOCAL && address.kind != EXPR_PEER) || address.scale != 4 ||
        (address.offset & 3) || end > b->shared->required_bytes ||
        (store && address.kind != EXPR_LOCAL) || (!store && end > b->shared_visible)) return -1;
    if (store && source_register(b, &in->Src[1], 0, 22, &value_reg)) return -1;
    /* A scalar load has no parallel components to preserve. Its address is
     * already captured above; an existing TEMP can receive the value directly
     * even when it previously held that address expression. */
    if (!store && d->File == TGSI_FILE_TEMPORARY && d->Index >= 0 &&
        temp < CS_TEMPS && (b->declared[temp] & 1u) &&
        b->reg[temp][0] >= 2 && b->reg[temp][0] <= 21)
        load_reg = b->reg[temp][0];
    unsigned peer = address.kind == EXPR_PEER, reg = 11 + peer;
    int64_t delta = (int64_t)address.offset - b->shared_address_offset[peer];
    if (!b->shared_address_valid[peer]) {
        word(b, 2u << 20 | 8u << 15 | 1u << 12 | reg << 7 | 0x13u);
        if (peer) word(b, 64u << 20 | reg << 15 | 4u << 12 | reg << 7 | 0x13u);
        scalar(b, b->shared->base);
        word(b, 10u << 20 | reg << 15 | reg << 7 | 0x33u);
        delta = address.offset;
    }
    if (delta >= -2048 && delta <= 2047) {
        if (delta) word(b, ((uint32_t)delta & 0xfffu) << 20 | reg << 15 | reg << 7 | 0x13u);
    } else {
        scalar(b, (uint32_t)delta);
        word(b, 10u << 20 | reg << 15 | reg << 7 | 0x33u);
    }
    b->shared_address_valid[peer] = 1; b->shared_address_offset[peer] = address.offset;
    word(b, 1u << 25 | reg << 15 | 6u << 12 | (store ? value_reg : load_reg) << 7 |
            (store ? 0x27u : 0x07u));
    b->shared_used = 1;
    if (!store) {
        if (load_reg == 26) return destination(b, d);
        b->expr[temp][0] = (struct shared_expr){0};
        return 0;
    }
    if (address.offset <= b->shared_written && end > b->shared_written) b->shared_written = (unsigned)end;
    return 0;
}

static int shared_symbolic(struct build *b, const struct tgsi_full_instruction *in)
{
    const struct tgsi_dst_register *d = &in->Dst[0].Register;
    struct shared_expr values[4] = {{0}};
    if (!b->shared || d->File != TGSI_FILE_TEMPORARY || d->Index < 0 ||
        (unsigned)d->Index >= CS_TEMPS || (d->WriteMask & ~b->declared[d->Index])) return 0;
    for (unsigned c = 0; c < 4; c++) if (d->WriteMask & (1u << c)) {
        values[c] = expression_op(in->Instruction.Opcode, expression(b, &in->Src[0], c),
            expression(b, &in->Src[1], c), b->p.fixed_block[0], b->shared->lanes);
        if (!values[c].kind) return 0;
    }
    for (unsigned c = 0; c < 4; c++) if (d->WriteMask & (1u << c)) {
        b->expr[d->Index][c] = values[c];
    }
    return 1;
}

/* Only statically declared buffer resources. The existing SIMT arm validator
 * does not admit these owners; no resource operation may hide in a CF program. */
static int texture(struct build *b, const struct tgsi_full_instruction *in)
{
    const struct tgsi_instruction *i = &in->Instruction;
    const struct tgsi_dst_register *d = &in->Dst[0].Register;
    const struct tgsi_src_register *unit = &in->Src[1].Register;
    unsigned op = i->Opcode, index = (unsigned)unit->Index;
    struct tgsi_dst_register used_destination = *d;
    if (b->cf || !i->Texture || i->Memory || i->NumDstRegs != 1 || i->NumSrcRegs != 2 ||
        d->File != TGSI_FILE_TEMPORARY || d->Index < 0 || (unsigned)d->Index >= CS_TEMPS ||
        !d->WriteMask || (d->WriteMask & ~b->declared[d->Index]) ||
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
        scalar(b, index);
        /* Even a levels-only query validates the selected descriptor. */
        word(b, 6u << 25 | 10u << 15 | 10u << 7 | 0x0bu);
        word(b, vec(0x17, 4, 26, 10, 0));
        if (d->WriteMask & 2) splat(b, 27, 0);
        if (d->WriteMask & 4) splat(b, 28, 0);
        if (d->WriteMask & 8) splat(b, 29, 1);
    } else {
        /* NTT writes a vec4 even when GLSL consumes only .x. Keep every
         * component read before its next overwrite, conservatively treating
         * all four source swizzles as reads. A dead result still performs
         * one checked fetch, retaining descriptor/data fault semantics. */
        if (b->texture_observed && d->File == TGSI_FILE_TEMPORARY && d->Index >= 0 &&
            (unsigned)d->Index < CS_TEMPS && d->WriteMask == 15 &&
            (b->declared[d->Index] & 15) == 15)
            used_destination.WriteMask = b->texture_observed[b->texture_position];
        if (source(b, &in->Src[0], 0, 22)) return -1;
        splat(b, 23, 0);
        resource_word(b, 3, 0, 0, 22, 23);
        for (unsigned pair = 1; pair < 8; pair++) resource_word(b, 3, pair, 0, 23, 23);
        splat(b, 24, index);
        unsigned fetched = used_destination.WriteMask ? used_destination.WriteMask : 1u;
        for (unsigned c = 0; c < 4; c++) if (fetched & (1u << c))
            resource_word(b, 5, c, 26 + c, 24, 23);
    }
    b->p.textures |= UINT32_C(1) << index;
    return used_destination.WriteMask ? destination(b, &used_destination) : 0;
}

static int image(struct build *b, const struct tgsi_full_instruction *in)
{
    const struct tgsi_instruction *i = &in->Instruction;
    const struct tgsi_dst_register *d = &in->Dst[0].Register;
    unsigned op = i->Opcode, store = op == TGSI_OPCODE_STORE, query = op == TGSI_OPCODE_RESQ;
    unsigned index = (unsigned)(store ? d->Index : in->Src[0].Register.Index);
    if (b->cf || !i->Memory || i->Texture || i->NumDstRegs != 1 ||
        i->NumSrcRegs != (query ? 1u : 2u) || index >= 4 || !(b->images & (1u << index)) ||
        /* Coherent buffer images use the same uncached, acknowledged typed
         * access and shared-L2 invalidation path. Other qualifier contracts
         * remain unsupported; do not erase them during lowering. */
        (in->Memory.Qualifier & ~TGSI_MEMORY_COHERENT) || in->Memory.Padding ||
        in->Memory.Texture != TGSI_TEXTURE_BUFFER ||
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

static int storage_atomic(struct build *b, const struct tgsi_full_instruction *in, unsigned operation)
{
    const struct tgsi_instruction *i=&in->Instruction;
    const struct tgsi_dst_register *d=&in->Dst[0].Register;
    const struct tgsi_src_register *resource=&in->Src[0].Register;
    unsigned binding=(unsigned)resource->Index;
    if(b->cf || i->NumDstRegs!=1 || i->NumSrcRegs!=(operation==9 ? 4u : 3u) ||
        d->File!=TGSI_FILE_TEMPORARY || d->WriteMask!=1 || !i->Memory || i->Texture ||
        in->Memory.Qualifier || in->Memory.Format || in->Memory.Padding ||
        in->Memory.Texture!=TGSI_TEXTURE_BUFFER || resource->File!=TGSI_FILE_BUFFER ||
        resource->Index<0 || binding>=4 || !(b->buffers&(1u<<binding))) return -1;
    /* Preserve every operand before staging native state or overwriting a
     * destination that aliases a TGSI source. CAS sources are compare,value. */
    if(source(b,&in->Src[1],0,25) || source(b,&in->Src[operation==9 ? 3 : 2],0,22)) return -1;
    if(operation==9) { if(source(b,&in->Src[2],0,23))return -1; }
    else splat(b,23,0);
    resource_word(b,24,0,0,22,23);
    splat(b,24,binding);
    resource_word(b,operation<8 ? 25 : 26,operation%8,26,24,25);
    b->p.storage_atomics|=1u<<binding;
    b->p.reads|=1u<<binding;b->p.writes|=1u<<binding;
    return destination(b,d);
}

static int atomic_counter(struct build *b, const struct tgsi_full_instruction *in)
{
    const struct tgsi_instruction *i = &in->Instruction;
    const struct tgsi_dst_register *d = &in->Dst[0].Register;
    const struct tgsi_full_src_register *memory = &in->Src[0];
    const struct tgsi_src_register *s = &memory->Register;
    unsigned read = i->Opcode == TGSI_OPCODE_LOAD, function = 17;
    if (b->cf || i->Texture || i->Label || !i->Memory || i->NumDstRegs != 1 ||
        i->NumSrcRegs != (read ? 2u : 3u) || d->File != TGSI_FILE_TEMPORARY || d->WriteMask != 1 ||
        s->File != TGSI_FILE_HW_ATOMIC || !s->Dimension || memory->Dimension.Index ||
        s->Index < 0 || s->Index >= 8 || !(b->counters & (1u << s->Index)) ||
        in->Memory.Qualifier || in->Memory.Format || in->Memory.Padding || in->Memory.Texture)
        return -1;
    if (!read) {
        const struct tgsi_src_register *operand = &in->Src[2].Register;
        if (i->Opcode != TGSI_OPCODE_ATOMUADD || operand->File != TGSI_FILE_IMMEDIATE ||
            operand->Index < 0 || (unsigned)operand->Index >= b->nimm || operand->Indirect || operand->Dimension)
            return -1;
        uint32_t value = b->imm[operand->Index][swizzle(operand, 0)];
        if (value != 1 && value != UINT32_MAX) return -1;
        function = value == 1 ? 18 : 19;
    }
    /* NTT supplies a redundant byte-offset operand for HW_ATOMIC. Its actual
     * address is the dimensioned word index (plus ADDR0 for indirect access),
     * as in Mesa's hardware-counter backend. Never add that byte offset twice. */
    const struct tgsi_src_register *offset = &in->Src[1].Register;
    if (offset->Indirect || offset->Dimension || offset->Index < 0 ||
        !((offset->File == TGSI_FILE_IMMEDIATE && (unsigned)offset->Index < b->nimm) ||
          (offset->File == TGSI_FILE_TEMPORARY && (unsigned)offset->Index < CS_TEMPS &&
           b->reg[offset->Index][swizzle(offset, 0)]))) return -1;
    unsigned last = (unsigned)s->Index;
    while (last < 7 && (b->counters & (1u << (last + 1)))) last++;
    splat(b, 25, (unsigned)s->Index);
    if (s->Indirect) {
        if (!(b->address_defined & 4) || !b->counter_address) return -1;
        word(b, vec(0, 0, 25, 25, b->counter_address));
        splat(b, 31, 8); scalar(b, last - (unsigned)s->Index);
        word(b, vec(0x1c, 4, 0, 10, b->counter_address));
        word(b, vec(0x17, 0, 25, 25, 31) & ~(1u << 25));
    }
    resource_word(b, function, 0, 26, 25, 0);
    if (function == 19) {
        /* Native decrement returns new; TGSI ATOMUADD(-1) returns old.
         * Mesa's later pre-decrement adjustment must see that genuine old. */
        scalar(b, 1); word(b, vec(0, 4, 26, 10, 26));
    }
    b->p.counter_reads = 1;
    if (!read) b->p.counter_writes = 1;
    unsigned bytes = (s->Indirect ? last + 1 : (unsigned)s->Index + 1) * 4;
    if (b->p.counter_bytes < bytes) b->p.counter_bytes = bytes;
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
    if (op == TGSI_OPCODE_MEMBAR) {
        const struct tgsi_src_register *s = &in->Src[0].Register;
        const unsigned allowed = TGSI_MEMBAR_SHADER_BUFFER | TGSI_MEMBAR_ATOMIC_BUFFER |
            TGSI_MEMBAR_SHADER_IMAGE | TGSI_MEMBAR_SHARED | TGSI_MEMBAR_THREAD_GROUP;
        if (i->NumDstRegs || i->NumSrcRegs != 1 || i->Memory || i->Texture || i->Label ||
            s->File != TGSI_FILE_IMMEDIATE || s->Index < 0 || (unsigned)s->Index >= b->nimm ||
            s->Indirect || s->Dimension || (b->imm[s->Index][swizzle(s, 0)] & ~allowed)) return -1;
        /* One unresolved instruction per warp: resource stores/AMOs retire
         * only after their acknowledgements, and texture/image loads retire
         * with their result. The full native fence orders every admitted
         * client, a conservative superset of the requested TGSI domains.
         * THREAD_GROUP scopes memory; it is not an execution rendezvous and
         * must not publish the initialized-shared proof below. */
        word(b, 0x0330000fu);
        return 0;
    }
    if (op == TGSI_OPCODE_BARRIER) {
        if (!b->shared || !b->shared_declared || b->cf || i->NumDstRegs ||
            i->NumSrcRegs || i->Memory || i->Texture || i->Label) return -1;
        word(b, 0x0330000fu);
        word(b, 0x0000000bu); b->shared_visible = b->shared_written;
        return 0;
    }
    if (i->NumDstRegs != 1 || !d->WriteMask) return -1;
    if (op == TGSI_OPCODE_TXF || op == TGSI_OPCODE_TXF_LZ || op == TGSI_OPCODE_TXQ)
        return texture(b, in);
    if ((op == TGSI_OPCODE_LOAD || storage_atomic_operation(op) >= 0) &&
        in->Src[0].Register.File == TGSI_FILE_HW_ATOMIC)
        return atomic_counter(b, in);
    if ((mem || op == TGSI_OPCODE_RESQ) &&
        (op == TGSI_OPCODE_STORE ? d->File : in->Src[0].Register.File) == TGSI_FILE_IMAGE)
        return image(b, in);
    if(storage_atomic_operation(op)>=0)
        return storage_atomic(b,in,(unsigned)storage_atomic_operation(op));
    if (i->Texture) return -1;
    if (mem) {
        if ((op == TGSI_OPCODE_LOAD ? in->Src[0].Register.File : d->File) == TGSI_FILE_MEMORY)
            return shared_memory(b, in);
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
        if ((d->Index != 0 && d->Index != 2) || !(b->address_declared & (1u << d->Index)) ||
            i->NumSrcRegs != 1 || d->File != TGSI_FILE_ADDRESS || d->WriteMask != 1 ||
            source(b, &in->Src[0], 0, 22)) return -1;
        if (d->Index == 2 && !b->counter_address) {
            if (b->next_reg > 21) return -1;
            b->counter_address = b->next_reg++;
        }
        copy(b, d->Index == 2 ? b->counter_address : 30, 22);
        b->address_defined |= 1u << d->Index;
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
    if (shared_symbolic(b, in)) return 0;
    for (c = 0; c < 4; c++) if (d->WriteMask & (1u << c)) {
        unsigned left, right, third;
        if (source_register(b, &in->Src[0], c, 22, &left)) return -1;
        if (op == TGSI_OPCODE_MOV) copy(b, 26 + c, left);
        else if (op == TGSI_OPCODE_UCMP) {
            if (source_register(b, &in->Src[1], c, 23, &right) ||
                source_register(b, &in->Src[2], c, 24, &third)) return -1;
            /* v0 is a packed lane mask. Any nonzero bit pattern is true.
             * Define old vd before masked merge, as the sandbox requires. */
            copy(b, 26 + c, third);
            word(b, vec(0x19, 4, 0, 0, left));
            word(b, vec(0x17, 0, 26 + c, right, third) & ~(1u << 25));
        }
        else {
            if (source_register(b, &in->Src[1], c, 23, &right)) return -1;
            if (op == TGSI_OPCODE_USEQ || op == TGSI_OPCODE_USNE ||
                op == TGSI_OPCODE_USLT || op == TGSI_OPCODE_USGE) {
                /* USGE(a,b) is b<=a. Materialize TGSI's per-component
                 * all-ones/zero result from the native packed predicate. */
                word(b, vec(fn, 0, 0, op == TGSI_OPCODE_USGE ? left : right,
                    op == TGSI_OPCODE_USGE ? right : left));
                splat(b, 26 + c, 0);
                word(b, vec(0x17, 3, 26 + c, 31, 26 + c) & ~(1u << 25));
            } else word(b, vec(fn, f3, 26 + c, right, left));
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
            if (count || b.nimm == CS_LEGACY_IMMS) goto parsed;
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

static int emit_program(const void *tokens, const struct opengpu_cs_shared *shared,
                        struct opengpu_cs_program *out)
{
    struct build b;
    struct tgsi_parse_context p;
    unsigned observed[CS_TOKENS + 2] = {0}, pending[CS_TEMPS][4] = {{0}};
    int ended = 0;
    if (!tokens || !out || packets(tokens)) return -1;
    if (tgsi_parse_init(&p, tokens) != TGSI_PARSE_OK) return -1;
    while (!tgsi_parse_end_of_tokens(&p)) {
        tgsi_parse_token(&p);
        if (p.FullToken.Token.Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            const struct tgsi_full_instruction *full = &p.FullToken.FullInstruction;
            const struct tgsi_instruction *in = &full->Instruction;
            unsigned position = p.Position - in->NrTokens - 1;
            for (unsigned s = 0; s < in->NumSrcRegs; s++) {
                const struct tgsi_src_register *src = &full->Src[s].Register;
                if (src->File != TGSI_FILE_TEMPORARY || src->Index < 0 ||
                    (unsigned)src->Index >= CS_TEMPS) continue;
                for (unsigned c = 0; c < 4; c++) {
                    unsigned component = swizzle(src, c), definition = pending[src->Index][component];
                    if (definition) observed[definition] |= 1u << component;
                }
            }
            if (in->NumDstRegs) {
                const struct tgsi_dst_register *dst = &full->Dst[0].Register;
                if (dst->File == TGSI_FILE_TEMPORARY && dst->Index >= 0 &&
                    (unsigned)dst->Index < CS_TEMPS)
                    for (unsigned c = 0; c < 4; c++) if (dst->WriteMask & (1u << c))
                        pending[dst->Index][c] = in->Texture &&
                            (in->Opcode == TGSI_OPCODE_TXF || in->Opcode == TGSI_OPCODE_TXF_LZ) ? position : 0;
            }
        }
        if (p.FullToken.Token.Type == TGSI_TOKEN_TYPE_INSTRUCTION &&
            (p.FullToken.FullInstruction.Instruction.Opcode == TGSI_OPCODE_UIF ||
             p.FullToken.FullInstruction.Instruction.Opcode == TGSI_OPCODE_ELSE ||
             p.FullToken.FullInstruction.Instruction.Opcode == TGSI_OPCODE_ENDIF)) {
            tgsi_parse_free(&p);
            if (shared) return -1; /* shared barriers require a straight, converged program */
            return control_program(tokens, out);
        }
    }
    tgsi_parse_free(&p);
    memset(&b, 0, sizeof(b));
    b.texture_observed = observed;
    b.shared = shared;
    b.next_reg = 2;
    if (tgsi_parse_init(&p, tokens) != TGSI_PARSE_OK) return -1;
    word(&b, 0x01007557u); /* VLMAX e32,m1,tu,mu -> scratch x10; preserve x5..7 */
    /* Shared admission proves full physical warps, rather than treating VLMAX
     * as the entire workgroup. Keep the scratch result and legacy prologue. */
    if (shared) word(&b, 0xc1007057u | shared->lanes << 15);
    while (!tgsi_parse_end_of_tokens(&p)) {
        int ret = 0;
        if (ended) { b.failed = 1; break; }
        tgsi_parse_token(&p);
        switch (p.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_DECLARATION:
            ret = declaration(&b, &p.FullToken.FullDeclaration); break;
        case TGSI_TOKEN_TYPE_IMMEDIATE: {
            unsigned c;
            if (b.nimm == (shared ? CS_IMMS : CS_LEGACY_IMMS)) { ret = -1; break; }
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
            b.texture_position = p.Position - p.FullToken.FullInstruction.Instruction.NrTokens - 1;
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
    if (!ended || b.failed || (shared && (!b.shared_used || !b.p.fixed_block[0] || b.p.fixed_block[0] > 128 ||
        b.p.fixed_block[1] != 1 || b.p.fixed_block[2] != 1 ||
        b.p.fixed_block[0] % shared->lanes))) return -1;
    *out = b.p;
    return 0;
}

int opengpu_cs_emit(const void *tokens, struct opengpu_cs_program *out)
{
    return emit_program(tokens, NULL, out);
}

int opengpu_cs_emit_shared(const void *tokens, const struct opengpu_cs_shared *shared,
                          struct opengpu_cs_program *out)
{
    if (!shared || !shared->bytes || !shared->lanes || shared->lanes > 31 ||
        (shared->lanes & (shared->lanes - 1)) || !shared->required_bytes ||
        ((shared->base | shared->bytes | shared->required_bytes) & 3u) ||
        shared->required_bytes > shared->bytes ||
        (uint64_t)shared->base + shared->bytes > UINT64_C(0x100000000)) return -1;
    return emit_program(tokens, shared, out);
}
