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

static void remove_end(struct builder *b)
{
    struct tgsi_parse_context p;unsigned end=0;
    assert(tgsi_parse_init(&p,b->tokens)==TGSI_PARSE_OK);
    while(!tgsi_parse_end_of_tokens(&p)) {
        unsigned position=p.Position;tgsi_parse_token(&p);
        if(p.FullToken.Token.Type==TGSI_TOKEN_TYPE_INSTRUCTION &&
            p.FullToken.FullInstruction.Instruction.Opcode==TGSI_OPCODE_END)end=position;
    }
    tgsi_parse_free(&p);assert(end);b->header->BodySize=end-b->header->HeaderSize;
}
static void control(struct builder *b,unsigned op)
{
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=op;i.Instruction.NumSrcRegs=op==TGSI_OPCODE_UIF;
    i.Instruction.NumDstRegs=0;
    if(i.Instruction.NumSrcRegs)i.Src[0].Register=source(TGSI_FILE_SYSTEM_VALUE,0,0);
    APPEND(b,tgsi_build_full_instruction,i);
}
static void dynamic_program(struct builder *b,unsigned operation,unsigned kind)
{
    program(b,0,0);remove_end(b);
    if(kind!=1)instruction(b,TGSI_OPCODE_BARRIER,0,0,source(0,0,0),source(0,0,0));
    /* Adjacent-warp permutation, and a genuinely bounded AND address that
     * cannot use the emitter's affine-LOCAL shortcut. */
    instruction(b,kind==2?TGSI_OPCODE_AND:TGSI_OPCODE_XOR,TGSI_FILE_TEMPORARY,0,
        source(TGSI_FILE_SYSTEM_VALUE,0,0),source(TGSI_FILE_IMMEDIATE,0,3));
    instruction(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,
        source(TGSI_FILE_TEMPORARY,0,0),source(TGSI_FILE_IMMEDIATE,0,0));
    if(kind==3)instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,
        source(TGSI_FILE_TEMPORARY,0,0),source(TGSI_FILE_IMMEDIATE,0,0)); // misalign
    control(b,TGSI_OPCODE_UIF);
    if(kind==4)instruction(b,TGSI_OPCODE_BARRIER,0,0,source(0,0,0),source(0,0,0));
    atomic_instruction(b,operation,1,0);
    control(b,TGSI_OPCODE_ELSE);
    atomic_instruction(b,operation,1,0);
    control(b,TGSI_OPCODE_ENDIF);
    instruction(b,TGSI_OPCODE_END,0,0,source(0,0,0),source(0,0,0));
}

