// SPDX-License-Identifier: GPL-2.0
/* Linux ownership and reservation boundaries for protected native graphs. */
#include <linux/dma-resv.h>
#include <linux/overflow.h>
#include <linux/slab.h>
#include <linux/uaccess.h>
#include <linux/vmalloc.h>
#ifdef CONFIG_COMPAT
#include <linux/compat.h>
#endif
#include <drm/drm_device.h>
#include <drm/drm_exec.h>
#include <drm/drm_gem_dma_helper.h>
#include <drm/drm_syncobj.h>
#include "opengpu_scheduler.h"
#include "opengpu_private_snapshot.h"
#include "opengpu_native_job_snapshot.h"
#include "opengpu_native_binned_abi.h"
#include "opengpu_native_advanced_blend_gfs.h"
#include "opengpu_native_tessellation_sequence_abi.h"
#include "opengpu_typed_fragment_state_abi.h"
#include "opengpu_admission.h"
#include "opengpu_protected_tessellation_submit.h"
#include "opengpu_protected_tessellation_joint.h"

struct native_reference {
    struct drm_gem_object *object;
    u32 handle;
    bool writable;
};
struct native_sources {
    struct drm_file *file;
    struct native_reference *refs;
    u32 count, capacity;
    bool producers_complete;
};
static int native_retain(struct native_sources *s,u32 handle,bool writable)
{
    struct drm_gem_object *object;
    struct drm_gem_dma_object *dma;
    u32 i;
    if(!handle)return -EINVAL;
    for(i=0;i<s->count;++i)if(s->refs[i].handle==handle){
        s->refs[i].writable|=writable;return 0;
    }
    if(s->count==s->capacity)return -ERANGE;
    object=drm_gem_object_lookup(s->file,handle);
    if(!object)return -ENOENT;
    dma=to_drm_gem_dma_obj(object);
    if(object->import_attach || !dma->vaddr || !object->size ||
        (dma->dma_addr&4095u) || (object->size&4095u) ||
        (u64)object->size>(1ULL<<32) || (u64)dma->dma_addr>(1ULL<<32)-object->size){
        drm_gem_object_put(object);return -EOPNOTSUPP;
    }
    s->refs[s->count++]=(struct native_reference){object,handle,writable};
    return 0;
}
static int native_view(struct native_sources *s,const struct drm_opengpu_compute_uniform_view *v,bool writable)
{
    u32 i;
    int ret;
    if(!v->handle)return (v->offset || v->bytes)?-EINVAL:0;
    if(!v->bytes || v->offset>~(u64)0-v->bytes)return -EINVAL;
    ret=native_retain(s,v->handle,writable);if(ret)return ret;
    for(i=0;i<s->count;++i)if(s->refs[i].handle==v->handle)
        return v->offset+v->bytes<=s->refs[i].object->size?0:-ERANGE;
    return -EINVAL;
}
static int native_resolve(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
    struct native_sources *s=opaque;
    u32 i;
    if(!s->producers_complete || !out)return -EINVAL;
    for(i=0;i<s->count;++i)if(s->refs[i].handle==handle){
        struct drm_gem_object *object=s->refs[i].object;
        *out=(struct opengpu_native_frame_backing){to_drm_gem_dma_obj(object)->dma_addr,object->size};
        return 0;
    }
    return -ENOENT;
}
static int native_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *view,void *out)
{
    struct native_sources *s=opaque;
    u32 i;
    if(!s->producers_complete || !out || !view || !view->bytes ||
        view->offset>~(u64)0-view->bytes)return -EINVAL;
    for(i=0;i<s->count;++i)if(s->refs[i].handle==view->handle){
        struct drm_gem_object *object=s->refs[i].object;
        if(view->offset+view->bytes>object->size)return -ERANGE;
        /* Reservation locks and completed producer fences precede this exact
         * original-GEM copy. The destination is kernel-only owner staging. */
        memcpy(out,(const u8 *)to_drm_gem_dma_obj(object)->vaddr+view->offset,view->bytes);
        return 0;
    }
    return -ENOENT;
}
static int native_collect(struct native_sources *sources,const struct opengpu_native_job_source *s)
{
    u32 i,j,stage;
    int ret;
    for(i=0;i<s->plan_count;++i){
        const struct drm_opengpu_native_draw_plan *p=opengpu_native_job_draw_plan(s,i);
        const struct drm_opengpu_native_layered_draw_plan *g=s->layered?&s->layered_plans[i]:NULL;
        const struct drm_opengpu_native_program_view *code[3]={&p->vertex_program,&p->fragment_program,g?&g->geometry_program:NULL};
        const struct drm_opengpu_native_uniform_stage *uniforms[3]={&p->vertex_uniforms,&p->fragment_uniforms,g?&g->geometry_uniforms:NULL};
        for(stage=0;stage<(s->layered?3u:2u);++stage){
            const struct drm_opengpu_compute_uniform_view v={.offset=code[stage]->offset,
                .handle=code[stage]->handle,.bytes=code[stage]->bytes};
            ret=native_view(sources,&v,false);if(ret)return ret;
            for(j=0;j<12;++j){ret=native_view(sources,&uniforms[stage]->blocks[j],false);if(ret)return ret;}
            ret=native_view(sources,&uniforms[stage]->defaults,false);if(ret)return ret;
        }
        for(j=0;j<16;++j){ret=native_view(sources,&p->bindings[j],false);if(ret)return ret;}
        ret=native_view(sources,&p->indices,false);if(ret)return ret;
        ret=native_view(sources,&p->indirect,false);if(ret)return ret;
        ret=native_view(sources,&p->vertex_scratch,true);if(ret)return ret;
    }
    if(s->layered){ret=native_view(sources,&s->completion,true);if(ret)return ret;}
    for(i=0;i<s->resource_count;++i){
        const struct opengpu_native_frame_resource *r=&s->resources[i];
        ret=native_retain(sources,r->gem_handle,!!(r->access&OPENGPU_NATIVE_FRAME_RESOURCE_WRITE));
        if(ret)return ret;
    }
    return 0;
}

