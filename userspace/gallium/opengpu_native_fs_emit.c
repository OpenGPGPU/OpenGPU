/* SPDX-License-Identifier: MIT */
/* Explicit bounded native sample FS backend. No legacy ABI translation. */
#include "opengpu_native_fs_emit.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_parse.h"
#include <stdio.h>
#include <string.h>

#define LIMIT 4096u
#define REGS 64u
struct semantic { unsigned declared, mask, name, index; };
struct builder {
    struct opengpu_native_fs_program p;
    struct semantic input[REGS], system[REGS], output[REGS];
    unsigned temp[REGS], reg[REGS][4], defined[REGS], next;
    uint32_t imm[64][4];
    unsigned nimm, color_written[4], depth, sample_mask, failed;
};
static void emit(struct builder *b, uint32_t w)
{ if(b->p.words==512)b->failed=1;else b->p.code[b->p.words++]=w; }
static uint32_t vec(unsigned fn,unsigned f3,unsigned d,unsigned a,unsigned z)
{ return fn<<26|1u<<25|z<<20|a<<15|f3<<12|d<<7|0x57u; }
static void copy(struct builder *b,unsigned d,unsigned s)
{ emit(b,vec(0,4,d,0,s)); }
static void scalar(struct builder *b,uint32_t v)
{
    uint32_t hi=(v+0x800u)&0xfffff000u,lo=v&0xfffu;
    if(hi)emit(b,hi|10u<<7|0x37u);
    if(lo||!hi)emit(b,lo<<20|(hi?10u:0u)<<15|10u<<7|0x13u);
}
static void splat(struct builder *b,unsigned d,uint32_t v)
{ scalar(b,v);emit(b,vec(0x17,4,d,10,0)); }
/* x5 = original kernarg + four times this warp's local base (x8). Each
 * canonical array has 128 words. Address construction retains native
 * validator provenance, including the exact per-warp byte offset. */
static void address(struct builder *b,unsigned slice)
{
    emit(b,slice<<20|10u<<7|0x13u);
    emit(b,9u<<20|10u<<15|1u<<12|10u<<7|0x13u);
    emit(b,10u<<20|5u<<15|6u<<7|0x33u);
}
static void load(struct builder *b,unsigned d,unsigned slice)
{ address(b,slice);emit(b,1u<<25|6u<<15|6u<<12|d<<7|0x07u); }
static void store(struct builder *b,unsigned s,unsigned slice)
{ address(b,slice);emit(b,1u<<25|6u<<15|6u<<12|s<<7|0x27u); }

/* Parse only after all packet lengths and optional-field shapes were bounded.
 * In particular no indirect/dimension/texture/label token reader may run. */
