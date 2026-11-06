/* SPDX-License-Identifier: MIT */
/* Actual Mesa NIR -> TGSI component-mask controls; no GPU/device/RTL model. */
#include <assert.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "compiler/nir/nir.h"
#include "compiler/nir/nir_builder.h"
#include "nir/nir_to_tgsi.h"
#include "pipe/p_screen.h"
#include "tgsi/tgsi_parse.h"
#include "tgsi/tgsi_text.h"
#include "tgsi/tgsi_util.h"
#include "cs_glsl_frontend.h"
#include "opengpu_cs_emit.h"
#include "cs_word_vm.h"
#include "../../driver/opengpu_compute_texture_snapshot.h"

static int param(struct pipe_screen *screen, enum pipe_cap cap)
{ (void)screen; (void)cap; return 0; }
static int shader_param(struct pipe_screen *screen, enum pipe_shader_type stage, enum pipe_shader_cap cap)
{
    (void)screen; (void)stage;
    switch(cap) {
    case PIPE_SHADER_CAP_INTEGERS: return 1;
    case PIPE_SHADER_CAP_MAX_TEMPS: return 64;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFERS: return 13;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE: return 4096;
    case PIPE_SHADER_CAP_MAX_SHADER_BUFFERS: return 4;
    case PIPE_SHADER_CAP_MAX_TEXTURE_SAMPLERS: return 32;
    case PIPE_SHADER_CAP_MAX_CONTROL_FLOW_DEPTH: return 32;
    default: return 0;
    }
}
static nir_ssa_def *fetch(nir_builder *b, nir_ssa_def *coordinate, unsigned unit,
                          enum glsl_sampler_dim dimension, int query)
{
    nir_tex_instr *tex=nir_tex_instr_create(b->shader,query?1:2);
    tex->op=query?nir_texop_txs:nir_texop_txf;
    tex->sampler_dim=dimension; tex->dest_type=query?nir_type_int32:nir_type_uint32;
    tex->texture_index=tex->sampler_index=unit;
    tex->coord_components=dimension==GLSL_SAMPLER_DIM_BUF?1:2;
    if(!query) tex->src[0]=(nir_tex_src){.src=nir_src_for_ssa(coordinate),.src_type=nir_tex_src_coord};
    tex->src[query?0:1]=(nir_tex_src){.src=nir_src_for_ssa(nir_imm_int(b,0)),.src_type=nir_tex_src_lod};
    nir_ssa_dest_init(&tex->instr,&tex->dest,query?1:4,32,NULL);
    nir_builder_instr_insert(b,&tex->instr);
    return &tex->dest.ssa;
}
static void store(nir_builder *b,nir_ssa_def *value,unsigned byte)
{
    nir_store_ssbo(b,value,nir_imm_int(b,0),nir_imm_int(b,byte),
                   .write_mask=(1u<<value->num_components)-1,.align_mul=4);
}
static void run_case(struct pipe_screen *screen,const char *out,const char *name,
                     unsigned kind,unsigned expected)
{
    gl_shader_stage stage=kind==8?MESA_SHADER_VERTEX:MESA_SHADER_COMPUTE;
    const nir_shader_compiler_options *options=nir_to_tgsi_get_compiler_options(screen,PIPE_SHADER_IR_NIR,
        stage==MESA_SHADER_COMPUTE?PIPE_SHADER_COMPUTE:PIPE_SHADER_VERTEX);
    nir_builder b=nir_builder_init_simple_shader(stage,options,"mask-control-%s",name);
    if(stage==MESA_SHADER_COMPUTE) {
        b.shader->info.workgroup_size[0]=16; b.shader->info.workgroup_size[1]=b.shader->info.workgroup_size[2]=1;
    }
    b.shader->info.num_ssbos=1;
    nir_ssa_def *id=stage==MESA_SHADER_COMPUTE?nir_channel(&b,nir_load_local_invocation_id(&b),0):nir_imm_int(&b,0);
    if(kind==6) {
        /* Side-effecting stores retain the real CFG; the vec4 phi web must
         * preserve every channel and can become a register destination. */
        nir_if *nif=nir_push_if(&b,nir_ine_imm(&b,id,0));
        nir_ssa_def *yes=fetch(&b,id,0,GLSL_SAMPLER_DIM_BUF,0); store(&b,nir_channel(&b,yes,0),32);
        nir_push_else(&b,nif);
        nir_ssa_def *no=fetch(&b,nir_iadd_imm(&b,id,1),1,GLSL_SAMPLER_DIM_BUF,0); store(&b,nir_channel(&b,no,3),36);
        nir_pop_if(&b,nif); store(&b,nir_if_phi(&b,yes,no),0);
    } else {
        enum glsl_sampler_dim dim=kind==7?GLSL_SAMPLER_DIM_2D:GLSL_SAMPLER_DIM_BUF;
        nir_ssa_def *coordinate=kind==7?nir_vec2(&b,id,id):id;
        nir_ssa_def *value=fetch(&b,coordinate,0,dim,kind==9);
        if(kind==0 || kind==7 || kind==8) store(&b,nir_channel(&b,value,0),0);
        else if(kind==1) store(&b,nir_channel(&b,value,1),0);
        else if(kind==2) { unsigned swizzle[]={2,3}; store(&b,nir_swizzle(&b,value,swizzle,2),0); }
        else if(kind==3) { unsigned swizzle[]={2,0,2,3}; store(&b,nir_swizzle(&b,value,swizzle,4),0); }
        else if(kind==4 || kind==9) store(&b,value,0);
        else {
            /* A single dominating SSA texture result is used in both CFG
             * arms. Read-mask analysis must union .y with .zw. */
            nir_if *nif=nir_push_if(&b,nir_ine_imm(&b,id,0));
            store(&b,nir_channel(&b,value,1),0); nir_push_else(&b,nif);
            unsigned swizzle[]={2,3}; store(&b,nir_swizzle(&b,value,swizzle,2),0); nir_pop_if(&b,nif);
        }
    }
    nir_validate_shader(b.shader,"before component-mask control");
    const struct tgsi_token *tokens=nir_to_tgsi(b.shader,screen); assert(tokens);
    struct tgsi_parse_context parser; assert(tgsi_parse_init(&parser,tokens)==TGSI_PARSE_OK);
    unsigned count=0,branches=0;
    while(!tgsi_parse_end_of_tokens(&parser)) {
        tgsi_parse_token(&parser);
        if(parser.FullToken.Token.Type!=TGSI_TOKEN_TYPE_INSTRUCTION) continue;
        const struct tgsi_full_instruction *insn=&parser.FullToken.FullInstruction;
        unsigned op=insn->Instruction.Opcode;
        if(op==TGSI_OPCODE_IF || op==TGSI_OPCODE_UIF) branches++;
        if(op==TGSI_OPCODE_TXF || op==TGSI_OPCODE_TXQ) {
            unsigned got=insn->Dst[0].Register.WriteMask;
            printf("MASK_CHANNEL case=%s opcode=%u mask=%x expected=%x\n",name,op,got,expected);
            assert(got==expected); count++;
        }
    }
    tgsi_parse_free(&parser); assert(count==(kind==6?2u:1u));
    if(kind==5 || kind==6) assert(branches);
    char path[4096]; assert(snprintf(path,sizeof(path),"%s/%s.tgsi.bin",out,name)>0);
    FILE *f=fopen(path,"wx"); assert(f);
    unsigned bytes=tgsi_num_tokens(tokens)*sizeof(*tokens);
    assert(fwrite(tokens,1,bytes,f)==bytes && !fclose(f)); free((void *)tokens);
    printf("MASK_CASE name=%s textureInstructions=%u branches=%u PASS\n",name,count,branches);
}
static unsigned sample_reads,sample_queries;
static uint32_t channel_value(unsigned index,unsigned component)
{ return UINT32_C(0x12340000)^index*257u^component*UINT32_C(0x101); }
static uint32_t sample(void *unused,unsigned unit,uint32_t index,unsigned component,int query)
{
    (void)unused; assert(unit==0 && component<4);
    if(query) { assert(!component); sample_queries++; return 1024; }
    assert(index<16); sample_reads++; return channel_value(index,component);
}