static bool native_pointer_valid(u64 pointer)
{
    if(sizeof(void *)<8 && pointer>>32)return false;
#ifdef CONFIG_COMPAT
    if(in_compat_syscall() && pointer>>32)return false;
#endif
    return true;
}
int opengpu_native_binned_ioctl(struct drm_device *drm,void *data,struct drm_file *file)
{
    struct drm_opengpu_submit_native_binned *args=data;
    struct opengpu_device *gpu=dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file=file->driver_priv;
    struct opengpu_render_context *context;
    struct opengpu_native_job_source source={0};
    struct native_sources sources={.file=file};
    struct opengpu_native_job_copy copy={native_copy,native_resolve,&sources};
    struct opengpu_sched_dep *deps=NULL;
    struct opengpu_sched_job *job=NULL;
    struct drm_syncobj *out_sync=NULL;
    struct drm_exec exec;
    void *records[7]={0};
    bool layered;
    u64 plans_bytes,resources_bytes,max_refs,seqno=0;
    u32 i;
    int ret=-EINVAL;
    if(!args)return -EINVAL;
    layered=args->version==OPENGPU_NATIVE_LAYERED_SUBMIT_VERSION;
    if((args->version!=1 && !layered) || args->flags || args->pad || args->reserved[1] ||
        (layered?!args->reserved[0]:!!args->reserved[0]) ||
        args->plan_stride!=(layered?sizeof(struct drm_opengpu_native_layered_draw_plan):sizeof(struct drm_opengpu_native_draw_plan)) ||
        args->resource_stride!=sizeof(struct opengpu_native_frame_resource) ||
        !args->job || !args->frame || !args->states || !args->states_bytes || (args->states_bytes&511u) ||
        !args->resource_count || args->resource_count>27 || !args->resources ||
        (args->plan_count && (!args->plans || !args->draws || !args->draws_bytes)) ||
        args->draws_bytes>(1ULL<<32) || args->states_bytes>(1ULL<<32) ||
        args->draws_bytes>(u64)(~(size_t)0) || args->states_bytes>(u64)(~(size_t)0))return -EINVAL;
    if(layered && (!native_pointer_valid(args->reserved[0]) || !native_pointer_valid(args->job) ||
       !native_pointer_valid(args->frame) || !native_pointer_valid(args->draws) || !native_pointer_valid(args->states) ||
       !native_pointer_valid(args->plans) || !native_pointer_valid(args->resources)))return -EINVAL;
    if((layered!=gpu->hw.native_layered_transport) ||
       !gpu->hw.native_sources.available || !gpu->hw.native_descriptor_preflight ||
       !gpu->hw.native_sources.capacity || gpu->hw.native_sources.capacity>255)return -EOPNOTSUPP;
    plans_bytes=(u64)args->plan_count*args->plan_stride;
    resources_bytes=(u64)args->resource_count*sizeof(struct opengpu_native_frame_resource);
    max_refs=(u64)args->plan_count*(layered?61u:47u)+args->resource_count+(layered?1u:0u);
    if(plans_bytes>(u64)(~(size_t)0) || max_refs>0xffffffffu ||
       max_refs*sizeof(*sources.refs)>(u64)(~(size_t)0) ||
       max_refs*sizeof(*deps)>(u64)(~(size_t)0))return -ERANGE;
#define COPY(index,pointer,bytes) do { \
    if(bytes){records[index]=vmemdup_user(u64_to_user_ptr(pointer),(size_t)(bytes)); \
        if(IS_ERR(records[index])){ret=PTR_ERR(records[index]);records[index]=NULL;goto out;}} \
} while(0)
    COPY(0,args->job,512u);COPY(1,args->frame,2048u);
    COPY(2,args->draws,args->draws_bytes);COPY(3,args->states,args->states_bytes);
    COPY(4,args->plans,plans_bytes);COPY(5,args->resources,resources_bytes);
    if(layered){
        const struct drm_opengpu_native_completion_view *completion;
        COPY(6,args->reserved[0],sizeof(struct drm_opengpu_native_completion_view));
        completion=records[6];
        if(completion->reserved[0] || completion->reserved[1]){ret=-EINVAL;goto out;}
    }
