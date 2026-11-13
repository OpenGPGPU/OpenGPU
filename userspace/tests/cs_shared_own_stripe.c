/* SPDX-License-Identifier: MIT */
/* Real Mesa TGSI builders/parser and production emitter/validator, CPU only. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "pipe/p_defines.h"
#include "tgsi/tgsi_build.h"
#include "opengpu_cs_emit.h"
#include "opengpu_shader_validator.h"

struct builder { struct tgsi_token tokens[2048]; struct tgsi_header *header; };

#define APPEND(b, function, value) \
    assert(function(&(value), (b)->tokens+(b)->header->HeaderSize+(b)->header->BodySize, \
                    (b)->header, 2048-(b)->header->HeaderSize-(b)->header->BodySize))

static struct tgsi_src_register source(unsigned file, unsigned index, unsigned component)
{
    struct tgsi_src_register s={0};
    s.File=file;s.Index=index;s.SwizzleX=s.SwizzleY=s.SwizzleZ=s.SwizzleW=component;
    return s;
}

static void instruction(struct builder *b, unsigned op, unsigned file, unsigned index,
                        struct tgsi_src_register a, struct tgsi_src_register z)
{
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=op;
    i.Instruction.NumDstRegs=i.Instruction.NumSrcRegs=0;
    if(op!=TGSI_OPCODE_END && op!=TGSI_OPCODE_BARRIER) {
        i.Instruction.NumDstRegs=1;i.Instruction.NumSrcRegs=2;
        i.Dst[0].Register.File=file;i.Dst[0].Register.Index=index;i.Dst[0].Register.WriteMask=1;
        i.Src[0].Register=a;i.Src[1].Register=z;
    }
    if(op==TGSI_OPCODE_LOAD || op==TGSI_OPCODE_STORE) {
        i.Instruction.Memory=1;i.Memory.Texture=TGSI_TEXTURE_BUFFER;
    }
    APPEND(b,tgsi_build_full_instruction,i);
}

static void program(struct builder *b, unsigned offset, unsigned kind)
{
    memset(b,0,sizeof(*b));b->header=(struct tgsi_header *)b->tokens;
    *b->header=tgsi_build_header();
    struct tgsi_processor processor=tgsi_build_processor(PIPE_SHADER_COMPUTE,b->header);
    memcpy(b->tokens+1,&processor,4);
    for(unsigned axis=0;axis<3;axis++) {
        struct tgsi_full_property p=tgsi_default_full_property();
        p.Property.PropertyName=TGSI_PROPERTY_CS_FIXED_BLOCK_WIDTH+axis;
        p.Property.NrTokens=2;
        p.u[0].Data=axis?1:128;APPEND(b,tgsi_build_full_property,p);
    }
    for(unsigned type=0;type<3;type++) {
        struct tgsi_full_declaration d=tgsi_default_full_declaration();
        d.Declaration.File=type==0?TGSI_FILE_MEMORY:type==1?TGSI_FILE_SYSTEM_VALUE:TGSI_FILE_TEMPORARY;
        d.Declaration.UsageMask=1;
        if(type==0) {
            d.Declaration.MemType=TGSI_MEMORY_TYPE_SHARED;
            d.Range.First=d.Range.Last=TGSI_MEMORY_TYPE_SHARED;
        } else if(type==1) {
            d.Declaration.Semantic=1;d.Semantic.Name=TGSI_SEMANTIC_THREAD_ID;
        } else d.Range.Last=1;
        APPEND(b,tgsi_build_full_declaration,d);
    }
    struct tgsi_full_immediate imm=tgsi_default_full_immediate();
    imm.Immediate.NrTokens=5;imm.Immediate.DataType=TGSI_IMM_UINT32;
    imm.u[0].Uint=2;imm.u[1].Uint=offset;imm.u[2].Uint=0xabc;imm.u[3].Uint=kind==6?(offset?0:516):kind==7?512:16;
    APPEND(b,tgsi_build_full_immediate,imm);
#define S source(TGSI_FILE_SYSTEM_VALUE,0,0)
#define T source(TGSI_FILE_TEMPORARY,0,0)
#define I(c) source(TGSI_FILE_IMMEDIATE,0,c)
#define MEMORY source(TGSI_FILE_MEMORY,TGSI_MEMORY_TYPE_SHARED,0)
    instruction(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,S,I(0));
    instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(1));
    if(kind!=3)instruction(b,TGSI_OPCODE_STORE,TGSI_FILE_MEMORY,TGSI_MEMORY_TYPE_SHARED,T,I(2));
    if(kind==6) {
        instruction(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,S,I(0));
        instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(3));
        instruction(b,TGSI_OPCODE_STORE,TGSI_FILE_MEMORY,TGSI_MEMORY_TYPE_SHARED,T,I(2));
        instruction(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,S,I(0));
        instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(1));
    }
    if(kind==7) {
        for(unsigned stripe=1;stripe<32;stripe++) {
            instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(3));
            instruction(b,TGSI_OPCODE_STORE,TGSI_FILE_MEMORY,TGSI_MEMORY_TYPE_SHARED,T,I(2));
        }
        instruction(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,S,I(0));
        instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(1));
        for(unsigned stripe=0;stripe<31;stripe++) {
            instruction(b,TGSI_OPCODE_LOAD,TGSI_FILE_TEMPORARY,1,MEMORY,T);
            instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(3));
        }
    }
    if(kind==1)instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(3));
    if(kind==5) {
        instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(3));
        instruction(b,TGSI_OPCODE_STORE,TGSI_FILE_MEMORY,TGSI_MEMORY_TYPE_SHARED,T,I(2));
        instruction(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,S,I(0));
        instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(1));
    }
    if(kind==2 || kind==4) {
        if(kind==4)instruction(b,TGSI_OPCODE_BARRIER,0,0,T,T);
        instruction(b,TGSI_OPCODE_XOR,TGSI_FILE_TEMPORARY,0,S,I(3));
        instruction(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,T,I(0));
        instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,T,I(1));
    }
    instruction(b,TGSI_OPCODE_LOAD,TGSI_FILE_TEMPORARY,1,MEMORY,T);
    instruction(b,TGSI_OPCODE_END,0,0,T,T);
#undef S
#undef T
#undef I
#undef MEMORY
}

int main(void)
{
    struct opengpu_cs_shared shared={0x10000000u,16384,16,16384};
    const uint32_t bases[]={0,0x10000000u,0xffffc000u};
    for(unsigned base=0;base<3;base++) {
        shared.base=bases[base];
        for(unsigned offset=0;offset<=15872;offset+=15872) {
            for(unsigned kind=0;kind<8;kind++) {
                if(kind==7&&offset)continue;
                struct builder b;program(&b,offset,kind);
                struct opengpu_cs_program p,unchanged;
                memset(&unchanged,0x5a,sizeof(unchanged));p=unchanged;
                bool accept=kind==0 || kind==6 || kind==7 || (kind==4 && offset==0);
                int result=opengpu_cs_emit_shared(b.tokens,&shared,&p);
                if(!accept) {assert(result<0&&!memcmp(&p,&unchanged,sizeof(p)));continue;}
                if(result)fprintf(stderr,"unexpected own-stripe rejection offset=%u kind=%u\n",offset,kind);
                assert(!result && p.words<=512);
                assert(opengpu_compute_shader_validate_words_shared_resources(p.code,p.words,12,128,
                    false,false,false,false,false,false,false,false,false,
                    shared.base,shared.bytes,shared.lanes));
                unsigned loads=0,stores=0,barriers=0;
                for(unsigned i=0;i<p.words;i++) {
                    loads+=(p.code[i]&127)==0x07;stores+=(p.code[i]&127)==0x27;
                    barriers+=p.code[i]==0x0000000b;
                }
                assert(loads==(kind==7?32u:1u)&&stores==(kind==7?32u:kind==6?2u:1u)&&barriers==(kind==4));
                /* Every retained full-stripe read requires its own real store.
                 * The spare disjoint store in kind6 is not read. */
                if(kind==7)for(unsigned i=0;i<p.words;i++)if((p.code[i]&127u)==0x27u) {
                    uint32_t saved=p.code[i];p.code[i]=0x13u;
                    assert(!opengpu_compute_shader_validate_words_shared_resources(p.code,p.words,12,128,
                        false,false,false,false,false,false,false,false,false,
                        shared.base,shared.bytes,shared.lanes));p.code[i]=saved;
                }
                for(unsigned i=0;i<p.words;i++)if((p.code[i]&127u)==0x27u)p.code[i]=0x13u;
                assert(!opengpu_compute_shader_validate_words_shared_resources(p.code,p.words,12,128,
                    false,false,false,false,false,false,false,false,false,
                    shared.base,shared.bytes,shared.lanes));
            }
        }
    }
    puts("shared own-stripe TGSI/emitter/validator: PASS (CPU only, no GPU execution)");
    return 0;
}
