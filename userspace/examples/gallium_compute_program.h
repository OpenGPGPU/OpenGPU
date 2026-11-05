/* SPDX-License-Identifier: MIT */
/* Shared TGSI input and ordinary uint32 oracle for the explicit Linux smoke.
 * There is no native instruction interpreter in the guest application. */
#ifndef OPENGPU_GALLIUM_COMPUTE_PROGRAM_H
#define OPENGPU_GALLIUM_COMPUTE_PROGRAM_H

#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "pipe/p_defines.h"
#include "tgsi/tgsi_build.h"

#define GC_VIEW_BYTES UINT32_C(134217728)
#define GC_PREFIX_BYTES 8192u
#define GC_TAIL_BYTES 64u
#define GC_GUARD UINT32_C(0xdeadface)
#define GC_TOKEN_CAPACITY 2048u

struct gc_program {
    struct tgsi_token tokens[GC_TOKEN_CAPACITY];
    struct tgsi_header *header;
};

static inline struct tgsi_src_register gc_src(unsigned file,int index,unsigned component)
{
    struct tgsi_src_register s={0};
    s.File=file;s.Index=index;s.SwizzleX=s.SwizzleY=s.SwizzleZ=s.SwizzleW=component;
    return s;
}
#define GC_T(i,c) gc_src(TGSI_FILE_TEMPORARY,i,c)
#define GC_S(i,c) gc_src(TGSI_FILE_SYSTEM_VALUE,i,c)
#define GC_I(i,c) gc_src(TGSI_FILE_IMMEDIATE,i,c)

static inline void gc_decl(struct gc_program *p,unsigned file,unsigned first,unsigned last,int semantic)
{
    struct tgsi_full_declaration d=tgsi_default_full_declaration();
    d.Declaration.File=file;d.Range.First=first;d.Range.Last=last;
    if(semantic>=0){d.Declaration.Semantic=1;d.Declaration.UsageMask=7;d.Semantic.Name=(unsigned)semantic;}
    unsigned at=p->header->HeaderSize+p->header->BodySize;
    assert(tgsi_build_full_declaration(&d,p->tokens+at,p->header,GC_TOKEN_CAPACITY-at));
}

static inline void gc_imm(struct gc_program *p,uint32_t a,uint32_t b,uint32_t c,uint32_t d)
{
    struct tgsi_full_immediate i=tgsi_default_full_immediate();
    i.Immediate.NrTokens=5;i.Immediate.DataType=TGSI_IMM_UINT32;
    i.u[0].Uint=a;i.u[1].Uint=b;i.u[2].Uint=c;i.u[3].Uint=d;
    unsigned at=p->header->HeaderSize+p->header->BodySize;
    assert(tgsi_build_full_immediate(&i,p->tokens+at,p->header,GC_TOKEN_CAPACITY-at));
}

static inline void gc_op(struct gc_program *p,unsigned op,unsigned file,unsigned index,
                         struct tgsi_src_register a,struct tgsi_src_register b)
{
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=op;
    if(op!=TGSI_OPCODE_END){
        i.Instruction.NumDstRegs=1;i.Instruction.NumSrcRegs=op==TGSI_OPCODE_MOV?1:2;
        i.Dst[0].Register.File=file;i.Dst[0].Register.Index=(int)index;i.Dst[0].Register.WriteMask=1;
        i.Src[0].Register=a;i.Src[1].Register=b;
    }else{i.Instruction.NumDstRegs=i.Instruction.NumSrcRegs=0;}
    if(op==TGSI_OPCODE_LOAD || op==TGSI_OPCODE_STORE){i.Instruction.Memory=1;i.Memory.Texture=TGSI_TEXTURE_BUFFER;}
    unsigned at=p->header->HeaderSize+p->header->BodySize;
    assert(tgsi_build_full_instruction(&i,p->tokens+at,p->header,GC_TOKEN_CAPACITY-at));
}