#undef COPY
    source=(struct opengpu_native_job_source){.job=records[0],.frame=records[1],.draws=records[2],.states=records[3],
        .draws_bytes=args->draws_bytes,.states_bytes=args->states_bytes,.plans=layered?NULL:records[4],.plan_count=args->plan_count,
        .layered=layered,.layered_plans=layered?records[4]:NULL,
        .resources=records[5],.resource_count=args->resource_count,.context=args->context_id,
        .capacity=gpu->hw.native_sources.capacity,.engines=gpu->hw.native_sources.engines,
        .scalar_fpu=!!(gpu->hw.capabilities&GPU_CAP_COMPUTE_SCALAR_FPU),
        .advanced_blend=gpu->hw.native_advanced_blend_transport,
        .sample_inputs=gpu->hw.native_sample_transport};
    if(layered)source.completion=((const struct drm_opengpu_native_completion_view *)records[6])->view;
    /* These identities are checked against every copied record by the builder. */
    source.pass=opengpu_native_advanced_blend_word(records[0],OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_PASS_WORD);
    source.generation=opengpu_native_advanced_blend_word(records[0],OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_GENERATION_WORD);
    sources.capacity=max_refs;
    sources.refs=kvzalloc((size_t)max_refs*sizeof(*sources.refs),GFP_KERNEL);
    deps=kvzalloc((size_t)max_refs*sizeof(*deps),GFP_KERNEL);
    if(!sources.refs || !deps){ret=-ENOMEM;goto out;}
    /* Capture the input fence before copying sources; do not re-read a
     * replaceable syncobj at scheduler enqueue. No reservation or submit lock
     * is held while this producer is allowed to make progress. */
    if(args->in_syncobj){
        struct dma_fence *input=NULL;
        ret=drm_syncobj_find_fence(file,args->in_syncobj,0,0,&input);
        if(ret)goto out;
        ret=opengpu_hw_fence_wait(gpu,input,OPENGPU_DRAW_WAIT_MS+100,true);
        dma_fence_put(input);if(ret)goto out;
    }
    mutex_lock(&render_file->lock);
    context=opengpu_context_lookup(render_file,args->context_id);
    if(!context || !context->vm.enabled){ret=-EINVAL;goto out_file;}
    ret=native_collect(&sources,&source);if(ret)goto out_file;
    if(args->out_syncobj){out_sync=drm_syncobj_find(file,args->out_syncobj);if(!out_sync){ret=-ENOENT;goto out_file;}}
    for(i=0;i<sources.count;++i)deps[i]=(struct opengpu_sched_dep){sources.refs[i].object,
        sources.refs[i].writable?DMA_RESV_USAGE_READ:DMA_RESV_USAGE_WRITE,
        sources.refs[i].writable?DMA_RESV_USAGE_WRITE:DMA_RESV_USAGE_READ};
    drm_exec_init(&exec,DRM_EXEC_INTERRUPTIBLE_WAIT|DRM_EXEC_IGNORE_DUPLICATES,0);
    drm_exec_until_all_locked(&exec){
        for(i=0,ret=0;!ret && i<sources.count;++i)ret=drm_exec_prepare_obj(&exec,sources.refs[i].object,1);
        drm_exec_retry_on_contention(&exec);if(ret)goto out_exec;
    }
    /* Producer waits do not hold submit_lock. Retirement and fault recovery
     * remain independently able to drain and release previous owners. */
    for(i=0;i<sources.count;++i){
        struct dma_resv_iter cursor;
        struct dma_fence *producer;
        dma_resv_for_each_fence(&cursor,sources.refs[i].object->resv,deps[i].wait_usage,producer){
            ret=opengpu_hw_fence_wait(gpu,producer,OPENGPU_DRAW_WAIT_MS+100,true);
            if(ret)goto out_exec;
        }
    }
    sources.producers_complete=true;
    job=kzalloc(sizeof(*job),GFP_KERNEL);if(!job){ret=-ENOMEM;goto out_exec;}
    job->gpu=gpu;job->context=context;job->vm=&context->vm;job->type=OPENGPU_SCHED_NATIVE_BINNED;
    job->native_owner=opengpu_private_snapshot_create(gpu,context);
    if(IS_ERR(job->native_owner)){ret=PTR_ERR(job->native_owner);job->native_owner=NULL;goto out_exec;}
    ret=opengpu_native_job_snapshot_build(job->native_owner,&source,&copy,&job->native_job_segment);
    if(!ret)ret=opengpu_admission_native_lock(gpu);
    if(!ret){
        ret=opengpu_private_snapshot_prepare(job->native_owner);
        if(!ret)ret=opengpu_private_snapshot_publish(job->native_owner);
        if(!ret)ret=opengpu_sched_job_submit_locked(render_file,file,context,job,0,out_sync,deps,sources.count,&seqno);
        opengpu_admission_unlock(gpu);
    }
    if(!ret){job=NULL;args->fence_seqno=seqno;}
out_exec:
    if(job){
        int revoke=job->native_owner?opengpu_private_snapshot_retire(job->native_owner):0;
        if(revoke){
            /* Keep every original object reference, the context/owner and the
             * job together. Unmap failure cannot authorize backing release. */
            WRITE_ONCE(gpu->hw.wedged,true);
            job->native_quarantine_refs=sources.refs;
            sources.refs=NULL;sources.count=0;ret=revoke;
            dev_err(gpu->dev,"retaining failed native submission after owner revocation failure: %d\n",revoke);
        }else kfree(job);
    }
    drm_exec_fini(&exec);
out_file:
    if(out_sync)drm_syncobj_put(out_sync);
    mutex_unlock(&render_file->lock);
out:
    for(i=0;i<sources.count;++i)drm_gem_object_put(sources.refs[i].object);
    kvfree(deps);kvfree(sources.refs);
    for(i=0;i<7;++i)kvfree(records[i]);
    return ret;
}

/* Separate protected tessellation admission. This does not extend the old
 * trusted GNQ ioctl or native binned discovery. No ioctl number is registered. */
int opengpu_hw_protected_tessellation_profile(struct opengpu_device *gpu,
    struct opengpu_protected_tessellation_profile *profile)
{
    (void)gpu;(void)profile;
    /* Future probe must bind exact GNT4/5/6, GUR3/GUB3, provider launch/RAM
     * initialization, private MMU route and cancellation/retirement contracts.
     * Legacy capability bits and a caller-supplied profile are insufficient. */
    return -EOPNOTSUPP;
}
struct dma_fence *opengpu_protected_tessellation_job_run(struct opengpu_sched_job *job)
{
    struct opengpu_protected_tessellation_profile profile;
    struct dma_fence *fence;
    int ret;

