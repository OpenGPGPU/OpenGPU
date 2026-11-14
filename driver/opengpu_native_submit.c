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
#include "opengpu_admission.h"

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
        .advanced_blend=gpu->hw.native_advanced_blend_transport};
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
