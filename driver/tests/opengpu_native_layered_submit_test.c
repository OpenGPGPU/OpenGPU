/* Actual version-2 Linux ioctl -> scheduler -> GS/layered private graph.
 * OS primitives and the final hardware kick are modeled: CPU-only scope. */
#include <stdbool.h>
#define CONFIG_COMPAT 1
static bool layered_compat;
static bool in_compat_syscall(void){return layered_compat;}
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN layered_legacy_submit_regression
#include "opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
#include "../opengpu_native_frame_internal.h"
#include "../opengpu_texture_image_format_abi.h"
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
#define J(field) OPENGPU_NATIVE_LAYERED_JOB_##field##_WORD
#define D(field) OPENGPU_NATIVE_BINNED_DRAW_DRAW_##field##_WORD
#define V(field) D(VERTEX_GEOMETRY_VERTEX_##field)
#define I(field) V(INPUT_##field)
#define F(field) OPENGPU_TYPED_FRAGMENT_STATE_##field##_WORD
#define G(field) OPENGPU_NATIVE_GEOMETRY_PROGRAM_##field##_WORD

static size_t read_compiler_output(const char *path,void *bytes,size_t capacity)
{
    FILE *file=fopen(path,"rb");assert(file);
    size_t size=fread(bytes,1,capacity,file);
    assert(fgetc(file)==EOF && !ferror(file));
    assert(!fclose(file));
    return size;
}

static void check_pte(struct native_kernel_session *s,u32 address,u32 flags)
{
    const struct opengpu_buffer *l1=vm_live_l1(&s->context.vm,address/MMU_SUPERPAGE_SIZE);
    assert(l1);
    u32 leaf=((const u32 *)l1->cpu)[(address%MMU_SUPERPAGE_SIZE)/4096];
    assert((leaf&15)==flags);
}

