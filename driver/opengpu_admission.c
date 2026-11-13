// SPDX-License-Identifier: GPL-2.0
/* Execution order and ownership retirement are distinct across entities.
 * Native successors may execute after earlier hardware completion while its
 * owner remains retained. Ordinary writers wait for complete lease retirement. */
#include <linux/delay.h>
#include <linux/dma-fence-chain.h>
#include <linux/jiffies.h>
#include <linux/slab.h>
#include "opengpu_scheduler.h"
#include "opengpu_admission.h"
struct admission_fence {struct dma_fence base;spinlock_t lock;};
static const char *admission_name(struct dma_fence *f){(void)f;return "opengpu-retirement";}
static const struct dma_fence_ops admission_ops={.get_driver_name=admission_name,
    /* No auxiliary resources: default dma_fence_free releases this base-first
     * allocation after its RCU readers, including fence-chain walkers. */
    .get_timeline_name=admission_name};
void opengpu_admission_init(struct opengpu_device *gpu)
{
    mutex_init(&gpu->compute.admission_lock);
    atomic64_set(&gpu->compute.ordinary_pending,0);
    gpu->compute.admission_context=dma_fence_context_alloc(1);
    gpu->compute.admission_seqno=0;gpu->compute.admission_tail=NULL;
    gpu->compute.execution_tail=NULL;
}
void opengpu_admission_fini(struct opengpu_device *gpu)
{dma_fence_put(gpu->compute.admission_tail);gpu->compute.admission_tail=NULL;
 dma_fence_put(gpu->compute.execution_tail);gpu->compute.execution_tail=NULL;}
int opengpu_admission_native_lock(struct opengpu_device *gpu)
{
    unsigned long deadline=jiffies+msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS+100);
    mutex_lock(&gpu->compute.admission_lock);
    while(atomic64_read(&gpu->compute.ordinary_pending)){
        if(READ_ONCE(gpu->hw.wedged)){mutex_unlock(&gpu->compute.admission_lock);return -EIO;}
        opengpu_hw_progress_tick(gpu);
        if(time_after_eq(jiffies,deadline)){mutex_unlock(&gpu->compute.admission_lock);return -ETIMEDOUT;}
        usleep_range(100,200);
    }
    if(READ_ONCE(gpu->hw.wedged)){mutex_unlock(&gpu->compute.admission_lock);return -EIO;}
    return 0;
}
void opengpu_admission_unlock(struct opengpu_device *gpu){mutex_unlock(&gpu->compute.admission_lock);}
int opengpu_admission_job_prepare(struct opengpu_sched_job *job)
{
    struct opengpu_device *gpu=job->gpu;
    struct admission_fence *f=kzalloc(sizeof(*f),GFP_KERNEL);
    struct dma_fence_chain *chain;
    int ret;
    if(!f)return -ENOMEM;
    spin_lock_init(&f->lock);
    dma_fence_init(&f->base,&admission_ops,&f->lock,gpu->compute.admission_context,++gpu->compute.admission_seqno);
    job->retirement_fence=&f->base;
    chain=dma_fence_chain_alloc();
    if(!chain){opengpu_admission_job_abort(job);return -ENOMEM;}
    job->execution_chain=dma_fence_chain_alloc();
    if(!job->execution_chain){dma_fence_chain_free(chain);opengpu_admission_job_abort(job);return -ENOMEM;}
    /* Cancellation may retire this job before its predecessor. AND both
     * fences so that an early free cannot sever older ownership from the
     * dependency chain observed by another entity. init consumes both refs. */
    dma_fence_chain_init(chain,dma_fence_get(gpu->compute.admission_tail),
        dma_fence_get(job->retirement_fence),gpu->compute.admission_seqno);
    job->admission_chain=&chain->base;
    /* DRM consumes this reference on both success and failure. Native jobs
     * need older execution; ordinary jobs need older complete retirement. */
    struct dma_fence *previous=job->type==OPENGPU_SCHED_NATIVE_BINNED?
        gpu->compute.execution_tail:gpu->compute.admission_tail;
    ret=previous?drm_sched_job_add_dependency(&job->base,dma_fence_get(previous)):0;
    if(ret)opengpu_admission_job_abort(job);
    return ret;
}
void opengpu_admission_job_abort(struct opengpu_sched_job *job)
{dma_fence_put(job->admission_chain);job->admission_chain=NULL;
 dma_fence_put(job->retirement_fence);job->retirement_fence=NULL;
 if(job->execution_chain){
     if(job->admission_armed)dma_fence_put(&job->execution_chain->base);
     else dma_fence_chain_free(job->execution_chain);
     job->execution_chain=NULL;
 }job->admission_armed=false;}
void opengpu_admission_job_armed(struct opengpu_sched_job *job)
{
    struct opengpu_device *gpu=job->gpu;
    /* drm_sched_job_arm has initialized finished. AND the full older chain:
     * cancellation of this job must never erase older unfinished execution. */
    dma_fence_chain_init(job->execution_chain,dma_fence_get(gpu->compute.execution_tail),
        dma_fence_get(&job->base.s_fence->finished),gpu->compute.admission_seqno);
    job->admission_armed=true;
    dma_fence_put(gpu->compute.execution_tail);
    gpu->compute.execution_tail=dma_fence_get(&job->execution_chain->base);
    if(job->type!=OPENGPU_SCHED_NATIVE_BINNED){atomic64_inc(&gpu->compute.ordinary_pending);job->ordinary_pending=true;}
    dma_fence_put(gpu->compute.admission_tail);
    gpu->compute.admission_tail=dma_fence_get(job->admission_chain);
}
void opengpu_admission_job_retired(struct opengpu_sched_job *job)
{
    /* Caller has revoked the complete owner and released all original GEMs
     * and context refs. Quarantine paths must never call this function. */
    if(job->ordinary_pending){atomic64_dec(&job->gpu->compute.ordinary_pending);job->ordinary_pending=false;}
    dma_fence_signal(job->retirement_fence);
    opengpu_admission_job_abort(job);
}
