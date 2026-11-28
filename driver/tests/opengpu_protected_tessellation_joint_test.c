/* Actual owner/MMU BUILD graph and verified kernel enqueue with modeled MMIO. */
#include "../opengpu_tessellation_joint_host.h"
#define OPENGPU_MMU_TEST_HW_EXTRA struct opengpu_protected_tessellation_joint_profile joint_tessellation;
#define OPENGPU_PROTECTED_TESS_TEST_MAIN joint_old_protected_regression_main
#include "opengpu_protected_tessellation_test.c"
#undef OPENGPU_PROTECTED_TESS_TEST_MAIN
#undef OPENGPU_MMU_TEST_HW_EXTRA
#include "../opengpu_tessellation_vertex_source.c"
#include "../opengpu_tessellation_vertex_plan.c"
#ifndef OPENGPU_JOINT_SOURCE
#define OPENGPU_JOINT_SOURCE "../opengpu_protected_tessellation_joint.c"
#endif
#include OPENGPU_JOINT_SOURCE

struct opengpu_tessellation_scope { u32 flags, program, cookie; };
struct opengpu_command_events { u32 flags, wait_event, signal_event; };
struct opengpu_kernel_launch;
struct dma_fence;
#include "joint_hw_submission_under_test.inc"

static bool joint_busy, joint_kick_fail;
static unsigned joint_kicks;
static int joint_fence_token;
static struct opengpu_tessellation_scope joint_expected_scope;
static struct opengpu_protected_tessellation_joint_host_command joint_expected_command;

static bool opengpu_hw_execution_busy(struct opengpu_device *gpu)
{
    assert(gpu->hw.submit_lock.held);
    return joint_busy;
}

static int opengpu_hw_unified_submit_locked(struct opengpu_device *gpu,
    const struct opengpu_kernel_launch *launch, const struct opengpu_command_events *events,
    u32 opcode, u32 source, u32 destination, u32 bytes, u32 pattern, u32 sample_mode,
    u32 width, u32 height, u32 source_stride, u32 destination_stride, u64 expected_bytes,
    const struct opengpu_vm *vm, u32 delay,
    const struct opengpu_hw_joint_submission *joint, struct dma_fence **output)
{
    assert(gpu->hw.submit_lock.held && gpu->mmu.snapshot_leases && vm && vm->enabled);
    assert(!launch && events && !events->wait_event && !events->signal_event);
    assert(joint && joint->completion && !sample_mode && !expected_bytes && !delay);
    assert(opcode == 15 && source == joint_expected_command.source &&
        destination == joint_expected_command.destination && bytes == 512 &&
        pattern == joint_expected_command.pattern && width == joint_expected_command.width);
    assert(height == joint_expected_command.height && source_stride == joint_expected_command.source_stride &&
        destination_stride == joint_expected_command.destination_stride);
    assert(events->flags == joint_expected_scope.flags &&
        joint->feedback_program == joint_expected_scope.program &&
        joint->feedback_cookie == joint_expected_scope.cookie);
    ++joint_kicks;
    if (joint_kick_fail)
        return -ENOMEM;
    *output = (struct dma_fence *)&joint_fence_token;
    return 0;
}

#include "joint_enqueue_under_test.inc"

