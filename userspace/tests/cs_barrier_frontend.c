/* SPDX-License-Identifier: MIT */
/* Actual Mesa ESSL -> NIR -> TGSI -> native fence + kernel admission.
 * This CPU check does not model memory-barrier execution or coherence. */
#define OPENGPU_CS_REAL_TGSI_DUMP 1
#define main old_callback_corpus
#include "cs_callback_flow.c"
#undef main
#include "cs_glsl_frontend.h"
#include "tgsi/tgsi_text.h"

static unsigned barriers(const struct tgsi_token *tokens, unsigned *mask)
{
    struct tgsi_parse_context p; unsigned count=0,nimm=0;uint32_t imm[32][4];
    assert(tgsi_parse_init(&p,tokens)==TGSI_PARSE_OK);
    while(!tgsi_parse_end_of_tokens(&p)) {
        tgsi_parse_token(&p);
        if(p.FullToken.Token.Type==TGSI_TOKEN_TYPE_IMMEDIATE) {
            assert(nimm<32);
            for(unsigned c=0;c<4;c++)imm[nimm][c]=p.FullToken.FullImmediate.u[c].Uint;
            nimm++;
        } else if(p.FullToken.Token.Type==TGSI_TOKEN_TYPE_INSTRUCTION &&
                  p.FullToken.FullInstruction.Instruction.Opcode==TGSI_OPCODE_MEMBAR) {
            const struct tgsi_src_register *s=&p.FullToken.FullInstruction.Src[0].Register;
            assert(s->File==TGSI_FILE_IMMEDIATE&&s->Index>=0&&(unsigned)s->Index<nimm);
            *mask=imm[s->Index][s->SwizzleX];count++;
        }
    }
    tgsi_parse_free(&p);return count;
}

static void reject(const void *tokens)
{
    struct opengpu_cs_program code,before;memset(&code,0x71,sizeof(code));before=code;
    assert(opengpu_cs_emit(tokens,&code)<0&&!memcmp(&code,&before,sizeof(code)));
}

int main(int argc,char **argv)
{
    assert(argc==2);assert(!old_callback_corpus());
    const char *names[]={"buffer","image","counter","shared","group","all","branch","publication-1","publication-2","loop-branch"};
    const unsigned masks[]={TGSI_MEMBAR_SHADER_BUFFER,TGSI_MEMBAR_SHADER_IMAGE,
        TGSI_MEMBAR_ATOMIC_BUFFER,TGSI_MEMBAR_SHARED,31,15,TGSI_MEMBAR_SHADER_BUFFER,1,1,TGSI_MEMBAR_SHADER_BUFFER};
    for(unsigned c=0;c<sizeof(names)/sizeof(names[0]);c++) {
        char path[4096],dump[4096];
        assert(snprintf(path,sizeof(path),"%s/%s.comp",argv[1],names[c])>0);
        assert(snprintf(dump,sizeof(dump),"%s/%s",argv[1],names[c])>0);
        void *f=cs_frontend_compile(path,dump);assert(f);
        const struct tgsi_token *tokens=cs_frontend_tokens(f);
        unsigned mask=0;assert(barriers(tokens,&mask)==1&&mask==masks[c]);
        struct opengpu_cs_program code;assert(!opengpu_cs_emit(tokens,&code));
        assert(opengpu_compute_shader_validate_words_uniform_resources(code.code,code.words,
            12,128,false,false,false,true,false,false,false,false));
        unsigned fences=0,rendezvous=0;
        for(unsigned w=0;w<code.words;w++) {
            fences+=code.code[w]==0x0330000fu;rendezvous+=code.code[w]==0x0000000bu;
        }
        assert(fences>=4&&!rendezvous&&code.reads==(c<7 || c==9)&&code.writes==1);
        if(c==9) {
            unsigned loop=0, branch=0, backedge=0;
            for(unsigned w=0;w<code.words;w++) {
                uint32_t insn=code.code[w];
                if((insn&0x707fu)==0x305bu) {
                    if(!loop) loop=w;
                    else branch=w;
                }
                if((insn&0x80000fffu)==0x8000006fu) backedge=w;
            }
            assert(loop && branch>loop && backedge>branch);
            unsigned join=branch+opengpu_shader_simt_displacement(code.code[branch])/4;
            assert(join<backedge && code.code[join]==OPENGPU_SHADER_SIMT_JOIN);
            unsigned inside=0;
            for(unsigned w=branch+2;w<join;w++) inside+=code.code[w]==0x0330000fu;
            assert(inside>=1);
        }
        assert(snprintf(path,sizeof(path),"%s/%s.native.words",argv[1],names[c])>0);
        FILE *out=fopen(path,"wx");assert(out);
        for(unsigned w=0;w<code.words;w++)assert(fprintf(out,"%08x\n",code.code[w])==9);
        assert(!fclose(out));cs_frontend_destroy(f);
    }
    for(unsigned mask=0;mask<64;mask++) {
        char text[512];struct tgsi_token tokens[128];struct opengpu_cs_program code;
        assert(snprintf(text,sizeof(text),"COMP\nIMM[0] UINT32 {%u,0,0,0}\nMEMBAR IMM[0].xxxx\nEND\n",mask)>0);
        assert(tgsi_text_translate(text,tokens,128));
        if(mask<32) {
            assert(!opengpu_cs_emit(tokens,&code));
            assert(code.words==4&&code.code[1]==0x0330000fu&&code.code[2]==0x0330000fu);
        } else reject(tokens);
    }
    const char *bad[]={"COMP\nDCL TEMP[0]\nIMM[0] UINT32 {1,0,0,0}\nMOV TEMP[0].x, IMM[0].xxxx\nMEMBAR TEMP[0].xxxx\nEND\n",
        "COMP\nIMM[0] UINT32 {1,0,0,0}\nMEMBAR IMM[1].xxxx\nEND\n",
        "COMP\nBARRIER\nEND\n"};
    for(unsigned c=0;c<sizeof(bad)/sizeof(bad[0]);c++) {
        struct tgsi_token tokens[128];assert(tgsi_text_translate(bad[c],tokens,128));reject(tokens);
    }
    puts("MEMBAR_COMPILER_PASS actualESSL310=10 masks=32 invalidMasks=32 dynamicAndUndeclaredRejected=true noExecutionRendezvous=true loopBranchFence=true noGPU=true");
    return 0;
}
