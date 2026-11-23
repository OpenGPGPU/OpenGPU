/* SPDX-License-Identifier: MIT */
/* Native graphics uniform loads retain the existing GUR1/GUB1 checked service.
 * SoA input/output access stays inside the native stage's private payload. */
#include "opengpu_native_tess_uniform_emit.h"
#include "opengpu_native_uniform_emit.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_parse.h"
#include <limits.h>
#include <string.h>

struct build {
    struct opengpu_native_uniform_program p;
    struct opengpu_native_uniform_layout layout;
    struct opengpu_native_fragment_metadata_v2 *v2;
    bool geometry_vertex;
    unsigned input_kind[32], output_kind[32], system_decl[32], system_array[32][4], system_kind[32];
    unsigned depth, sample_mask;
    unsigned temp_decl[32], temp[32][4], next;
    unsigned constants[13], first[13], last[13];
    unsigned input[32][4], output[32][4], input_decl[32], output_decl[32], written[32];
    uint32_t immediate[16][4];
    unsigned immediates, position;
    int failed;
};
static void word(struct build *b, uint32_t v)
{ if (b->p.words == 512) b->failed = 1; else b->p.code[b->p.words++] = v; }
static uint32_t vec(unsigned fn, unsigned f3, unsigned rd, unsigned a, unsigned z)
{ return fn << 26 | 1u << 25 | z << 20 | a << 15 | f3 << 12 | rd << 7 | 0x57u; }
static void copy(struct build *b, unsigned to, unsigned from)
{ word(b, vec(0, 4, to, 0, from)); }
static void scalar(struct build *b, unsigned reg, uint32_t value)
{
    uint32_t high = (value + 0x800u) & 0xfffff000u, low = value & 4095;
    if (high) word(b, high | reg << 7 | 0x37u);
    if (low || !high) word(b, low << 20 | (high ? reg : 0) << 15 | reg << 7 | 0x13u);
}
static void splat(struct build *b, unsigned to, uint32_t value)
{ scalar(b, 10, value); word(b, vec(0x17, 4, to, 10, 0)); }
static void address(struct build *b, unsigned array)
{
    uint32_t offset = array * b->layout.capacity * 4u;
    /* Keep the validator's bounded x1/warp-relative pointer provenance.
     * LUI is a data value, never an ordinary-memory address authority. */
    if (offset <= 4088) {
        unsigned step = offset > 2044 ? 2044 : offset;
        word(b, step << 20 | 7u << 15 | 6u << 7 | 0x13u);
        if (offset > step) word(b, (offset-step) << 20 | 6u << 15 | 6u << 7 | 0x13u);
    } else {
        scalar(b, 6, array);
        word(b, 1u << 25 | 6u << 20 | 5u << 15 | 6u << 7 | 0x33u);
        word(b, 6u << 20 | 7u << 15 | 6u << 7 | 0x33u);
    }
}
static void load(struct build *b, unsigned to, unsigned array)
{ address(b, array); word(b, 1u << 25 | 6u << 15 | 6u << 12 | to << 7 | 7u); }
static void store(struct build *b, unsigned from, unsigned array)
{ address(b, array); word(b, 1u << 25 | 6u << 15 | 6u << 12 | from << 7 | 0x27u); }
static int sources(unsigned op, bool v2, bool geometry_vertex)
{
    switch (op) {
    case TGSI_OPCODE_END: return 0;
    case TGSI_OPCODE_MOV: return 1;
    case TGSI_OPCODE_ADD: return v2 || geometry_vertex ? 2 : -1;
    case TGSI_OPCODE_UADD: case TGSI_OPCODE_UMUL: case TGSI_OPCODE_XOR:
    case TGSI_OPCODE_AND: case TGSI_OPCODE_OR: case TGSI_OPCODE_SHL: case TGSI_OPCODE_USHR: return 2;
    default:
        if(v2){
            if(op==TGSI_OPCODE_UCMP)return 3;
            if(op==TGSI_OPCODE_U2F||op==TGSI_OPCODE_I2F||op==TGSI_OPCODE_F2U||op==TGSI_OPCODE_F2I)return 1;
            if(op==TGSI_OPCODE_MUL||op==TGSI_OPCODE_ISHR)return 2;
        }
        return -1;
    }
}
/* Count and validate every optional token before calling Mesa's real parser. */
static int packets_mode(const struct tgsi_token *t, unsigned stage, bool v2, unsigned next_stage)
{
    struct tgsi_header h; struct tgsi_processor p;
    memcpy(&h, t, 4); memcpy(&p, t + 1, 4);
    if (h.HeaderSize != 2 || !h.BodySize || h.BodySize > 4096 || p.Padding ||
        p.Processor != (stage ? PIPE_SHADER_FRAGMENT : PIPE_SHADER_VERTEX)) return -1;
    for (unsigned pos = 2, end = 2 + h.BodySize; pos < end;) {
        struct tgsi_token token; memcpy(&token, t + pos, 4);
        unsigned n = token.NrTokens;
        if (token.Type == TGSI_TOKEN_TYPE_DECLARATION) {
            struct tgsi_declaration d; memcpy(&d, t + pos, 4);
            if ((v2&&d.Local) || d.Array || d.Atomic || d.Padding || d.MemType ||
                n != 2u + d.Dimension + d.Semantic + d.Interpolate) return -1;
            if (d.File == TGSI_FILE_CONSTANT) {
                struct tgsi_declaration_dimension dim;
                if (!d.Dimension || d.Semantic || d.Interpolate || n > end - pos) return -1;
                memcpy(&dim, t + pos + 2, 4); if (dim.Padding || dim.Index2D > 12) return -1;
            } else if (d.Dimension || (d.Interpolate && (d.File != TGSI_FILE_INPUT || !stage)) ||
                (d.File != TGSI_FILE_INPUT && d.File != TGSI_FILE_OUTPUT && d.File != TGSI_FILE_TEMPORARY && (!v2||d.File!=TGSI_FILE_SYSTEM_VALUE))) return -1;
        } else if (token.Type == TGSI_TOKEN_TYPE_IMMEDIATE) {
            struct tgsi_immediate i; memcpy(&i, t + pos, 4); n = i.NrTokens;
            if (n != 5 || i.Padding || (i.DataType != TGSI_IMM_UINT32 &&
                i.DataType != TGSI_IMM_INT32 && i.DataType != TGSI_IMM_FLOAT32)) return -1;
        } else if (token.Type == TGSI_TOKEN_TYPE_PROPERTY) {
            struct tgsi_property v; memcpy(&v, t + pos, 4);
            if(v.NrTokens!=2||v.Padding)return -1;
            if(v2){
                if(v.PropertyName!=TGSI_PROPERTY_FS_COORD_ORIGIN&&v.PropertyName!=TGSI_PROPERTY_FS_COORD_PIXEL_CENTER)return -1;
            }else if(stage||v.PropertyName!=TGSI_PROPERTY_NEXT_SHADER)return -1;
        } else if (token.Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            struct tgsi_instruction i;
            memcpy(&i, t + pos, 4);
            int count = sources(i.Opcode, v2, !stage && next_stage == PIPE_SHADER_GEOMETRY);
            n = i.NrTokens + 1;
            if (count < 0 || i.NumSrcRegs != (unsigned)count || i.NumDstRegs != (i.Opcode != TGSI_OPCODE_END) ||
                (v2&&i.Precise) || i.Saturate || i.Label || i.Texture || i.Memory || i.Padding || n > end - pos) return -1;
            unsigned q = pos + 1;
            if (i.NumDstRegs) {
                struct tgsi_dst_register d; if (q >= pos + n) return -1;
                memcpy(&d, t + q++, 4); if (d.Indirect || d.Dimension || d.Padding) return -1;
            }
            for (unsigned k = 0; k < i.NumSrcRegs; k++) {
                struct tgsi_src_register s; if (q >= pos + n) return -1;
                memcpy(&s, t + q++, 4); if (s.Indirect || s.Absolute || s.Negate) return -1;
                if (s.File == TGSI_FILE_CONSTANT) {
                    struct tgsi_dimension d; if (!s.Dimension || q >= pos + n) return -1;
                    memcpy(&d, t + q++, 4); if (d.Indirect || d.Dimension || d.Padding || d.Index < 0 || d.Index > 12) return -1;
                } else if (s.Dimension) return -1;
            }
            if (q != pos + n) return -1;
        } else return -1;
        if (!n || n > end - pos) return -1;
        pos += n;
    }
    return 0;
}
static int declaration_v2(struct build *b,const struct tgsi_full_declaration *d)
{
    unsigned first=d->Range.First,last=d->Range.Last,name=d->Semantic.Name,mask=d->Declaration.UsageMask;
    if(!d->Declaration.Semantic||last>=32||first>last||!mask||d->Semantic.StreamX||d->Semantic.StreamY||
       d->Semantic.StreamZ||d->Semantic.StreamW)return -1;
    for(unsigned i=first;i<=last;i++){
        unsigned index=d->Semantic.Index+i-first,arrays[4]={0};
        unsigned *decl=d->Declaration.File==TGSI_FILE_SYSTEM_VALUE?b->system_decl:
            d->Declaration.File==TGSI_FILE_OUTPUT?b->output_decl:b->input_decl;
        unsigned (*mapping)[4]=d->Declaration.File==TGSI_FILE_SYSTEM_VALUE?b->system_array:
            d->Declaration.File==TGSI_FILE_OUTPUT?b->output:b->input;
        if(decl[i])return -1;
        if(d->Declaration.File==TGSI_FILE_OUTPUT){
            unsigned array=b->v2->input_arrays;
            if(name==TGSI_SEMANTIC_COLOR&&index<b->layout.colors)array+=4*index;
            else if(name==TGSI_SEMANTIC_POSITION&&!index)array+=4*b->layout.colors-2;
            else if(name==TGSI_SEMANTIC_SAMPLEMASK&&!index)array=~2u;
            else return -1;
            for(unsigned c=0;c<4;c++)arrays[c]=array==~2u?array:array+c;
            b->output_kind[i]=name;
        }else{
            if(d->Declaration.File==TGSI_FILE_INPUT){
                if(!d->Declaration.Interpolate||d->Interp.Padding)return -1;
                unsigned mode=d->Interp.Interpolate,loc=d->Interp.Location;
                if(name==TGSI_SEMANTIC_GENERIC){
                    if(index>=b->layout.varyings||(mode!=TGSI_INTERPOLATE_CONSTANT&&mode!=TGSI_INTERPOLATE_PERSPECTIVE)||
                       loc>TGSI_INTERPOLATE_LOC_SAMPLE||(loc==TGSI_INTERPOLATE_LOC_SAMPLE&&b->v2->abi_version!=9)||
                       (mode==TGSI_INTERPOLATE_CONSTANT&&loc!=TGSI_INTERPOLATE_LOC_CENTER))return -1;
                    uint64_t bits=(uint64_t)mask<<(4*index);
                    if(mode==TGSI_INTERPOLATE_CONSTANT)b->v2->flat_mask|=bits;
                    if(loc==TGSI_INTERPOLATE_LOC_CENTROID)b->v2->centroid_mask|=bits;
                }else if(name==TGSI_SEMANTIC_PRIMID||name==TGSI_SEMANTIC_LAYER){
                    if(mode!=TGSI_INTERPOLATE_CONSTANT||loc!=TGSI_INTERPOLATE_LOC_CENTER)return -1;
                }else if(name!=TGSI_SEMANTIC_POSITION||mode!=TGSI_INTERPOLATE_LINEAR||loc!=TGSI_INTERPOLATE_LOC_CENTER)return -1;
            }else if(name==TGSI_SEMANTIC_GENERIC||name==TGSI_SEMANTIC_POSITION||name==TGSI_SEMANTIC_PRIMID||name==TGSI_SEMANTIC_LAYER)return -1;
            for(unsigned c=0;c<4;c++){
                if(name==TGSI_SEMANTIC_GENERIC)arrays[c]=6+4*index+c;
                else if(name==TGSI_SEMANTIC_POSITION&&!index)arrays[c]=c;
                else if(name==TGSI_SEMANTIC_PRIMID&&!index)arrays[c]=8+4*b->layout.varyings;
                else if(name==TGSI_SEMANTIC_LAYER&&!index)arrays[c]=~0u;
                else if(name==TGSI_SEMANTIC_HELPER_INVOCATION&&!index)arrays[c]=~1u;
                else if(name==TGSI_SEMANTIC_SAMPLEMASK&&!index)arrays[c]=5;
                else if(name==TGSI_SEMANTIC_SAMPLEID&&!index&&b->v2->abi_version==9)arrays[c]=b->v2->input_arrays-3;
                else if(name==TGSI_SEMANTIC_SAMPLEPOS&&!index&&b->v2->abi_version==9)arrays[c]=b->v2->input_arrays-2+c;
                else return -1;
            }
            if(d->Declaration.File==TGSI_FILE_SYSTEM_VALUE)b->system_kind[i]=name;
            else b->input_kind[i]=name;
        }
        decl[i]=mask;memcpy(mapping[i],arrays,sizeof(arrays));
    }
    return 0;
}
static int declaration(struct build *b, const struct tgsi_full_declaration *d)
{
    if(b->v2&&(d->Declaration.File==TGSI_FILE_INPUT||d->Declaration.File==TGSI_FILE_OUTPUT||d->Declaration.File==TGSI_FILE_SYSTEM_VALUE))return declaration_v2(b,d);
    unsigned first = d->Range.First, last = d->Range.Last, mask = d->Declaration.UsageMask;
    if (!mask || first > last) return -1;
    switch (d->Declaration.File) {
    case TGSI_FILE_CONSTANT: {
        unsigned slot = d->Dim.Index2D;
        if (b->constants[slot] || last >= (slot ? 1024u : 256u)) return -1;
        b->constants[slot] = mask; b->first[slot] = first; b->last[slot] = last; return 0;
    }
    case TGSI_FILE_TEMPORARY:
        if (last >= 32 || d->Declaration.Semantic) return -1;
        for (unsigned i = first; i <= last; i++) { if (b->temp_decl[i]) return -1; b->temp_decl[i] = mask; }
        return 0;
    case TGSI_FILE_INPUT: case TGSI_FILE_OUTPUT: {
        int output = d->Declaration.File == TGSI_FILE_OUTPUT;
        if (last >= 32 || (output && !d->Declaration.Semantic)) return -1;
        if (d->Declaration.Semantic && (d->Semantic.StreamX || d->Semantic.StreamY ||
            d->Semantic.StreamZ || d->Semantic.StreamW)) return -1;
        for (unsigned i = first; i <= last; i++) {
            unsigned index = d->Semantic.Index + i - first, array;
            unsigned *decl = output ? b->output_decl : b->input_decl;
            unsigned (*mapping)[4] = output ? b->output : b->input;
            if (decl[i]) return -1;
            if (!b->layout.stage) {
                if (!output) {
                    if (d->Declaration.Semantic || i >= b->layout.attributes) return -1;
                    array = i * 4;
                } else if (d->Semantic.Name == TGSI_SEMANTIC_POSITION && first == last && !index) {
                    if (b->position) return -1;
                    b->position = i + 1; array = b->layout.attributes * 4 + 3;
                } else if (d->Semantic.Name == TGSI_SEMANTIC_GENERIC && index < b->layout.varyings)
                    array = b->layout.attributes * 4 + 7 + index * 4;
                else return -1;
            } else {
                if (!d->Declaration.Semantic) return -1;
                if (!output && d->Semantic.Name == TGSI_SEMANTIC_GENERIC && index < b->layout.varyings &&
                    d->Declaration.Interpolate && d->Interp.Interpolate == TGSI_INTERPOLATE_CONSTANT) array = 6 + index * 4;
                else if (!output && d->Semantic.Name == TGSI_SEMANTIC_POSITION && first == last && !index) array = 0;
                else if (output && d->Semantic.Name == TGSI_SEMANTIC_COLOR && index < b->layout.colors)
                    array = 6 + b->layout.varyings * 4 + index * 4;
                else return -1;
            }
            decl[i] = mask; for (unsigned c = 0; c < 4; c++) mapping[i][c] = array + c;
        }
        return 0;
    }
    default: return -1;
    }
}
static int source_v2(struct build *b,unsigned file,unsigned i,unsigned c,unsigned to)
{
    if(i>=32)return -1;
    unsigned kind=file==TGSI_FILE_SYSTEM_VALUE?b->system_kind[i]:b->input_kind[i];
    unsigned decl=file==TGSI_FILE_SYSTEM_VALUE?b->system_decl[i]:b->input_decl[i];
    unsigned array=file==TGSI_FILE_SYSTEM_VALUE?b->system_array[i][c]:b->input[i][c];
    if(!(decl&(1u<<c)))return -1;
    uint32_t bit=0;
    switch(kind){
    case TGSI_SEMANTIC_GENERIC:bit=OPENGPU_FRAGMENT_VARYING;b->p.varying_mask|=UINT64_C(1)<<(array-6);break;
    case TGSI_SEMANTIC_POSITION:bit=OPENGPU_FRAGMENT_FRAG_COORD;break;
    case TGSI_SEMANTIC_SAMPLEID:if(c)return -1;bit=OPENGPU_FRAGMENT_SAMPLE_ID;break;
    case TGSI_SEMANTIC_SAMPLEPOS:if(c>1)return -1;bit=OPENGPU_FRAGMENT_SAMPLE_POSITION;break;
    case TGSI_SEMANTIC_SAMPLEMASK:if(c)return -1;bit=OPENGPU_FRAGMENT_SAMPLE_MASK_IN;break;
    case TGSI_SEMANTIC_PRIMID:if(c)return -1;bit=OPENGPU_FRAGMENT_PRIMITIVE_ID;break;
    case TGSI_SEMANTIC_LAYER:if(c)return -1;bit=OPENGPU_FRAGMENT_LAYER_ZERO;break;
    case TGSI_SEMANTIC_HELPER_INVOCATION:if(c)return -1;bit=OPENGPU_FRAGMENT_HELPER;break;
    default:return -1;
    }
    b->v2->used_sysvals|=bit;
    if(array==~0u)splat(b,to,0);
    else if(array==~1u){
        load(b,to,4);word(b,vec(0x28,3,to,1,to));word(b,vec(9,3,to,1,to));
        word(b,vec(11,3,to,31,to));word(b,vec(0,3,to,1,to));
    }else load(b,to,array);
    return 0;
}
static int source(struct build *b, const struct tgsi_full_src_register *s, unsigned channel, unsigned to)
{
    unsigned swizzle[] = {s->Register.SwizzleX, s->Register.SwizzleY, s->Register.SwizzleZ, s->Register.SwizzleW};
    unsigned c = swizzle[channel]; int i = s->Register.Index;
    if (i < 0) return -1;
    if(b->v2&&(s->Register.File==TGSI_FILE_INPUT||s->Register.File==TGSI_FILE_SYSTEM_VALUE))
        return source_v2(b,s->Register.File,(unsigned)i,c,to);
    switch (s->Register.File) {
    case TGSI_FILE_CONSTANT: {
        unsigned slot = s->Dimension.Index, bytes = (unsigned)i * 16 + c * 4 + 4;
        if (!(b->constants[slot] & (1u << c)) || (unsigned)i < b->first[slot] || (unsigned)i > b->last[slot]) return -1;
        scalar(b, 11, slot ? slot - 1 : 31); scalar(b, 12, bytes - 4);
        word(b, 7u << 25 | 12u << 20 | 11u << 15 | 13u << 7 | 0x0bu);
        word(b, vec(0x17, 4, to, 13, 0));
        if (b->p.constant_bytes[slot] < bytes) b->p.constant_bytes[slot] = bytes;
        return 0;
    }
    case TGSI_FILE_TEMPORARY:
        if (i >= 32 || !b->temp[i][c]) return -1;
        copy(b, to, b->temp[i][c]); return 0;
    case TGSI_FILE_INPUT:
        if (i >= 32 || !(b->input_decl[i] & (1u << c))) return -1;
        if (b->layout.stage && b->input[i][c] >= 6)
            b->p.varying_mask |= UINT64_C(1) << (b->input[i][c] - 6);
        load(b, to, b->input[i][c]); return 0;
    case TGSI_FILE_IMMEDIATE:
        if ((unsigned)i >= b->immediates) return -1;
        splat(b, to, b->immediate[i][c]); return 0;
    default: return -1;
    }
}
static int instruction(struct build *b, const struct tgsi_full_instruction *in)
{
    unsigned op = in->Instruction.Opcode; if (op == TGSI_OPCODE_END) return 1;
    const struct tgsi_dst_register *dst = &in->Dst[0].Register;
    int i = dst->Index; unsigned mask = dst->WriteMask, fn = 0, f3 = 0,convert=99;
    if (i < 0 || i >= 32 || !mask) return -1;
    unsigned declared = dst->File == TGSI_FILE_TEMPORARY ? b->temp_decl[i] :
        dst->File == TGSI_FILE_OUTPUT ? b->output_decl[i] : 0;
    if (mask & ~declared) return -1;
    switch (op) {
    case TGSI_OPCODE_MOV: break;
    case TGSI_OPCODE_ADD:
        if (!b->v2 && !b->geometry_vertex) return -1;
        f3 = 1;
        break;
    case TGSI_OPCODE_UADD: break;
    case TGSI_OPCODE_UMUL: fn = 0x25; f3 = 2; break;
    case TGSI_OPCODE_XOR: fn = 11; break;
    case TGSI_OPCODE_AND: fn = 9; break;
    case TGSI_OPCODE_OR: fn = 10; break;
    case TGSI_OPCODE_SHL: fn = 0x25; break;
    case TGSI_OPCODE_USHR: fn = 0x28; break;
    default:
        if(!b->v2)return -1;
        switch(op){
        case TGSI_OPCODE_UCMP:break;
        case TGSI_OPCODE_U2F:convert=2;break;case TGSI_OPCODE_I2F:convert=3;break;
        case TGSI_OPCODE_F2U:convert=6;break;case TGSI_OPCODE_F2I:convert=7;break;
        case TGSI_OPCODE_MUL:fn=0x24;f3=1;break;
        case TGSI_OPCODE_ISHR:fn=0x29;break;default:return -1;
        }
    }
    if(b->v2&&dst->File==TGSI_FILE_OUTPUT){
        if((b->output_kind[i]==TGSI_SEMANTIC_POSITION&&mask!=4)||
           (b->output_kind[i]==TGSI_SEMANTIC_SAMPLEMASK&&mask!=1))return -1;
    }
    /* Read all source channels before committing any destination channel. */
    for (unsigned c = 0; c < 4; c++) if (mask & (1u << c)) {
        if (b->geometry_vertex && op == TGSI_OPCODE_MOV) {
            if (source(b, &in->Src[0], c, 22 + c)) return -1;
            continue;
        }
        if (source(b, &in->Src[0], c, 26)) return -1;
        if (op == TGSI_OPCODE_MOV) copy(b, 22 + c, 26);
        else if(op==TGSI_OPCODE_UCMP){
            if(source(b,&in->Src[1],c,27)||source(b,&in->Src[2],c,28))return -1;
            copy(b,22+c,28);word(b,vec(0x19,3,0,0,26));word(b,vec(23,0,22+c,27,28)&~(1u<<25));
        }else if(convert!=99)word(b,vec(0x12,1,22+c,convert,26));
        else {
            if (source(b, &in->Src[1], c, 27)) return -1;
            word(b, vec(fn, f3, 22 + c, 27, 26));
        }
    }
    for (unsigned c = 0; c < 4; c++) if (mask & (1u << c)) {
        if (dst->File == TGSI_FILE_TEMPORARY) {
            if (!b->temp[i][c]) { if (b->next > 21) return -1; b->temp[i][c] = b->next++; }
            copy(b, b->temp[i][c], 22 + c);
        } else {
            unsigned array = b->output[i][c];
            if(b->v2&&array==~2u){copy(b,30,22+c);b->sample_mask=1;}
            else store(b,22+c,array);
            b->written[i]|=1u<<c;
            if(b->v2){
                if(array==~2u)continue;
                if(b->output_kind[i]==TGSI_SEMANTIC_POSITION){b->depth=1;continue;}
                b->p.color_mask|=1u<<((array-b->v2->input_arrays)/4);
            }else if (b->layout.stage) b->p.color_mask |= 1u << ((array - 6 - b->layout.varyings * 4) / 4);
            else if ((unsigned)i + 1 != b->position) b->p.varying_mask |= UINT64_C(1) << (array - b->layout.attributes * 4 - 7);
        }
    }
    return 0;
}
static int emit_mode(const void *tokens, const struct opengpu_native_uniform_layout *layout,
    struct opengpu_native_uniform_program *out,unsigned next_stage,struct opengpu_native_fragment_metadata_v2 *v2)
{
    struct build b = {0}; struct tgsi_parse_context parse; int ended = 0;
    if (!tokens || !layout || !out || layout->stage > 1 || !layout->capacity ||
        layout->attributes > 16 || layout->varyings > 16 || layout->colors > 4 ||
        (layout->stage ? layout->attributes != 0 : layout->colors != 0) ||
        layout->capacity > UINT_MAX / (4u * 136u) ||
        packets_mode(tokens, layout->stage, v2 != NULL, next_stage)) return -1;
    b.layout = *layout; b.next = 2;b.v2=v2;
    b.geometry_vertex = !layout->stage && next_stage == PIPE_SHADER_GEOMETRY;
    word(&b, 0x01007557u); /* e32 VLMAX; decoder retains the task active mask. */
    word(&b, 0x00241393u); word(&b, 0x007083b3u); /* x7 = kernarg + 4*local base */
    word(&b, 0x00249293u); /* x5 = 4*trusted physical task capacity */
    if (tgsi_parse_init(&parse, tokens) != TGSI_PARSE_OK) return -1;
    while (!tgsi_parse_end_of_tokens(&parse)) {
        int ret = 0; if (ended) { b.failed = 1; break; } tgsi_parse_token(&parse);
        switch (parse.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_DECLARATION: ret = declaration(&b, &parse.FullToken.FullDeclaration); break;
        case TGSI_TOKEN_TYPE_IMMEDIATE:
            if (b.immediates == 16) { ret = -1; break; }
            for (unsigned c = 0; c < 4; c++) b.immediate[b.immediates][c] = parse.FullToken.FullImmediate.u[c].Uint;
            b.immediates++; break;
        case TGSI_TOKEN_TYPE_PROPERTY:
            if(v2){
                unsigned name=parse.FullToken.FullProperty.Property.PropertyName,value=parse.FullToken.FullProperty.u[0].Data;
                if(value!=(name==TGSI_PROPERTY_FS_COORD_ORIGIN?TGSI_FS_COORD_ORIGIN_LOWER_LEFT:TGSI_FS_COORD_PIXEL_CENTER_HALF_INTEGER))ret=-1;
            }else if (parse.FullToken.FullProperty.u[0].Data != next_stage) ret = -1;
            break;
        case TGSI_TOKEN_TYPE_INSTRUCTION:
            ret = instruction(&b, &parse.FullToken.FullInstruction); if (ret == 1) { ended = 1; ret = 0; } break;
        default: ret = -1;
        }
        if (ret < 0) { b.failed = 1; break; }
    }
    tgsi_parse_free(&parse);
    if(v2){
        for(unsigned i=0;i<32;i++)if(b.output_decl[i]&&b.output_kind[i]==TGSI_SEMANTIC_COLOR&&b.written[i]&&b.written[i]!=15)b.failed=1;
        unsigned control=1u|(b.depth<<1)|(b.p.color_mask<<2);
        if(b.sample_mask){word(&b,vec(9,3,30,15,30));word(&b,vec(0x25,3,30,7,30));
            splat(&b,31,control|64u);word(&b,vec(10,0,31,30,31));}
        else splat(&b,31,control);
        store(&b,31,v2->input_arrays+4*layout->colors+1);
        v2->varying_mask=b.p.varying_mask;v2->output_color_mask=b.p.color_mask;
        v2->writes_depth=b.depth;v2->writes_sample_mask=b.sample_mask;
        memcpy(v2->constant_bytes,b.p.constant_bytes,sizeof(v2->constant_bytes));
        for(unsigned i=0;i<13;i++)v2->requires_uniform_owner|=!!b.p.constant_bytes[i];
    }else if (layout->stage) {
        splat(&b, 26, 1u | b.p.color_mask << 2);
        store(&b, 26, 7 + layout->varyings * 4 + layout->colors * 4);
    } else if (!b.position || b.written[b.position - 1] != 15) b.failed = 1;
    word(&b, 0x0330000fu); word(&b, 0x30500073u);
    if (!ended || b.failed || (layout->stage && !v2 && !b.p.color_mask)) return -1;
    *out = b.p; return 0;
}

