/* SPDX-License-Identifier: MIT */
/* Supplemental literal Mesa-builder/parser controls, not original ESSL or ISA
 * execution. Real linked ESSL cases use native_geometry_frontend_test.cpp. */
#include "opengpu_native_geometry_emit.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_build.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
struct tokens {struct tgsi_token t[4098];struct tgsi_header *h;};
#define APPEND(b,f,v) assert(f(&(v),(b)->t+(b)->h->HeaderSize+(b)->h->BodySize,(b)->h,4098-(b)->h->HeaderSize-(b)->h->BodySize))
static void property(struct tokens *b,unsigned name,unsigned value){struct tgsi_full_property p=tgsi_default_full_property();p.Property.NrTokens=2;p.Property.PropertyName=name;p.u[0].Data=value;APPEND(b,tgsi_build_full_property,p);}
static void declaration(struct tokens *b,unsigned file,unsigned index,unsigned semantic){
    struct tgsi_full_declaration d=tgsi_default_full_declaration();d.Declaration.File=file;d.Range.First=d.Range.Last=index;
    if(file==TGSI_FILE_INPUT||file==TGSI_FILE_OUTPUT){d.Declaration.Semantic=1;d.Semantic.Name=semantic;}
    if(file==TGSI_FILE_CONSTANT){d.Declaration.Dimension=1;d.Dim.Index2D=0;d.Range.First=0;d.Range.Last=255;}
    APPEND(b,tgsi_build_full_declaration,d);
}
static void instruction(struct tokens *b,unsigned op){struct tgsi_full_instruction i=tgsi_default_full_instruction();i.Instruction.Opcode=op;i.Instruction.NumDstRegs=i.Instruction.NumSrcRegs=0;APPEND(b,tgsi_build_full_instruction,i);}
static void build(struct tokens *b,unsigned primitive,unsigned invocations,unsigned max_vertices,uint32_t index,unsigned base){
    memset(b,0,sizeof(*b));b->h=(void *)b->t;*b->h=tgsi_build_header();struct tgsi_processor p=tgsi_build_processor(PIPE_SHADER_GEOMETRY,b->h);memcpy(b->t+1,&p,4);
    property(b,TGSI_PROPERTY_GS_INPUT_PRIM,primitive);property(b,TGSI_PROPERTY_GS_OUTPUT_PRIM,PIPE_PRIM_POINTS);
    property(b,TGSI_PROPERTY_GS_MAX_OUTPUT_VERTICES,max_vertices);property(b,TGSI_PROPERTY_GS_INVOCATIONS,invocations);
    declaration(b,TGSI_FILE_INPUT,0,TGSI_SEMANTIC_POSITION);declaration(b,TGSI_FILE_OUTPUT,0,TGSI_SEMANTIC_POSITION);declaration(b,TGSI_FILE_ADDRESS,0,0);
    struct tgsi_full_immediate v=tgsi_default_full_immediate();v.Immediate.DataType=TGSI_IMM_UINT32;v.Immediate.NrTokens=5;
    v.u[0].Uint=index;APPEND(b,tgsi_build_full_immediate,v);
    struct tgsi_full_instruction i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_UARL;i.Instruction.NumSrcRegs=i.Instruction.NumDstRegs=1;
    i.Dst[0].Register.File=TGSI_FILE_ADDRESS;i.Dst[0].Register.WriteMask=1;
    i.Src[0].Register.File=TGSI_FILE_IMMEDIATE;i.Src[0].Register.SwizzleX=0;APPEND(b,tgsi_build_full_instruction,i);
    i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_MOV;i.Instruction.NumSrcRegs=i.Instruction.NumDstRegs=1;
    i.Dst[0].Register.File=TGSI_FILE_OUTPUT;i.Dst[0].Register.WriteMask=15;
    i.Src[0].Register.File=TGSI_FILE_INPUT;i.Src[0].Register.Dimension=1;i.Src[0].Dimension.Index=base;i.Src[0].Dimension.Indirect=1;i.Src[0].DimIndirect.File=TGSI_FILE_ADDRESS;
    APPEND(b,tgsi_build_full_instruction,i);
    v=tgsi_default_full_immediate(); /* stream comes from original literal index0 */
    for(unsigned op=0;op<2;op++){
        i=tgsi_default_full_instruction();i.Instruction.Opcode=op?TGSI_OPCODE_ENDPRIM:TGSI_OPCODE_EMIT;i.Instruction.NumSrcRegs=1;i.Instruction.NumDstRegs=0;
        /* EMIT stream must be literal zero, separate from the index immediate. */
        i.Src[0].Register.File=TGSI_FILE_IMMEDIATE;i.Src[0].Register.SwizzleX=1;APPEND(b,tgsi_build_full_instruction,i);
    }
    instruction(b,TGSI_OPCODE_END);
}
/* Runtime branches must not borrow an ADDR definition from their sibling.
 * Conversely both-arm definitions dominate a continuation without any
 * software restoration of physical scalar registers. */