    if (job->joint_report_kind == OPENGPU_TESSELLATION_JOINT_REPORT_GNZ3) {
        if (!job->joint_geometry_report || job->joint_report)
            return ERR_PTR(-EINVAL);
        ret = opengpu_hw_protected_tessellation_joint_geometry_async(job->gpu, job->native_owner,
            job->context, job->native_job_segment, job->joint_geometry_report, &fence);
        return ret ? ERR_PTR(ret) : fence;
    }
    if (job->joint_report_kind == OPENGPU_TESSELLATION_JOINT_REPORT_GNZ2) {
        if (!job->joint_report || job->joint_geometry_report)
            return ERR_PTR(-EINVAL);
        ret = opengpu_hw_protected_tessellation_joint_async(job->gpu, job->native_owner,
            job->context, job->native_job_segment, NULL, job->joint_report, &fence);
        return ret ? ERR_PTR(ret) : fence;
    }
    if (job->joint_report || job->joint_geometry_report ||
        job->joint_report_kind != OPENGPU_TESSELLATION_JOINT_REPORT_NONE)
        return ERR_PTR(-EINVAL);
    ret = opengpu_hw_protected_tessellation_profile(job->gpu, &profile);
    /* Even a future profile getter cannot silently enable a legacy kick. A
     * matched private-owner hardware route must be supplied independently. */
    return ERR_PTR(ret?ret:-EOPNOTSUPP);
}
static int native_collect_protected_tessellation(struct native_sources *sources,
    const struct opengpu_protected_tessellation_source *s)
{
    u32 i,j,stage;int ret;
    for(i=0;i<s->plan_count;++i){
        const struct opengpu_protected_tessellation_plan *p=&s->plans[i];
        const struct drm_opengpu_native_program_view *code[2]={&p->control_program,&p->evaluation_program};
        const struct drm_opengpu_native_uniform_stage *uniforms[2]={&p->control_uniforms,&p->evaluation_uniforms};
        for(stage=0;stage<2;++stage){
            const struct drm_opengpu_compute_uniform_view v={.handle=code[stage]->handle,.offset=code[stage]->offset,.bytes=code[stage]->bytes};
            ret=native_view(sources,&v,false);if(ret)return ret;
            for(j=0;j<12;++j){ret=native_view(sources,&uniforms[stage]->blocks[j],false);if(ret)return ret;}
            ret=native_view(sources,&uniforms[stage]->defaults,false);if(ret)return ret;
        }
        const struct opengpu_protected_tessellation_input *v=&p->input;
        if(!v->handle){if(v->offset || v->bytes)return -EINVAL;continue;}
        if(!v->bytes || v->offset>~(u64)0-v->bytes)return -EINVAL;
        ret=native_retain(sources,v->handle,false);if(ret)return ret;
        for(j=0;j<sources->count;++j)if(sources->refs[j].handle==v->handle &&
            v->offset+v->bytes>sources->refs[j].object->size)return -ERANGE;
    }
    for(i=0;i<s->state_plan_count;++i){
        const struct opengpu_protected_tessellation_state_plan *p=&s->state_plans[i];
        const struct drm_opengpu_compute_uniform_view v={.handle=p->fragment_program.handle,
            .offset=p->fragment_program.offset,.bytes=p->fragment_program.bytes};
        ret=native_view(sources,&v,false);if(ret)return ret;
        for(j=0;j<12;++j){ret=native_view(sources,&p->fragment_uniforms.blocks[j],false);if(ret)return ret;}
        ret=native_view(sources,&p->fragment_uniforms.defaults,false);if(ret)return ret;
    }
    for(i=0;i<s->resource_count;++i){
        const struct opengpu_native_frame_resource *r=&s->resources[i];
        ret=native_retain(sources,r->gem_handle,!!(r->access&OPENGPU_NATIVE_FRAME_RESOURCE_WRITE));if(ret)return ret;
    }
    return 0;
}
/* The production wrapper obtains verified from the hardware getter, never
 * userspace. CPU composition tests invoke this body with an explicit test
 * profile; they do not exercise or replace the unavailable production probe. */