static int emit_next(const void *tokens,const struct opengpu_native_uniform_layout *layout,
    struct opengpu_native_uniform_program *out,unsigned next_stage)
{ return emit_mode(tokens,layout,out,next_stage,NULL); }
int opengpu_native_fragment_uniform_compile_v2(const void *tokens,unsigned token_words,
    const struct opengpu_native_fragment_reflection_v2 *r,const struct opengpu_native_fragment_config_v2 *c,
    struct opengpu_native_fragment_program_v2 *out)
{
    struct opengpu_native_fragment_program_v2 p={0};struct opengpu_native_uniform_program program;
    struct tgsi_header h;
    if(!out||!tokens||token_words<3||token_words>4098||opengpu_native_fragment_metadata_init_v2(r,c,&p.metadata))return -1;
    memcpy(&h,tokens,4);
    if(h.HeaderSize!=2||h.BodySize!=token_words-2)return -1;
    struct opengpu_native_uniform_layout layout={1,128,0,c->varying_slots,c->color_slots};
    if(emit_mode(tokens,&layout,&program,0,&p.metadata)||!opengpu_native_fragment_metadata_valid_v2(&p.metadata))return -1;
    p.words=program.words;memcpy(p.code,program.code,p.words*4);*out=p;return 0;
}
static int compile_next(const void *tokens, unsigned stage,
    unsigned capacity, struct opengpu_native_uniform_layout *out_layout,
    struct opengpu_native_uniform_program *out_program, unsigned next_stage)
{
    struct opengpu_native_uniform_layout layout = {.stage=stage,.capacity=capacity};
    struct opengpu_native_uniform_program program;
    struct tgsi_parse_context parse;
    int failed = 0; unsigned next_count=0;
    if (!tokens || !out_layout || !out_program || stage > 1 || !capacity ||
        packets_mode(tokens, stage, false, next_stage) ||
        tgsi_parse_init(&parse,tokens) != TGSI_PARSE_OK) return -1;
    while (!tgsi_parse_end_of_tokens(&parse)) {
        tgsi_parse_token(&parse);
        if (parse.FullToken.Token.Type == TGSI_TOKEN_TYPE_PROPERTY &&
            parse.FullToken.FullProperty.Property.PropertyName == TGSI_PROPERTY_NEXT_SHADER) next_count++;
        if (parse.FullToken.Token.Type != TGSI_TOKEN_TYPE_DECLARATION) continue;
        const struct tgsi_full_declaration *d = &parse.FullToken.FullDeclaration;
        unsigned first = d->Range.First, last = d->Range.Last, count, *slots = NULL;
        if (first > last) { failed = 1; break; }
        count = last - first + 1;
        if (!stage && d->Declaration.File == TGSI_FILE_INPUT) {
            if (d->Declaration.Semantic || last >= 16) { failed = 1; break; }
            if (layout.attributes < last+1) layout.attributes = last+1;
        } else if ((d->Declaration.File == (stage ? TGSI_FILE_INPUT : TGSI_FILE_OUTPUT)) &&
            d->Declaration.Semantic && d->Semantic.Name == TGSI_SEMANTIC_GENERIC) {
            slots = &layout.varyings;
        } else if (stage && d->Declaration.File == TGSI_FILE_OUTPUT &&
            d->Declaration.Semantic && d->Semantic.Name == TGSI_SEMANTIC_COLOR) slots = &layout.colors;
        if (slots) {
            unsigned end = d->Semantic.Index + count;
            if (end > (slots == &layout.colors ? 4u : 16u)) { failed = 1; break; }
            if (*slots < end) *slots = end;
        }
    }
    tgsi_parse_free(&parse);
    if (failed || (next_stage != PIPE_SHADER_FRAGMENT && next_count != 1) ||
        emit_next(tokens, &layout, &program, next_stage)) return -1;
    *out_layout = layout; *out_program = program; return 0;
}

