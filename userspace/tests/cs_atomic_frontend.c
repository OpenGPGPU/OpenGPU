/* SPDX-License-Identifier: MIT */
/* Actual ESSL310/Mesa -> checked storage RMW words and production callbacks.
 * The arithmetic VM is a CPU oracle, not an implementation of GPU/L2 timing. */
#define OPENGPU_CS_REAL_TGSI_DUMP 1
#define main old_callback_corpus
#include "cs_callback_flow.c"
#undef main
#include "cs_glsl_frontend.h"
#include "tgsi/tgsi_text.h"

static void reject_atomic(const void *tokens,const struct opengpu_cs_shared *shared)
{
    struct opengpu_cs_program code,before;memset(&code,0x71,sizeof(code));before=code;
    assert((shared?opengpu_cs_emit_shared(tokens,shared,&code):opengpu_cs_emit(tokens,&code))<0);
    assert(!memcmp(&code,&before,sizeof(code)));
}

static unsigned atomic_at(struct tgsi_token *tokens)
{
    struct tgsi_header h;memcpy(&h,tokens,4);
    unsigned end=(unsigned)h.HeaderSize+(unsigned)h.BodySize;
    for(unsigned pos=h.HeaderSize;pos<end;) {
        struct tgsi_token t;memcpy(&t,tokens+pos,4);
        if(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION) {
            struct tgsi_instruction i;memcpy(&i,tokens+pos,4);
            if(i.Opcode==TGSI_OPCODE_ATOMCAS)return pos;
            pos+=1+i.NrTokens;
        } else pos+=t.NrTokens;
    }
    assert(!"missing actual compare-swap instruction");return 0;
}

static void negative_resources(void)
{
    const char *base="COMP\nPROPERTY CS_FIXED_BLOCK_WIDTH 16\nPROPERTY CS_FIXED_BLOCK_HEIGHT 1\n"
        "PROPERTY CS_FIXED_BLOCK_DEPTH 1\nDCL SV[0], THREAD_ID\nDCL MEMORY[1], SHARED\n"
        "DCL BUFFER[1]\nDCL TEMP[0..1]\nIMM[0] UINT32 {0,2,7,0}\n"
        "SHL TEMP[0].x, SV[0].xxxx, IMM[0].yyyy\nSTORE MEMORY[1].x, TEMP[0].xxxx, IMM[0].zzzz\n";
    const struct opengpu_cs_shared shared={.base=0x10000000u,.bytes=16384,.lanes=16,.required_bytes=64};
    char text[2048];struct tgsi_token tokens[512];struct opengpu_cs_program code;
    assert(snprintf(text,sizeof(text),"%sEND\n",base)>0&&tgsi_text_translate(text,tokens,512));
    assert(!opengpu_cs_emit_shared(tokens,&shared,&code));
    assert(snprintf(text,sizeof(text),"%sATOMUADD TEMP[1].x, MEMORY[1], IMM[0].xxxx, IMM[0].zzzz\nEND\n",base)>0);
    assert(tgsi_text_translate(text,tokens,512));reject_atomic(tokens,&shared);
}

static void negative_opcodes(const void *actual)
{
    unsigned count=tgsi_num_tokens(actual);
    struct tgsi_token *copy=malloc((count+1)*4);assert(copy);
    const unsigned files[]={TGSI_FILE_MEMORY,TGSI_FILE_IMAGE,TGSI_FILE_HW_ATOMIC};
    for(unsigned kind=0;kind<3;kind++) {
        memcpy(copy,actual,count*4);unsigned pos=atomic_at(copy);
        struct tgsi_instruction *i=(struct tgsi_instruction *)(copy+pos);
        assert(i->Memory&&i->NumDstRegs==1&&i->NumSrcRegs==4);
        struct tgsi_src_register *resource=(struct tgsi_src_register *)(copy+pos+3);
        resource->File=files[kind];reject_atomic(copy,NULL);
    }
    memcpy(copy,actual,count*4);unsigned pos=atomic_at(copy);
    struct tgsi_instruction *i=(struct tgsi_instruction *)(copy+pos);
    i->Opcode=TGSI_OPCODE_ATOMUADD;reject_atomic(copy,NULL); /* Four is only legal for CAS. */
    i->Opcode=TGSI_OPCODE_MOV;reject_atomic(copy,NULL); /* Ordinary sources stay bounded. */
    memcpy(copy,actual,count*4);pos=atomic_at(copy);i=(struct tgsi_instruction *)(copy+pos);
    unsigned last=pos+i->NrTokens;
    memmove(copy+last,copy+last+1,(count-last-1)*4);
    i->NrTokens--;i->NumSrcRegs--;((struct tgsi_header *)copy)->BodySize--;
    reject_atomic(copy,NULL); /* CAS must retain both compare and replacement. */
    free(copy);
}

