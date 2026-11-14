/* CPU source-proof fixture: placeholder CEASE words, not Mesa or GPU evidence.
 * Actual Linux snapshot/MMU/relocation/sidecar algorithms remain unmodified. */
#include "graphics_sample_kernel.c"
#include "sample_profile.inc"
static u32 fixed_input(unsigned binding,unsigned word)
{return (0x71005013u+binding*0x10203u+word*0x9e3779b9u)^((binding+13u)<<(word%23));}
static struct drm_opengpu_compute_uniform_view input_uniform(struct native_kernel_session *s,unsigned binding,unsigned record)
{
    unsigned size=binding>=40?4096:16384,offset=binding>=40?0:4+64*(binding%16);
    if(record && binding==35)offset=68;
    struct opengpu_native_frame_gem gem=test_gem(s,binding>=40?4096:20480);
    u32 *p=(u32 *)((u8 *)original_handle(s,gem.handle)->gem.vaddr+offset);
    for(unsigned i=0;i<size/4;i++)p[i]=fixed_input(binding,i);
    if(record && binding==35)p[0]^=0x12345678;
    if(record && binding==40)p[0]^=0xfedc1234;
    return (struct drm_opengpu_compute_uniform_view){.handle=gem.handle,.offset=offset,.bytes=size};
}
int main(int argc,char **argv)
{
    assert(argc==2);struct opengpu_native_sample_runtime runtime;assert(!opengpu_native_sample_runtime_decode(profile,117,&runtime));
    struct native_kernel_session *s=graphics_sample_session_create(17,&runtime);assert(s);test_next_handle=0;
    struct opengpu_native_frame_gem code[2]={test_gem(s,4096),test_gem(s,4096)};
    unsigned words[2]={308,301};
    for(unsigned stage=0;stage<2;stage++){
        u32 *p=original_handle(s,code[stage].handle)->gem.vaddr;
        for(unsigned i=0;i<words[stage];i++)p[i]=OPENGPU_SHADER_CEASE;
        char path[4096];sample_path(path,sizeof(path),argv[1],stage?"compiler-fs.words":"compiler-vs.words");
        FILE *f=fopen(path,"wx");assert(f && fwrite(p,4,words[stage],f)==words[stage] && !fclose(f));
    }
    struct drm_opengpu_compute_uniform_view uniform[2][13];unsigned bindings[2][12];
    for(unsigned stage=0;stage<2;stage++){
        uniform[stage][0]=input_uniform(s,40+stage,0);
        for(unsigned slot=0;slot<12;slot++){
            bindings[stage][slot]=stage?24+(slot+5)%12:11-slot;
            uniform[stage][slot+1]=input_uniform(s,bindings[stage][slot],0);
        }
    }
    for(unsigned record=0;record<2;record++){
        if(record){uniform[0][0]=input_uniform(s,40,1);
            for(unsigned slot=0;slot<12;slot++)if(bindings[1][slot]==35)uniform[1][slot+1]=input_uniform(s,35,1);}
        struct native_test_record r;test_record_init(s,&r,code[0].handle,uniform[0][0].handle);
        struct graphics_sample_source_plan plan={0};plan.draw=r.plan;
        plan.draw.vertex_program=(struct drm_opengpu_native_program_view){.handle=code[0].handle,.bytes=308*4};
        plan.draw.fragment_program=(struct drm_opengpu_native_program_view){.handle=code[1].handle,.bytes=301*4};
        memcpy(plan.bindings,bindings,sizeof(bindings));
        unsigned layouts[2][5]={{0,128,1,1,0},{1,128,0,1,1}};memcpy(plan.layout,layouts,sizeof(layouts));
        for(unsigned stage=0;stage<2;stage++){
            struct drm_opengpu_native_uniform_stage *u=stage?&plan.draw.fragment_uniforms:&plan.draw.vertex_uniforms;
            *u=(struct drm_opengpu_native_uniform_stage){.active_mask=4095,.defaults=uniform[stage][0]};
            memcpy(u->blocks,&uniform[stage][1],12*sizeof(u->blocks[0]));
        }
        struct opengpu_native_frame_gem scratch=test_gem(s,7680),input=test_gem(s,20480);
        plan.draw.vertex_scratch=(struct drm_opengpu_compute_uniform_view){.handle=scratch.handle,.bytes=7680};
        plan.draw.bindings[0]=(struct drm_opengpu_compute_uniform_view){.handle=input.handle,.offset=4,.bytes=48};
        const float triangle[12]={-1,-1,0,1,3,-1,0,1,-1,3,0,1};
        memcpy((u8 *)original_handle(s,input.handle)->gem.vaddr+4,triangle,sizeof(triangle));
        r.draw[34]=4;r.draw[35]=1;r.draw[37]=4;r.draw[42]=1;r.draw[189]=16;r.draw[343]=15;r.draw[345]=1;
        r.state[55]=1;
        struct opengpu_native_sample_frame_request req={.runtime=&runtime,.source_pitch={256},.frame={
            .caps=&runtime.limits.frame,.width=4,.height=4,.samples=1,.color_mask=1,.context=17,.pass=11,.generation=13,
            .state_count=1,.scratch_stride=8192,.primitives=test_gem(s,65536),.directory=test_gem(s,4096)}};
        struct opengpu_native_frame_gem color=test_gem(s,384);memset(original_handle(s,color.handle)->gem.vaddr,0x5a,384);
        req.frame.attachments[0]=(struct opengpu_native_frame_attachment){.format=26,.channel_mask=15,.load_op=1,
            .image={.gem={.handle=color.handle,.offset=64,.bytes=256},.row_pitch=64,.store=1}};
        req.frame.pool[0]=test_gem(s,4096);
        for(unsigned e=0;e<4;e++)req.frame.scratch[e]=test_gem(s,8192);
        assert(!opengpu_native_sample_frame_encode(&req,&r.encoded));
        memcpy(plan.resources,r.encoded.resources,r.encoded.resource_count*sizeof(plan.resources[0]));plan.resource_count=r.encoded.resource_count;
        struct opengpu_native_frame_gem completion=test_gem(s,16384);
        struct drm_opengpu_compute_uniform_view cv={.handle=completion.handle,.bytes=16384};
        struct opengpu_native_sample_job_encoding job;assert(!opengpu_native_sample_job_encode(&runtime,17,11,13,1,&cv,&job));
        plan.completion=job.completion;memcpy(r.job,job.job.words,sizeof(r.job));
        r.draw[314]=17;r.draw[315]=13;r.draw[316]=11;r.state[5]=r.state[38]=11;r.state[6]=r.state[37]=13;
        struct drm_opengpu_native_layered_draw_plan extended;assert(!opengpu_native_sample_ordinary_plan(&runtime,&plan.draw,&extended));
        r.submit.version=2;r.submit.plans=(uintptr_t)&extended;r.submit.plan_stride=sizeof(extended);
        r.submit.reserved[0]=(uintptr_t)&plan.completion;
        assert(!native_kernel_submit(s,&r.submit,NULL,NULL));
        char path[4096],name[64];snprintf(name,sizeof(name),"record-%u.source-plan.json",record);sample_path(path,sizeof(path),argv[1],name);
        assert(!graphics_sample_source_plan_write(s,record,path,&plan));
    }
    char path[4096];sample_path(path,sizeof(path),argv[1],"union.memory");assert(!native_kernel_export_memory(s,path));
    /* No MMIO execution or consumer exists in this source-only test. Cancel
     * its unused export marker before ordinary CPU fixture teardown. This is
     * not a cleanup ACK and is never compiled into the producer entry. */
    struct native_mmio_state *mmio=native_mmio_state(&s->gpu);
    assert(!mmio->native_submissions && !mmio->native_waiting && !s->hardware);
    mmio->exported=false;
    /* Source-test teardown only, no held/retired/GNY2 result is manufactured. */
    assert(!native_kernel_retire(s,0,0) && !native_kernel_retire(s,0,0));
    for(struct native_original *o=s->originals;o;o=o->next)if(o->visible)assert(!native_kernel_original_close(s,o->handle));
    assert(!native_kernel_session_destroy(s) && !fixture_buffers && !live_allocations);
    puts("actual Linux source snapshot exporter CPU fixture PASS; placeholder code, no compiler/GPU claim");return 0;
}
