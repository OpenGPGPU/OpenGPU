/* SPDX-License-Identifier: MIT */
/* Actual production BUILD/prepare/owner/MMU. Only OS memory/GEM ownership and
 * completed-producer prerequisites are modeled. Never enqueue or execute ISA. */
#define OPENGPU_TESS_JOINT_TEST_MAIN unused_joint_component_corpus
#include "../../driver/tests/opengpu_protected_tessellation_joint_test.c"
#undef OPENGPU_TESS_JOINT_TEST_MAIN
/* The OS fixture and userspace both have a private type named opengpu_buffer.
 * Only the unused userspace declaration is namespaced in this test TU; no
 * production function or captured object representation is replaced. */
#define opengpu_buffer userspace_buffer_declaration
#include "opengpu_native_joint_build_source.h"
#undef opengpu_buffer
#include "native_joint_build_bridge.h"
struct native_joint_build_session {
    struct opengpu_device gpu;struct opengpu_render_context context;
    struct opengpu_buffer *original;unsigned *references;unsigned count;
    unsigned resolves,copies,fail_copy,fail_resolve;bool producers_complete;
};
struct native_joint_build_session *native_joint_build_session_create(unsigned context)
{
    struct native_joint_build_session *s=calloc(1,sizeof(*s));assert(s);
    s->original=calloc(4096,sizeof(*s->original));s->references=calloc(4096,sizeof(*s->references));assert(s->original&&s->references);
    s->context=(struct opengpu_render_context){.gpu=&s->gpu,.references=1,.id=context};
    s->gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&s->gpu)&&!opengpu_mmu_vm_create(&s->gpu,&s->context.vm));
    copy_gpu=&s->gpu;s->producers_complete=true;return s;
}
void *native_joint_build_original_allocate(struct native_joint_build_session *s,unsigned handle,uint64_t bytes)
{
    assert(s&&handle&&handle<4096&&!(bytes&4095)&&!s->references[handle]);
    assert(!opengpu_buffer_alloc(&s->gpu,s->original+handle,bytes));s->references[handle]=1;s->count++;
    return s->original[handle].cpu;
}
int native_joint_build_original_close(struct native_joint_build_session *s,unsigned handle)
{
    assert(s&&handle&&handle<4096&&s->references[handle]==1);
    assert(!s->gpu.mmu.snapshot_leases);s->references[handle]=0;s->count--;
    opengpu_buffer_free(&s->gpu,s->original+handle);return 0;
}
int native_joint_build_original_references(struct native_joint_build_session *s,unsigned handle)
{return handle&&handle<4096?s->references[handle]:0;}
static int actual_resolve(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
    struct native_joint_build_session *s=opaque;if(!s->producers_complete)return -EAGAIN;
    if(++s->resolves==s->fail_resolve)return -EIO;
    if(!handle||handle>=4096||!s->references[handle])return -EINVAL;
    *out=(struct opengpu_native_frame_backing){s->original[handle].dma,s->original[handle].size};return 0;
}
static int actual_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *v,void *out)
{
    struct native_joint_build_session *s=opaque;struct opengpu_native_frame_backing b;
    if(++s->copies==s->fail_copy)return -EIO;
    if(actual_resolve(opaque,v->handle,&b)||v->offset>b.bytes||v->bytes>b.bytes-v->offset)return -EINVAL;
    memcpy(out,(u8 *)s->original[v->handle].cpu+v->offset,v->bytes);return 0;
}
static void uniform_oracle(struct native_joint_build_session *s,u32 root,u32 stage,
    const struct drm_opengpu_native_uniform_stage *original)
{
    struct opengpu_vm *vm=&s->context.vm;bool tess=stage>=4;
    if(!original->active_mask&&!original->defaults.handle){assert(!root);return;}
    assert(graph_read(vm,root,3)==(tess?0x47555203u:0x47555201u));
    assert(graph_read(vm,root+4,3)==64&&graph_read(vm,root+8,3)==(tess?3u:1u));
    assert(graph_read(vm,root+12,3)==s->context.id&&graph_read(vm,root+16,3)==11&&graph_read(vm,root+20,3)==13);
    assert(graph_read(vm,root+56,3)==!!original->defaults.handle&&!graph_read(vm,root+60,3));
    assert(graph_read(vm,root+24,3)==stage&&graph_read(vm,root+28,3)==original->active_mask);
    u32 table=graph_read(vm,root+40,3);assert(graph_read(vm,root+44,3)==(table?table+24*64:0));
    assert(!!table==!!original->active_mask);
    assert(!(graph_read(vm,root+32,3)>>30)&&!(graph_read(vm,root+36,3)>>30));
    for(u32 block=0;block<13;block++){
        const struct drm_opengpu_compute_uniform_view *v=block<12?original->blocks+block:&original->defaults;
        u32 map=graph_read(vm,root+4*(block<6?8:9),3),slot=block<12?(map>>(5*(block%6)))&31u:31;
        if(!v->handle){if(block<12)assert(slot==0);else assert(!graph_read(vm,root+52,3));continue;}
        assert(block==12||slot<24);
        u32 record=block==12?graph_read(vm,root+52,3):table+slot*64;
        assert(graph_read(vm,record,3)==(tess?0x47554203u:0x47554201u));
        assert(graph_read(vm,record+4,3)==64&&graph_read(vm,record+8,3)==(tess?3u:1u));
        assert(graph_read(vm,record+12,3)==s->context.id&&graph_read(vm,record+16,3)==11&&graph_read(vm,record+20,3)==13);
        assert(graph_read(vm,record+24,3)==slot&&graph_read(vm,record+28,3)==1u<<stage);
        assert(graph_read(vm,record+56,3)==1&&!graph_read(vm,record+60,3));
        u32 data=graph_read(vm,record+32,3);u64 limit=graph_read(vm,record+36,3)|((u64)graph_read(vm,record+40,3)<<32);
        assert(limit==(u64)data+v->bytes);
        assert(graph_read(vm,record+44,3)==data);
        assert((graph_read(vm,record+48,3)|((u64)graph_read(vm,record+52,3)<<32))==((u64)data+v->bytes+63)/64*64);
        unsigned source_stage=stage==0?0:stage==4?1:stage==5?2:3;
        if(block<12){assert(v->offset>=4&&(v->offset-4)%16448==0&&v->bytes==16384);
            u64 binding=(v->offset-4)/16448;
            assert(binding>=(source_stage==3?60:source_stage*12)&&binding<(source_stage==3?72:(source_stage+1)*12));}
        for(u32 n=0;n<v->bytes;n+=4){
            u32 expected=block<12?0x13579bdfu+(u32)((v->offset+n)/4)*33u:0x76543210u-n/4-source_stage*4096;
            u32 actual;memcpy(&actual,(u8 *)s->original[v->handle].cpu+v->offset+n,4);
            assert(actual==expected);assert(graph_read(vm,data+n,3)==expected);
        }
        for(u32 n=v->bytes;n<(v->bytes+63)/64*64;n+=4)assert(!graph_read(vm,data+n,3));
        /* R/NX: independently inspect actual PTE flags, not just read success. */
        (void)graph_translate(vm,data,3);
    }
}
static const u32 input_bases[16]={185,194,202,210,218,226,234,242,250,258,266,274,282,290,298,306};
static u64 wide_graph(struct opengpu_vm *vm,u32 record,u32 word)
{return graph_read(vm,record+word*4,3)|((u64)graph_read(vm,record+(word+1)*4,3)<<32);}
/* Literal offsets independently transcribed from native-binned-graphics-v1
 * frame JSON. Never reuse production relocation lookup arrays as expected. */