static void *front(const char *directory,const char *name)
{
    char source[4096],prefix[4096];
    assert(snprintf(source,sizeof(source),"%s/%s.comp",directory,name)>0);
    assert(snprintf(prefix,sizeof(prefix),"%s/%s",directory,name)>0);
    void *f=cs_frontend_compile(source,prefix);assert(f);return f;
}

static struct opengpu_cs_program compile(const char *directory,const char *name,void **frontend)
{
    *frontend=front(directory,name);
    struct opengpu_cs_program code;assert(!opengpu_cs_emit(cs_frontend_tokens(*frontend),&code));
    unsigned target=cs_frontend_storage_binding(*frontend,0)==0?0:1;
    unsigned uniform=!strcmp(name,"compare-inputs");
    assert(cs_frontend_storage_binding(*frontend,target)==0&&cs_frontend_storage_binding(*frontend,1-target)==1);
    assert(code.storage_atomics==(1u<<target)&&code.reads==(uniform?3u:1u<<target)&&code.writes==3&&code.words<=512);
    if(uniform)assert(code.constant_bytes[1]&&code.constant_bytes[1]<=64&&
        opengpu_compute_shader_validate_words_uniform_resources(code.code,code.words,
            12,128,false,false,false,true,false,false,true,false));
    else assert(opengpu_compute_shader_validate_words_all_resources(code.code,code.words,
        12,128,false,false,false,true,false,false,true));
    assert(!opengpu_compute_shader_validate_words_all_resources(code.code,code.words,
        12,128,false,false,false,true,false,false,false));
    char path[4096];assert(snprintf(path,sizeof(path),"%s/%s.native.words",directory,name)>0);
    FILE *out=fopen(path,"wx");assert(out);
    for(unsigned w=0;w<code.words;w++)assert(fprintf(out,"%08x\n",code.code[w])==9);
    assert(!fclose(out));
    assert(snprintf(path,sizeof(path),"%s/%s.native.bindings",directory,name)>0);
    out=fopen(path,"wx");assert(out);
    assert(fprintf(out,"target=%u\noutput=%u\n",target,1-target)>0&&!fclose(out));
    return code;
}