/* Authored compiler controls only. Descriptor rejection uses the production
 * C validator; longjmp is not a model of hardware faults or retirement. */
static jmp_buf query_fault;
static unsigned query_unit,query_calls,query_bad_descriptor;
static uint32_t query_output[68];
static uint32_t query_size(void *unused,unsigned unit,uint32_t index,unsigned component,int query)
{
    (void)unused; assert(unit==query_unit && !index && !component && query);
    query_calls++;
    const uint32_t base=0x10000000u,bytes=(1024+unit)*4;
    const uint32_t limit=base+bytes-(query_bad_descriptor?4u:0u),address=0x4000+unit*128;
    struct opengpu_compute_texture_root_config root={.context=7,.pass=11,.generation=13,.count=32,
        .bindings_base=0x1000,.bindings_limit=0x1800,.images_base=0x2000,.images_limit=0x3000,.buffer_only=true};
    struct opengpu_compute_texture_binding_config binding={.context=7,.pass=11,.generation=13,.unit=unit,
        .image_handle=unit,.sampler_handle=0xffffffffu,.target=OPENGPU_TEXTURE_BUFFER_TARGET,.expected_type=1};
    struct opengpu_compute_texture_image_config image={.context=7,.pass=11,.generation=13,.handle=unit,
        .format=OPENGPU_TEXTURE_FORMAT_R32UI,.target=OPENGPU_TEXTURE_BUFFER_TARGET,.width=1,.height=1,.depth=1,.layers=1,
        .allocation_base=base,.allocation_limit=limit,.levels_base=address,.levels_limit=address+128,
        .buffer_texels=1024+unit,.buffer_bytes=bytes};
    struct opengpu_compute_texture_level_config level={.context=7,.pass=11,.generation=13,.handle=0,.level=0,
        .target=OPENGPU_TEXTURE_BUFFER_TARGET,.width=1,.height=1,.depth=1,.layers=1,.block_bytes=4,
        .allocation_base=base,.allocation_limit=limit,.base=base,.buffer_texels=1024+unit,.buffer_bytes=bytes};
    struct opengpu_compute_texture_snapshot snapshot={.root=&root,.binding=&binding,.image=&image,.levels=&level,
        .level_addresses=&address,.root_address=0x800,.binding_address=0x1000+unit*64,.image_address=0x2000+unit*128,
        .level_count=1,.selected_level=0,.operation=OPENGPU_COMPUTE_TEXTURE_SIZE};
    int legal=opengpu_validate_compute_texture_snapshot(&snapshot);
    assert(!!legal==!query_bad_descriptor);
    if(!legal) longjmp(query_fault,1);
    return 1024+unit;
}
static void query_control(unsigned unit,unsigned mask,unsigned items,unsigned predicate,int reject,int grid_source)
{
    char text[4096],channels[5]={0},pred[256]={0};unsigned at=0;
    for(unsigned c=0;c<4;c++) if(mask&(1u<<c)) channels[at++]="xyzw"[c];
    if(predicate==1) strcpy(pred,"USLT TEMP[2].x, SV[0].xxxx, IMM[0].xxxx\n");
    if(predicate==2) strcpy(pred,"AND TEMP[2].x, SV[0].xxxx, IMM[0].yyyy\nUSNE TEMP[2].x, TEMP[2].xxxx, IMM[0].xxxx\n");
    assert(snprintf(text,sizeof(text),"COMP\nDCL SV[0], THREAD_ID\nDCL SV[1], GRID_SIZE\nDCL SAMP[%u]\nDCL SVIEW[%u], BUFFER, UINT\nDCL BUFFER[0]\nDCL TEMP[0..3]\nIMM[0] UINT32 {0,1,4,8}\n%sTXQ TEMP[0].%s, IMM[0].xxxx, SAMP[%u], BUFFER\n%sSHL TEMP[1].x, SV[0].xxxx, IMM[0].zzzz\nSTORE BUFFER[0].%s, TEMP[1].xxxx, TEMP[0]\nEND\n",
        unit,unit,pred,channels,unit,grid_source?"UADD TEMP[0].x, TEMP[0].xxxx, SV[1].xxxx\n":"",channels)>0);
    struct tgsi_token tokens[512];assert(tgsi_text_translate(text,tokens,512));
    struct opengpu_cs_program p;assert(!opengpu_cs_emit(tokens,&p));
    assert(p.textures==(UINT32_C(1)<<unit) && !p.reads && p.writes==1);
    for(unsigned i=0;i<68;i++) query_output[i]=UINT32_C(0xdeadface);
    query_unit=unit;query_calls=0;query_bad_descriptor=reject;
    struct cs_vm_view memory[4]={{query_output+2,256,2}};
    const struct cs_vm_resources resources={NULL,query_size,NULL};
    const uint32_t grid[3]={7,9,11},group[3]={2,3,5},local[3]={items,1,1};
    if(!setjmp(query_fault)) {
        cs_vm_group_resources_checked(p.code,p.words,grid,group,local,memory,NULL,NULL,&resources);
        assert(!reject);
    } else assert(reject);
    assert(query_calls==1);
    for(unsigned i=0;i<68;i++) {
        uint32_t want=UINT32_C(0xdeadface);
        if(!reject && i>=2 && i<66 && (i-2)/4<items && (mask&(1u<<((i-2)%4)))) {
            unsigned c=(i-2)%4;want=c==0?1024+unit+(grid_source?7u:0u):c==3?1u:0u;
        }
        assert(query_output[i]==want);
    }
}
static void query_controls(void)
{
    const unsigned masks[]={1,2,4,8,10,15};
    for(unsigned u=0;u<2;u++) for(unsigned m=0;m<6;m++) query_control(u?31:0,masks[m],16,0,0,0);
    query_control(31,15,1,0,0,0);query_control(31,15,15,0,0,0);
    query_control(0,15,16,1,0,0);query_control(31,15,16,2,0,0);
    query_control(31,1,16,0,0,1);
    query_control(0,1,16,0,1,0);query_control(31,8,16,0,1,0);
    puts("SCALAR_BUFFER_TXQ controls=19 unit0And31 masksXYZW_YW_RGBA items1_15_16 packedV0 levelsOnlyReject guards=PASS hardware=UNRUN");
}
static void real_essl(const char *source,const char *out,unsigned kind,int patched)
{
    const char *names[]={"x","y","zw","mixed-zxzw","rgba","texture-size"};
    const unsigned masks[]={1,2,12,13,15,1};
    const unsigned channels[][4]={{0,0,0,0},{1,1,1,1},{2,3,2,3},{2,0,2,3},{0,1,2,3}};
    char path[4096],prefix[4096];
    assert(snprintf(path,sizeof(path),"%s/%s.comp",source,names[kind])>0);
    assert(snprintf(prefix,sizeof(prefix),"%s/essl-%s",out,names[kind])>0);
    void *front=cs_frontend_compile_buffers(path,prefix); assert(front);
    struct tgsi_parse_context parser; assert(tgsi_parse_init(&parser,cs_frontend_tokens(front))==TGSI_PARSE_OK);
    unsigned mask=kind==5?1:patched?masks[kind]:15,count=0;
    while(!tgsi_parse_end_of_tokens(&parser)) {
        tgsi_parse_token(&parser);
        if(parser.FullToken.Token.Type!=TGSI_TOKEN_TYPE_INSTRUCTION) continue;
        const struct tgsi_full_instruction *insn=&parser.FullToken.FullInstruction;
        if(insn->Instruction.Opcode==TGSI_OPCODE_TXF || insn->Instruction.Opcode==TGSI_OPCODE_TXQ) {
            assert(insn->Dst[0].Register.WriteMask==mask); count++;
        }
    }
    tgsi_parse_free(&parser); assert(count==1);
    struct opengpu_cs_program p; assert(!opengpu_cs_emit(cs_frontend_tokens(front),&p) && p.words<=512);
    assert(p.reads==0 && p.writes==1 && p.textures==1 && !p.image_reads && !p.image_writes && !p.image_queries);
    assert(p.fixed_block[0]==16 && p.fixed_block[1]==1 && p.fixed_block[2]==1);
    uint32_t output[68]; for(unsigned i=0;i<68;i++) output[i]=UINT32_C(0xdeadface);
    struct cs_vm_view memory[4]={{output,sizeof(output),2}},uniforms[13]={{0}};
    const uint32_t grid[3]={1,1,1},group[3]={0},local[3]={16,1,1};
    const struct cs_vm_resources resources={NULL,sample,NULL}; struct cs_vm_uniform_trace trace={0};
    sample_reads=sample_queries=0;
    cs_vm_group_resources_checked(p.code,p.words,grid,group,local,memory,uniforms,&trace,&resources);
    assert(!trace.reads && !trace.fault_warps);
    for(unsigned id=0;id<16;id++) for(unsigned c=0;c<4;c++)
        assert(output[id*4+c]==(kind==5?1024u:channel_value(id,channels[kind][c])));
    for(unsigned i=64;i<68;i++) assert(output[i]==UINT32_C(0xdeadface));
    assert(sample_queries==(kind==5?1u:0u));
    assert(sample_reads==(kind==5?0u:16u*(unsigned)__builtin_popcount(mask)));
    assert(snprintf(path,sizeof(path),"%s/essl-%s.tgsi.bin",out,names[kind])>0);
    FILE *raw=fopen(path,"wx"); assert(raw);
    unsigned bytes=tgsi_num_tokens(cs_frontend_tokens(front))*sizeof(struct tgsi_token);
    assert(fwrite(cs_frontend_tokens(front),1,bytes,raw)==bytes && !fclose(raw));
    cs_frontend_destroy(front);
    printf("ACTUAL_ESSL_WORD_CONTROL name=%s mask=%x reads=%u queries=%u words=%u exact64PayloadAnd4Guards=PASS GPU=UNRUN\n",
        names[kind],mask,sample_reads,sample_queries,p.words);
}
int main(int argc,char **argv)
{
    assert(argc==4 && (!strcmp(argv[1],"baseline") || !strcmp(argv[1],"patched")));
    (void)cs_vm_group; (void)cs_vm_group_uniforms_checked;
    query_controls();
    int patched=!strcmp(argv[1],"patched");
    struct pipe_screen screen={.get_param=param,.get_shader_param=shader_param};
    const char *names[]={"x","y","zw","mixed-zxzw","rgba","conditional-union","phi-register","nonbuffer","graphics","texture-size"};
    const unsigned narrowed[]={1,2,12,13,15,14,15,15,15,1};
    for(unsigned i=0;i<10;i++) run_case(&screen,argv[2],names[i],i,patched?narrowed[i]:i==9?1:15);
    for(unsigned i=0;i<6;i++) real_essl(argv[3],argv[2],i,patched);
    puts("ACTUAL_MESA_NIR_TGSI_MASK_CONTROLS cases=10 PASS GPU=UNRUN");
    return 0;
}