static void runtime_branch(struct tokens *b,unsigned else_defines){
    build(b,PIPE_PRIM_LINES,1,2,0,0);
    unsigned at=b->h->HeaderSize;
    while(b->t[at].Type!=TGSI_TOKEN_TYPE_INSTRUCTION){
        unsigned size=b->t[at].NrTokens;
        if(b->t[at].Type==TGSI_TOKEN_TYPE_IMMEDIATE){struct tgsi_immediate m;memcpy(&m,b->t+at,4);size=m.NrTokens;}
        at+=size;
    }
    b->h->BodySize=at-b->h->HeaderSize;
    declaration(b,TGSI_FILE_CONSTANT,0,0);
    struct tgsi_full_instruction i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_UIF;i.Instruction.NumSrcRegs=1;i.Instruction.NumDstRegs=0;
    i.Src[0].Register.File=TGSI_FILE_CONSTANT;i.Src[0].Register.Dimension=1;i.Src[0].Dimension.Index=0;i.Src[0].Register.SwizzleX=0;APPEND(b,tgsi_build_full_instruction,i);
    i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_UARL;i.Instruction.NumSrcRegs=i.Instruction.NumDstRegs=1;
    i.Dst[0].Register.File=TGSI_FILE_ADDRESS;i.Dst[0].Register.WriteMask=1;i.Src[0].Register.File=TGSI_FILE_IMMEDIATE;i.Src[0].Register.SwizzleX=0;
    APPEND(b,tgsi_build_full_instruction,i);instruction(b,TGSI_OPCODE_ELSE);
    if(else_defines)APPEND(b,tgsi_build_full_instruction,i);
    i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_MOV;i.Instruction.NumSrcRegs=i.Instruction.NumDstRegs=1;
    i.Dst[0].Register.File=TGSI_FILE_OUTPUT;i.Dst[0].Register.WriteMask=15;i.Src[0].Register.File=TGSI_FILE_INPUT;
    i.Src[0].Register.Dimension=1;i.Src[0].Dimension.Indirect=1;i.Src[0].DimIndirect.File=TGSI_FILE_ADDRESS;APPEND(b,tgsi_build_full_instruction,i);
    instruction(b,TGSI_OPCODE_ENDIF);
    /* This continuation also requires the intersection of both arms. */
    APPEND(b,tgsi_build_full_instruction,i);
    i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_EMIT;i.Instruction.NumSrcRegs=1;i.Instruction.NumDstRegs=0;
    i.Src[0].Register.File=TGSI_FILE_IMMEDIATE;i.Src[0].Register.SwizzleX=1;APPEND(b,tgsi_build_full_instruction,i);
    instruction(b,TGSI_OPCODE_END);
}
static struct opengpu_native_tess_input_map map(unsigned input){return (struct opengpu_native_tess_input_map){.version=1,.count=1,.spans={{0,1,input,TGSI_SEMANTIC_POSITION,0,15,0}}};}
static void reject_named(struct tokens *b,unsigned n,struct opengpu_native_tess_input_map in,struct opengpu_native_tess_input_map out,const char *name){
    struct opengpu_native_geometry_program p,old;memset(&p,0xa5,sizeof(p));old=p;
    int result=opengpu_native_geometry_compile(b->t,n,&in,&out,&p);
    if(!result)fprintf(stderr,"GS_UNEXPECTED_ACCEPT %s\n",name);
    assert(result);assert(!memcmp(&p,&old,sizeof(p)));
}
static void reject(struct tokens *b,unsigned n,struct opengpu_native_tess_input_map in,struct opengpu_native_tess_input_map out){
    reject_named(b,n,in,out,"other-rejection");
}
int main(void){
    struct tokens b;struct opengpu_native_tess_input_map in=map(1),out=map(0);struct opengpu_native_geometry_program p;
    const unsigned modes[]={PIPE_PRIM_POINTS,PIPE_PRIM_LINES,PIPE_PRIM_TRIANGLES,PIPE_PRIM_LINES_ADJACENCY,PIPE_PRIM_TRIANGLES_ADJACENCY};
    const unsigned counts[]={1,2,3,4,6};
    for(unsigned k=0;k<5;k++)for(unsigned invocation=1;invocation<=32;invocation+=31){
        build(&b,modes[k],invocation,256,0,0);assert(!opengpu_native_geometry_compile(b.t,b.h->HeaderSize+b.h->BodySize,&in,&out,&p));
        assert(p.layout.input_vertices==counts[k]&&p.layout.invocations==invocation&&p.layout.output_components==4&&p.words<=512);
    }
    for(unsigned index=0;index<5;index++){
        const uint32_t values[]={0,1,5,0x80000000u,0xffffffffu};build(&b,PIPE_PRIM_TRIANGLES_ADJACENCY,32,256,values[index],0);
        assert(!opengpu_native_geometry_compile(b.t,b.h->HeaderSize+b.h->BodySize,&in,&out,&p));
        unsigned guards=0;for(unsigned i=0;i<p.words;i++)if((p.code[i]&0x707fu)==0x7063u)guards++;
        assert(guards==4); /* real full-width BGEU before all four input reads */
    }
    build(&b,PIPE_PRIM_LINES,1,2,0,0);unsigned n=b.h->HeaderSize+b.h->BodySize;
    for(unsigned len=0;len<n;len++)reject(&b,len,in,out);
    reject(&b,n+1,in,out);
    struct tgsi_processor *processor=(void *)(b.t+1);processor->Processor=PIPE_SHADER_VERTEX;reject(&b,n,in,out);processor->Processor=PIPE_SHADER_GEOMETRY;
    in.version=2;reject(&b,n,in,out);in=map(1);out.version=0;reject(&b,n,in,out);out=map(0);
    in.spans[0].per_vertex=0;reject(&b,n,in,out);in=map(1);in.spans[0].mask=7;reject(&b,n,in,out);in=map(1);
    build(&b,PIPE_PRIM_LINES,33,2,0,0);reject(&b,b.h->HeaderSize+b.h->BodySize,in,out);
    build(&b,PIPE_PRIM_LINES,1,257,0,0);reject(&b,b.h->HeaderSize+b.h->BodySize,in,out);
    build(&b,PIPE_PRIM_QUADS,1,2,0,0);reject(&b,b.h->HeaderSize+b.h->BodySize,in,out);
    build(&b,PIPE_PRIM_LINES,1,2,0,2);reject(&b,b.h->HeaderSize+b.h->BodySize,in,out);
    build(&b,PIPE_PRIM_LINES,1,2,0,0);n=b.h->HeaderSize+b.h->BodySize;
    in.spans[1].mask=1;reject_named(&b,n,in,out,"unused-span-canonical");in=map(1);
    in.spans[0].first=1;reject(&b,n,in,out);in=map(1);
    runtime_branch(&b,0);reject_named(&b,b.h->HeaderSize+b.h->BodySize,in,out,"sibling-addr-definition");
    runtime_branch(&b,1);assert(!opengpu_native_geometry_compile(b.t,b.h->HeaderSize+b.h->BodySize,&in,&out,&p));
    puts("scalar GS Mesa parser/emitter guards PASS (literal tokens, CPU only)");
}