static void alias_operands(void)
{
    for(unsigned destination=0;destination<3;destination++) {
        char text[1024];struct tgsi_token tokens[256];struct opengpu_cs_program code;
        assert(snprintf(text,sizeof(text),"COMP\nDCL BUFFER[0..1]\nDCL TEMP[0..2]\n"
            "IMM[0] UINT32 {0,10,7,0}\nMOV TEMP[0].x, IMM[0].xxxx\n"
            "MOV TEMP[1].x, IMM[0].yyyy\nMOV TEMP[2].x, IMM[0].zzzz\n"
            "ATOMCAS TEMP[%u].x, BUFFER[0], TEMP[0].xxxx, TEMP[1].xxxx, TEMP[2].xxxx\n"
            "STORE BUFFER[1].x, IMM[0].xxxx, TEMP[%u].xxxx\nEND\n",destination,destination)>0);
        assert(tgsi_text_translate(text,tokens,256)&&!opengpu_cs_emit(tokens,&code));
        assert(opengpu_compute_shader_validate_words_all_resources(code.code,code.words,
            12,128,false,false,false,true,false,false,true));
        uint32_t target[4]={10,0x12345678,0,0},output[4]={0,0x98765432,0,0};
        struct cs_vm_view views[4]={{target,16,3},{output,16,3},{0},{0}};
        const uint32_t grid[3]={1,1,1},group[3]={0,0,0},local[3]={1,1,1};
        cs_vm_group(code.code,code.words,grid,group,local,views);
        assert(target[0]==7&&output[0]==10&&target[1]==0x12345678&&output[1]==0x98765432);
    }
    const char *text="COMP\nDCL BUFFER[0..1]\nDCL TEMP[0]\nDCL CONST[0][0]\n"
        "IMM[0] UINT32 {12,0,0,0}\nMOV TEMP[0].x, IMM[0].xxxx\n"
        "ATOMCAS TEMP[0].x, BUFFER[0], TEMP[0].xxxx, CONST[0][0].xxxx, CONST[0][0].yyyy\n"
        "STORE BUFFER[1].x, IMM[0].yyyy, TEMP[0].xxxx\nEND\n";
    struct tgsi_token tokens[256];struct opengpu_cs_program code;
    assert(tgsi_text_translate(text,tokens,256)&&!opengpu_cs_emit(tokens,&code));
    assert(opengpu_compute_shader_validate_words_uniform_resources(code.code,code.words,
        12,128,false,false,false,true,false,false,true,false));
    uint32_t target[5]={0x11,0x22,0x33,10,0x44},output[4]={0,0x55,0,0},params[4]={10,7,0,0};
    struct cs_vm_view views[4]={{target,20,3},{output,16,3},{0},{0}},uniforms[13]={{params,16,1}};
    const uint32_t grid[3]={1,1,1},group[3]={0,0,0},local[3]={1,1,1};
    cs_vm_group_uniforms(code.code,code.words,grid,group,local,views,uniforms);
    assert(target[3]==7&&output[0]==10&&target[0]==0x11&&target[2]==0x33&&target[4]==0x44&&output[1]==0x55);
}

static void dynamic_constants(const char *directory)
{
    void *f;compile(directory,"compare-inputs",&f);
    features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS|
        OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(f)};
    void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
    struct pipe_resource *r[4],*uniforms[36]={0};unsigned offsets[36]={0},sizes[36]={0};
    uint32_t *words[4];
    for(unsigned b=0;b<4;b++) {
        r[b]=buffer(s,4096);assert(r[b]);words[b]=pipe_opengpu_resource_map(opengpu_resource(r[b])->gpu);
        for(unsigned w=0;w<1024;w++)words[b][w]=0x1234fedeu;
    }
    for(unsigned w=0;w<4;w++)words[0][w]=(w+1)*11;
    for(unsigned w=0;w<8;w++)words[1][w]=w&3;
    uniforms[0]=buffer(s,4096);assert(uniforms[0]);sizes[0]=64;
    uint32_t *params=pipe_opengpu_resource_map(opengpu_resource(uniforms[0])->gpu);
    for(unsigned w=0;w<4;w++){params[w*4]=(w+1)*11;params[w*4+1]=params[w*4]+100;}
    cs_frontend_ubos(f,p,uniforms,offsets,sizes);cs_frontend_storage(f,p,r);
    struct pipe_grid_info grid={.work_dim=1,.block={4,1,1},.grid={2,1,1}};
    unsigned before=computes;p->launch_grid(p,&grid);assert(computes==before+1);
    for(unsigned w=0;w<4;w++)assert(words[0][w]==(w+1)*11+100);
    for(unsigned w=0;w<8;w++)assert(words[1][w]==(w%4+1)*11+(w>=4?100u:0u));
    for(unsigned b=0;b<4;b++)for(unsigned w=0;w<1024;w++)
        if((b==0&&w>=4)||(b==1&&w>=8)||b>=2)assert(words[b][w]==0x1234fedeu);
    p->delete_compute_state(p,cs);
    for(unsigned b=0;b<4;b++)pipe_resource_reference(&r[b],NULL);
    pipe_resource_reference(&uniforms[0],NULL);p->destroy(p);s->destroy(s);no_leaks();cs_frontend_destroy(f);
}

