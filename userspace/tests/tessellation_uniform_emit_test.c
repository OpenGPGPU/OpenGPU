/* SPDX-License-Identifier: MIT */
/* Supplemental parser/admission tests with actual Mesa builders/parser. These
 * hand-built tokens are NOT original ESSL or GPU execution evidence. */
#include "opengpu_native_tess_uniform_emit.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_build.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
struct builder {struct tgsi_token t[1024];struct tgsi_header *h;};
static unsigned io_copy;
#define APPEND(b,f,v) assert(f(&(v),(b)->t+(b)->h->HeaderSize+(b)->h->BodySize,(b)->h,1024-(b)->h->HeaderSize-(b)->h->BodySize))
static void property(struct builder *b,unsigned name,unsigned value)
{
    struct tgsi_full_property p=tgsi_default_full_property();p.Property.NrTokens=2;p.Property.PropertyName=name;p.u[0].Data=value;APPEND(b,tgsi_build_full_property,p);
}
static void declaration(struct builder *b,unsigned file,unsigned index,unsigned semantic,unsigned slot)
{
    struct tgsi_full_declaration d=tgsi_default_full_declaration();d.Declaration.File=file;d.Range.First=d.Range.Last=index;
    if(file==TGSI_FILE_CONSTANT){d.Declaration.Dimension=1;d.Dim.Index2D=slot;d.Range.First=0;}
    else {d.Declaration.Semantic=1;d.Semantic.Name=semantic;if(semantic==TGSI_SEMANTIC_GENERIC)d.Semantic.Index=15;}
    APPEND(b,tgsi_build_full_declaration,d);
}
static void build(struct builder *b,unsigned stage,unsigned slot,unsigned vec4)
{
    memset(b,0,sizeof(*b));b->h=(void *)b->t;*b->h=tgsi_build_header();
    struct tgsi_processor p=tgsi_build_processor(stage==4?PIPE_SHADER_TESS_CTRL:PIPE_SHADER_TESS_EVAL,b->h);memcpy(b->t+1,&p,4);
    if(stage==4)property(b,TGSI_PROPERTY_TCS_VERTICES_OUT,32);
    else {
        property(b,TGSI_PROPERTY_TES_PRIM_MODE,1);property(b,TGSI_PROPERTY_TES_SPACING,2);
        property(b,TGSI_PROPERTY_TES_VERTEX_ORDER_CW,0);property(b,TGSI_PROPERTY_TES_POINT_MODE,0);property(b,TGSI_PROPERTY_NEXT_SHADER,PIPE_SHADER_FRAGMENT);
    }
    declaration(b,TGSI_FILE_OUTPUT,0,io_copy&&stage==4?TGSI_SEMANTIC_GENERIC:TGSI_SEMANTIC_POSITION,0);
    declaration(b,TGSI_FILE_INPUT,0,TGSI_SEMANTIC_GENERIC,0);
    declaration(b,TGSI_FILE_CONSTANT,vec4,0,slot);
    struct tgsi_full_instruction i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_MOV;i.Instruction.NumDstRegs=i.Instruction.NumSrcRegs=1;
    i.Dst[0].Register.File=TGSI_FILE_OUTPUT;i.Dst[0].Register.WriteMask=15;i.Dst[0].Register.Dimension=stage==4;
    i.Src[0].Register.File=io_copy?TGSI_FILE_INPUT:TGSI_FILE_CONSTANT;i.Src[0].Register.Index=io_copy?0:vec4;i.Src[0].Register.Dimension=1;i.Src[0].Dimension.Index=io_copy?0:slot;
    i.Src[0].Register.SwizzleX=i.Src[0].Register.SwizzleY=i.Src[0].Register.SwizzleZ=i.Src[0].Register.SwizzleW=3;
    APPEND(b,tgsi_build_full_instruction,i);
    i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_END;i.Instruction.NumDstRegs=i.Instruction.NumSrcRegs=0;APPEND(b,tgsi_build_full_instruction,i);
}
static void rejected(struct builder *b,unsigned n,struct opengpu_native_tess_layout l)
{
    struct opengpu_native_tess_program out,saved;memset(&out,0xa5,sizeof(out));saved=out;
    assert(opengpu_native_tess_uniform_emit(b->t,n,&l,&out));assert(!memcmp(&out,&saved,sizeof(out)));
}
static void old_vs_isolation(void)
{
    struct builder b={0};b.h=(void *)b.t;*b.h=tgsi_build_header();
    struct tgsi_processor p=tgsi_build_processor(PIPE_SHADER_VERTEX,b.h);memcpy(b.t+1,&p,4);
    property(&b,TGSI_PROPERTY_NEXT_SHADER,PIPE_SHADER_FRAGMENT);
    declaration(&b,TGSI_FILE_OUTPUT,0,TGSI_SEMANTIC_POSITION,0);
    declaration(&b,TGSI_FILE_CONSTANT,255,0,0);
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=TGSI_OPCODE_MOV;i.Instruction.NumDstRegs=i.Instruction.NumSrcRegs=1;
    i.Dst[0].Register.File=TGSI_FILE_OUTPUT;i.Dst[0].Register.WriteMask=15;
    i.Src[0].Register.File=TGSI_FILE_CONSTANT;i.Src[0].Register.Dimension=1;i.Src[0].Register.Index=255;
    APPEND(&b,tgsi_build_full_instruction,i);
    i=tgsi_default_full_instruction();i.Instruction.Opcode=TGSI_OPCODE_END;i.Instruction.NumDstRegs=i.Instruction.NumSrcRegs=0;APPEND(&b,tgsi_build_full_instruction,i);
    struct opengpu_native_uniform_program old,next,sentinel;struct opengpu_native_uniform_layout l,ln;
    assert(!opengpu_native_uniform_compile(b.t,0,128,&l,&old));
    memset(&sentinel,0xa5,sizeof(sentinel));next=sentinel;
    assert(opengpu_native_vertex_tess_compile(b.t,128,&ln,&next));assert(!memcmp(&next,&sentinel,sizeof(next)));
    uint32_t value=PIPE_SHADER_TESS_CTRL;memcpy(b.t+3,&value,4);
    assert(opengpu_native_uniform_compile(b.t,0,128,&ln,&next));assert(!memcmp(&next,&sentinel,sizeof(next)));
    assert(!opengpu_native_vertex_tess_compile(b.t,128,&ln,&next));
    assert(!memcmp(&l,&ln,sizeof(l))&&!memcmp(&old,&next,sizeof(old)));
}
int main(void)
{
    old_vs_isolation();
    struct builder b;struct opengpu_native_tess_program out;
    for(unsigned stage=4;stage<=5;stage++){
        struct opengpu_native_tess_layout l={.version=1,.stage=stage,.capacity=128,.input_vertices=32,.output_vertices=32,
            .input_components=64,.output_components=64,.output_position=1,.domain=1,.spacing=2};
        build(&b,stage,12,1023);unsigned n=b.h->HeaderSize+b.h->BodySize;
        assert(!opengpu_native_tess_uniform_emit(b.t,n,&l,&out));assert(out.constant_bytes[12]==16384&&out.words<=512);
        for(unsigned truncated=0;truncated<n;truncated++)rejected(&b,truncated,l);
        l.stage=stage==4?5:4;rejected(&b,n,l);l.stage=stage;
        l.version=2;rejected(&b,n,l);l.version=1;
        l.patch_components=120;rejected(&b,n,l);l.patch_components=0;
        l.input_components=65;rejected(&b,n,l);l.input_components=64;
        build(&b,stage,13,1023);rejected(&b,b.h->HeaderSize+b.h->BodySize,l);
        build(&b,stage,12,1024);rejected(&b,b.h->HeaderSize+b.h->BodySize,l);
        build(&b,stage,0,256);rejected(&b,b.h->HeaderSize+b.h->BodySize,l);
        build(&b,stage,0,255);n=b.h->HeaderSize+b.h->BodySize;
        assert(!opengpu_native_tess_uniform_emit(b.t,n,&l,&out));assert(out.constant_bytes[0]==4096);
        io_copy=1;l.input_position=0;l.output_position=stage==5;
        build(&b,stage,0,255);n=b.h->HeaderSize+b.h->BodySize;
        assert(!opengpu_native_tess_uniform_emit(b.t,n,&l,&out));
        if(stage==5){assert(out.selection_count==1&&out.selections[0].component==63);}
        else assert(out.output_mask==((uint64_t)15<<60));
        io_copy=0;l.output_position=1;
        build(&b,stage,0,255);n=b.h->HeaderSize+b.h->BodySize;
        /* Corrupt every actual packet length, including the fixed header. */
        struct builder original=b;original.h=(void *)original.t;
        for(unsigned at=2;at<n;){struct tgsi_token token;memcpy(&token,b.t+at,4);unsigned size=token.NrTokens;
            if(token.Type==TGSI_TOKEN_TYPE_INSTRUCTION)size++;
            b.t[at].NrTokens=token.NrTokens?0:255;rejected(&b,n,l);b=original;b.h=(void *)b.t;at+=size;
        }
    }
    puts("Tess emitter strict stage/version/packet/12block/default1024/16KiB/2048 capacity CPU guards PASS (no ESSL/GPU claim)");
}
