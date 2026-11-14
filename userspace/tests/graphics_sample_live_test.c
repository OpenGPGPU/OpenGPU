/* Actual Linux owner/lifetime glue with an EXTERNAL SYNTHETIC FILE peer.
 * Placeholder CEASE programs do not execute or establish Mesa/GPU evidence. */
#define OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE 1
#include "graphics_sample_kernel.c"
#include "sample_profile.inc"

static u32 input_value(unsigned binding,unsigned word)
{return (0x71005013u+binding*0x10203u+word*0x9e3779b9u)^((binding+13u)<<(word%23));}
static struct drm_opengpu_compute_uniform_view source_uniform(struct native_kernel_session *s,unsigned binding,unsigned record)
{
    unsigned size=binding>=40?4096:16384,offset=binding>=40?0:4+64*(binding%16);
    if(record && binding==35)offset=68;
    struct opengpu_native_frame_gem gem=test_gem(s,binding>=40?4096:20480);
    u32 *p=(u32 *)((u8 *)original_handle(s,gem.handle)->gem.vaddr+offset);
    for(unsigned i=0;i<size/4;i++)p[i]=input_value(binding,i);
    if(record && binding==35)p[0]^=0x12345678;
    if(record && binding==40)p[0]^=0xfedc1234;
    return (struct drm_opengpu_compute_uniform_view){.handle=gem.handle,.offset=offset,.bytes=size};
}
static void check_full_final_fields(void)
{
    struct graphics_native_observation a={0},b;
#define DIFFER(member) do {b=a;b.member^=1;assert(!sample_live_same_retired(&a,&b));}while(0)
    DIFFER(record);DIFFER(command_id);DIFFER(completion);DIFFER(append_read_bytes);
    DIFFER(held_legacy_status);DIFFER(held_status);DIFFER(held_irq);DIFFER(held_irq_asserted);
    DIFFER(retired_status);DIFFER(retired_irq);DIFFER(retired_irq_asserted);DIFFER(external_drained);
    for(unsigned i=0;i<12;i++){b=a;b.gnx[i]^=1;assert(!sample_live_same_retired(&a,&b));}
#undef DIFFER
    assert(sample_live_same_retired(&a,&a));
}
static void retained(struct native_kernel_session *s)
{
    assert(s->count==2 && !s->context_destroyed && s->context.vm.root.cpu);
    assert(native_kernel_session_destroy(s)==-EBUSY);
    for(unsigned i=0;i<2;i++){
        struct native_kernel_record *r=native_session_record(s,i);assert(r && r->job && r->job->native_owner && !r->freed);
        for(struct snapshot_segment *p=r->job->native_owner->segments;p;p=p->next)
            assert(p->backing.cpu && p->lease.active);
        for(unsigned n=0;n<r->job->object_count;n++)assert(original_object(r->job->objects[n])->refs>=2);
    }
}
static void destroy_safe(struct native_kernel_session *s)
{
    for(struct native_original *o=s->originals;o;o=o->next)if(o->visible)assert(!native_kernel_original_close(s,o->handle));
    assert(!native_kernel_session_destroy(s) && !fixture_buffers && !live_allocations);
}
int main(int argc,char **argv)
{
    assert(argc==3);const char *directory=argv[1],*mode=argv[2];check_full_final_fields();
    struct opengpu_native_sample_runtime runtime;assert(!opengpu_native_sample_runtime_decode(profile,117,&runtime));
    struct native_kernel_session *s=graphics_sample_session_create(17,&runtime);assert(s);test_next_handle=0;
    struct opengpu_native_frame_gem code[2]={test_gem(s,4096),test_gem(s,4096)};
    struct drm_opengpu_native_program_view programs[2];unsigned words[2]={308,301};
    for(unsigned stage=0;stage<2;stage++){
        u32 *p=original_handle(s,code[stage].handle)->gem.vaddr;
        for(unsigned n=0;n<words[stage];n++)p[n]=OPENGPU_SHADER_CEASE;
        programs[stage]=(struct drm_opengpu_native_program_view){.handle=code[stage].handle,.bytes=words[stage]*4};
        char path[4096];sample_path(path,sizeof(path),directory,stage?"native-real-fs.words":"native-real-vs.words");
        FILE *f=fopen(path,"wx");assert(f && fwrite(p,4,words[stage],f)==words[stage] && !fclose(f));
    }
    unsigned bindings[2][12];struct drm_opengpu_compute_uniform_view uniform[2][13];
    for(unsigned stage=0;stage<2;stage++){
        uniform[stage][0]=source_uniform(s,40+stage,0);
        for(unsigned slot=0;slot<12;slot++){
            bindings[stage][slot]=stage?24+(slot+5)%12:11-slot;
            uniform[stage][slot+1]=source_uniform(s,bindings[stage][slot],0);
        }
    }
    for(unsigned record=0;record<2;record++){
        if(record){uniform[0][0]=source_uniform(s,40,1);
            for(unsigned slot=0;slot<12;slot++)if(bindings[1][slot]==35)uniform[1][slot+1]=source_uniform(s,35,1);}
        struct native_test_record r;test_record_init(s,&r,code[0].handle,uniform[0][0].handle);
        struct graphics_sample_source_plan proof={0};proof.draw=r.plan;
        proof.draw.vertex_program=programs[0];proof.draw.fragment_program=programs[1];
        memcpy(proof.bindings,bindings,sizeof(bindings));unsigned layouts[2][5]={{0,128,1,1,0},{1,128,0,1,1}};
        memcpy(proof.layout,layouts,sizeof(layouts));
        for(unsigned stage=0;stage<2;stage++){
            struct drm_opengpu_native_uniform_stage *u=stage?&proof.draw.fragment_uniforms:&proof.draw.vertex_uniforms;
            *u=(struct drm_opengpu_native_uniform_stage){.active_mask=4095,.defaults=uniform[stage][0]};
            memcpy(u->blocks,&uniform[stage][1],12*sizeof(u->blocks[0]));
        }
        struct opengpu_native_frame_gem vs=test_gem(s,7680),input=test_gem(s,20480);
        proof.draw.vertex_scratch=(struct drm_opengpu_compute_uniform_view){.handle=vs.handle,.bytes=7680};
        proof.draw.bindings[0]=(struct drm_opengpu_compute_uniform_view){.handle=input.handle,.offset=4,.bytes=48};
        const float triangle[]={-1,-1,0,1,3,-1,0,1,-1,3,0,1};
        memcpy((u8 *)original_handle(s,input.handle)->gem.vaddr+4,triangle,sizeof(triangle));
        r.draw[34]=4;r.draw[35]=1;r.draw[37]=4;r.draw[42]=1;r.draw[189]=16;r.draw[343]=15;r.draw[345]=1;r.state[55]=1;
        struct opengpu_native_sample_frame_request request={.runtime=&runtime,.source_pitch={256},.frame={
            .caps=&runtime.limits.frame,.width=4,.height=4,.samples=1,.color_mask=1,.context=17,.pass=11,.generation=13,
            .state_count=1,.scratch_stride=8192,.primitives=test_gem(s,65536),.directory=test_gem(s,4096)}};
        struct opengpu_native_frame_gem color=test_gem(s,384);memset(original_handle(s,color.handle)->gem.vaddr,0x5a,384);
        request.frame.attachments[0]=(struct opengpu_native_frame_attachment){.format=26,.channel_mask=15,.load_op=1,
            .image={.gem={.handle=color.handle,.offset=64,.bytes=256},.row_pitch=64,.store=1}};
        request.frame.pool[0]=test_gem(s,4096);for(unsigned i=0;i<4;i++)request.frame.scratch[i]=test_gem(s,8192);
        assert(!opengpu_native_sample_frame_encode(&request,&r.encoded));
        memcpy(proof.resources,r.encoded.resources,r.encoded.resource_count*sizeof(proof.resources[0]));proof.resource_count=r.encoded.resource_count;
        struct opengpu_native_frame_gem completion=test_gem(s,16384);
        struct drm_opengpu_compute_uniform_view cv={.handle=completion.handle,.bytes=16384};
        struct opengpu_native_sample_job_encoding job;assert(!opengpu_native_sample_job_encode(&runtime,17,11,13,1,&cv,&job));
        proof.completion=job.completion;memcpy(r.job,job.job.words,sizeof(r.job));
        r.draw[314]=17;r.draw[315]=13;r.draw[316]=11;r.state[5]=r.state[38]=11;r.state[6]=r.state[37]=13;
        struct drm_opengpu_native_layered_draw_plan plan;assert(!opengpu_native_sample_ordinary_plan(&runtime,&proof.draw,&plan));
        r.submit.version=2;r.submit.plans=(uintptr_t)&plan;r.submit.plan_stride=sizeof(plan);r.submit.reserved[0]=(uintptr_t)&proof.completion;
        assert(!native_kernel_submit(s,&r.submit,NULL,NULL));
        char name[64],path[4096];snprintf(name,sizeof(name),"record-%u.source-plan.json",record);sample_path(path,sizeof(path),directory,name);
        assert(!graphics_sample_source_plan_write(s,record,path,&proof));
    }
    char path[4096];sample_path(path,sizeof(path),directory,"compiler-reflection.json");
    if(strcmp(mode,"missing-input")){
        FILE *f=fopen(path,"wx");assert(f);fputs("{\"schema\":\"explicit-synthetic-control-fixture\"}\n",f);assert(!fclose(f));
    }
    if(!strcmp(mode,"bad-profile"))s->profile->words[0].value^=1;
    if(!strcmp(mode,"bad-rx")){
        struct snapshot_segment *p=native_session_record(s,1)->job->native_owner->segments;
        while(p && !p->executable) {
            p=p->next;
        }
        assert(p);
        ((u8 *)p->backing.cpu)[0]^=1;
    }
    if(!strcmp(mode,"unretained")){
        struct opengpu_native_frame_gem other=test_gem(s,4096);memcpy(original_handle(s,other.handle)->gem.vaddr,
            original_handle(s,programs[0].handle)->gem.vaddr,programs[0].bytes);programs[0].handle=other.handle;
    }
    if(!strcmp(mode,"bad-word-file")){
        sample_path(path,sizeof(path),directory,"native-real-vs.words");FILE *f=fopen(path,"r+b");assert(f);assert(fputc(0,f)!=EOF && !fclose(f));
    }
    if(!strcmp(mode,"closed-inputs")){
        /* Mirror the Mesa entry: Gallium/context references are gone before
         * execution, leaving each original code view owned by the two jobs. */
        for(struct native_original *o=s->originals;o;o=o->next)if(o->visible)assert(!native_kernel_original_close(s,o->handle));
        for(unsigned i=0;i<2;i++)assert(native_kernel_original_refcount(s,programs[i].handle)==2);
    }
    int error=graphics_sample_graphics_host_execute(s,directory,programs);
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    bool early=!strcmp(mode,"bad-profile") || !strcmp(mode,"bad-rx") || !strcmp(mode,"unretained") ||
        !strcmp(mode,"bad-word-file") || !strcmp(mode,"missing-input");
    if(early){
        assert(error<0 && !m->exported && !m->native_submissions);retained(s);
        /* No command/union was ever exposed; ordinary test-only cancellation. */
        assert(!native_kernel_retire(s,0,-ECANCELED) && !native_kernel_retire(s,0,-ECANCELED));destroy_safe(s);
    }else if(!strcmp(mode,"ok") || !strcmp(mode,"closed-inputs") || !strcmp(mode,"final-bad")){
        assert(strcmp(mode,"final-bad")?!error:error<0);
        assert(m->cleanup_done && !m->cleanup_active && !m->cleanup_error && s->context_destroyed && !s->count);
        assert(m->native_submissions==2 && m->count==m->cleanup_start);
        for(unsigned i=0;i<2;i++)assert(native_session_record(s,i)->freed && !native_session_record(s,i)->job);
        destroy_safe(s);
    }else{
        assert(error<0 && !m->cleanup_done);retained(s);
        if(!strcmp(mode,"held-bad") || !strcmp(mode,"held-unbound")){
            assert(m->native_submissions==1 && !native_session_record(s,0)->drained && !native_session_record(s,1)->captured);
        }else if(!strcmp(mode,"retired-bad")){
            assert(m->native_submissions==1 && native_session_record(s,0)->drained && !native_session_record(s,0)->verified && !native_session_record(s,1)->captured);
        }else if(!strcmp(mode,"cleanup-denied")){
            assert(!m->cleanup_active && m->native_submissions==2 && native_session_record(s,0)->verified && native_session_record(s,1)->verified);
        }else{
            assert(!strcmp(mode,"cleanup-bad") || !strcmp(mode,"cleanup-missing"));
            assert(m->cleanup_active && m->cleanup_error && s->gpu.hw.wedged);
        }
        /* Deliberately quarantine pending test state, never fake a reset,
         * completion, cleanup ACK or release after external protocol failure. */
        printf("CPU control-negative %s quarantined, GPU=false\n",mode);fflush(stdout);_exit(0);
    }
    printf("CPU ordinary112 transport %s PASS, synthetic files/CEASE, GPU=false\n",mode);return 0;
}