/* Existing VS/FS entry points remain exactly NEXT_SHADER=FRAGMENT. */
int opengpu_native_uniform_emit(const void *tokens,const struct opengpu_native_uniform_layout *layout,
    struct opengpu_native_uniform_program *out)
{ return emit_next(tokens,layout,out,PIPE_SHADER_FRAGMENT); }
int opengpu_native_uniform_compile(const void *tokens,unsigned stage,unsigned capacity,
    struct opengpu_native_uniform_layout *layout,struct opengpu_native_uniform_program *out)
{ return compile_next(tokens,stage,capacity,layout,out,PIPE_SHADER_FRAGMENT); }
/* Explicit actual linked VS->TCS compiler. Identical VS SoA v1 word layout,
 * original NEXT_SHADER property retained; no token rewriting or public gate. */
int opengpu_native_vertex_tess_compile(const void *tokens,unsigned capacity,
    struct opengpu_native_uniform_layout *layout,struct opengpu_native_uniform_program *out)
{ return compile_next(tokens,0,capacity,layout,out,PIPE_SHADER_TESS_CTRL); }

int opengpu_native_vertex_geometry_compile(const void *tokens, unsigned token_words,
    unsigned capacity, struct opengpu_native_uniform_layout *layout,
    struct opengpu_native_uniform_program *out)
{
    struct tgsi_header header;
    if (!tokens || token_words < 3 || token_words > 4098)
        return -1;
    memcpy(&header, tokens, sizeof(header));
    if (header.HeaderSize != 2 || header.BodySize != token_words - 2)
        return -1;
    return compile_next(tokens, 0, capacity, layout, out, PIPE_SHADER_GEOMETRY);
}