static void full_symbolic_program(struct builder *b,unsigned operation)
{
    struct builder original;program(&original,0,0);
    memset(b,0,sizeof(*b));b->header=(struct tgsi_header *)b->tokens;*b->header=tgsi_build_header();
    struct tgsi_processor processor=tgsi_build_processor(PIPE_SHADER_COMPUTE,b->header);
    memcpy(b->tokens+1,&processor,4);
    struct tgsi_parse_context p;assert(tgsi_parse_init(&p,original.tokens)==TGSI_PARSE_OK);
    bool instructions=false;
    while(!tgsi_parse_end_of_tokens(&p)) {
        tgsi_parse_token(&p);
        switch(p.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_PROPERTY:APPEND(b,tgsi_build_full_property,p.FullToken.FullProperty);break;
        case TGSI_TOKEN_TYPE_DECLARATION:
            if(p.FullToken.FullDeclaration.Declaration.File==TGSI_FILE_TEMPORARY)
                p.FullToken.FullDeclaration.Range.Last=63;
            APPEND(b,tgsi_build_full_declaration,p.FullToken.FullDeclaration);break;
        case TGSI_TOKEN_TYPE_IMMEDIATE:APPEND(b,tgsi_build_full_immediate,p.FullToken.FullImmediate);break;
        case TGSI_TOKEN_TYPE_INSTRUCTION:
            if(!instructions) {
                struct tgsi_full_immediate step=tgsi_default_full_immediate();
                step.Immediate.NrTokens=5;step.Immediate.DataType=TGSI_IMM_UINT32;
                step.u[0].Uint=512;APPEND(b,tgsi_build_full_immediate,step);instructions=true;
            }
            if(p.FullToken.FullInstruction.Instruction.Opcode!=TGSI_OPCODE_END)
                APPEND(b,tgsi_build_full_instruction,p.FullToken.FullInstruction);
            break;
        default:assert(0);
        }
    }
    tgsi_parse_free(&p);
    for(unsigned stripe=1;stripe<32;stripe++) {
        unsigned t=stripe+1;
        instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,t,
            source(TGSI_FILE_TEMPORARY,stripe==1?0:t-1,0),source(TGSI_FILE_IMMEDIATE,1,0));
        instruction(b,TGSI_OPCODE_STORE,TGSI_FILE_MEMORY,TGSI_MEMORY_TYPE_SHARED,
            source(TGSI_FILE_TEMPORARY,t,0),source(TGSI_FILE_IMMEDIATE,0,2));
    }
    instruction(b,TGSI_OPCODE_BARRIER,0,0,source(0,0,0),source(0,0,0));
    instruction(b,TGSI_OPCODE_XOR,TGSI_FILE_TEMPORARY,0,
        source(TGSI_FILE_SYSTEM_VALUE,0,0),source(TGSI_FILE_IMMEDIATE,0,3));
    instruction(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,
        source(TGSI_FILE_TEMPORARY,0,0),source(TGSI_FILE_IMMEDIATE,0,0));
    for(unsigned stripe=1;stripe<32;stripe++)
        instruction(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,
            source(TGSI_FILE_TEMPORARY,0,0),source(TGSI_FILE_IMMEDIATE,1,0));
    control(b,TGSI_OPCODE_UIF);atomic_instruction(b,operation,1,0);
    control(b,TGSI_OPCODE_ENDIF);
    instruction(b,TGSI_OPCODE_END,0,0,source(0,0,0),source(0,0,0));
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
    unsigned dynamic_cases=0;
    for(unsigned base=0;base<3;base++)for(unsigned op=0;op<10;op++)for(unsigned kind=0;kind<5;kind++) {
        struct builder b;dynamic_program(&b,operations[op],kind);
        struct opengpu_cs_shared shared={bases[base],16384,16,512};
        struct opengpu_cs_program p,before;memset(&p,0x5a,sizeof(p));before=p;
        int result=opengpu_cs_emit_shared(b.tokens,&shared,&p);
        if(kind==1 || kind==3 || kind==4)assert(result<0 && !memcmp(&p,&before,sizeof(p)));
        else {
            if(result)fprintf(stderr,"dynamic branch emitter rejected base%u op%u kind%u\n",base,op,kind);
            assert(!result && p.words<=512 && p.shared_atomics);
            assert(opengpu_compute_shader_validate_words_shared_atomics(p.code,p.words,12,128,
                false,false,false,true,false,false,false,false,false,
                shared.base,shared.bytes,shared.lanes,false,true));
            assert(opengpu_cs_emit(b.tokens,&before)<0); // legacy entry stays closed
        }
        ++dynamic_cases;
    }
    printf("shared atomic actual TGSI/emitter/validator: %u programs PASS (CPU only)\n",cases);
    printf("shared atomic bounded address/SIMT: %u programs PASS (CPU only)\n",dynamic_cases);
    for(unsigned op=0;op<10;op++) {
        struct builder b;full_symbolic_program(&b,operations[op]);
        struct opengpu_cs_shared shared={0x10000000,16384,16,16384};
        struct opengpu_cs_program p;assert(!opengpu_cs_emit_shared(b.tokens,&shared,&p));
        assert(p.words<=512 && p.shared_atomics);
        assert(opengpu_compute_shader_validate_words_shared_atomics(p.code,p.words,12,128,
            false,false,false,false,false,false,false,false,false,
            shared.base,shared.bytes,shared.lanes,false,true));
        unsigned stores=0;for(unsigned i=0;i<p.words;i++)stores+=(p.code[i]&127)==0x27;
        assert(stores==32);
        for(unsigned i=0;i<p.words;i++)if((p.code[i]&127)==0x27) {
            uint32_t saved=p.code[i];p.code[i]=0x13;
            assert(!opengpu_compute_shader_validate_words_shared_atomics(p.code,p.words,12,128,
                false,false,false,false,false,false,false,false,false,
                shared.base,shared.bytes,shared.lanes,false,true));
            p.code[i]=saved;
        }
    }
    puts("shared atomic full 16KiB/32 symbolic stripes/20 registers: 10 programs PASS (CPU only)");
    return 0;
}
