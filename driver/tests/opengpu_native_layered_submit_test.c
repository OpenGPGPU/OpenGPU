/* Actual version-2 Linux ioctl -> scheduler -> GS/layered private graph.
 * OS primitives and the final hardware kick are modeled: CPU-only scope. */
#include <stdbool.h>
#define CONFIG_COMPAT 1
static bool layered_compat;
static bool in_compat_syscall(void){return layered_compat;}
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN layered_legacy_submit_regression
#include "opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
static void sample_submit_gate(void)
{
    for(u32 version=6;version<=7;version++){
        struct native_kernel_session *s=native_kernel_session_create(7);assert(s);
        test_next_handle=2;
        u32 *code=native_kernel_original_allocate(s,1,4096);assert(code);code[0]=OPENGPU_SHADER_CEASE;
        u32 *defaults=native_kernel_original_allocate(s,2,4096);assert(defaults);defaults[1]=0x3f123456;
        struct native_test_record *r=calloc(1,sizeof(*r));
        struct drm_opengpu_native_layered_draw_plan *p=calloc(1,sizeof(*p));
        assert(r&&p);test_record_init(s,r,1,2);p->draw=r->plan;
        r->job[0]=0x474e4a02;r->job[2]=2;r->job[54]=1;
        r->encoded.frame.words[0]=0x474e4603;r->encoded.frame.words[2]=3;r->encoded.frame.words[299]=3;
        r->encoded.frame.words[320]=1;r->encoded.frame.words[321]=1;r->encoded.frame.words[322]=4096;
        r->encoded.frame.words[291]=10240;
        for(u32 i=0;i<r->encoded.resource_count;i++)if(r->encoded.resources[i].role==6){
            struct opengpu_native_frame_gem scratch=test_gem(s,10240);
            r->encoded.resources[i].gem_handle=scratch.handle;r->encoded.resources[i].bytes=scratch.bytes;
        }
        if(version==7){
            r->draw[0]=0x474e4402;r->draw[2]=2;r->draw[34]=12;r->draw[311]=6;r->draw[508]=1;
            p->geometry_program=(struct drm_opengpu_native_program_view){.handle=1,.bytes=4};
            p->geometry_uniforms=p->draw.vertex_uniforms;
            const u32 meta[]={0x474e4701,1024,1,7,8,9,6,32,0,256,4,4,0,0,0,1};
            memcpy(p->geometry_metadata,meta,sizeof(meta));
            for(u32 c=0;c<4;c++){p->geometry_metadata[32+c]=c;p->geometry_metadata[96+2*c]=1;p->geometry_metadata[97+2*c]=c;}
        }
        r->state[56]=version;
        struct opengpu_native_frame_gem output=test_gem(s,16384);
        struct drm_opengpu_native_completion_view view={.view={.handle=output.handle,.bytes=16384}};
        r->submit.version=2;r->submit.plan_stride=sizeof(*p);r->submit.plans=(uintptr_t)p;
        r->submit.reserved[0]=(uintptr_t)&view;s->gpu.hw.native_layered_transport=true;
        u64 seqno=r->submit.fence_seqno;
        assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && !s->count && r->submit.fence_seqno==seqno);
        /* Explicit CPU test-route cache, not an observed hardware capability. */
        s->gpu.hw.native_sample_transport=true;
        assert(!native_kernel_submit(s,&r->submit,NULL,NULL) && s->count==1);
        struct opengpu_sched_job *job=native_record(s,0);u32 va;
        assert(job && !opengpu_private_snapshot_native_layered_job_address(job->native_owner,job->native_job_segment,&va));
        u32 frame=test_read_va(s,va+21*4),states=test_read_va(s,frame+292*4);
        assert(test_read_va(s,states+56*4)==version && test_read_va(s,frame+291*4)==10240);
        assert(!native_kernel_run(s,0) && !native_kernel_retire(s,0,0));
        assert(!native_kernel_session_destroy(s) && !live_allocations && !fixture_buffers);
        free(p);free(r);
    }
    puts("sample submit: actual ioctl cache-to-builder ABI6/7 gate, copied RX graph and retirement PASS (CPU only; modeled hardware kick)");
}
int main(void)
{
    struct native_kernel_session *s=native_kernel_session_create(7);assert(s);
    test_next_handle=2;
    u32 *code=native_kernel_original_allocate(s,1,4096);assert(code);code[0]=0x30500073;
    u32 *defaults=native_kernel_original_allocate(s,2,4096);assert(defaults);defaults[1]=0x3f123456;
    struct native_test_record *r=calloc(1,sizeof(*r));
    struct drm_opengpu_native_layered_draw_plan *plan=calloc(1,sizeof(*plan));
    struct drm_opengpu_native_completion_view view={0};
    assert(r && plan);test_record_init(s,r,1,2);plan->draw=r->plan;
    r->job[0]=0x474e4a02;r->job[2]=2;r->job[54]=1;
    r->encoded.frame.words[0]=0x474e4603;r->encoded.frame.words[2]=3;r->encoded.frame.words[299]=3;
    r->encoded.frame.words[320]=1;r->encoded.frame.words[321]=256;r->encoded.frame.words[322]=4096;
    struct opengpu_native_frame_gem layered=test_gem(s,1024*1024+16384),output=test_gem(s,16384);
    for(u32 i=0;i<r->encoded.resource_count;i++)if(r->encoded.resources[i].role==1){
        r->encoded.resources[i].gem_handle=layered.handle;r->encoded.resources[i].bytes=1024*1024;
    }
    r->draw[0]=0x474e4402;r->draw[2]=2;r->draw[34]=12;r->draw[311]=6;r->draw[508]=1;
    r->state[56]=5;
    plan->geometry_program=(struct drm_opengpu_native_program_view){.handle=1,.bytes=4};
    plan->geometry_uniforms=plan->draw.vertex_uniforms;
    const u32 meta[]={0x474e4701,1024,1,7,8,9,6,32,0,256,4,4,0,0,0,1};
    memcpy(plan->geometry_metadata,meta,sizeof(meta));
    for(u32 c=0;c<4;c++){plan->geometry_metadata[32+c]=c;plan->geometry_metadata[96+2*c]=1;plan->geometry_metadata[97+2*c]=c;}
    view.view=(struct drm_opengpu_compute_uniform_view){.handle=output.handle,.bytes=16384};
    r->submit.version=2;r->submit.plan_stride=sizeof(*plan);r->submit.plans=(uintptr_t)plan;
    r->submit.reserved[0]=(uintptr_t)&view;
    u64 seqno=r->submit.fence_seqno;
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EOPNOTSUPP && s->count==0 && r->submit.fence_seqno==seqno);
    /* Explicit CPU test-route enable, not hardware discovery qualification. */
    s->gpu.hw.native_layered_transport=true;
    layered_compat=true;
    assert(native_pointer_valid(0x1234) && !native_pointer_valid(0x100001234ULL));
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && !s->count && r->submit.fence_seqno==seqno);
    layered_compat=false;
    view.reserved[1]=1;
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && s->count==0 && r->submit.fence_seqno==seqno);view.reserved[1]=0;
    r->submit.plan_stride=792;
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && s->count==0);r->submit.plan_stride=sizeof(*plan);
    r->submit.version=1;
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && s->count==0);r->submit.version=2;
    assert(!native_kernel_submit(s,&r->submit,NULL,NULL) && s->count==1 && r->submit.fence_seqno!=seqno);
    struct opengpu_sched_job *job=native_record(s,0);u32 va;assert(job && job->native_owner);
    assert(!opengpu_private_snapshot_native_layered_job_address(job->native_owner,job->native_job_segment,&va));
    assert(test_read_va(s,va)==0x474e4a02 && test_read_va(s,va+8)==2);
    u32 completion=test_read_va(s,va+48*4),draw=test_read_va(s,va+16*4),node=test_read_va(s,draw+509*4);
    assert(test_read_va(s,va+49*4)==completion+16384 && test_read_va(s,node)==0x474e4701);
    u32 root=test_read_va(s,node+24*4);assert(test_read_va(s,root)==0x47555202 && test_read_va(s,root+24)==3);
    assert(native_kernel_original_refcount(s,1)>1 && native_kernel_original_refcount(s,output.handle)>1);
    assert(!native_kernel_original_close(s,1) && !native_kernel_original_close(s,output.handle));
    assert(native_kernel_original_refcount(s,1)>0 && native_kernel_original_refcount(s,output.handle)>0);
    assert(!native_kernel_run(s,0) && !native_kernel_retire(s,0,0));
    assert(native_kernel_original_refcount(s,1)==0 && native_kernel_original_refcount(s,output.handle)==0);
    assert(!native_kernel_session_destroy(s));assert(!live_allocations && !fixture_buffers);free(plan);free(r);
    sample_submit_gate();
    puts("layered submit: actual version2 ioctl/scheduler/GS GUR2/RX/GNY lease, strict legacy/version/view gates and original GEM close refs PASS (CPU only; modeled hardware kick)");
    return 0;
}