static inline void gc_select(struct gc_program *p,unsigned index,struct tgsi_src_register condition,
                             struct tgsi_src_register yes,struct tgsi_src_register no)
{
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=TGSI_OPCODE_UCMP;i.Instruction.NumDstRegs=1;i.Instruction.NumSrcRegs=3;
    i.Dst[0].Register.File=TGSI_FILE_TEMPORARY;i.Dst[0].Register.Index=(int)index;i.Dst[0].Register.WriteMask=1;
    i.Src[0].Register=condition;i.Src[1].Register=yes;i.Src[2].Register=no;
    unsigned at=p->header->HeaderSize+p->header->BodySize;
    assert(tgsi_build_full_instruction(&i,p->tokens+at,p->header,GC_TOKEN_CAPACITY-at));
}

static inline void gc_build(struct gc_program *p,uint32_t seed,int consumer)
{
    memset(p,0,sizeof(*p));p->header=(struct tgsi_header *)p->tokens;*p->header=tgsi_build_header();
    struct tgsi_processor processor=tgsi_build_processor(PIPE_SHADER_COMPUTE,p->header);
    memcpy(p->tokens+1,&processor,sizeof(processor));
    gc_decl(p,TGSI_FILE_TEMPORARY,0,7,-1);gc_decl(p,TGSI_FILE_BUFFER,0,3,-1);
    gc_decl(p,TGSI_FILE_SYSTEM_VALUE,0,0,TGSI_SEMANTIC_THREAD_ID);
    gc_decl(p,TGSI_FILE_SYSTEM_VALUE,1,1,TGSI_SEMANTIC_BLOCK_ID);
    gc_decl(p,TGSI_FILE_SYSTEM_VALUE,2,2,TGSI_SEMANTIC_BLOCK_SIZE);
    gc_decl(p,TGSI_FILE_SYSTEM_VALUE,3,3,TGSI_SEMANTIC_GRID_SIZE);
    gc_imm(p,2,seed,UINT32_MAX,GC_VIEW_BYTES-4);
    gc_imm(p,1,0,0x80000000u,128);
    gc_imm(p,0xdeadbeefu,0x13579bdfu,0x2468ace0u,0);
    /* t0=global linear item; t2=items in the current group. */
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,0,GC_S(0,2),GC_S(2,1));
    gc_op(p,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,GC_T(0,0),GC_S(0,1));
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,0,GC_T(0,0),GC_S(2,0));
    gc_op(p,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,GC_T(0,0),GC_S(0,0));
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,1,GC_S(1,2),GC_S(3,1));
    gc_op(p,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,1,GC_T(1,0),GC_S(1,1));
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,1,GC_T(1,0),GC_S(3,0));
    gc_op(p,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,1,GC_T(1,0),GC_S(1,0));
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,2,GC_S(2,0),GC_S(2,1));
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,2,GC_T(2,0),GC_S(2,2));
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,1,GC_T(1,0),GC_T(2,0));
    gc_op(p,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,GC_T(0,0),GC_T(1,0));
    /* The final item really accesses each view's last word. */
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,3,GC_S(3,0),GC_S(3,1));
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,3,GC_T(3,0),GC_S(3,2));
    gc_op(p,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,3,GC_T(3,0),GC_T(2,0));
    gc_op(p,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,3,GC_T(3,0),GC_I(0,2));
    gc_op(p,TGSI_OPCODE_USEQ,TGSI_FILE_TEMPORARY,4,GC_T(0,0),GC_T(3,0));
    gc_op(p,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,1,GC_T(0,0),GC_I(0,0));
    gc_select(p,1,GC_T(4,0),GC_I(0,3),GC_T(1,0));
    if(!consumer){
        gc_op(p,TGSI_OPCODE_AND,TGSI_FILE_TEMPORARY,4,GC_T(0,0),GC_I(1,0));
        gc_select(p,4,GC_T(4,0),GC_I(1,2),GC_I(1,1));
        gc_op(p,TGSI_OPCODE_OR,TGSI_FILE_TEMPORARY,4,GC_T(0,0),GC_T(4,0));
        gc_op(p,TGSI_OPCODE_XOR,TGSI_FILE_TEMPORARY,4,GC_T(4,0),GC_I(0,1));
        gc_op(p,TGSI_OPCODE_USEQ,TGSI_FILE_TEMPORARY,5,GC_T(0,0),GC_I(1,1));
        gc_select(p,4,GC_T(5,0),GC_I(1,1),GC_T(4,0));
        gc_op(p,TGSI_OPCODE_USEQ,TGSI_FILE_TEMPORARY,5,GC_T(0,0),GC_I(1,0));
        gc_select(p,4,GC_T(5,0),GC_I(0,2),GC_T(4,0));
        gc_op(p,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,0,GC_T(1,0),GC_T(4,0));
    }else{
        gc_op(p,TGSI_OPCODE_LOAD,TGSI_FILE_TEMPORARY,4,gc_src(TGSI_FILE_BUFFER,0,0),GC_T(1,0));
        gc_op(p,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,5,GC_T(4,0),GC_I(0,1));
        gc_op(p,TGSI_OPCODE_USEQ,TGSI_FILE_TEMPORARY,6,GC_T(4,0),GC_I(1,1));
        gc_select(p,5,GC_T(6,0),GC_I(2,0),GC_T(5,0));
        gc_op(p,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,1,GC_T(1,0),GC_T(5,0));
        gc_op(p,TGSI_OPCODE_USNE,TGSI_FILE_TEMPORARY,5,GC_T(4,0),GC_I(1,1));
        gc_op(p,TGSI_OPCODE_USLT,TGSI_FILE_TEMPORARY,6,GC_T(4,0),GC_I(1,3));
        gc_op(p,TGSI_OPCODE_AND,TGSI_FILE_TEMPORARY,6,GC_T(6,0),GC_I(2,1));
        gc_op(p,TGSI_OPCODE_XOR,TGSI_FILE_TEMPORARY,5,GC_T(5,0),GC_T(6,0));
        gc_op(p,TGSI_OPCODE_USGE,TGSI_FILE_TEMPORARY,6,GC_T(4,0),GC_I(1,2));
        gc_op(p,TGSI_OPCODE_AND,TGSI_FILE_TEMPORARY,6,GC_T(6,0),GC_I(2,2));
        gc_op(p,TGSI_OPCODE_XOR,TGSI_FILE_TEMPORARY,5,GC_T(5,0),GC_T(6,0));
        gc_op(p,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,2,GC_T(1,0),GC_T(5,0));
        gc_op(p,TGSI_OPCODE_XOR,TGSI_FILE_TEMPORARY,5,GC_T(4,0),GC_I(0,1));
        gc_select(p,5,GC_T(4,0),GC_T(5,0),GC_T(0,0));
        gc_op(p,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,3,GC_T(1,0),GC_T(5,0));
    }
    gc_op(p,TGSI_OPCODE_END,0,0,GC_I(0,0),GC_I(0,0));
}

static inline uint32_t gc_produced(unsigned item,uint32_t seed)
{
    if(!item)return 0;
    if(item==1)return UINT32_MAX;
    return (item|((item&1)?UINT32_C(0x80000000):0))^seed;
}

static inline uint32_t gc_expected(unsigned binding,unsigned item,uint32_t seed)
{
    uint32_t v=gc_produced(item,seed);
    if(binding==0)return v;
    if(binding==1)return v?v+seed:UINT32_C(0xdeadbeef);
    if(binding==2)return (v?UINT32_MAX:0)^(v<128?UINT32_C(0x13579bdf):0)^
        (v>=UINT32_C(0x80000000)?UINT32_C(0x2468ace0):0);
    assert(binding==3);return v?v^seed:item;
}

static inline uint32_t gc_offset(unsigned item,unsigned total)
{assert(total && item<total);return item==total-1?GC_VIEW_BYTES-4:4*item;}

#endif