static uint32_t initial(unsigned op)
{
    const uint32_t values[]={10,0xfffffffeu,0xf0f0f0f0u,0x80u,0xf0f0f0f0u,
        7,0xffffffffu,0xffffffffu,7,10,10};
    assert(op<11);return values[op];
}
static uint32_t old_value(unsigned op,unsigned lane)
{
    if(op==1)return initial(op)+lane*5u;
    if(op==2)return initial(op)^((lane&1)?0x80000007u:0);
    if(!lane)return initial(op);
    const uint32_t after[]={7,0,0,0x80000087u,0x80000000u,0xfffffff9u,
        7,7,0x80000007u,7,10};
    return after[op];
}

int main(int argc,char **argv)
{
    assert(argc==2);assert(!old_callback_corpus());
    const char *names[]={"exchange","add","xor","or","and","signed-min","signed-max",
        "unsigned-min","unsigned-max","compare-match","compare-miss"};
    for(unsigned op=0;op<11;op++) {
        void *f;struct opengpu_cs_program code=compile(argv[1],names[op],&f);(void)code;
        if(op==9)negative_opcodes(cs_frontend_tokens(f));
        struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(f)};
        for(unsigned missing=0;missing<3;missing++) {
            features=missing==0?0:missing==1?OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE:
                OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS;
            struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
            assert(!pipe_opengpu_screen_compute_storage_atomics(opengpu_screen_gpu(s)));
            struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
            assert(!p->create_compute_state(p,&state));p->destroy(p);s->destroy(s);no_leaks();
        }
        features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS;
        struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
        assert(pipe_opengpu_screen_compute_storage_atomics(opengpu_screen_gpu(s)));
        struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
        void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
        struct pipe_resource *r[4];uint32_t *words[4];
        for(unsigned b=0;b<4;b++) {
            r[b]=buffer(s,4096);assert(r[b]);words[b]=pipe_opengpu_resource_map(opengpu_resource(r[b])->gpu);
            assert(words[b]);for(unsigned w=0;w<1024;w++)words[b][w]=0xa5a5a5a5u;
        }
        words[0][0]=initial(op);
        struct pipe_grid_info grid={.work_dim=1,.block={4,1,1},.grid={2,1,1}};
        struct pipe_shader_buffer native[2];unsigned target=0;
        for(unsigned slot=0;slot<2;slot++) {
            unsigned api=cs_frontend_storage_binding(f,slot);assert(api<2);
            native[slot]=(struct pipe_shader_buffer){.buffer=r[api],.buffer_size=4096};
            if(!api)target=slot;
        }
        p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,2,native,1u<<(1-target));
        unsigned before=computes;
        p->launch_grid(p,&grid);assert(computes==before&&words[0][0]==initial(op));
        cs_frontend_storage(f,p,r);p->launch_grid(p,&grid);assert(computes==before+1);
        for(unsigned lane=0;lane<8;lane++)assert(words[1][lane]==old_value(op,lane));
        assert(words[0][0]==old_value(op,8));
        for(unsigned b=0;b<4;b++)for(unsigned w=0;w<1024;w++)
            if((b==0&&w!=0)||(b==1&&w>=8)||b>=2)assert(words[b][w]==0xa5a5a5a5u);
        p->delete_compute_state(p,cs);
        for(unsigned b=0;b<4;b++)pipe_resource_reference(&r[b],NULL);
        p->destroy(p);s->destroy(s);no_leaks();cs_frontend_destroy(f);
    }
    for(unsigned edge=0;edge<2;edge++) {
        void *f;compile(argv[1],edge?"past-end":"endpoint",&f);cs_frontend_destroy(f);
    }
    void *f=front(argv[1],"branch");struct opengpu_cs_program code,before;
    memset(&code,0x79,sizeof(code));before=code;
    assert(opengpu_cs_emit(cs_frontend_tokens(f),&code)<0&&!memcmp(&code,&before,sizeof(code)));
    cs_frontend_destroy(f);
    negative_resources();
    alias_operands();dynamic_constants(argv[1]);
    puts("STORAGE_ATOMIC_FRONTEND_PASS operations=10 compareMatchAndMiss=true actualESSL310=true returnedOldWords=96 dynamicConstantAndTempAliases=true discoveryAndRWGates=true otherResourceKindsRejected=true guards=true noGPU=true");
    return 0;
}