static void compiled_geometry_submit(const char *const paths[4])
{
    enum { context=7,pass=11,generation=13,layer_pitch=4096,
        width=4,height=4,samples=4,lanes=128,vs_scratch=79*lanes*4,fs_scratch=95*lanes*4 };
    const struct opengpu_native_binned_host_caps caps={{
        0x474e4305,8,0x474e4a02,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813db02}};
    struct native_kernel_session *s=native_kernel_session_create(context);assert(s);
    test_next_handle=0;
    struct native_test_record *r=calloc(1,sizeof(*r));
    struct drm_opengpu_native_layered_draw_plan *plan=calloc(1,sizeof(*plan));
    assert(r && plan);

    u8 original[3][2048];
    struct opengpu_native_geometry_program metadata;
    struct drm_opengpu_native_program_view *programs[]={
        &plan->draw.vertex_program,&plan->geometry_program,&plan->draw.fragment_program};
    u8 *source[3];
    for(unsigned stage=0;stage<3;stage++){
        size_t size=read_compiler_output(paths[stage],original[stage],sizeof(original[stage]));
        assert(size && !(size&3u));
        u32 handle=++test_next_handle;
        u8 *allocation=native_kernel_original_allocate(s,handle,4096);assert(allocation);
        source[stage]=allocation+64;
        memcpy(source[stage],original[stage],size);
        *programs[stage]=(struct drm_opengpu_native_program_view){.handle=handle,.offset=64,.bytes=size};
    }
    assert(read_compiler_output(paths[3],&metadata,sizeof(metadata))==sizeof(metadata));
    assert(opengpu_native_geometry_program_encoding_valid(&metadata));
    assert(opengpu_native_geometry_word_at(&metadata,G(FLATMASK))==0 &&
        opengpu_native_geometry_word_at(&metadata,G(FLATMASK)+1)==0x70000000);
    memcpy(plan->geometry_metadata,&metadata,sizeof(metadata));

    struct opengpu_native_frame_request frame={
        .caps=&caps,.width=width,.height=height,.samples=samples,.color_mask=15,
        .tile_log2=3,.context=context,.pass=pass,.generation=generation,
        .state_count=1,.scratch_stride=fs_scratch};
    uint64_t source_pitch[6]={0},resolve_pitch[6]={0};
    for(unsigned mrt=0;mrt<4;mrt++){
        const unsigned texel=OPENGPU_TEXTURE_FORMAT_RGBA32F_BYTES;
        struct opengpu_native_frame_attachment *a=&frame.attachments[mrt];
        a->format=OPENGPU_TEXTURE_FORMAT_RGBA32F;a->load_op=1;a->channel_mask=15;
        a->image=(struct opengpu_native_frame_image){
            .gem=test_gem(s,layer_pitch+width*height*samples*texel),
            .row_pitch=width*samples*texel,.store=1};
        a->resolve=(struct opengpu_native_frame_image){
            .gem=test_gem(s,layer_pitch+width*height*texel),.row_pitch=width*texel,.store=1};
        source_pitch[mrt]=resolve_pitch[mrt]=layer_pitch;
        frame.scratch[mrt]=test_gem(s,fs_scratch);
    }
    frame.primitives=test_gem(s,4096);frame.directory=test_gem(s,64);frame.pool[0]=test_gem(s,4096);
    assert(!opengpu_native_frame_encode_gnc5(&frame,source_pitch,resolve_pitch,&r->encoded));
    r->encoded.frame.words[OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_LAYERS_WORD]=2;

    const u32 job[128]={OPENGPU_NATIVE_LAYERED_JOB_MAGIC,512,2,16,16,16,4,4,
        [J(JOB_DRAWCOUNT)]=1,[J(JOB_DRAWSTRIDE)]=2048,[J(JOB_IDENTITY_CONTEXT)]=context,
        [J(JOB_IDENTITY_PASS)]=pass,[J(JOB_IDENTITY_GENERATION)]=generation,[J(COMPLETION_WRITABLE)]=1};
    const u32 draw[512]={OPENGPU_NATIVE_GEOMETRY_DRAW_MAGIC,2048,2,16,16,16,4,4,
        [D(VERSION)]=1,[D(RASTER_VERSION)]=1,[D(RASTER_SAMPLEMODE)]=2,
        [D(RASTER_SCREENW)]=width,[D(RASTER_SCREENH)]=height,[D(RASTER_FRONTFACECCW)]=1,
        [D(RASTER_LINEWIDTH)]=0x3f800000,[D(RASTER_POINTSIZE)]=0x3f800000,
        [D(VERTEX_VIEWPORT_VERSION)]=1,[D(VERTEX_VIEWPORT_EXTENTX)]=width,
        [D(VERTEX_VIEWPORT_EXTENTY)]=height,[D(VERTEX_VIEWPORT_FARDEPTH)]=0x3f800000,
        [D(VERTEX_GEOMETRY_TOPOLOGY)]=4,[V(ATTRIBUTESLOTS)]=2,[V(VARYINGSLOTS)]=16,
        [V(VARYINGMASK)]=0x77777777,[V(VARYINGMASK)+1]=0x77777777,[V(VERSION)]=1,
        [I(ATTRIBUTES0_COMPONENTS)]=4,[I(ATTRIBUTES0_ENABLED)]=1,
        [I(ATTRIBUTES1_COMPONENTS)]=3,[I(ATTRIBUTES1_ENABLED)]=1,[I(ATTRIBUTES1_OFFSET)]=16,
        [I(BINDINGS0_STRIDE)]=32,[I(COUNT)]=3,[I(INSTANCES)]=1,[I(VERSION)]=2,
        [I(IDENTITY_CONTEXT)]=context,[I(IDENTITY_PASS)]=pass,[I(IDENTITY_GENERATION)]=generation,
        [OPENGPU_NATIVE_GEOMETRY_DRAW_GEOMETRY_VERSION_WORD]=1};
    const u32 state[128]={OPENGPU_TYPED_FRAGMENT_STATE_MAGIC,512,1,4,
        [F(HEADER_CONTEXT)]=context,[F(HEADER_PASS)]=pass,[F(HEADER_GENERATION)]=generation,
        [F(BLEND_VERSION)]=1,[F(TESTS_VERSION)]=1,[F(VERSION)]=1,[F(ENGINECOUNT)]=4,
        [F(FRAGMENT_IDENTITY_CONTEXT)]=context,[F(FRAGMENT_IDENTITY_PASS)]=pass,
        [F(FRAGMENT_IDENTITY_GENERATION)]=generation,[F(FRAGMENT_VERSION)]=7,
        [F(FRAGMENT_VARYINGSLOTS)]=16,[F(FRAGMENT_COLORSLOTS)]=4,[F(FRAGMENT_COLORMASK)]=15,
        [F(FRAGMENT_SAMPLEMODE)]=2};
    memcpy(r->job,job,sizeof(job));memcpy(r->draw,draw,sizeof(draw));memcpy(r->state,state,sizeof(state));
    const float vertices[3][8]={
        {-1.5f,-0.75f,0,1,0.25f,0.5f,0.75f,0},
        {0.75f,-0.75f,0,1,1.25f,1.5f,1.75f,0},
        {0,0.75f,0,1,2.25f,2.5f,2.75f,0}};
    u32 vertex_handle=++test_next_handle;
    void *vertex=native_kernel_original_allocate(s,vertex_handle,4096);assert(vertex);
    memcpy(vertex,vertices,sizeof(vertices));
    plan->draw.bindings[0]=(struct drm_opengpu_compute_uniform_view){.handle=vertex_handle,.bytes=sizeof(vertices)};
    plan->draw.vertex_scratch=(struct drm_opengpu_compute_uniform_view){
        .handle=test_gem(s,vs_scratch).handle,.bytes=vs_scratch};
    u32 completion_handle=test_gem(s,16384).handle;
    struct drm_opengpu_native_completion_view completion={.view={.handle=completion_handle,.bytes=16384}};
    r->submit=(struct drm_opengpu_submit_native_binned){.version=2,.context_id=context,.out_syncobj=2,
        .job=(uintptr_t)r->job,.frame=(uintptr_t)&r->encoded.frame,.draws=(uintptr_t)r->draw,
        .draws_bytes=sizeof(r->draw),.states=(uintptr_t)r->state,.states_bytes=sizeof(r->state),
        .plans=(uintptr_t)plan,.plan_count=1,.plan_stride=sizeof(*plan),
        .resources=(uintptr_t)r->encoded.resources,.resource_count=r->encoded.resource_count,
        .resource_stride=sizeof(r->encoded.resources[0]),.reserved={(uintptr_t)&completion,0},.fence_seqno=0xfeed};
    s->gpu.hw.native_layered_transport=true;
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && !s->count && r->submit.fence_seqno==0xfeed);
    /* The CPU fixture populates the sample discovery cache for admission. */
    s->gpu.hw.native_sample_transport=true;
    memset(source[2],0xff,4);
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && !s->count && r->submit.fence_seqno==0xfeed);
    memcpy(source[2],original[2],4);
    assert(!native_kernel_submit(s,&r->submit,NULL,NULL) && s->count==1 && r->submit.fence_seqno!=0xfeed);

    struct opengpu_sched_job *published=native_record(s,0);u32 va;
    assert(published && !opengpu_private_snapshot_native_layered_job_address(
        published->native_owner,published->native_job_segment,&va));
    const u32 draw_va=test_read_va(s,va+4*J(JOB_DRAWBASE)),frame_va=test_read_va(s,va+4*J(JOB_FRAMEBASE));
    const u32 state_va=test_read_va(s,frame_va+OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_BASE_WORD*4);
    const u32 gng_va=test_read_va(s,draw_va+4*OPENGPU_NATIVE_GEOMETRY_DRAW_GEOMETRY_BASE_WORD);
    const u32 pc[]={test_read_va(s,draw_va+4*V(SHADERPC)),test_read_va(s,gng_va+4*G(PC)),
        test_read_va(s,state_va+4*F(FRAGMENT_SHADERPC))};
    memset(plan->geometry_metadata,0xa5,sizeof(plan->geometry_metadata));
    for(unsigned stage=0;stage<3;stage++){
        u32 handle=programs[stage]->handle,bytes=programs[stage]->bytes;
        memset(source[stage],0xa5,bytes);
        assert(native_kernel_original_refcount(s,handle)>1);
        assert(!native_kernel_original_close(s,handle) && native_kernel_original_refcount(s,handle)>0);
        check_pte(s,pc[stage],11);
        for(unsigned word=0;word<bytes/4;word++)
            assert(test_read_va(s,pc[stage]+4*word)==opengpu_native_geometry_word_at(original[stage],word));
    }
    check_pte(s,gng_va,3);
    for(unsigned word=0;word<OPENGPU_NATIVE_GEOMETRY_PROGRAM_WORDS;word++){
        if(word>=G(PC) && word<=G(UNIFORMS_ADDRESS))continue;
        assert(test_read_va(s,gng_va+4*word)==opengpu_native_geometry_word_at(&metadata,word));
    }
    assert(test_read_va(s,gng_va+4*G(CODE_LIMIT))==pc[1]+programs[1]->bytes);
    assert(test_read_va(s,frame_va+4*OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_LAYERS_WORD)==2);
    const u32 gny_va=test_read_va(s,va+4*J(COMPLETION_BASE));
    check_pte(s,gny_va,7);
    check_pte(s,gny_va+16384-4,7);
    assert(!native_kernel_run(s,0) && !native_kernel_retire(s,0,0));
    for(unsigned stage=0;stage<3;stage++)assert(!native_kernel_original_refcount(s,programs[stage]->handle));
    assert(!native_kernel_session_destroy(s) && !live_allocations && !fixture_buffers);
    free(plan);free(r);
    puts("Original Mesa VS/GS/ABI7 FS on the protected version-2 ioctl, RX code and GNG1 flat mask PASS (CPU only)");
}

#undef J
#undef D
#undef V
#undef I
#undef F
#undef G

int main(int argc,char **argv)
{
    assert(argc==1 || argc==5);
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
    if(argc==5)compiled_geometry_submit((const char *const *)(argv+1));
    puts("layered submit: actual version2 ioctl/scheduler/GS GUR2/RX/GNY lease, strict legacy/version/view gates and original GEM close refs PASS (CPU only; modeled hardware kick)");
    return 0;
}