static int protected_tessellation_submit_verified(struct drm_device *drm,
    struct opengpu_protected_tessellation_request *request,struct drm_file *file,
    const struct opengpu_protected_tessellation_profile *verified)
{
    struct opengpu_protected_tessellation_request captured;
    struct opengpu_protected_tessellation_profile profile;
    struct opengpu_device *gpu=dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file=file->driver_priv;
    struct opengpu_render_context *context;
    struct opengpu_protected_tessellation_source source={0};
    struct native_sources sources={.file=file};
    struct opengpu_native_job_copy copy={native_copy,native_resolve,&sources};
    struct opengpu_sched_dep *deps=NULL;
    struct opengpu_sched_job *job=NULL;
    struct drm_syncobj *out_sync=NULL;
    struct drm_exec exec;
    void *records[7]={0};
    u64 plans_bytes,state_plans_bytes,resources_bytes,max_refs,seqno=0;
    u32 i;int ret=-EINVAL;
    if(!request || !verified)return -EINVAL;
    profile=*verified;
    if(!opengpu_protected_tessellation_profile_valid(&profile))return -EOPNOTSUPP;
    captured=*request;
    const struct opengpu_protected_tessellation_request *args=&captured;
    if(args->version!=1 || args->flags || args->pad || args->reserved[0] || args->reserved[1] ||
        args->plan_stride!=sizeof(struct opengpu_protected_tessellation_plan) ||
        args->state_plan_stride!=sizeof(struct opengpu_protected_tessellation_state_plan) ||
        args->resource_stride!=sizeof(struct opengpu_native_frame_resource) ||
        !args->job || !args->frame || !args->states || !args->states_bytes || (args->states_bytes&511u) ||
        !args->state_plan_count || !args->state_plans || !args->resources || !args->resource_count || args->resource_count>27 ||
        (args->plan_count?(!args->plans || !args->sequences || !args->sequences_bytes):(args->plans || args->sequences || args->sequences_bytes)) ||
        args->sequences_bytes>(1ULL<<32) || args->states_bytes>(1ULL<<32) ||
        args->sequences_bytes>(u64)(~(size_t)0) || args->states_bytes>(u64)(~(size_t)0))return -EINVAL;
    const u64 pointers[]={args->job,args->frame,args->sequences,args->states,args->plans,args->state_plans,args->resources};
    for(i=0;i<7;++i)if(!native_pointer_valid(pointers[i]))return -EINVAL;
    plans_bytes=(u64)args->plan_count*sizeof(struct opengpu_protected_tessellation_plan);
    state_plans_bytes=(u64)args->state_plan_count*sizeof(struct opengpu_protected_tessellation_state_plan);
    resources_bytes=(u64)args->resource_count*sizeof(struct opengpu_native_frame_resource);
    max_refs=(u64)args->plan_count*29u+(u64)args->state_plan_count*14u+args->resource_count;
    if(plans_bytes>(u64)(~(size_t)0) || state_plans_bytes>(u64)(~(size_t)0) || max_refs>0xffffffffu ||
       max_refs*sizeof(*sources.refs)>(u64)(~(size_t)0) || max_refs*sizeof(*deps)>(u64)(~(size_t)0))return -ERANGE;
#define PT_COPY(index,pointer,bytes) do { \
    if(bytes){records[index]=vmemdup_user(u64_to_user_ptr(pointer),(size_t)(bytes)); \
        if(IS_ERR(records[index])){ret=PTR_ERR(records[index]);records[index]=NULL;goto out;}} \
} while(0)
    PT_COPY(0,args->job,512u);PT_COPY(1,args->frame,2048u);
    PT_COPY(2,args->sequences,args->sequences_bytes);PT_COPY(3,args->states,args->states_bytes);
    PT_COPY(4,args->plans,plans_bytes);PT_COPY(5,args->state_plans,state_plans_bytes);PT_COPY(6,args->resources,resources_bytes);
#undef PT_COPY
    source=(struct opengpu_protected_tessellation_source){.job=records[0],.frame=records[1],.sequences=records[2],.states=records[3],
        .sequences_bytes=args->sequences_bytes,.states_bytes=args->states_bytes,.plans=records[4],.plan_count=args->plan_count,
        .state_plans=records[5],.state_plan_count=args->state_plan_count,.resources=records[6],.resource_count=args->resource_count,
        .context=args->context_id,.profile=profile};
    source.pass=opengpu_native_advanced_blend_word(records[0],OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_PASS_WORD);
    source.generation=opengpu_native_advanced_blend_word(records[0],OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_GENERATION_WORD);
    sources.capacity=max_refs;sources.refs=kvzalloc((size_t)max_refs*sizeof(*sources.refs),GFP_KERNEL);
    deps=kvzalloc((size_t)max_refs*sizeof(*deps),GFP_KERNEL);
    if(!sources.refs || !deps){ret=-ENOMEM;goto out;}
    if(args->in_syncobj){
        struct dma_fence *input=NULL;
        ret=drm_syncobj_find_fence(file,args->in_syncobj,0,0,&input);if(ret)goto out;
        ret=opengpu_hw_fence_wait(gpu,input,OPENGPU_DRAW_WAIT_MS+100,true);
        dma_fence_put(input);if(ret)goto out;
    }
    mutex_lock(&render_file->lock);
    context=opengpu_context_lookup(render_file,args->context_id);
    if(!context || !context->vm.enabled){ret=-EINVAL;goto out_file;}
    ret=native_collect_protected_tessellation(&sources,&source);if(ret)goto out_file;
    if(args->out_syncobj){out_sync=drm_syncobj_find(file,args->out_syncobj);if(!out_sync){ret=-ENOENT;goto out_file;}}
    for(i=0;i<sources.count;++i)deps[i]=(struct opengpu_sched_dep){sources.refs[i].object,
        sources.refs[i].writable?DMA_RESV_USAGE_READ:DMA_RESV_USAGE_WRITE,
        sources.refs[i].writable?DMA_RESV_USAGE_WRITE:DMA_RESV_USAGE_READ};
    drm_exec_init(&exec,DRM_EXEC_INTERRUPTIBLE_WAIT|DRM_EXEC_IGNORE_DUPLICATES,0);
    drm_exec_until_all_locked(&exec){
        for(i=0,ret=0;!ret && i<sources.count;++i)ret=drm_exec_prepare_obj(&exec,sources.refs[i].object,1);
        drm_exec_retry_on_contention(&exec);if(ret)goto out_exec;
    }
    /* No submit_lock while producer fences can require old-owner retirement. */
    for(i=0;i<sources.count;++i){
        struct dma_resv_iter cursor;struct dma_fence *producer;
        dma_resv_for_each_fence(&cursor,sources.refs[i].object->resv,deps[i].wait_usage,producer){
            ret=opengpu_hw_fence_wait(gpu,producer,OPENGPU_DRAW_WAIT_MS+100,true);if(ret)goto out_exec;
        }
    }
    sources.producers_complete=true;
    job=kzalloc(sizeof(*job),GFP_KERNEL);if(!job){ret=-ENOMEM;goto out_exec;}
    job->gpu=gpu;job->context=context;job->vm=&context->vm;job->type=OPENGPU_SCHED_PROTECTED_TESSELLATION;
    job->native_owner=opengpu_private_snapshot_create(gpu,context);
    if(IS_ERR(job->native_owner)){ret=PTR_ERR(job->native_owner);job->native_owner=NULL;goto out_exec;}
    ret=opengpu_protected_tessellation_build(job->native_owner,&source,&copy,&job->native_job_segment);
    if(!ret)ret=opengpu_admission_native_lock(gpu);
    if(!ret){
        ret=opengpu_private_snapshot_prepare(job->native_owner);
        if(!ret)ret=opengpu_private_snapshot_publish(job->native_owner);
        if(!ret)ret=opengpu_sched_job_submit_locked(render_file,file,context,job,0,out_sync,deps,sources.count,&seqno);
        opengpu_admission_unlock(gpu);
    }
    if(!ret){job=NULL;request->fence_seqno=seqno;}
