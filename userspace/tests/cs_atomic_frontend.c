/* SPDX-License-Identifier: MIT */
/* Actual ESSL310/Mesa -> checked storage RMW words and production callbacks.
 * The arithmetic VM is a CPU oracle, not an implementation of GPU/L2 timing. */
#define OPENGPU_CS_REAL_TGSI_DUMP 1
#define main old_callback_corpus
#include "cs_callback_flow.c"
#undef main
#include "cs_glsl_frontend.h"

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
    assert(cs_frontend_storage_binding(*frontend,target)==0&&cs_frontend_storage_binding(*frontend,1-target)==1);
    assert(code.storage_atomics==(1u<<target)&&code.reads==(1u<<target)&&code.writes==3&&code.words<=512);
    assert(opengpu_compute_shader_validate_words_all_resources(code.code,code.words,
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
    puts("STORAGE_ATOMIC_FRONTEND_PASS operations=10 compareMatchAndMiss=true actualESSL310=true returnedOldWords=88 discoveryAndRWGates=true guards=true noGPU=true");
    return 0;
}