static const u32 oracle_images[12][6]={{1,0,210,213,207,208},{1,1,222,225,219,220},{1,2,234,237,231,232},{1,3,246,249,243,244},{1,4,258,261,255,256},{1,5,270,273,267,268},{2,0,130,133,126,128},{2,1,143,146,140,141},{2,2,156,160,153,154},{2,3,170,173,167,168},{2,4,183,186,180,181},{2,5,196,199,193,194}};
static void frame_oracle(struct native_joint_build_session *s,u32 frame,const struct opengpu_native_frame_encoding *e)
{
    struct opengpu_vm *vm=&s->context.vm;
    for(u32 n=0;n<e->resource_count;n++){
        const struct opengpu_native_frame_resource *r=e->resources+n;u32 baseword,limitword;
        const u32 *view=NULL;
        switch(r->role){
        case OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE:view=oracle_images[r->index];baseword=view[2];limitword=view[3];break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_RESOLVE:view=oracle_images[6+r->index];baseword=view[2];limitword=view[3];break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_PRIMITIVES:baseword=57;limitword=61;break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_DIRECTORY:baseword=16;limitword=17;break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_POOL:baseword=r->index?29+3*(r->index-1):19;limitword=r->index?30+3*(r->index-1):25;break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_SCRATCH:baseword=279+3*r->index;limitword=280+3*r->index;break;
        default:assert(0);return;
        }
        u32 base=graph_read(vm,frame+baseword*4,3);
        assert(wide_graph(vm,frame,limitword)==(u64)base+r->bytes);
        u32 permission=(r->access&OPENGPU_NATIVE_FRAME_RESOURCE_WRITE)?7:3;
        assert(graph_translate(vm,base,permission)==s->original[r->gem_handle].dma+r->offset);
        assert(graph_translate(vm,base+r->bytes-1,permission)==s->original[r->gem_handle].dma+r->offset+r->bytes-1);
        if(view){
            u32 allocation=graph_read(vm,frame+view[4]*4,3);
            assert(graph_translate(vm,allocation,permission)==(s->original[r->gem_handle].dma+r->offset)/64*64);
            assert(wide_graph(vm,frame,view[5])==allocation+((r->offset%64+r->bytes+63)/64)*64);
        }
    }
}
static void code_oracle(struct native_joint_build_session *s,u32 pc,const struct drm_opengpu_native_program_view *v)
{
    assert(pc>=v->entry_offset);u32 base=pc-v->entry_offset;
    for(u32 i=0;i<v->bytes;i+=4){u32 want;memcpy(&want,(u8 *)s->original[v->handle].cpu+v->offset+i,4);assert(graph_read(&s->context.vm,base+i,11)==want);}
    (void)graph_translate(&s->context.vm,pc,11);
}
int native_joint_build_prove(struct native_joint_build_session *s,
    const struct opengpu_native_joint_build_description *d,const struct opengpu_native_joint_description *program)
{
    assert(s&&d&&program&&d->version==1&&d->context==s->context.id);
    struct opengpu_protected_tessellation_joint_plan *plans=calloc(d->range_count?d->range_count:1,sizeof(*plans));
    struct opengpu_protected_tessellation_state_plan *states=calloc(d->state_count?d->state_count:1,sizeof(*states));
    struct opengpu_tessellation_vertex_outer_invocation_v2 *inv=calloc(d->invocation_count?d->invocation_count:1,sizeof(*inv));
    struct opengpu_tessellation_vertex_outer_range_v2 *ranges=calloc(d->range_count?d->range_count:1,sizeof(*ranges));
    assert(plans&&states&&inv&&ranges);
    for(u32 i=0;i<d->range_count;i++){
        const struct opengpu_native_joint_build_plan *p=d->plans+i;
        plans[i]=(struct opengpu_protected_tessellation_joint_plan){.sequence_index=p->sequence_index,.state_handle=p->state_handle,
            .control_program=p->control_program,.evaluation_program=p->evaluation_program,
            .control_uniforms=p->control_uniforms,.evaluation_uniforms=p->evaluation_uniforms};
        const struct opengpu_native_joint_range *r=d->ranges+i;
        ranges[i]=(struct opengpu_tessellation_vertex_outer_range_v2){.sequence_index=r->sequence_index,.invocation_index=r->invocation_index,
            .original_draw_index=r->original_draw_index,.range_ordinal=r->range_ordinal,.flags=r->flags,
            .task_id_base=r->task_id_base,.patch_count=r->patch_count,
            .slice={r->slice_version,r->first_ordinal,r->patches_per_instance,r->first_instance,r->instances},
            .layout={r->layout.input_vertices,r->layout.input_components,r->layout.output_vertices,r->layout.output_components,r->layout.patch_components}};
        for(u32 j=0;j<64;j++)ranges[i].components[j]=(struct opengpu_tessellation_vertex_component){r->components[j].kind,r->components[j].component};
    }
    for(u32 i=0;i<d->state_count;i++)states[i]=(struct opengpu_protected_tessellation_state_plan){
        .state_handle=d->state_plans[i].state_handle,.fragment_program=d->state_plans[i].fragment_program,
        .fragment_uniforms=d->state_plans[i].fragment_uniforms};
    for(u32 i=0;i<d->invocation_count;i++)inv[i]=(struct opengpu_tessellation_vertex_outer_invocation_v2){
        .invocation_id=d->invocations[i].invocation_id,.original_draw_index=d->invocations[i].draw_index,
        .input_patch_vertices=d->invocations[i].patch_vertices};
    struct opengpu_protected_tessellation_joint_source source={.job=d->job,.frame=&d->frame->frame,
        .sequences=d->sequences,.sequences_bytes=(size_t)d->range_count*2048,.states=d->states,.states_bytes=(size_t)d->state_count*512,
        .draws=d->draws,.draws_bytes=(size_t)d->draw_count*2048,.draw_plans=d->draw_plans,.draw_count=d->draw_count,.draw_plan_count=d->draw_count,
        .invocations=d->invocation_count?inv:NULL,.invocation_count=d->invocation_count,.ranges=d->range_count?ranges:NULL,.range_count=d->range_count,
        .plans=d->range_count?plans:NULL,.plan_count=d->range_count,.state_plans=d->state_count?states:NULL,.state_plan_count=d->state_count,
        .resources=d->frame->resources,.resource_count=d->frame->resource_count,.context=d->context,.pass=d->pass,.generation=d->generation,
        /* Independent CPU-only kernel policy; absent from producer metadata. */
        .profile={.version=1,.domains=7,.compute_units=2,.lanes=16,.warps=8,.engines=4,.available=true,
            .scalar_fpu=true,.owned_vertex_source=true,.patch_zero_initialized=true,.owned_patch_reads=true,
            .owned_tessellation_info=true,.primitive_inputs=true,.sample_inputs=true,.joint_directory_route=true,.whole_owner_retirement=true}};
    const struct opengpu_native_job_copy copy={actual_copy,actual_resolve,s};
    /* Original bytes include tails and all frame/scratch allocations. BUILD
     * snapshots and maps them; this CPU-only proof must not alter originals. */
    u8 **before=calloc(4096,sizeof(*before));assert(before);
    for(u32 h=1;h<4096;h++)if(s->references[h]){before[h]=malloc(s->original[h].size);assert(before[h]);memcpy(before[h],s->original[h].cpu,s->original[h].size);}
    /* Actual independent admission/fence/copy failures leave output unchanged
     * and are cleaned by the same real owner/MMU retirement, before reuse. */
    for(u32 bad=0;bad<(d->range_count?8u:2u);bad++){
        struct opengpu_protected_tessellation_joint_source wrong=source;
        int expected=-EOPNOTSUPP;
        if(bad==0)wrong.profile.primitive_inputs=false;
        if(bad==1){s->producers_complete=false;expected=-EAGAIN;}
        if(bad==2){s->fail_copy=1;expected=-EIO;}
        if(bad==3){s->fail_resolve=1;expected=-EIO;}
        if(bad==4){wrong.profile.sample_inputs=false;
            if(program->fragment.metadata.abi_version!=9)continue;
            expected=-EINVAL;}
        struct drm_opengpu_native_draw_plan *bad_draws=NULL;
        if(bad>=5){bad_draws=calloc(d->draw_count,sizeof(*bad_draws));assert(bad_draws);
            memcpy(bad_draws,d->draw_plans,d->draw_count*sizeof(*bad_draws));wrong.draw_plans=bad_draws;expected=-EINVAL;
            if(bad==5)bad_draws[0].vertex_program.entry_offset=bad_draws[0].vertex_program.bytes;
            if(bad==6)bad_draws[0].draw_index=bad_draws[1].draw_index;
            if(bad==7)bad_draws[0].bindings[0]=bad_draws[0].vertex_scratch;
        }
        s->resolves=s->copies=0;
        struct opengpu_private_snapshot *failed=opengpu_private_snapshot_create(&s->gpu,&s->context);assert(!IS_ERR(failed));
        u32 output=0xa5a5a5a5;int error=opengpu_protected_tessellation_joint_build(failed,&wrong,&copy,&output);
        assert(error==expected&&output==0xa5a5a5a5);free(bad_draws);
        assert(!opengpu_private_snapshot_retire(failed)&&s->context.references==1&&!s->gpu.mmu.snapshot_leases);
        s->producers_complete=true;s->fail_copy=s->fail_resolve=0;
    }
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&s->gpu,&s->context);assert(!IS_ERR(owner));
    assert(opengpu_protected_tessellation_joint_job_canonical(d->job,512,false));
    u32 segment=0xa5a5a5a5;s->resolves=s->copies=0;
    int result=opengpu_protected_tessellation_joint_build(owner,&source,&copy,&segment);
    if(result)fprintf(stderr,"actual linked original-GEM joint BUILD rejected %d domain%u resolves%u copies%u\n",result,d->domain,s->resolves,s->copies);
    assert(!result&&s->resolves&&(!d->range_count||s->copies));
    assert(!opengpu_protected_tessellation_joint_prepare(owner,segment)&&!opengpu_private_snapshot_publish(owner));
    u32 address;assert(!opengpu_private_snapshot_address(owner,segment,&address));
    struct opengpu_vm *vm=&s->context.vm;
    assert(graph_read(vm,address,3)==0x474e5104u+d->domain);
    u32 seq=graph_read(vm,address+16*4,3),directory=graph_read(vm,address+28*4,3),frame=graph_read(vm,address+21*4,3);
    assert(graph_read(vm,frame,3)==0x474e4601);
    if(d->range_count)assert(graph_read(vm,directory,3)==0x47564d02);else assert(!directory&&!seq);
    frame_oracle(s,frame,d->frame);
    const struct jt_layout *l=jt_layouts+d->domain;
    for(u32 i=0;i<d->range_count;i++){
        const struct opengpu_native_joint_range *r=d->ranges+i;u32 gnt=seq+i*2048,entry=directory+64+i*64;
        assert(graph_read(vm,entry,3)==i&&graph_read(vm,entry+4,3)==r->original_draw_index);
        assert(graph_read(vm,entry+8,3)==(u32)d->invocations[r->invocation_index].invocation_id);
        assert(graph_read(vm,entry+12,3)==(u32)(d->invocations[r->invocation_index].invocation_id>>32));
        u32 gvs=graph_read(vm,gnt+509*4,3);assert(graph_read(vm,entry+48,3)==gvs);
        assert(graph_read(vm,gvs,3)==0x47565301&&graph_read(vm,gvs+19*4,3)==i);
        assert(graph_read(vm,gvs+22*4,3)==r->patch_count);
        if(!r->patch_count){assert(graph_read(vm,entry+16,3)==3);
            for(u32 k=33;k<=36;k++)assert(!graph_read(vm,gvs+k*4,3));}
        u32 gnd=graph_read(vm,gvs+24*4,3),pc=graph_read(vm,gnd+342*4,3);
        code_oracle(s,pc,program->code);uniform_oracle(s,graph_read(vm,gnd+507*4,3),0,program->uniforms);
        for(u32 j=0;j<64;j++)assert(graph_read(vm,gvs+(64+j)*4,3)==
            (program->vertex_components[j].kind|(program->vertex_components[j].component<<2)));
        const struct drm_opengpu_native_draw_plan *dp=d->draw_plans+r->original_draw_index;
        for(u32 b=0;b<16;b++)if(dp->bindings[b].handle){u32 base=graph_read(vm,gnd+input_bases[b]*4,3);
            assert(graph_translate(vm,base,3)==s->original[dp->bindings[b].handle].dma+dp->bindings[b].offset);}
        if(dp->indices.handle){u32 base=graph_read(vm,gnd+322*4,3);
            assert(graph_translate(vm,base,3)==s->original[dp->indices.handle].dma+dp->indices.offset);
            assert(wide_graph(vm,gnd,323)==(u64)base+dp->indices.bytes);
            assert(graph_read(vm,gnd+181*4,3)==0xffffffffu&&graph_read(vm,gnd+312*4,3)==1&&graph_read(vm,gnd+317*4,3)==2);
        }
        assert(graph_read(vm,gnd+180*4,3)==7); /* original baseInstance */
        u32 scratch=graph_read(vm,gnd+339*4,3);
        assert(graph_translate(vm,scratch,7)==s->original[dp->vertex_scratch.handle].dma+dp->vertex_scratch.offset);
        code_oracle(s,graph_read(vm,gnt+l->control_pc*4,3),program->code+1);
        code_oracle(s,graph_read(vm,gnt+l->evaluation_pc*4,3),program->code+2);
        uniform_oracle(s,graph_read(vm,gnt+500*4,3),4,program->uniforms+1);
        uniform_oracle(s,graph_read(vm,gnt+505*4,3),5,program->uniforms+2);
    }
    u32 fs=graph_read(vm,frame+292*4,3);
    for(u32 i=0;i<d->state_count;i++){
        u32 f=fs+i*512;assert(graph_read(vm,f+56*4,3)==program->fragment.metadata.abi_version);
        code_oracle(s,graph_read(vm,f+54*4,3),program->code+3);
        uniform_oracle(s,graph_read(vm,f+119*4,3),1,program->uniforms+3);
        for(u32 e=0;e<4;e++){
            u32 base=graph_read(vm,f+state_scratch[e].base*4,3);
            assert(graph_read(vm,f+state_scratch[e].limit*4,3)==base+program->fragment.metadata.prefix_bytes);
            (void)graph_translate(vm,base,7);
        }
    }
    assert(!opengpu_private_snapshot_retire(owner)&&s->context.references==1&&!s->gpu.mmu.snapshot_leases);
    for(u32 h=1;h<4096;h++)if(before[h]){assert(s->references[h]&&!memcmp(before[h],s->original[h].cpu,s->original[h].size));free(before[h]);}
    free(before);
    free(plans);free(states);free(inv);free(ranges);
    puts("actual linked4 source/GUR1+3/full original bytes/RX/MMU joint BUILD proof PASS; ISA=false submit=false");return 0;
}
void native_joint_build_session_destroy(struct native_joint_build_session *s)
{
    assert(s&&!s->count&&!s->gpu.mmu.snapshot_leases&&s->context.references==1);
    opengpu_mmu_vm_destroy(&s->gpu,&s->context.vm);opengpu_mmu_fini(&s->gpu);
    free(s->original);free(s->references);free(s);
}
