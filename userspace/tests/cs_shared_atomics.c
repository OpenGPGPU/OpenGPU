/* SPDX-License-Identifier: MIT */
/* Real Mesa TGSI builder/parser -> production emitter -> Linux validator. */
#define main retained_stripes_main
#include "cs_shared_own_stripe.c"
#undef main
#include "tgsi/tgsi_parse.h"

static void atomic_instruction(struct builder *b,unsigned op,unsigned local,unsigned alias)
{
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=op;i.Instruction.NumDstRegs=1;
    i.Instruction.NumSrcRegs=op==TGSI_OPCODE_ATOMCAS?4:3;
    i.Instruction.Memory=1;i.Memory.Texture=TGSI_TEXTURE_BUFFER;
    i.Dst[0].Register.File=TGSI_FILE_TEMPORARY;
    i.Dst[0].Register.Index=alias?0:1;i.Dst[0].Register.WriteMask=1;
    i.Src[0].Register=source(TGSI_FILE_MEMORY,TGSI_MEMORY_TYPE_SHARED,0);
    i.Src[1].Register=source(local?TGSI_FILE_TEMPORARY:TGSI_FILE_IMMEDIATE,0,local?0:1);
    i.Src[2].Register=source(TGSI_FILE_TEMPORARY,1,0);
    if(op==TGSI_OPCODE_ATOMCAS)i.Src[3].Register=source(TGSI_FILE_IMMEDIATE,0,2);
    APPEND(b,tgsi_build_full_instruction,i);
}

int main(void)
{
    assert(retained_stripes_main()==0); // retain the complete original corpus
    const unsigned operations[]={TGSI_OPCODE_ATOMXCHG,TGSI_OPCODE_ATOMUADD,
        TGSI_OPCODE_ATOMXOR,TGSI_OPCODE_ATOMOR,TGSI_OPCODE_ATOMAND,
        TGSI_OPCODE_ATOMIMIN,TGSI_OPCODE_ATOMIMAX,TGSI_OPCODE_ATOMUMIN,
        TGSI_OPCODE_ATOMUMAX,TGSI_OPCODE_ATOMCAS};
    const uint32_t bases[]={0,0x10000000u,0xffffc000u};
    unsigned cases=0;
    for(unsigned base=0;base<3;base++)for(unsigned op=0;op<10;op++)
    for(unsigned local=0;local<2;local++)for(unsigned alias=0;alias<2;alias++) {
        struct builder b;program(&b,local?15872:0,0);
        struct tgsi_parse_context parser;
        assert(tgsi_parse_init(&parser,b.tokens)==TGSI_PARSE_OK);
        unsigned end=0;
        while(!tgsi_parse_end_of_tokens(&parser)) {
            unsigned position=parser.Position;
            tgsi_parse_token(&parser);
            if(parser.FullToken.Token.Type==TGSI_TOKEN_TYPE_INSTRUCTION &&
               parser.FullToken.FullInstruction.Instruction.Opcode==TGSI_OPCODE_END)
                end=position;
        }
        tgsi_parse_free(&parser);assert(end>=b.header->HeaderSize);
        b.header->BodySize=end-b.header->HeaderSize;
        if(!local)instruction(&b,TGSI_OPCODE_BARRIER,0,0,source(0,0,0),source(0,0,0));
        atomic_instruction(&b,operations[op],local,alias);
        instruction(&b,TGSI_OPCODE_END,0,0,source(0,0,0),source(0,0,0));
        struct opengpu_cs_shared shared={bases[base],16384,16,16384};
        struct opengpu_cs_program p;
        assert(!opengpu_cs_emit_shared(b.tokens,&shared,&p));
        assert(p.words<=512 && p.shared_atomics);
        assert(opengpu_compute_shader_validate_words_shared_atomics(p.code,p.words,12,128,
            false,false,false,false,false,false,false,false,false,
            shared.base,shared.bytes,shared.lanes,false,true));
        assert(!opengpu_compute_shader_validate_words_shared_resources(p.code,p.words,12,128,
            false,false,false,false,false,false,false,false,false,
            shared.base,shared.bytes,shared.lanes));
        unsigned params=0,atomics=0;
        for(unsigned i=0;i<p.words;i++)if((p.code[i]&127)==0x2b) {
            params+=p.code[i]>>26==30;
            if(p.code[i]>>26==31 || p.code[i]>>26==32) {
                ++atomics;assert((p.code[i]>>12&7)==op%8);
                assert((p.code[i]>>26)==(op<8?31u:32u));
            }
        }
        assert(params==1 && atomics==1);
        for(unsigned i=0;i<p.words;i++)if((p.code[i]&127)==0x27) {
            uint32_t saved=p.code[i];p.code[i]=0x00000013;
            assert(!opengpu_compute_shader_validate_words_shared_atomics(p.code,p.words,12,128,
                false,false,false,false,false,false,false,false,false,
                shared.base,shared.bytes,shared.lanes,false,true));
            p.code[i]=saved;
        }
        cases++;
    }
    printf("shared atomic actual TGSI/emitter/validator: %u programs PASS (CPU only)\n",cases);
    return 0;
}