static int packets(const struct tgsi_token *t,unsigned words)
{
    struct tgsi_header h;struct tgsi_processor p;unsigned at;
    if(!t||words<3||words>LIMIT+2)return -1;
    memcpy(&h,t,4);memcpy(&p,t+1,4);
    if(h.HeaderSize!=2||h.BodySize!=words-2||p.Processor!=PIPE_SHADER_FRAGMENT||p.Padding)return -1;
    for(at=2;at<words;){
        struct tgsi_token token;unsigned n;memcpy(&token,t+at,4);n=token.NrTokens;
        if(token.Type==TGSI_TOKEN_TYPE_DECLARATION){
            struct tgsi_declaration d;memcpy(&d,t+at,4);
            if(d.Dimension||d.Local||d.Array||d.Atomic||d.MemType||d.Padding||
               (d.File!=TGSI_FILE_TEMPORARY&&d.File!=TGSI_FILE_INPUT&&
                d.File!=TGSI_FILE_OUTPUT&&d.File!=TGSI_FILE_SYSTEM_VALUE)||
               n!=2u+d.Semantic+d.Interpolate||
               (d.Interpolate&&d.File!=TGSI_FILE_INPUT))return -1;
        }else if(token.Type==TGSI_TOKEN_TYPE_IMMEDIATE){
            struct tgsi_immediate i;memcpy(&i,t+at,4);n=i.NrTokens;
            if(n!=5||i.Padding||(i.DataType!=TGSI_IMM_FLOAT32&&i.DataType!=TGSI_IMM_UINT32&&i.DataType!=TGSI_IMM_INT32))return -1;
        }else if(token.Type==TGSI_TOKEN_TYPE_PROPERTY){
            struct tgsi_property p2;memcpy(&p2,t+at,4);if(p2.NrTokens!=2||p2.Padding)return -1;
        }else if(token.Type==TGSI_TOKEN_TYPE_INSTRUCTION){
            struct tgsi_instruction i;unsigned j;memcpy(&i,t+at,4);n=1u+i.NrTokens;
            if(i.Label||i.Texture||i.Memory||i.Precise||i.Saturate||i.Padding||
               i.NumDstRegs>1||i.NumSrcRegs>2||n!=1u+i.NumDstRegs+i.NumSrcRegs||n>words-at)return -1;
            for(j=0;j<i.NumDstRegs;j++){
                struct tgsi_dst_register d;memcpy(&d,t+at+1+j,4);
                if(d.Indirect||d.Dimension||d.Padding)return -1;
            }
            for(j=0;j<i.NumSrcRegs;j++){
                struct tgsi_src_register s;memcpy(&s,t+at+1+i.NumDstRegs+j,4);
                if(s.Indirect||s.Dimension||s.Absolute||s.Negate)return -1;
            }
        }else return -1;
        if(!n||n>words-at)return -1;
        at+=n;
    }
    return 0;
}
static int input_slice(struct builder *b,const struct semantic *s,unsigned c,unsigned *slice)
{
    unsigned sample=b->p.metadata.input_arrays-3;
    if(!s->declared||!(s->mask&(1u<<c)))return -1;
    switch(s->name){
    case TGSI_SEMANTIC_SAMPLEID:
        if(s->index||c)return -1;
        *slice=sample;b->p.metadata.used_sysvals|=OPENGPU_NATIVE_FS_SAMPLE_ID;break;
    case TGSI_SEMANTIC_SAMPLEPOS:
        if(s->index||c>1)return -1;
        *slice=sample+1+c;b->p.metadata.used_sysvals|=OPENGPU_NATIVE_FS_SAMPLE_POSITION;break;
    case TGSI_SEMANTIC_SAMPLEMASK:
        if(s->index||c)return -1;
        *slice=5;b->p.metadata.used_sysvals|=OPENGPU_NATIVE_FS_SAMPLE_MASK_IN;break;
    case TGSI_SEMANTIC_POSITION:
        if(s->index)return -1;
        *slice=c;b->p.metadata.used_sysvals|=OPENGPU_NATIVE_FS_FRAG_COORD;break;
    case TGSI_SEMANTIC_GENERIC:
        if(s->index>=16)return -1;
        *slice=6+4*s->index+c;b->p.metadata.used_sysvals|=OPENGPU_NATIVE_FS_VARYING;break;
    case TGSI_SEMANTIC_PRIMID:case TGSI_SEMANTIC_LAYER:
        if(b->p.metadata.abi_version!=7||s->index||c)return -1;
        *slice=72+(s->name==TGSI_SEMANTIC_LAYER);
        b->p.metadata.used_sysvals|=OPENGPU_NATIVE_FS_GEOMETRY;break;
    default:return -1;
    }
    return 0;
}
static int source(struct builder *b,const struct tgsi_full_src_register *s,unsigned component,unsigned scratch,unsigned *r)
{
    unsigned c=component==0?s->Register.SwizzleX:component==1?s->Register.SwizzleY:
        component==2?s->Register.SwizzleZ:s->Register.SwizzleW;
    unsigned i=(unsigned)s->Register.Index,slice;
    if(s->Register.Index<0||i>=REGS)return -1;
    switch(s->Register.File){
    case TGSI_FILE_TEMPORARY:
        if(!(b->defined[i]&(1u<<c)))return -1;
        *r=b->reg[i][c];return 0;
    case TGSI_FILE_IMMEDIATE:
        if(i>=b->nimm)return -1;
        splat(b,scratch,b->imm[i][c]);break;
    case TGSI_FILE_INPUT:case TGSI_FILE_SYSTEM_VALUE:
        if(input_slice(b,s->Register.File==TGSI_FILE_INPUT?b->input+i:b->system+i,c,&slice))return -1;
        load(b,scratch,slice);break;
    default:return -1;
    }
    *r=scratch;return 0;
}
static int instruction(struct builder *b,const struct tgsi_full_instruction *in)
{
    unsigned op=in->Instruction.Opcode,n=0,fn=0,f3=0,convert=99,c;
    const struct tgsi_dst_register *d=&in->Dst[0].Register;
    switch(op){
    case TGSI_OPCODE_MOV:n=1;break;
    case TGSI_OPCODE_U2F:n=1;convert=2;break;
    case TGSI_OPCODE_I2F:n=1;convert=3;break;
    case TGSI_OPCODE_F2U:n=1;convert=6;break; /* TGSI truncates toward zero. */
    case TGSI_OPCODE_F2I:n=1;convert=7;break;
    case TGSI_OPCODE_ADD:n=2;f3=1;break;
    case TGSI_OPCODE_MUL:n=2;fn=0x24;f3=1;break;
    case TGSI_OPCODE_UADD:n=2;break;
    case TGSI_OPCODE_AND:n=2;fn=9;break;
    case TGSI_OPCODE_OR:n=2;fn=10;break;
    case TGSI_OPCODE_XOR:n=2;fn=11;break;
    case TGSI_OPCODE_SHL:n=2;fn=0x25;break;
    case TGSI_OPCODE_USHR:n=2;fn=0x28;break;
    case TGSI_OPCODE_ISHR:n=2;fn=0x29;break;
    default:return -1;
    }
    if(in->Instruction.NumSrcRegs!=n||in->Instruction.NumDstRegs!=1||d->Index<0||
       (unsigned)d->Index>=REGS||!d->WriteMask)return -1;
    if(d->File==TGSI_FILE_TEMPORARY){
        if(d->WriteMask&~b->temp[d->Index])return -1;
    }else if(d->File==TGSI_FILE_OUTPUT){
        struct semantic *s=b->output+d->Index;
        if(!s->declared||(d->WriteMask&~s->mask)||
           (s->name==TGSI_SEMANTIC_COLOR?s->index>=4:
            s->name==TGSI_SEMANTIC_POSITION?(s->index||d->WriteMask!=4):
            s->name==TGSI_SEMANTIC_SAMPLEMASK?(s->index||d->WriteMask!=1):1))return -1;
    }else return -1;
    /* Snapshot all components before assigning any destination: swizzled
     * in-place MOV/ALU is parallel TGSI assignment, not scalar updates. */
    for(c=0;c<4;c++)if(d->WriteMask&(1u<<c)){
        unsigned a,z=0;
        if(source(b,in->Src,c,28,&a)||(n==2&&source(b,in->Src+1,c,29,&z)))return -1;
        if(op==TGSI_OPCODE_MOV)copy(b,24+c,a);
        else if(convert!=99)emit(b,vec(0x12,1,24+c,convert,a));
        else emit(b,vec(fn,f3,24+c,z,a));
    }
    for(c=0;c<4;c++)if(d->WriteMask&(1u<<c)){
        if(d->File==TGSI_FILE_TEMPORARY){
            unsigned *r=&b->reg[d->Index][c];
            if(!*r){if(b->next>23)return -1;*r=b->next++;}
            copy(b,*r,24+c);b->defined[d->Index]|=1u<<c;
        }else{
            struct semantic *s=b->output+d->Index;unsigned first=b->p.metadata.input_arrays;
            if(s->name==TGSI_SEMANTIC_COLOR){store(b,24+c,first+4*s->index+c);b->color_written[s->index]|=1u<<c;}
            else if(s->name==TGSI_SEMANTIC_POSITION){store(b,24+c,first+16);b->depth=1;}
            else{copy(b,30,24+c);b->sample_mask=1;}
        }
    }
    return b->failed?-1:0;
}
int opengpu_native_fs_emit(const void *tokens,unsigned token_words,
    const struct opengpu_native_fs_config *config,struct opengpu_native_fs_program *out)
{
    struct builder b={0};struct tgsi_parse_context parser;unsigned ended=0,result=-1;
    if(!config||!out||(config->abi_version!=6&&config->abi_version!=7)||
       config->thread_capacity!=128||config->varying_slots!=16||config->color_slots!=4||
       config->sample_transport!=1||config->geometry_transport>1||
       (config->abi_version==7&&!config->geometry_transport)||packets(tokens,token_words))return -1;
    b.next=2;b.p.metadata=(struct opengpu_native_fs_metadata){
        .abi_version=config->abi_version,.input_arrays=config->abi_version==6?75:77,
        .array_stride=512,.thread_capacity=128,.varying_slots=16,.color_slots=4,
        .requires_sample_owner=1,.requires_geometry_owner=config->abi_version==7};
    b.p.metadata.prefix_bytes=(b.p.metadata.input_arrays+18)*512;
    emit(&b,0x01007557u); /* full hardware vector length, active lanes remain masked */
    emit(&b,2u<<20|8u<<15|1u<<12|7u<<7|0x13u);
    emit(&b,7u<<20|1u<<15|5u<<7|0x33u);
    if(tgsi_parse_init(&parser,tokens)!=TGSI_PARSE_OK)return -1;
    while(!tgsi_parse_end_of_tokens(&parser)){
        tgsi_parse_token(&parser);if(ended)goto done;
        switch(parser.FullToken.Token.Type){
        case TGSI_TOKEN_TYPE_DECLARATION:{
            const struct tgsi_full_declaration *d=&parser.FullToken.FullDeclaration;
            unsigned first=d->Range.First,last=d->Range.Last;
            if(first>last||last>=REGS||!d->Declaration.UsageMask)goto done;
            for(unsigned i=first;i<=last;i++){
                if(d->Declaration.File==TGSI_FILE_TEMPORARY){
                    if(d->Declaration.Semantic||b.temp[i])goto done;
                    b.temp[i]=d->Declaration.UsageMask;
                }else{
                    struct semantic *s=d->Declaration.File==TGSI_FILE_INPUT?b.input+i:
                        d->Declaration.File==TGSI_FILE_SYSTEM_VALUE?b.system+i:b.output+i;
                    if(!d->Declaration.Semantic||s->declared)goto done;
                    *s=(struct semantic){1,d->Declaration.UsageMask,d->Semantic.Name,d->Semantic.Index+i-first};
                    unsigned dummy;
                    if(d->Declaration.File!=TGSI_FILE_OUTPUT){
                        /* Scalar system-value declarations may use the default
                         * xyzw usage mask; validate consumed swizzles separately. */
                        if(input_slice(&b,s,0,&dummy))goto done;
                        if(d->Declaration.File==TGSI_FILE_INPUT){
                            if((s->name!=TGSI_SEMANTIC_GENERIC&&s->name!=TGSI_SEMANTIC_POSITION)||
                               !d->Declaration.Interpolate||d->Interp.Padding||
                               d->Interp.Location!=TGSI_INTERPOLATE_LOC_CENTER||
                               d->Interp.Interpolate!=(s->name==TGSI_SEMANTIC_GENERIC?
                                   TGSI_INTERPOLATE_PERSPECTIVE:TGSI_INTERPOLATE_LINEAR))goto done;
                        }else if(s->name==TGSI_SEMANTIC_GENERIC||s->name==TGSI_SEMANTIC_POSITION)goto done;
                    }else if(s->name!=TGSI_SEMANTIC_COLOR&&s->name!=TGSI_SEMANTIC_POSITION&&s->name!=TGSI_SEMANTIC_SAMPLEMASK)goto done;
                }
            }break;
        }
        case TGSI_TOKEN_TYPE_IMMEDIATE:
            if(b.nimm==64)goto done;
            for(unsigned c=0;c<4;c++)b.imm[b.nimm][c]=parser.FullToken.FullImmediate.u[c].Uint;
            b.nimm++;break;
        case TGSI_TOKEN_TYPE_PROPERTY:
            /* Coordinate origin/center changes cannot silently affect native
             * sample position. Require the native lower-left/half-integer ABI. */
            if(parser.FullToken.FullProperty.Property.PropertyName==TGSI_PROPERTY_FS_COORD_ORIGIN){
                if(parser.FullToken.FullProperty.u[0].Data!=TGSI_FS_COORD_ORIGIN_LOWER_LEFT)goto done;
            }else if(parser.FullToken.FullProperty.Property.PropertyName==TGSI_PROPERTY_FS_COORD_PIXEL_CENTER){
                if(parser.FullToken.FullProperty.u[0].Data!=TGSI_FS_COORD_PIXEL_CENTER_HALF_INTEGER)goto done;
            }else goto done;
            break;
        case TGSI_TOKEN_TYPE_INSTRUCTION:{
            const struct tgsi_full_instruction *i=&parser.FullToken.FullInstruction;
            if(i->Instruction.Opcode==TGSI_OPCODE_END){
                if(i->Instruction.NumSrcRegs||i->Instruction.NumDstRegs)goto done;
                ended=1;
            }else if(instruction(&b,i))goto done;
            break;
        }
        default:goto done;
        }
    }
    if(!ended)goto done;
    for(unsigned i=0;i<4;i++){
        if(b.color_written[i]&&b.color_written[i]!=15)goto done;
        if(b.color_written[i])b.p.metadata.output_color_mask|=1u<<i;
    }
    b.p.metadata.writes_depth=b.depth;b.p.metadata.writes_sample_mask=b.sample_mask;
    unsigned control=1u|(b.depth<<1)|(b.p.metadata.output_color_mask<<2);
    if(b.sample_mask){
        emit(&b,vec(9,3,30,15,30)); /* low four sample bits */
        emit(&b,vec(0x25,3,30,7,30));
        splat(&b,31,control|64u);emit(&b,vec(10,0,31,30,31));
    }else splat(&b,31,control);
    store(&b,31,b.p.metadata.input_arrays+17);
    emit(&b,0x0330000fu);emit(&b,0x30500073u);
    if(!b.failed){*out=b.p;result=0;}
done:tgsi_parse_free(&parser);return (int)result;
}
static int le32(FILE *f,uint32_t v)
{ unsigned char a[4]={(unsigned char)v,(unsigned char)(v>>8),(unsigned char)(v>>16),(unsigned char)(v>>24)};return fwrite(a,1,4,f)==4?0:-1; }
int opengpu_native_fs_write(const char *path,const struct opengpu_native_fs_program *p)
{
    if(!path||!p||!p->words||p->words>512)return -1;
    FILE *f=fopen(path,"wx");if(!f)return -1;
    const struct opengpu_native_fs_metadata *m=&p->metadata;
    uint32_t fields[]={1,m->abi_version,m->input_arrays,m->array_stride,m->prefix_bytes,m->used_sysvals,
        m->output_color_mask,m->writes_depth,m->writes_sample_mask,m->requires_sample_owner,
        m->requires_geometry_owner,m->thread_capacity,m->varying_slots,m->color_slots,p->words};
    int error=fwrite("OGNSF001",1,8,f)!=8;
    for(unsigned i=0;i<15;i++)if(le32(f,fields[i]))error=1;
    for(unsigned i=0;i<p->words;i++)if(le32(f,p->code[i]))error=1;
    if(fclose(f))error=1;
    return error?-1:0;
}