struct joint_fixture {
    struct pt_records old;
    u32 draws[3][512];
    struct drm_opengpu_native_draw_plan draw_plans[3];
    struct opengpu_tessellation_vertex_outer_invocation_v2 invocations[3];
    struct opengpu_tessellation_vertex_outer_range_v2 ranges[3];
    struct opengpu_protected_tessellation_joint_plan plans[3];
    struct opengpu_protected_tessellation_joint_source source;
};
static struct joint_fixture *joint_mutate;
static int joint_resolve(void *context,u32 handle,struct opengpu_native_frame_backing *out)
{
    if(!graph_producers_complete)return -EAGAIN;
    int ret=graph_resolve(context,handle,out);
    if(joint_mutate){memset(joint_mutate,0xa7,sizeof(*joint_mutate));joint_mutate=NULL;}
    return ret;
}
static const struct opengpu_native_job_copy joint_callbacks={pt_test_copy,joint_resolve,graph_original};
static void joint_uniform_oracle(struct opengpu_vm *vm,u32 root,u32 stage)
{
    const bool tess=stage>=4;const u32 index=tess?stage-4:stage;
    assert(graph_read(vm,root,3)==(tess?0x47555203u:0x47555201u));
    assert(graph_read(vm,root+8,3)==(tess?3u:1u));
    assert(graph_read(vm,root+12,3)==7 && graph_read(vm,root+16,3)==8 && graph_read(vm,root+20,3)==9);
    assert(graph_read(vm,root+24,3)==stage && graph_read(vm,root+28,3)==0xfff);
    u32 table=graph_read(vm,root+40,3);
    assert(graph_read(vm,root+44,3)==table+24*64 && !graph_read(vm,root+48,3));
    for(u32 b=0;b<13;b++) {
        u32 map=graph_read(vm,root+(b<6?8:9)*4,3);
        u32 slot=b<12?(map>>(5*(b%6)))&31u:31;
        u32 record=b==12?graph_read(vm,root+52,3):table+slot*64;
        u32 handle=graph_ubo[b==12?24+index:index*12+b],offset=b==12?4:(b&1?4:64),bytes=b==12?4096:16384;
        assert(graph_read(vm,record,3)==(tess?0x47554203u:0x47554201u));
        assert(graph_read(vm,record+24,3)==slot && graph_read(vm,record+28,3)==(1u<<stage));
        u32 data=graph_read(vm,record+32,3);
        assert(graph_read(vm,record+36,3)==data+bytes && !graph_read(vm,record+40,3));
        for(u32 w=0;w<bytes;w+=4)assert(graph_read(vm,data+w,3)==source_word(handle,offset+w,0));
    }
}
static void joint_endpoints(const u32 *source)
{
    u32 words[128];memcpy(words,source,512);
    assert(opengpu_protected_tessellation_joint_job_canonical(words,512,false));
    const u32 addresses[]={16,18,19,21,22,23,28,29,30};
    for(u32 i=0;i<sizeof(addresses)/sizeof(addresses[0]);i++) {
        u32 n=addresses[i];words[n]=1;
        bool accepted=opengpu_protected_tessellation_joint_job_canonical(words,512,false);
        if(accepted)fprintf(stderr,"joint endpoint source-relocation word=%u\n",n);
        assert(!accepted);words[n]=0;
    }
    for(u32 i=0;i<128;i++)if(!opengpu_tess_joint_word_rules[i]) {
        words[i]=1;assert(!opengpu_protected_tessellation_joint_job_canonical(words,512,false));words[i]=0;
    }
    words[16]=0xffffe800u;words[18]=0;words[19]=1;
    words[21]=0xfffff800u;words[22]=0;words[23]=1;
    words[28]=0xffffff00u;words[29]=0;words[30]=1;
    assert(opengpu_protected_tessellation_joint_job_canonical(words,512,true));
    for(u32 i=0;i<3;i++) {
        u32 low=i==0?18:i==1?22:29,high=low+1;
        words[low]=0xffffffffu;words[high]=0;
        assert(!opengpu_protected_tessellation_joint_job_canonical(words,512,true));
        words[low]=0;words[high]=2;
        assert(!opengpu_protected_tessellation_joint_job_canonical(words,512,true));words[high]=1;
    }
    words[20]=0xffffffc0u;
    assert(!opengpu_protected_tessellation_joint_job_canonical(words,512,true));
    memcpy(words,source,512);words[17]=0xffffffffu;words[31]=0xffffffffu;
    assert(!opengpu_protected_tessellation_joint_job_canonical(words,512,false));
    /* Exercise the actual production relocation arithmetic for each logical
     * record extent at the exclusive endpoint; no fabricated MMU execution. */
    const u32 lengths[]={512,2048,256};
    for(u32 i=0;i<3;i++) {
        u8 bytes[16],expected[8]={0,0,0,0,1,0,0,0};
        struct snapshot_segment from={.backing.cpu=bytes},to={.va=(1ULL<<32)-lengths[i]};
        struct snapshot_relocation r={.source=&from,.target=&to,.offset=4,.addend=lengths[i],
            .kind=OPENGPU_PRIVATE_SNAPSHOT_LIMIT33};
        memset(bytes,0xa5,sizeof(bytes));assert(!snapshot_apply_relocation(&r));
        assert(!memcmp(bytes+4,expected,8) && bytes[3]==0xa5 && bytes[12]==0xa5);
        memset(bytes,0xa5,sizeof(bytes));r.kind=OPENGPU_PRIVATE_SNAPSHOT_VA32;
        assert(snapshot_apply_relocation(&r)==-ERANGE);
        for(u32 j=0;j<16;j++)assert(bytes[j]==0xa5);
    }
}
static void joint_init(struct joint_fixture *f,struct opengpu_device *gpu,u32 domain)
{
    memset(f,0,sizeof(*f));pt_init(&f->old,gpu,domain);
    const struct jt_layout *l=&jt_layouts[domain];
    /* Full ABI9 writable/input prefix, not the old ABI1 88-word prefix. */
    f->old.original.request.scratch_stride=94*128*4;
    for(u32 i=0;i<4;i++) {
        struct opengpu_native_frame_gem *v=&f->old.original.request.scratch[i];
        opengpu_buffer_free(gpu,&graph_original[v->handle]);
        v->bytes=(u64)2*f->old.original.request.scratch_stride;
        assert(!opengpu_buffer_alloc(gpu,&graph_original[v->handle],(size_t)(v->bytes+64+4095)&~4095u));
    }
    for(u32 i=0;i<4;i++)f->old.original.request.attachments[i].format=35; /* RGBA32F, same 16-byte capacity */
    assert(!opengpu_native_frame_encode(&f->old.original.request,&f->old.original.encoded));
    memcpy(f->draw_plans,f->old.original.plans,sizeof(f->draw_plans));
    for(u32 i=0;i<3;i++) {
        memcpy(f->draws[i],f->old.original.draws+(size_t)i*GRAPH_STRIDE/4,2048);
        f->draws[i][311]=4;f->draws[i][325]=3;f->draws[i][326]=1;
        f->draws[i][343]=f->draws[i][344]=0xffffffffu;
        u32 *d=f->old.sequences+i*512;
        d[0]=0x474e5407+domain;d[2]=7+domain;d[16]=6;d[17]=0;d[31]=2;
        d[l->vertex_stride]=0;d[l->old_uniform]=0;
        for(u32 w=237+(domain!=0);w<=482+(domain!=0);w++)d[w]=0;
        struct opengpu_tessellation_vertex_outer_range_v2 *r=&f->ranges[i];
        r->sequence_index=i;r->invocation_index=i;r->original_draw_index=i;
        r->flags=OPENGPU_VERTEX_OUTER_FIRST|OPENGPU_VERTEX_OUTER_LAST;r->task_id_base=100+10*i;r->patch_count=6;
        r->slice=(struct opengpu_tessellation_vertex_slice){1,0,2,0,3};
        r->layout=(struct opengpu_tessellation_patch_layout){2,4,2,8,0};
        for(u32 j=0;j<4;j++)r->components[j]=(struct opengpu_tessellation_vertex_component){0,j};
        d[l->task_id]=r->task_id_base;
        f->invocations[i]=(struct opengpu_tessellation_vertex_outer_invocation_v2){.invocation_id=(1ULL<<32)+i,
            .original_draw_index=i,.input_patch_vertices=2};
        /* Plans remain permuted independently of sequence/catalog indices. */
        const struct opengpu_protected_tessellation_plan *p=&f->old.plans[i];
        f->plans[i]=(struct opengpu_protected_tessellation_joint_plan){.sequence_index=p->sequence_index,
            .state_handle=p->state_handle,.control_program=p->control_program,.evaluation_program=p->evaluation_program,
            .control_uniforms=p->control_uniforms,.evaluation_uniforms=p->evaluation_uniforms};
    }
    for(u32 i=0;i<2;i++){
        f->old.original.states[i*128+56]=domain==1?9:8;
        f->old.original.states[i*128+39]=2;
        f->old.original.states[i*128+93]=f->old.original.states[i*128+94]=1;
    }
    u32 *job=f->old.original.job;
    job[0]=0x474e5104+domain;job[2]=2;job[27]=2;job[31]=3;job[32]=256;job[33]=64;job[34]=3;job[35]=3;
    f->source=(struct opengpu_protected_tessellation_joint_source){.job=job,.frame=&f->old.original.encoded.frame,
        .sequences=f->old.sequences,.sequences_bytes=sizeof(f->old.sequences),
        .states=f->old.original.states,.states_bytes=sizeof(f->old.original.states),
        .draws=f->draws,.draws_bytes=sizeof(f->draws),.draw_plans=f->draw_plans,.draw_count=3,.draw_plan_count=3,
        .invocations=f->invocations,.invocation_count=3,.ranges=f->ranges,.range_count=3,
        .plans=f->plans,.plan_count=3,.state_plans=f->old.states,.state_plan_count=2,
        .resources=f->old.original.encoded.resources,.resource_count=27,.context=7,.pass=8,.generation=9,
        .profile={.version=1,.domains=7,.compute_units=2,.lanes=16,.warps=8,.engines=4,.available=true,
            .scalar_fpu=true,.owned_vertex_source=true,.patch_zero_initialized=true,.owned_patch_reads=true,
            .owned_tessellation_info=true,.primitive_inputs=true,.sample_inputs=true,.joint_directory_route=true,.whole_owner_retirement=true}};
}
static void joint_expect(struct joint_fixture *f,struct opengpu_device *gpu,struct opengpu_render_context *ctx,int expected)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);assert(!IS_ERR(o));
    u32 out=0x5a5a5a5a;
    int ret=opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&out);
    if(ret!=expected)fprintf(stderr,"joint case%u expected%d got%d\n",graph_rejection_case,expected,ret);
    assert(ret==expected && out==0x5a5a5a5a);
    assert(!opengpu_private_snapshot_retire(o));assert(ctx->references==1 && !gpu->mmu.snapshot_leases);
}
static void joint_same_root(struct opengpu_vm *vm,u32 entry,u32 root,u32 sequence)
{
    u32 actual=graph_read(vm,entry+12*4,3);
    if(actual!=root)fprintf(stderr,"joint directory same-root sequence=%u\n",sequence);
    assert(actual==root);
}
static void joint_job_permission(struct opengpu_vm *vm,u32 address)
{
    const struct opengpu_buffer *l1=vm_live_l1(vm,address/MMU_SUPERPAGE_SIZE);assert(l1);
    u32 permission=((u32 *)l1->cpu)[(address%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE]&15u;
    if(permission!=3)fprintf(stderr,"joint permission job expected=3 actual=%u\n",permission);
    assert(permission==3);
}
static int joint_prepare_test(struct opengpu_private_snapshot *o,u32 segment)
{
    int ret=opengpu_protected_tessellation_joint_prepare(o,segment);
    const struct snapshot_segment *root=snapshot_segment_find(o,segment);
    if(ret && root && root->executable)fprintf(stderr,"joint prepare root must be RO/NX\n");
    return ret;
}
static int joint_request(struct opengpu_private_snapshot *o,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx,u32 segment,struct opengpu_private_snapshot_joint_request *request)
{
    mutex_lock(&gpu->hw.submit_lock);
    int ret=opengpu_private_snapshot_joint_request_locked(o,gpu,ctx,segment,request);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

static void joint_request_rejected(struct opengpu_private_snapshot *o,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx,u32 segment)
{
    struct opengpu_private_snapshot_joint_request request;
    unsigned char original[sizeof(request)];
    memset(original,0xa7,sizeof(original));
    memcpy(&request,original,sizeof(request));
    assert(joint_request(o,gpu,ctx,segment,&request)<0);
    assert(!memcmp(&request,original,sizeof(request)));
    struct opengpu_protected_tessellation_joint_host_command command={.opcode=0xa7,.width=0xa7};
    mutex_lock(&gpu->hw.submit_lock);
    int ret=opengpu_protected_tessellation_joint_command_locked(o,gpu,ctx,segment,&command);
    mutex_unlock(&gpu->hw.submit_lock);
    assert(ret<0 && command.opcode==0xa7 && command.width==0xa7);
}

static void joint_enqueue_oracle(struct opengpu_private_snapshot *owner, struct opengpu_device *gpu,
    const struct opengpu_render_context *context, u32 segment,
    const struct opengpu_protected_tessellation_joint_host_command *command)
{
    struct opengpu_tessellation_joint_completion completion = {0};
    struct dma_fence *fence = NULL;
    struct opengpu_tessellation_scope scope = {
        .flags = GPU_UCMD_FLAG_RASTER_DISCARD | GPU_UCMD_FLAG_FEEDBACK_CAPTURE,
        .program = 33, .cookie = 44,
    };
    unsigned kicks = joint_kicks, cached = invalidations;

    joint_expected_command = *command;
    gpu->hw.joint_tessellation = (struct opengpu_protected_tessellation_joint_profile){0};
    assert(opengpu_hw_protected_tessellation_joint_async(gpu, owner, context, segment, NULL,
        &completion, &fence) == -EOPNOTSUPP && !fence && invalidations == cached);
    gpu->hw.joint_tessellation = (struct opengpu_protected_tessellation_joint_profile) {
        .version = 1, .domains = 7, .compute_units = 2, .lanes = 16, .warps = 8,
        .engines = 4, .available = true, .owned_vertex_source = true,
        .patch_zero_initialized = true, .owned_patch_reads = true, .owned_tessellation_info = true,
        .primitive_inputs = true, .joint_directory_route = true, .whole_owner_retirement = true,
    };
    assert(opengpu_hw_protected_tessellation_joint_async(gpu, owner, context, segment, &scope,
        &completion, &fence) == -EOPNOTSUPP && !fence && invalidations == cached);
    joint_busy = true;
    assert(opengpu_hw_protected_tessellation_joint_async(gpu, owner, context, segment, NULL,
        &completion, &fence) == -EBUSY && !fence && invalidations == cached);
    joint_busy = false;
    assert(opengpu_hw_protected_tessellation_joint_async(gpu, owner, context, segment + 1, NULL,
        &completion, &fence) == -EINVAL && !fence && invalidations == cached);
    invalidate_failure = (int)cached + 1;
    assert(opengpu_hw_protected_tessellation_joint_async(gpu, owner, context, segment, NULL,
        &completion, &fence) == -EIO && !fence && invalidations == cached + 1);
    invalidate_failure = 0;
    assert(joint_kicks == kicks && !gpu->hw.submit_lock.held);
    joint_expected_scope = (struct opengpu_tessellation_scope){0};
    joint_kick_fail = true;
    assert(opengpu_hw_protected_tessellation_joint_async(gpu, owner, context, segment, NULL,
        &completion, &fence) == -ENOMEM && !fence && joint_kicks == kicks + 1);
    joint_kick_fail = false;
    gpu->hw.joint_tessellation.transform_feedback = true;
    joint_expected_scope = scope;
    assert(!opengpu_hw_protected_tessellation_joint_async(gpu, owner, context, segment, &scope,
        &completion, &fence) && fence == (struct dma_fence *)&joint_fence_token);
    assert(joint_kicks == kicks + 2 && !gpu->hw.submit_lock.held);
}

static void joint_request_oracle(struct opengpu_private_snapshot *o,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx,u32 segment,u32 domain)
{
    struct opengpu_private_snapshot_joint_request request;
    const struct snapshot_segment *root=snapshot_segment_find(o,segment);
    u64 first=1ULL<<32,last=0;
    assert(root && !joint_request(o,gpu,ctx,segment,&request));
    for(const struct snapshot_segment *s=o->segments;s;s=s->next) {
        if(s->va<first)first=s->va;
        if(s->va+s->backing.size>last)last=s->va+s->backing.size;
    }
    for(const struct snapshot_resource *r=o->resources;r;r=r->next) {
        if(r->reservation.va<first)first=r->reservation.va;
        if(r->reservation.va+r->reservation.size>last)last=r->reservation.va+r->reservation.size;
    }
    assert(request.job_base==root->va && request.job_limit==root->va+512 &&
           request.job_allocation_base==root->va && request.job_allocation_limit==root->va+root->backing.size);
    assert(request.owner_base==first && request.owner_limit==last && request.owner_limit>=request.job_allocation_limit);
    assert(request.domain==domain && request.context==ctx->id && request.pass==8 && request.generation==9);
    assert(!o->joint_geometry_root && !o->joint_geometry_request.job_base &&
        !o->joint_geometry_request.job_limit && !o->joint_geometry_request.job_allocation_limit);
    struct opengpu_private_snapshot_joint_request geometry_request;
    memset(&geometry_request,0xa5,sizeof(geometry_request));
    mutex_lock(&gpu->hw.submit_lock);
    int geometry_ret=opengpu_private_snapshot_joint_geometry_request_locked(o,gpu,ctx,segment,&geometry_request);
    mutex_unlock(&gpu->hw.submit_lock);
    assert(geometry_ret==-EINVAL && geometry_request.job_base==0xa5a5a5a5u);
    struct opengpu_protected_tessellation_joint_host_command command;
    mutex_lock(&gpu->hw.submit_lock);
    int ret=opengpu_protected_tessellation_joint_command_locked(o,gpu,ctx,segment,&command);
    mutex_unlock(&gpu->hw.submit_lock);
    assert(!ret && command.opcode==15 && command.bytes==512 && command.source==root->va);
    assert(command.destination==first && command.width==(u32)last);
    assert(command.pattern==(0x474a0100u | domain<<1 | (u32)(last>>32)));
    assert(command.height==ctx->id && command.source_stride==8 && command.destination_stride==9);
    joint_request_rejected(o,gpu,ctx,segment+1);
    struct opengpu_render_context foreign={.gpu=gpu,.id=ctx->id};
    joint_request_rejected(o,gpu,&foreign,segment);

    const struct opengpu_buffer *l1=vm_live_l1(&ctx->vm,request.job_base/MMU_SUPERPAGE_SIZE);
    assert(l1);
    u32 *leaf=(u32 *)l1->cpu+(request.job_base%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE;
    u32 saved=*leaf;
    *leaf|=MMU_PTE_W;
    joint_request_rejected(o,gpu,ctx,segment);
    *leaf=saved;
    assert(!joint_request(o,gpu,ctx,segment,&request));
    joint_enqueue_oracle(o,gpu,ctx,segment,&command);
}

static void joint_oracle(struct opengpu_private_snapshot *o,struct opengpu_render_context *ctx,u32 segment,u32 domain)
{
    u32 address;
    assert(!opengpu_private_snapshot_address(o,segment,&address));
    assert(opengpu_private_snapshot_native_job_address(o,segment,&address)==-EINVAL);
    assert(opengpu_private_snapshot_protected_tessellation_job_address(o,segment,&address)==-EINVAL);
    joint_job_permission(&ctx->vm,address);
    assert(graph_read(&ctx->vm,address,3)==0x474e5104+domain);
    u32 job[128];for(u32 i=0;i<128;i++)job[i]=graph_read(&ctx->vm,address+i*4,3);
    assert(opengpu_protected_tessellation_joint_job_canonical(job,512,true));
    u32 array=job[16],map=job[28],frame=job[21];
    assert(graph_read(&ctx->vm,map,3)==OPENGPU_VERTEX_OUTER_MAP_MAGIC);
    assert(job[31]==3 && job[32]==256 && job[34]==3 && job[35]==3);
    const struct jt_layout *l=&jt_layouts[domain];
    for(u32 i=0;i<3;i++) {
        u32 d=array+i*2048,entry=map+64+i*64;
        assert(graph_read(&ctx->vm,d,3)==0x474e5407+domain);
        assert(graph_read(&ctx->vm,entry,3)==i && graph_read(&ctx->vm,entry+4,3)==i);
        assert(graph_read(&ctx->vm,entry+8,3)==i && graph_read(&ctx->vm,entry+12,3)==1);
        u32 root=graph_read(&ctx->vm,d+509*4,3);
        joint_same_root(&ctx->vm,entry,root,i);
        assert(graph_read(&ctx->vm,d+510*4,3)==root+512 && !graph_read(&ctx->vm,d+511*4,3));
        assert(graph_read(&ctx->vm,root,3)==0x47565301);
        assert(graph_read(&ctx->vm,root+19*4,3)==i && graph_read(&ctx->vm,root+20*4,3)==i);
        assert(graph_read(&ctx->vm,root+21*4,3)==100+10*i && graph_read(&ctx->vm,root+22*4,3)==6);
        u32 gnd=graph_read(&ctx->vm,root+24*4,3),vs=graph_read(&ctx->vm,root+30*4,3);
        assert(graph_read(&ctx->vm,gnd+342*4,3)==vs && graph_read(&ctx->vm,gnd+326*4,3)==0);
        assert(graph_read(&ctx->vm,vs-4,11)==0x93000000 && graph_read(&ctx->vm,vs,11)==0x30500073);
        joint_uniform_oracle(&ctx->vm,graph_read(&ctx->vm,gnd+507*4,3),0);
        for(u32 b=0;b<16;b++) {
            u32 input=graph_read(&ctx->vm,gnd+binding_words[b].base*4,3);
            assert(graph_translate(&ctx->vm,input,3)==graph_original[graph_inputs[b]].dma+64);
            assert(graph_read(&ctx->vm,gnd+binding_words[b].limit*4,3)==input+512);
        }
        u32 scratch=graph_read(&ctx->vm,gnd+scratch_words.base*4,3);
        assert(graph_translate(&ctx->vm,scratch,7)==graph_original[graph_vs[i]].dma+64);
        assert(graph_read(&ctx->vm,gnd+scratch_words.limit*4,3)==scratch+GRAPH_VS_BYTES);
        u32 tcs=graph_read(&ctx->vm,d+l->control_pc*4,3),tes=graph_read(&ctx->vm,d+l->evaluation_pc*4,3);
        assert(graph_read(&ctx->vm,tcs,11)==0x30500073 && graph_read(&ctx->vm,tes,11)==(35u<<25|0xb));
        for(u32 k=0;k<2;k++) {
            u32 scope=d+(500+5*k)*4;
            u32 ur=graph_read(&ctx->vm,scope,3);
            assert(!graph_read(&ctx->vm,scope+4,3) && !graph_read(&ctx->vm,scope+8,3) &&
                !graph_read(&ctx->vm,scope+12,3));
            joint_uniform_oracle(&ctx->vm,ur,4+k);
        }
        assert(!graph_read(&ctx->vm,d+17*4,3) && !graph_read(&ctx->vm,d+21*4,3));
    }
    u32 states=graph_read(&ctx->vm,frame+292*4,3);
    u32 arena_base=graph_read(&ctx->vm,frame+OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_BASE_WORD*4,3);
    u64 arena_limit=graph_read(&ctx->vm,frame+OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_LIMIT_WORD*4,3);
    arena_limit|=(u64)graph_read(&ctx->vm,frame+(OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_LIMIT_WORD+1)*4,3)<<32;
    u64 bases[8],limits[8];u32 scratch_count=0;
    for(u32 i=0;i<2;i++) {
        u32 f=states+i*512,pc=graph_read(&ctx->vm,f+54*4,3);
        assert(graph_read(&ctx->vm,f+56*4,3)==(domain==1?9u:8u) && graph_read(&ctx->vm,pc,11)==0x30500073);
        joint_uniform_oracle(&ctx->vm,graph_read(&ctx->vm,f+119*4,3),1);
        assert(graph_read(&ctx->vm,f+93*4,3)==1 && graph_read(&ctx->vm,f+94*4,3)==1);
        assert(graph_read(&ctx->vm,f+39*4,3)==2);
        u64 prefix=(domain==1?94u:91u)*128u*4u;
        for(u32 e=0;e<4;e++) {
            const struct view_words *v=&state_scratch[e];
            u64 base=graph_read(&ctx->vm,f+v->base*4,3),limit=graph_read(&ctx->vm,f+v->limit*4,3);
            limit|=(u64)graph_read(&ctx->vm,f+(v->limit+1)*4,3)<<32;
            u64 allocation=graph_read(&ctx->vm,f+v->allocation_base*4,3);
            u64 allocation_limit=graph_read(&ctx->vm,f+v->allocation_limit*4,3);
            allocation_limit|=(u64)graph_read(&ctx->vm,f+(v->allocation_limit+1)*4,3)<<32;
            assert(!(base&63u) && !(allocation&63u) && !(allocation_limit&63u));
            assert(allocation<=base && base+prefix<=limit && limit<=allocation_limit && allocation_limit<=(1ULL<<32));
            assert(limit<=arena_base || base>=arena_limit);
            assert(base>=0x10004000ULL || base+prefix<=0x10000000ULL);
            (void)graph_translate(&ctx->vm,(u32)base,7);
            (void)graph_translate(&ctx->vm,(u32)(base+prefix-4),7);
            for(u32 n=0;n<scratch_count;n++)assert(limit<=bases[n] || base>=limits[n]);
            bases[scratch_count]=base;limits[scratch_count++]=limit;
        }
    }
}
static void joint_negatives(struct joint_fixture *f,struct opengpu_device *gpu,struct opengpu_render_context *ctx,u32 domain)
{
    struct joint_fixture *saved=malloc(sizeof(*saved));assert(saved);*saved=*f;
    const struct jt_layout *l=&jt_layouts[domain];
    for(u32 n=0;n<40;n++) {
        *f=*saved;graph_rejection_case=n;
        int expected=-EINVAL;
        switch(n) {
        case 0:f->source.profile.available=false;expected=-EOPNOTSUPP;break;
        case 1:f->source.profile.owned_patch_reads=false;expected=-EOPNOTSUPP;break;
        case 2:f->source.profile.owned_tessellation_info=false;expected=-EOPNOTSUPP;break;
        case 3:f->source.profile.primitive_inputs=false;expected=-EOPNOTSUPP;break;
        case 4:f->source.profile.whole_owner_retirement=false;expected=-EOPNOTSUPP;break;
        case 5:f->source.profile.joint_directory_route=false;expected=-EOPNOTSUPP;break;
        case 6:f->old.original.job[0]=0x474e5101+domain;break;
        case 7:f->old.original.job[2]=1;break;
        case 8:f->old.original.job[31]++;break;
        case 9:f->old.original.job[32]++;break;
        case 10:f->old.original.job[30]=1;break;
        case 11:f->old.original.job[36]=1;break;
        case 12:f->old.sequences[509]=64;break;
        case 13:f->old.sequences[0]=0x474e5407+(domain+1)%3;break;
        case 14:f->old.sequences[16]++;break;
        case 15:f->old.sequences[l->task_id]++;break;
        case 16:f->old.sequences[l->output_components]++;break;
        case 17:f->old.sequences[496]=7;break;
        case 18:f->old.sequences[21]=64;break;
        case 19:f->old.sequences[l->selections]=1;break;
        case 20:f->ranges[0].original_draw_index=1;break;
        case 21:f->invocations[1].invocation_id=f->invocations[0].invocation_id;break;
        case 22:f->draw_plans[0].fragment_program.offset+=4;break;
        case 23:f->draw_plans[0].fragment_uniforms.active_mask^=1;break;
        case 24:f->ranges[0].slice.first_ordinal=1;break;
        case 25:f->source.draw_plan_count--;break;
        case 26:f->old.original.states[56]=1;break;
        case 27:f->old.original.states[56]=9;f->source.profile.sample_inputs=false;break;
        case 28:f->old.original.states[54]=4;break;
        case 29:f->source.context++;break;
        case 30:f->plans[0].state_handle^=1;break;
        case 31:f->old.original.job[28]=64;break;
        case 32:f->old.original.job[19]=1;break;
        case 33:f->draws[0][328]=64;expected=-EOPNOTSUPP;break;
        case 34:f->ranges[0].flags=1;break;
        case 35:f->source.profile.version++;expected=-EOPNOTSUPP;break;
        case 36:f->old.original.job[35]++;break;
        case 37:f->old.original.job[34]++;break;
        case 38:f->source.profile.patch_zero_initialized=false;expected=-EOPNOTSUPP;break;
        default:f->source.profile.owned_vertex_source=false;expected=-EOPNOTSUPP;break;
        }
        unsigned copies=graph_copies,resolves=graph_resolves;
        joint_expect(f,gpu,ctx,expected);
        if(expected==-EOPNOTSUPP && n!=33)assert(copies==graph_copies && resolves==graph_resolves);
    }
    *f=*saved;free(saved);
}
static void joint_state_semantics(struct joint_fixture *f,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx)
{
    struct joint_fixture *saved=malloc(sizeof(*saved));assert(saved);*saved=*f;
    /* Every case starts from actual legal shared payload/late-test version 1. */
    for(u32 n=0;n<26;n++) {
        *f=*saved;graph_rejection_case=200+n;
        u32 *state=f->old.original.states;
        switch(n) {
        case 0:state[94]=0;break;
        case 1:state[93]=0;break;
        case 2:state[94]=2;break;
        case 3:state[27]=2;break;
        case 4:state[94]=3;state[27]=3;break;
        case 5:state[22]=1;state[27]=0;break;
        case 6:state[22]=1;state[27]=1;state[26]=15;break;
        case 7:state[22]=1;state[27]=1;state[24]=5;break;
        case 8:state[8]=1;break;
        case 9:state[94]=2;state[27]=2;state[22]=1;break;
        case 10:state[94]=2;state[27]=2;state[8]=1u<<27;break;
        case 11:state[94]=2;state[27]=2;state[8]=15u<<1;break;
        case 12:state[94]=2;state[27]=2;state[8]=5u<<20;break;
        case 13:state[31]=3;state[30]=8;break;
        case 14:state[32]=3;break;
        case 15:state[39]=3;break;
        case 16:state[55]=1;state[124]=16;break;
        case 17:f->old.original.encoded.frame.words[OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCHSTRIDE_WORD]-=64;state[56]=9;break;
        case 18:state[55]=17;break;
        case 19:state[31]=5;break;
        case 20:state[28]=0;break;
        case 21:state[51]=64;break;
        case 22:state[118]=1;break;
        case 23:state[39]=0;break;
        case 24:state[32]=1;break;
        default:f->old.sequences[31]=0;break;
        }
        joint_expect(f,gpu,ctx,-EINVAL);
    }
    /* Indexed controls and valid enabled shared blending remain supported. */
    for(u32 kind=0;kind<2;kind++) {
        *f=*saved;
        for(u32 i=0;i<2;i++) {
            u32 *state=f->old.original.states+i*128;
            state[94]=kind?1:2;state[27]=kind?1:2;
            if(kind){state[22]=1;state[26]=1;}else for(u32 c=0;c<4;c++)state[8+c]=15u<<23;
        }
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment;
        assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
        assert(!joint_prepare_test(o,segment) && !opengpu_private_snapshot_publish(o));
        assert(!opengpu_private_snapshot_retire(o));
    }
    *f=*saved;free(saved);
}
static void joint_empty(struct joint_fixture *f,struct opengpu_device *gpu,struct opengpu_render_context *ctx)
{
    struct opengpu_protected_tessellation_joint_source saved=f->source;
    u32 saved_job[128];memcpy(saved_job,f->old.original.job,512);
    f->source.sequences=f->source.states=f->source.draws=NULL;
    f->source.sequences_bytes=f->source.states_bytes=f->source.draws_bytes=0;
    f->source.draw_plans=NULL;f->source.invocations=NULL;f->source.ranges=NULL;f->source.plans=NULL;f->source.state_plans=NULL;
    f->source.draw_count=f->source.draw_plan_count=f->source.invocation_count=f->source.range_count=0;
    f->source.plan_count=f->source.state_plan_count=0;
    u32 *job=f->old.original.job;job[17]=job[20]=0;memset(job+27,0,9*4);
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment,address;
    assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
    assert(!joint_prepare_test(o,segment) && !opengpu_private_snapshot_publish(o));
    assert(!opengpu_private_snapshot_address(o,segment,&address));
    joint_job_permission(&ctx->vm,address);
    assert(!graph_read(&ctx->vm,address+17*4,3) && !graph_read(&ctx->vm,address+28*4,3));
    u32 frame=graph_read(&ctx->vm,address+21*4,3);
    assert(!graph_read(&ctx->vm,frame+292*4,3) && !graph_read(&ctx->vm,frame+296*4,3) && !graph_read(&ctx->vm,frame+297*4,3));
    assert(!opengpu_private_snapshot_retire(o));
    job[31]=1;joint_expect(f,gpu,ctx,-EINVAL);job[31]=0;
    f->source.state_plan_count=1;joint_expect(f,gpu,ctx,-EINVAL);f->source.state_plan_count=0;
    f->source.resource_count--;joint_expect(f,gpu,ctx,-EINVAL);
    f->source.resource_count++;
    u32 *raw_frame=(u32 *)&f->old.original.encoded.frame;
    raw_frame[292]=64;joint_expect(f,gpu,ctx,-EINVAL);raw_frame[292]=0;
    struct opengpu_native_frame_resource *resources=f->old.original.encoded.resources;
    u32 image0=0,image1=0;
    for(u32 i=0;i<27;i++)if(resources[i].role==OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE) {
        if(resources[i].index==0)image0=i;
        if(resources[i].index==1)image1=i;
    }
    u32 handle=resources[image1].gem_handle;resources[image1].gem_handle=resources[image0].gem_handle;
    joint_expect(f,gpu,ctx,-EINVAL);resources[image1].gem_handle=handle;
    f->source=saved;memcpy(job,saved_job,512);
}
static void joint_zero_work_invocations(struct joint_fixture *f,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx)
{
    struct joint_fixture *saved=malloc(sizeof(*saved));assert(saved);*saved=*f;
    for(u32 i=0;i<3;i++) {
        f->draws[i][311]=0;f->old.sequences[i*512+16]=0;
        f->ranges[i].patch_count=0;
        f->ranges[i].slice=(struct opengpu_tessellation_vertex_slice){1,0,0,0,0};
    }
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment,address;
    assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
    assert(!joint_prepare_test(o,segment) && !opengpu_private_snapshot_publish(o));
    assert(!opengpu_private_snapshot_address(o,segment,&address));
    joint_job_permission(&ctx->vm,address);
    u32 array=graph_read(&ctx->vm,address+16*4,3),directory=graph_read(&ctx->vm,address+28*4,3);
    assert(graph_read(&ctx->vm,address+17*4,3)==3 && graph_read(&ctx->vm,address+35*4,3)==3);
    for(u32 i=0;i<3;i++) {
        u32 entry=directory+64+i*64,root=graph_read(&ctx->vm,array+i*2048+509*4,3);
        assert(graph_read(&ctx->vm,entry+4*4,3)==3 && !graph_read(&ctx->vm,entry+11*4,3));
        joint_same_root(&ctx->vm,entry,root,i);
        assert(!graph_read(&ctx->vm,root+22*4,3));
        for(u32 w=33;w<=36;w++)assert(!graph_read(&ctx->vm,root+w*4,3));
    }
    assert(!opengpu_private_snapshot_retire(o));*f=*saved;free(saved);
}
static void joint_alias_cases(struct joint_fixture *f,struct opengpu_device *gpu,struct opengpu_render_context *ctx)
{
    struct joint_fixture *saved=malloc(sizeof(*saved));assert(saved);*saved=*f;
    const struct drm_opengpu_native_draw_plan *draw0=NULL;
    for(u32 i=0;i<3;i++)if(f->draw_plans[i].draw_index==0)draw0=&f->draw_plans[i];
    assert(draw0);
    u32 scratch_handle=draw0->vertex_scratch.handle;
    for(u32 n=0;n<7;n++) {
        *f=*saved;
        u32 alias=++graph_handles;assert(alias<96);
        graph_original[alias]=graph_original[scratch_handle];
        struct opengpu_protected_tessellation_joint_plan *tc=&f->plans[0];
        if(n==0)tc->control_uniforms.blocks[0]=(struct drm_opengpu_compute_uniform_view){64,alias,16384};
        else if(n==1)tc->evaluation_uniforms.defaults=(struct drm_opengpu_compute_uniform_view){64,alias,4096};
        else if(n==2) {
            f->old.states[0].fragment_uniforms.blocks[0]=(struct drm_opengpu_compute_uniform_view){64,alias,16384};
            for(u32 i=0;i<3;i++)if(f->draw_plans[i].state_handle==0)
                f->draw_plans[i].fragment_uniforms=f->old.states[0].fragment_uniforms;
        }else if(n==3) {
            for(u32 i=0;i<3;i++)if(f->draw_plans[i].draw_index==1)
                f->draw_plans[i].vertex_scratch=(struct drm_opengpu_compute_uniform_view){128,alias,GRAPH_VS_BYTES};
        }else if(n==4) {
            /* A distinct handle to a frame writer cannot become VS scratch. */
            u32 frame_scratch=f->old.original.request.scratch[0].handle;
            graph_original[alias]=graph_original[frame_scratch];
            f->draw_plans[0].vertex_scratch=(struct drm_opengpu_compute_uniform_view){64,alias,GRAPH_VS_BYTES};
        }else if(n==5) {
            /* Bytes do not overlap; the writable image's last64B line does. */
            u32 image=f->old.original.request.attachments[0].image.gem.handle;
            graph_original[alias]=graph_original[image];
            tc->control_uniforms.blocks[0]=(struct drm_opengpu_compute_uniform_view){1028,alias,4};
        }else {
            /* A different draw's immutable attribute view is also protected. */
            for(u32 i=0;i<3;i++)if(f->draw_plans[i].draw_index==1)
                f->draw_plans[i].bindings[0]=(struct drm_opengpu_compute_uniform_view){64,alias,512};
        }
        graph_rejection_case=100+n;joint_expect(f,gpu,ctx,-EINVAL);
        memset(&graph_original[alias],0,sizeof(graph_original[alias]));graph_handles--;
    }
    *f=*saved;
    /* Exactly the same serial VS scratch role is legal across original draws. */
    for(u32 i=0;i<3;i++)f->draw_plans[i].vertex_scratch=draw0->vertex_scratch;
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment;
    assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
    assert(!opengpu_private_snapshot_retire(o));
    *f=*saved;
    /* Same GEM, distinct RO UBO windows stay distinct (no handle-only dedup). */
    struct drm_opengpu_native_uniform_stage *u=&f->plans[0].control_uniforms;
    u->blocks[1]=(struct drm_opengpu_compute_uniform_view){128,u->blocks[0].handle,64};
    o=opengpu_private_snapshot_create(gpu,ctx);
    assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
    assert(!joint_prepare_test(o,segment) && !opengpu_private_snapshot_publish(o));
    assert(!opengpu_private_snapshot_retire(o));
    *f=*saved;free(saved);
}
static void joint_failures(struct joint_fixture *f,struct opengpu_device *gpu,struct opengpu_render_context *ctx)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 output;
    graph_copies=graph_resolves=0;
    assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&output));
    u32 copies=graph_copies,resolves=graph_resolves;
    assert(copies>70 && resolves>40);assert(!opengpu_private_snapshot_retire(o));
    for(u32 n=1;n<=copies;n++) {
        graph_copies=0;graph_copy_failure=n;joint_expect(f,gpu,ctx,-EIO);
    }
    graph_copy_failure=0;
    for(u32 n=1;n<=resolves;n++) {
        graph_resolves=0;graph_resolve_failure=n;joint_expect(f,gpu,ctx,-EIO);
    }
    graph_resolve_failure=0;
    int first_success=-1;
    for(int n=0;n<2048;n++) {
        o=opengpu_private_snapshot_create(gpu,ctx);assert(!IS_ERR(o));output=0x5a5a5a5a;
        private_alloc_fail_after=n;
        int ret=opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&output);
        private_alloc_fail_after=-1;
        if(ret)assert((ret==-ENOMEM || ret==-EINVAL) && output==0x5a5a5a5a);
        else first_success=n;
        assert(!opengpu_private_snapshot_retire(o) && ctx->references==1);
        if(!ret)break;
    }
    assert(first_success>100 && first_success<2048);
    puts("joint every copy/resolve/allocation/segment/relocation failure retires whole owner PASS");
}
static void joint_capacity_cases(struct joint_fixture *f,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx,u32 domain)
{
    struct joint_fixture *saved=malloc(sizeof(*saved));assert(saved);*saved=*f;
    const struct jt_layout *l=&jt_layouts[domain];
    for(u32 edge=0;edge<2;edge++) {
        *f=*saved;
        for(u32 i=0;i<3;i++) {
            f->draws[i][311]=64;
            for(u32 b=0;b<16;b++)f->draw_plans[i].bindings[b].bytes=2048;
            f->invocations[i].input_patch_vertices=32;
            struct opengpu_tessellation_vertex_outer_range_v2 *r=&f->ranges[i];
            r->layout=(struct opengpu_tessellation_patch_layout){32,64,32,edge?64u:60u,edge?0u:120u};
            for(u32 c=0;c<64;c++)r->components[c]=(struct opengpu_tessellation_vertex_component){c<4?0u:1u,c<4?c:c-4};
            u32 *d=f->old.sequences+i*512;
            d[l->input_vertices]=32;d[l->input_components]=64;d[l->output_vertices]=32;
            d[l->output_components]=r->layout.output_components;d[l->patch_components]=r->layout.patch_components;
            d[l->control_local]=32;d[l->bases+1]=16;
        }
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment;
        assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
        assert(!joint_prepare_test(o,segment) && !opengpu_private_snapshot_publish(o));
        assert(!opengpu_private_snapshot_retire(o));
        if(!edge) {
            f->old.sequences[l->output_components]=61;f->ranges[0].layout.output_components=61;
            joint_expect(f,gpu,ctx,-EINVAL);
        }
    }
    *f=*saved;free(saved);
}
static void joint_repeated_catalog_invocations(struct joint_fixture *f,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx,u32 domain)
{
    struct joint_fixture *saved=malloc(sizeof(*saved));assert(saved);*saved=*f;
    f->invocations[0].invocation_id=0;f->invocations[0].original_draw_index=1;
    f->ranges[0].original_draw_index=1;
    f->invocations[2].original_draw_index=1;f->ranges[2].original_draw_index=1;
    for(u32 i=0;i<3;i++)if(f->plans[i].sequence_index==2)f->plans[i].state_handle=0;
    f->old.sequences[2*512+jt_layouts[domain].state]=0;
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment,address;
    assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
    assert(!joint_prepare_test(o,segment) && !opengpu_private_snapshot_publish(o));
    assert(!opengpu_private_snapshot_address(o,segment,&address));
    joint_job_permission(&ctx->vm,address);
    u32 array=graph_read(&ctx->vm,address+16*4,3),directory=graph_read(&ctx->vm,address+28*4,3);
    for(u32 i=0;i<3;i++) {
        u32 entry=directory+64+i*64;
        assert(graph_read(&ctx->vm,entry+4,3)==1 && graph_read(&ctx->vm,entry+16,3)==3);
        u32 root=graph_read(&ctx->vm,array+i*2048+509*4,3);
        joint_same_root(&ctx->vm,entry,root,i);
        assert(graph_read(&ctx->vm,root+20*4,3)==1);
    }
    assert(!graph_read(&ctx->vm,directory+64+8,3) && !graph_read(&ctx->vm,directory+64+12,3));
    assert(!opengpu_private_snapshot_retire(o));*f=*saved;free(saved);
}
static void joint_engine_header_capacity(struct joint_fixture *f,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx)
{
    struct joint_fixture *saved=malloc(sizeof(*saved));assert(saved);*saved=*f;
    for(u32 engines=1;engines<4;engines++) {
        *f=*saved;f->source.profile.engines=engines;
        for(u32 i=0;i<2;i++)f->old.original.states[i*128+28]=engines;
        u32 count=0;
        for(u32 i=0;i<27;i++) {
            const struct opengpu_native_frame_resource *r=&saved->old.original.encoded.resources[i];
            if(r->role!=OPENGPU_NATIVE_FRAME_RESOURCE_SCRATCH || r->index<engines)
                f->old.original.encoded.resources[count++]=*r;
        }
        f->source.resource_count=count;
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment;
        assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
        assert(!joint_prepare_test(o,segment) && !opengpu_private_snapshot_publish(o));
        assert(!opengpu_private_snapshot_retire(o));
        f->old.original.states[3]=engines;joint_expect(f,gpu,ctx,-EINVAL);
    }
    *f=*saved;free(saved);
}
static void joint_placement(struct joint_fixture *f,struct opengpu_device *gpu,
    struct opengpu_render_context *ctx)
{
    for(u32 kind=0;kind<6;kind++) {
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment;
        assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
        u64 total=0;
        for(const struct snapshot_segment *v=o->segments;v;v=v->next)total+=v->allocation_bytes;
        for(const struct snapshot_resource *v=o->resources;v;v=v->next)total+=v->bytes;
        assert(total<0x10000000ULL-4096 && !(total&4095));
        /* Actual MMU reservations occupy VA, without fabricating relocated
         * addresses or allocating/touching a 256-MiB backing buffer. */
        u64 prefix_bytes=kind<2?0x10000000ULL-4096-total+(u64)kind*4096:
            (kind==2 || kind==5?0x10000000ULL-4096:0x10004000ULL-4096);
        if(kind==4)prefix_bytes=(1ULL<<32)-4096-total;
        struct opengpu_vm_reservation occupied={0};
        assert(!opengpu_mmu_vm_reserve(gpu,&ctx->vm,(size_t)prefix_bytes,&occupied) && occupied.va==4096);
        ctx->vm.reserve_joint_lds=kind==5;
        int ret=joint_prepare_test(o,segment),expected=(kind==1 || kind==2)?-EINVAL:0;
        if(ret!=expected)fprintf(stderr,"joint placement case%u expected%d got%d\n",300+kind,expected,ret);
        assert(ret==expected);
        u64 first=1ULL<<32,last=0;
        for(const struct snapshot_segment *v=o->segments;v;v=v->next) {
            if(v->va<first)first=v->va;
            if(v->va+v->allocation_bytes>last)last=v->va+v->allocation_bytes;
        }
        for(const struct snapshot_resource *v=o->resources;v;v=v->next) {
            if(v->reservation.va<first)first=v->reservation.va;
            if(v->reservation.va+v->bytes>last)last=v->reservation.va+v->bytes;
        }
        if(kind==0)assert(last==0x10000000ULL);
        if(kind==1)assert(last==0x10001000ULL);
        if(kind==2)assert(first==0x10000000ULL);
        if(kind==3 || kind==5)assert(first==0x10004000ULL);
        if(kind==4)assert(last==(1ULL<<32));
        assert(opengpu_private_snapshot_publish(o)==(expected?-EINVAL:0));
        if(kind==4) {
            struct opengpu_private_snapshot_joint_request request;
            assert(!joint_request(o,gpu,ctx,segment,&request) && request.owner_limit==(1ULL<<32));
            struct opengpu_protected_tessellation_joint_host_command command;
            mutex_lock(&gpu->hw.submit_lock);
            int ret=opengpu_protected_tessellation_joint_command_locked(o,gpu,ctx,segment,&command);
            mutex_unlock(&gpu->hw.submit_lock);
            assert(!ret && command.pattern==0x474a0101u && !command.width);
        }
        if(kind==1) {
            int allocations=live_allocations;idle_error=-ETIMEDOUT;
            assert(opengpu_private_snapshot_retire(o)==-ETIMEDOUT && live_allocations==allocations && ctx->references==2);
            idle_error=0;gpu->hw.wedged=false;
        }
        assert(!opengpu_private_snapshot_retire(o));
        assert(!opengpu_mmu_vm_release_reservation(gpu,&occupied));
        assert(ctx->references==1 && !gpu->mmu.snapshot_leases);
        ctx->vm.reserve_joint_lds=false;
    }
    /* Real prepared metadata and leaf faults fail publication, then retire. */
    for(u32 kind=0;kind<7;kind++) {
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment;
        assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
        assert(!opengpu_private_snapshot_prepare(o));
        struct snapshot_segment *root=snapshot_segment_find(o,segment);assert(root);
        if(kind==0)word(root->backing.cpu,24*4,ctx->id+1);
        if(kind==1)word(root->backing.cpu,22*4,0xffffffffu);
        if(kind==2)word(root->backing.cpu,28*4,(u32)root->va);
        if(kind==4)word(root->backing.cpu,25*4,0x12345678);
        if(kind==5)word(root->backing.cpu,26*4,0x12345678);
        if(kind==6) {
            u32 directory_va=0;assert(!opengpu_private_snapshot_read32(o,segment,28*4,&directory_va));
            struct snapshot_segment *directory=(struct snapshot_segment *)snapshot_joint_target(o,directory_va);assert(directory);
            word(directory->backing.cpu,4*4,0x12345678);
        }
        u32 *leaf=NULL,saved=0;
        if(kind==3) {
            const struct opengpu_buffer *l1=vm_live_l1(&ctx->vm,(u32)root->va/MMU_SUPERPAGE_SIZE);assert(l1);
            leaf=(u32 *)l1->cpu+(root->va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE;saved=*leaf;*leaf|=MMU_PTE_W;
        }
        assert(opengpu_private_snapshot_check_prepared_joint(o,segment)<0);
        assert(opengpu_private_snapshot_publish(o)==-EINVAL);
        if(leaf)*leaf=saved;
        assert(!opengpu_private_snapshot_retire(o));
    }

    /* A generic prepared/published graph never obtains a joint request. */
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment;
    assert(!IS_ERR(o) && !opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment));
    assert(!opengpu_private_snapshot_prepare(o) && !opengpu_private_snapshot_publish(o));
    joint_request_rejected(o,gpu,ctx,segment);
    assert(!opengpu_private_snapshot_retire(o));
}
#ifndef OPENGPU_TESS_JOINT_TEST_MAIN
#define OPENGPU_TESS_JOINT_TEST_MAIN main
#endif
int OPENGPU_TESS_JOINT_TEST_MAIN(void)
{
    for(u32 domain=0;domain<3;domain++) {
        struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
        gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
        assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
        struct joint_fixture *f=calloc(1,sizeof(*f));assert(f);joint_init(f,&gpu,domain);
        joint_endpoints(f->old.original.job);
        joint_negatives(f,&gpu,&ctx,domain);joint_state_semantics(f,&gpu,&ctx);joint_empty(f,&gpu,&ctx);
        joint_zero_work_invocations(f,&gpu,&ctx);joint_alias_cases(f,&gpu,&ctx);
        joint_capacity_cases(f,&gpu,&ctx,domain);
        joint_repeated_catalog_invocations(f,&gpu,&ctx,domain);
        joint_engine_header_capacity(f,&gpu,&ctx);
        if(!domain){joint_failures(f,&gpu,&ctx);joint_placement(f,&gpu,&ctx);}
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);u32 segment;
        graph_copies=graph_resolves=0;
        assert(!IS_ERR(o));
        if(domain==2)joint_mutate=f;
        int ret=opengpu_protected_tessellation_joint_build(o,&f->source,&joint_callbacks,&segment);
        if(ret){
            if(domain==2)fprintf(stderr,"joint caller-capture boundary domain=2\n");
            fprintf(stderr,"joint positive domain%u got%d\n",domain,ret);
        }
        assert(!ret && graph_copies>70);
        joint_request_rejected(o,&gpu,&ctx,segment);
        assert(!joint_prepare_test(o,segment));
        joint_request_rejected(o,&gpu,&ctx,segment);
        assert(!opengpu_private_snapshot_publish(o));
        joint_request_oracle(o,&gpu,&ctx,segment,domain);
        joint_oracle(o,&ctx,segment,domain);
        int allocations=live_allocations;idle_error=-ETIMEDOUT;
        assert(opengpu_private_snapshot_retire(o)==-ETIMEDOUT && live_allocations==allocations && ctx.references==2);
        joint_request_rejected(o,&gpu,&ctx,segment);
        idle_error=0;gpu.hw.wedged=false;asid_flush_failures=1;
        assert(opengpu_private_snapshot_retire(o)==-EIO && live_allocations==allocations && ctx.references==2);
        joint_request_rejected(o,&gpu,&ctx,segment);
        gpu.hw.wedged=false;assert(!opengpu_private_snapshot_retire(o));
        pt_free_originals(&gpu);free(f);
        opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
        assert(ctx.references==1 && !live_allocations && !fixture_buffers);
    }
    puts("joint published root request: all domains, LIMIT33, unpublished and foreign roots, live leaves and quarantine PASS");
    puts("joint GNQ4/5/6 original VS/GVS/GND/GUR1+GUR3/v3/RX/FS8+9/private owner/MMU PASS (BUILD only)");
    return 0;
}