out_exec:
    if(job){
        int revoke=job->native_owner?opengpu_private_snapshot_retire(job->native_owner):0;
        if(revoke){
            WRITE_ONCE(gpu->hw.wedged,true);
            job->native_quarantine_refs=sources.refs;job->native_quarantine_refcount=sources.count;
            sources.refs=NULL;sources.count=0;ret=revoke;
            dev_err(gpu->dev,"retaining protected tessellation originals after owner revocation failure: %d\n",revoke);
        }else kfree(job);
    }
    drm_exec_fini(&exec);
out_file:
    if(out_sync)drm_syncobj_put(out_sync);
    mutex_unlock(&render_file->lock);
out:
    for(i=0;i<sources.count;++i)drm_gem_object_put(sources.refs[i].object);
    kvfree(deps);kvfree(sources.refs);for(i=0;i<7;++i)kvfree(records[i]);return ret;
}
int opengpu_protected_tessellation_ioctl(struct drm_device *drm,void *data,struct drm_file *file)
{
    struct opengpu_protected_tessellation_profile profile;
    int ret;
    if(!drm || !file || !data)return -EINVAL;
    ret=opengpu_hw_protected_tessellation_profile(dev_get_drvdata(drm->dev),&profile);
    if(ret)return ret; /* Before request dereference, lookup, usercopy or allocation. */
    return protected_tessellation_submit_verified(drm,data,file,&profile);
}

enum joint_record {
    JOINT_JOB, JOINT_FRAME, JOINT_SEQUENCES, JOINT_STATES, JOINT_DRAWS,
    JOINT_DRAW_PLANS, JOINT_INVOCATIONS, JOINT_RANGES, JOINT_PLANS, JOINT_STATE_PLANS,
    JOINT_RESOURCES, JOINT_RECORD_COUNT,
};

static int joint_capture_request(const struct drm_opengpu_submit_tessellation_joint *args,
    const struct opengpu_protected_tessellation_joint_profile *profile,
    void **records, struct opengpu_protected_tessellation_joint_source *source)
{
    const u64 pointers[JOINT_RECORD_COUNT] = {
        args->job, args->frame, args->sequences, args->states, args->draws,
        args->draw_plans, args->invocations, args->ranges, args->plans, args->state_plans,
        args->resources,
    };
    const u64 sizes[JOINT_RECORD_COUNT] = {
        OPENGPU_TESS_JOINT_BYTES, OPENGPU_NATIVE_BINNED_FRAME_BYTES,
        args->sequences_bytes, (u64)args->state_count * OPENGPU_TYPED_FRAGMENT_STATE_BYTES,
        (u64)args->draw_count * OPENGPU_NATIVE_BINNED_DRAW_BYTES,
        (u64)args->draw_count * sizeof(*source->draw_plans),
        (u64)args->invocation_count * sizeof(*source->invocations),
        (u64)args->range_count * sizeof(*source->ranges),
        (u64)args->range_count * sizeof(*source->plans),
        (u64)args->state_count * sizeof(*source->state_plans),
        (u64)args->resource_count * sizeof(*source->resources),
    };
    u32 i, stride;

    if (args->version != OPENGPU_TESSELLATION_JOINT_SUBMIT_VERSION ||
        args->flags || args->reserved[0] || args->reserved[1] ||
        !args->resource_count || args->resource_count > 27 ||
        args->invocation_count > args->range_count || (args->range_count && !args->state_count) ||
        (!args->range_count && (args->draw_count || args->invocation_count || args->state_count)) ||
        sizes[JOINT_SEQUENCES] > (1ULL << 32) || sizes[JOINT_STATES] > (1ULL << 32) ||
        sizes[JOINT_DRAWS] > (1ULL << 32))
        return -EINVAL;
    for (i = 0; i < JOINT_RECORD_COUNT; i++) {
        if (!!pointers[i] != !!sizes[i] || !native_pointer_valid(pointers[i]))
            return -EINVAL;
        if (sizes[i] > (size_t)-1 || sizes[i] > (1ULL << 32))
            return -ERANGE;
    }
    /* Match the small canonical root before copying variable-length arrays. */
    records[JOINT_JOB] = vmemdup_user(u64_to_user_ptr(args->job), OPENGPU_TESS_JOINT_BYTES);
    if (IS_ERR(records[JOINT_JOB])) {
        int ret = PTR_ERR(records[JOINT_JOB]);

        records[JOINT_JOB] = NULL;
        return ret;
    }
    if (!opengpu_protected_tessellation_joint_job_canonical(records[JOINT_JOB],
            OPENGPU_TESS_JOINT_BYTES, false))
        return -EINVAL;
#define JOINT_WORD(field) opengpu_native_advanced_blend_word(records[JOINT_JOB], OPENGPU_TESS_JOINT_##field##_WORD)
    stride = JOINT_WORD(SEQUENCE_STRIDE);
    if (JOINT_WORD(IDENTITY_CONTEXT) != args->context_id ||
        JOINT_WORD(SEQUENCE_COUNT) != args->range_count ||
        JOINT_WORD(DIRECTORY_DRAWCOUNT) != args->draw_count ||
        JOINT_WORD(DIRECTORY_INVOCATIONCOUNT) != args->invocation_count ||
        sizes[JOINT_SEQUENCES] != (args->range_count ?
            (u64)(args->range_count - 1) * stride + OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES : 0))
        return -EINVAL;

    for (i = JOINT_FRAME; i < JOINT_RECORD_COUNT; i++) {
        if (!sizes[i])
            continue;
        records[i] = vmemdup_user(u64_to_user_ptr(pointers[i]), (size_t)sizes[i]);
        if (IS_ERR(records[i])) {
            int ret = PTR_ERR(records[i]);

            records[i] = NULL;
            return ret;
        }
    }
    *source = (struct opengpu_protected_tessellation_joint_source) {
        .job = records[JOINT_JOB], .frame = records[JOINT_FRAME],
        .sequences = records[JOINT_SEQUENCES], .states = records[JOINT_STATES],
        .draws = records[JOINT_DRAWS], .sequences_bytes = sizes[JOINT_SEQUENCES],
        .states_bytes = sizes[JOINT_STATES], .draws_bytes = sizes[JOINT_DRAWS],
        .draw_plans = records[JOINT_DRAW_PLANS], .invocations = records[JOINT_INVOCATIONS],
        .ranges = records[JOINT_RANGES], .plans = records[JOINT_PLANS],
        .state_plans = records[JOINT_STATE_PLANS], .resources = records[JOINT_RESOURCES],
        .draw_count = args->draw_count, .draw_plan_count = args->draw_count,
        .invocation_count = args->invocation_count, .range_count = args->range_count,
        .plan_count = args->range_count, .state_plan_count = args->state_count,
        .resource_count = args->resource_count, .context = args->context_id,
        .pass = JOINT_WORD(IDENTITY_PASS), .generation = JOINT_WORD(IDENTITY_GENERATION),
        .profile = *profile,
    };
#undef JOINT_WORD
    return 0;
}

