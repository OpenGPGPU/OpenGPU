/* Private CPU qualification bridge. Actual protected Linux ioctl/owner/MMU;
 * OS primitives and final kick/completion are modeled, never GPU evidence. */
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN sample_legacy_regression
#include "../driver/tests/opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
#include "../driver/opengpu_native_sample_transport_features.h"
#include "native_sample_expected_profile.inc"

struct sample_program {u32 meta[15], code[512];unsigned char bytes[2048];};
static bool read_le(FILE *f,u32 *v)
{unsigned char b[4];if(fread(b,1,4,f)!=4)return false;*v=b[0]|(u32)b[1]<<8|(u32)b[2]<<16|(u32)b[3]<<24;return true;}
static bool read_program(const char *path,struct sample_program *p)
{
    FILE *f=fopen(path,"rb");char magic[8];bool ok=false;
    if(!f)return false;
    if(fread(magic,1,8,f)!=8 || memcmp(magic,"OGNSF001",8))goto done;
    for(unsigned i=0;i<15;i++)if(!read_le(f,&p->meta[i]))goto done;
    if(!p->meta[14] || p->meta[14]>512)goto done;
    for(unsigned i=0;i<p->meta[14];i++){
        if(!read_le(f,&p->code[i]))goto done;
        for(unsigned b=0;b<4;b++)p->bytes[4*i+b]=(unsigned char)(p->code[i]>>(8*b));
    }
    ok=fgetc(f)==EOF && !ferror(f);
 done:fclose(f);return ok;
}
static bool valid_program(const struct sample_program *p)
{
    struct opengpu_native_sample_shader_layout l;
    /* ABI7 requires a real GS draw/owner, which this ordinary bridge lacks. */
    if(p->meta[0]!=1 || p->meta[1]!=6 || p->meta[11]!=128 || p->meta[12]!=16 || p->meta[13]!=4 ||
       p->meta[9]!=1 || p->meta[10]!=0 || p->meta[6]>15 || p->meta[7]>1 || p->meta[8]>1 ||
       !opengpu_native_sample_shader_layout(6,16,4,128,&l))return false;
    return p->meta[2]==l.input_arrays && p->meta[3]==512 && p->meta[4]==l.prefix_bytes;
}
struct bridge_profile {const struct native_kernel_profile_word *words;unsigned count;};
static u32 profile_read(void *opaque,u32 address)
{
    const struct bridge_profile *p=opaque;
    for(unsigned i=0;i<p->count;i++)if(p->words[i].address==address)return p->words[i].value;
    return 0;
}
static bool install_profile(struct native_kernel_session *s,unsigned count)
{
    /* Source-fixed CPU fixtures, never observed DUT words. Older tables are
     * preserved verbatim rather than masking a new profile's feature flags. */
    struct bridge_profile p={sample_expected_profile,117};
    if(count==96)p=(struct bridge_profile){legacy_expected_profile,101};
    else if(count==104)p=(struct bridge_profile){layered_expected_profile,109};
    else if(count!=112)return false;
    struct opengpu_native_source_limits limits={0};bool layered=false,sample=false;
    const u32 caps=profile_read(&p,GPU_REG_CAPABILITIES);
    if(!opengpu_read_native_sample_transport_limits(profile_read(&p,GPU_REG_ID),profile_read(&p,0x368),caps,
         count==112?0x540:0x520,&p,profile_read,&limits,&layered,&sample) || !layered || !sample)return false;
    s->gpu.hw.capabilities=caps;s->gpu.hw.native_sources.available=limits.available;
    s->gpu.hw.native_sources.capacity=limits.capacity;s->gpu.hw.native_sources.engines=limits.engines;
    s->gpu.hw.native_sources.frame=limits.frame;
    s->gpu.hw.native_layered_transport=layered;s->gpu.hw.native_sample_transport=sample;
    return true;
}
static void make_record(struct native_kernel_session *s,struct native_test_record *r,
    struct drm_opengpu_native_layered_draw_plan *plan,const struct sample_program *p,unsigned samples)
{
    test_record_init(s,r,1,2);
    const struct opengpu_native_binned_host_caps caps={{0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813d902}};
    struct opengpu_native_frame_request req={.caps=&caps,.width=4,.height=4,.samples=samples,.color_mask=15,
        .context=s->context.id,.pass=8,.generation=9,.state_count=1,.scratch_stride=49152};
    for(unsigned c=0;c<4;c++){
        req.attachments[c].format=35;req.attachments[c].channel_mask=15;req.attachments[c].load_op=1;
        for(unsigned k=0;k<4;k++)req.attachments[c].clear[k]=0x3e800000;
        req.attachments[c].image=(struct opengpu_native_frame_image){.gem=test_gem(s,4*4*16*samples),.row_pitch=4*16*samples,.store=1};
    }
    req.attachments[4].format=3;req.attachments[4].load_op=1;req.attachments[4].clear[0]=0x3f800000;
    req.attachments[4].image=(struct opengpu_native_frame_image){.gem=test_gem(s,4*4*4*samples),.row_pitch=4*4*samples,.store=1};
    req.primitives=test_gem(s,4096);req.directory=test_gem(s,64);req.pool[0]=test_gem(s,4096);
    for(unsigned e=0;e<4;e++)req.scratch[e]=test_gem(s,49152);
    assert(!opengpu_native_frame_encode(&req,&r->encoded));
    r->submit.resource_count=r->encoded.resource_count;
    r->job[0]=0x474e4a02;r->job[2]=2;r->job[54]=1;
    r->encoded.frame.words[0]=0x474e4603;r->encoded.frame.words[2]=3;r->encoded.frame.words[299]=3;
    r->encoded.frame.words[320]=1;r->encoded.frame.words[321]=1;r->encoded.frame.words[322]=4096;
    r->draw[20]=r->state[39]=samples==4?2:samples==2?1:0;
    r->draw[345]=16;
    struct opengpu_native_frame_gem vs_scratch=test_gem(s,40960);
    r->plan.vertex_scratch=(struct drm_opengpu_compute_uniform_view){.handle=vs_scratch.handle,.bytes=40960};
    r->state[82]=1;r->state[83]=7;r->state[84]=1;
    r->state[29]=p->meta[7];r->state[30]=15;r->state[31]=4;r->state[32]=0;r->state[55]=16;r->state[56]=6;
    *plan=(struct drm_opengpu_native_layered_draw_plan){.draw=r->plan};
    plan->draw.fragment_program=(struct drm_opengpu_native_program_view){.handle=3,.bytes=p->meta[14]*4};
    memset(&plan->draw.fragment_uniforms,0,sizeof(plan->draw.fragment_uniforms));
    struct opengpu_native_frame_gem out=test_gem(s,16384);
    static struct drm_opengpu_native_completion_view view;
    view=(struct drm_opengpu_native_completion_view){.view={.handle=out.handle,.bytes=16384}};
    r->submit.version=2;r->submit.plan_stride=sizeof(*plan);r->submit.plans=(uintptr_t)plan;r->submit.reserved[0]=(uintptr_t)&view;
}
static void assert_rx(struct native_kernel_session *s,u32 pc,const struct sample_program *p)
{
    for(unsigned i=0;i<p->meta[14];i++){
        u32 va=pc+4*i;const struct opengpu_buffer *l1=vm_live_l1(&s->context.vm,va/MMU_SUPERPAGE_SIZE);assert(l1);
        u32 leaf=((u32 *)l1->cpu)[(va%MMU_SUPERPAGE_SIZE)/4096];
        assert((leaf&(MMU_PTE_R|MMU_PTE_X|MMU_PTE_W))==(MMU_PTE_R|MMU_PTE_X));
        assert(test_read_va(s,va)==p->code[i]);
    }
}
#ifndef OPENGPU_NATIVE_SAMPLE_CPU_ENTRY
#define OPENGPU_NATIVE_SAMPLE_CPU_ENTRY main
#endif
int OPENGPU_NATIVE_SAMPLE_CPU_ENTRY(int argc,char **argv)
{
    if(argc!=4){fprintf(stderr,"usage: %s program.ognsf 1|2|4 96|104|112\n",argv[0]);return 2;}
    struct sample_program p={0};if(!read_program(argv[1],&p) || !valid_program(&p)){fprintf(stderr,"invalid ordinary ABI6 program contract\n");return 2;}
    if((strcmp(argv[2],"1") && strcmp(argv[2],"2") && strcmp(argv[2],"4")) ||
       (strcmp(argv[3],"96") && strcmp(argv[3],"104") && strcmp(argv[3],"112")))return 2;
    unsigned samples=(unsigned)strtoul(argv[2],NULL,10),count=(unsigned)strtoul(argv[3],NULL,10);
    if(samples!=1 && samples!=2 && samples!=4)return 2;
    struct native_kernel_session *s=native_kernel_session_create(7);assert(s);
    if(!install_profile(s,count)){assert(!native_kernel_session_destroy(s));fprintf(stderr,"sample owner profile gate rejected\n");return 2;}
    test_next_handle=3;
    u32 *vs=native_kernel_original_allocate(s,1,4096),*defaults=native_kernel_original_allocate(s,2,4096);
    u32 *fs=native_kernel_original_allocate(s,3,4096);assert(vs&&defaults&&fs);vs[0]=OPENGPU_SHADER_CEASE;
    memcpy(fs,p.bytes,p.meta[14]*4);
    struct native_test_record *r=calloc(1,sizeof(*r));struct drm_opengpu_native_layered_draw_plan *plan=calloc(1,sizeof(*plan));assert(r&&plan);
    make_record(s,r,plan,&p,samples);
    /* Compiler booleans do not authorize the program: the real kernel cache
     * and GND/GFS relationship must independently admit it. */
    s->gpu.hw.native_sample_transport=false;
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && !s->count && r->submit.fence_seqno==0xfeed);
    s->gpu.hw.native_sample_transport=true;
    r->state[55]=15;
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && !s->count && r->submit.fence_seqno==0xfeed);
    r->state[55]=16;r->state[56]=7;
    assert(native_kernel_submit(s,&r->submit,NULL,NULL)==-EINVAL && !s->count && r->submit.fence_seqno==0xfeed);
    r->state[56]=6;
    int ret=native_kernel_submit(s,&r->submit,NULL,NULL);
    if(ret){assert(!s->count && r->submit.fence_seqno==0xfeed);fprintf(stderr,"protected ioctl program rejected: %d\n",ret);}
    else {
        struct opengpu_sched_job *job=native_record(s,0);u32 va;assert(job);
        assert(!opengpu_private_snapshot_native_layered_job_address(job->native_owner,job->native_job_segment,&va));
        u32 frame=test_read_va(s,va+21*4),states=test_read_va(s,frame+292*4),pc=test_read_va(s,states+54*4);
        assert(test_read_va(s,states+29*4)==p.meta[7]);
        assert(test_read_va(s,va)==0x474e4a02 && test_read_va(s,frame)==0x474e4603 && test_read_va(s,states+56*4)==6);
        assert(test_read_va(s,va+49*4)-test_read_va(s,va+48*4)==16384);assert_rx(s,pc,&p);
        memset(fs,0,p.meta[14]*4);assert(!native_kernel_original_close(s,3));assert_rx(s,pc,&p);
        assert(native_kernel_original_refcount(s,3)>0);assert(!native_kernel_run(s,0));
        /* Modeled final kick/completion. No shader execution or GNY2 success. */
        assert(!native_kernel_retire(s,0,0));assert(native_kernel_original_refcount(s,3)==0);
        printf("CPU modeled final kick: ABI6 %ux samples, %u compiled RX words, GNJ2/GNF3/GNY2 lease; no GPU execution\n",samples,p.meta[14]);
    }
    assert(!native_kernel_session_destroy(s)&&!live_allocations&&!fixture_buffers);free(plan);free(r);return ret?2:0;
}