static int joint_retain_stage(struct native_sources *sources,
    const struct drm_opengpu_native_program_view *code,
    const struct drm_opengpu_native_uniform_stage *uniforms)
{
    const struct drm_opengpu_compute_uniform_view view = {
        .handle = code->handle, .offset = code->offset, .bytes = code->bytes,
    };
    int ret = native_view(sources, &view, false);
    u32 i;

    for (i = 0; !ret && i < OPENGPU_NATIVE_UNIFORM_BLOCKS; i++)
        ret = native_view(sources, &uniforms->blocks[i], false);
    return ret ? ret : native_view(sources, &uniforms->defaults, false);
}

static int joint_collect_sources(struct native_sources *sources,
    const struct opengpu_protected_tessellation_joint_source *s)
{
    const struct opengpu_native_job_source draws_and_frame = {
        .plans = s->draw_plans, .plan_count = s->draw_plan_count,
        .resources = s->resources, .resource_count = s->resource_count,
    };
    int ret = native_collect(sources, &draws_and_frame);
    u32 i;

    for (i = 0; !ret && i < s->plan_count; i++) {
        const struct opengpu_protected_tessellation_joint_plan *p = &s->plans[i];

        ret = joint_retain_stage(sources, &p->control_program, &p->control_uniforms);
        if (!ret)
            ret = joint_retain_stage(sources, &p->evaluation_program, &p->evaluation_uniforms);
    }
    for (i = 0; !ret && i < s->state_plan_count; i++) {
        const struct opengpu_protected_tessellation_state_plan *p = &s->state_plans[i];

        ret = joint_retain_stage(sources, &p->fragment_program, &p->fragment_uniforms);
    }
    return ret;
}

struct native_joint_job {
    struct opengpu_sched_job job;
    struct opengpu_tessellation_joint_completion report;
};

int opengpu_protected_tessellation_joint_ioctl(struct drm_device *drm, void *data, struct drm_file *file)
{
    struct drm_opengpu_submit_tessellation_joint *args = data, captured;
    struct opengpu_protected_tessellation_joint_profile profile = {0};
    struct opengpu_protected_tessellation_joint_source source;
    struct native_sources sources = {.file = file};
    const struct opengpu_native_job_copy copy = {native_copy, native_resolve, &sources};
    void *records[JOINT_RECORD_COUNT] = {0};
    struct opengpu_sched_dep *deps = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct opengpu_sched_job *job = NULL;
    struct native_joint_job *joint;
    struct opengpu_device *gpu;
    struct opengpu_file *render_file;
    struct opengpu_render_context *context;
    struct drm_exec exec;
    const u32 stage_refs = OPENGPU_NATIVE_UNIFORM_BLOCKS + 2;
    u64 max_refs, seqno = 0;
    u32 i;
    int ret;

    if (!drm || !file || !data)
        return -EINVAL;
    gpu = dev_get_drvdata(drm->dev);
    ret = opengpu_hw_protected_tessellation_joint_profile(gpu, &profile);
    if (ret)
        return ret;
    render_file = file->driver_priv;
    if (!render_file)
        return -EINVAL;
    captured = *args;
    max_refs = (u64)captured.draw_count * (2 * stage_refs + OPENGPU_NATIVE_VERTEX_BINDINGS + 3) +
        (u64)captured.range_count * 2 * stage_refs + (u64)captured.state_count * stage_refs +
        captured.resource_count;
    if (max_refs > (u32)-1 || max_refs > (size_t)-1 / sizeof(*sources.refs) ||
        max_refs > (size_t)-1 / sizeof(*deps))
        return -ERANGE;
    ret = joint_capture_request(&captured, &profile, records, &source);
    if (ret)
        goto out;
    sources.capacity = (u32)max_refs;
    sources.refs = kvzalloc((size_t)max_refs * sizeof(*sources.refs), GFP_KERNEL);
    deps = kvzalloc((size_t)max_refs * sizeof(*deps), GFP_KERNEL);
    if (!sources.refs || !deps) {
        ret = -ENOMEM;
        goto out;
    }
    if (captured.in_syncobj) {
        struct dma_fence *input = NULL;

        ret = drm_syncobj_find_fence(file, captured.in_syncobj, 0, 0, &input);
        if (ret)
            goto out;
        ret = opengpu_hw_fence_wait(gpu, input, OPENGPU_DRAW_WAIT_MS + 100, true);
        dma_fence_put(input);
        if (ret)
            goto out;
    }

    mutex_lock(&render_file->lock);
    context = opengpu_context_lookup(render_file, captured.context_id);
    if (!context || !context->vm.enabled) {
        ret = -EINVAL;
        goto out_file;
    }
    ret = joint_collect_sources(&sources, &source);
    if (ret)
        goto out_file;
    if (captured.out_syncobj) {
        out_sync = drm_syncobj_find(file, captured.out_syncobj);
        if (!out_sync) {
            ret = -ENOENT;
            goto out_file;
        }
    }
    for (i = 0; i < sources.count; i++)
        deps[i] = (struct opengpu_sched_dep) {
            sources.refs[i].object,
            sources.refs[i].writable ? DMA_RESV_USAGE_READ : DMA_RESV_USAGE_WRITE,
            sources.refs[i].writable ? DMA_RESV_USAGE_WRITE : DMA_RESV_USAGE_READ,
        };
    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT | DRM_EXEC_IGNORE_DUPLICATES, 0);
    drm_exec_until_all_locked(&exec) {
        for (i = 0, ret = 0; !ret && i < sources.count; i++)
            ret = drm_exec_prepare_obj(&exec, sources.refs[i].object, 1);
        drm_exec_retry_on_contention(&exec);
        if (ret)
            goto out_exec;
    }
    for (i = 0; i < sources.count; i++) {
        struct dma_resv_iter cursor;
        struct dma_fence *producer;

        dma_resv_for_each_fence(&cursor, sources.refs[i].object->resv, deps[i].wait_usage, producer) {
            ret = opengpu_hw_fence_wait(gpu, producer, OPENGPU_DRAW_WAIT_MS + 100, true);
            if (ret)
                goto out_exec;
        }
    }
    sources.producers_complete = true;
    joint = kzalloc(sizeof(*joint), GFP_KERNEL);
    if (!joint) {
        ret = -ENOMEM;
        goto out_exec;
    }
    job = &joint->job;
    job->joint_report = &joint->report;
    job->joint_report_kind = OPENGPU_TESSELLATION_JOINT_REPORT_GNZ2;
    job->gpu = gpu;
    job->context = context;
    job->vm = &context->vm;
    job->type = OPENGPU_SCHED_PROTECTED_TESSELLATION;
    job->native_owner = opengpu_private_snapshot_create(gpu, context);
    if (IS_ERR(job->native_owner)) {
        ret = PTR_ERR(job->native_owner);
        job->native_owner = NULL;
        goto out_exec;
    }
    ret = opengpu_protected_tessellation_joint_build(job->native_owner, &source, &copy,
        &job->native_job_segment);
    if (!ret)
        ret = opengpu_admission_native_lock(gpu);
    if (!ret) {
        ret = opengpu_protected_tessellation_joint_prepare(job->native_owner, job->native_job_segment);
        if (!ret)
            ret = opengpu_private_snapshot_publish(job->native_owner);
        if (!ret)
            ret = opengpu_sched_job_submit_locked(render_file, file, context, job, 0, out_sync,
                deps, sources.count, &seqno);
        opengpu_admission_unlock(gpu);
    }
    if (!ret) {
        job = NULL;
        args->fence_seqno = seqno;
    }
out_exec:
    if (job) {
        int revoke = job->native_owner ? opengpu_private_snapshot_retire(job->native_owner) : 0;

        if (revoke) {
            WRITE_ONCE(gpu->hw.wedged, true);
            job->native_quarantine_refs = sources.refs;
            job->native_quarantine_refcount = sources.count;
            sources.refs = NULL;
            sources.count = 0;
            ret = revoke;
            dev_err(gpu->dev, "retaining joint tessellation originals after revocation failure: %d\n", revoke);
        } else {
            kfree(job);
        }
    }
    drm_exec_fini(&exec);
out_file:
    if (out_sync)
        drm_syncobj_put(out_sync);
    mutex_unlock(&render_file->lock);
out:
    for (i = 0; i < sources.count; i++)
        drm_gem_object_put(sources.refs[i].object);
    kvfree(deps);
    kvfree(sources.refs);
    for (i = 0; i < JOINT_RECORD_COUNT; i++)
        kvfree(records[i]);
    return ret;
}
