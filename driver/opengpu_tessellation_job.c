// SPDX-License-Identifier: GPL-2.0
/* Scheduler-owned snapshots for an already validated native tessellation job. */
#include <linux/err.h>
#include <linux/slab.h>
#include <linux/string.h>

#include "opengpu_scheduler.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_tessellation_job_validate.h"

/* Draw arrays use a separate, larger private-VM region rather than one 4 MiB
 * command slot. The ordered scheduler revokes these mappings before another
 * job can reuse a slot. */
enum {
    OPENGPU_TESS_JOB_SLOT = 5,
    OPENGPU_TESS_FRAME_SLOT = 6,
    OPENGPU_TESS_DRAWS_SLOT = 7,
};

static void tess_put32(void *cpu, unsigned int word, u32 value)
{
    unsigned char *p = (unsigned char *)cpu + word * 4u;

    p[0] = value;
    p[1] = value >> 8;
    p[2] = value >> 16;
    p[3] = value >> 24;
}

static int tess_snapshot_plan(const struct opengpu_buffer *buffer, u32 slot,
                              struct opengpu_kernarg_va_plan *plan)
{
    int ret;

    u64 window_size = slot == OPENGPU_TESS_DRAWS_SLOT ?
        OPENGPU_TESS_DRAWS_VA_SIZE : OPENGPU_COMMAND_VA_STRIDE;

    if (!buffer->cpu || !buffer->size || buffer->size > window_size ||
        (buffer->dma & 63u))
        return -EINVAL;
    ret = slot == OPENGPU_TESS_DRAWS_SLOT ?
        opengpu_tessellation_draw_va_plan(buffer->dma, buffer->size,
                                          OPENGPU_MMU_PAGE_SIZE, plan) :
        opengpu_command_va_plan(buffer->dma, buffer->size, slot,
                                OPENGPU_MMU_PAGE_SIZE, plan);
    if (ret)
        return ret;
    if (plan->va > (1ULL << 32) - buffer->size ||
        plan->pa_page > (1ULL << 32) - plan->span)
        return -ERANGE;
    return 0;
}

/* Only use before enqueue or after the scheduler has revoked all mappings. */
void opengpu_tessellation_job_release(struct opengpu_sched_job *job)
{
    opengpu_buffer_free(job->gpu, &job->tess_draws);
    opengpu_buffer_free(job->gpu, &job->tess_frame);
    opengpu_buffer_free(job->gpu, &job->tess_job);
}

/* The caller holds the context lock and every dependency reservation. Check the
 * copied draw records, not caller memory: a 64-byte DMA read can reach any
 * part of the declared allocation. A vertex binding's VA and mapping remain
 * stable until this job's context fence completes. Other resources and aliases
 * still require independent validation by the trusted producer. */
static int tess_input_resources_check(
    struct opengpu_render_context *context, const void *draws,
    const struct opengpu_tessellation_job_view *view,
    const struct opengpu_sched_dep *deps, u32 dep_count)
{
    u32 i, slot, d;

    for (i = 0; i < view->draw_count; ++i) {
        const unsigned char *draw = (const unsigned char *)draws +
                                    (u64)i * view->draw_stride;
        struct opengpu_tessellation_input_view input;
        bool found = false;

        if (!opengpu_tessellation_draw_input_view_precheck(draw,
                OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES, view, i, &input))
            return -EINVAL;
        if (!input.active)
            continue;
        for (slot = 0; slot < OPENGPU_MAX_RESOURCE_BINDINGS && !found; ++slot) {
            const struct opengpu_resource_binding *binding = context->bindings[slot];
            u64 va;

            if (!binding || binding->type != OPENGPU_RESOURCE_VERTEX_BUFFER ||
                !binding->object || !binding->va || !binding->size)
                continue;
            va = binding->va;
            if (va >= OPENGPU_TESSELLATION_ADDRESS_END ||
                binding->size > OPENGPU_TESSELLATION_ADDRESS_END - va ||
                input.allocation_base < va ||
                input.allocation_limit > va + binding->size)
                continue;
            for (d = 0; d < dep_count; ++d) {
                if (deps[d].object == binding->object &&
                    (deps[d].wait_usage == DMA_RESV_USAGE_WRITE ||
                     deps[d].wait_usage == DMA_RESV_USAGE_READ) &&
                    (deps[d].signal_usage == DMA_RESV_USAGE_READ ||
                     deps[d].signal_usage == DMA_RESV_USAGE_WRITE)) {
                    found = true;
                    break;
                }
            }
        }
        if (!found)
            return -EINVAL;
    }
    return 0;
}

static int opengpu_tessellation_job_prepare(
    struct opengpu_sched_job *job, struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    const struct opengpu_sched_dep *deps, u32 dep_count)
{
    struct opengpu_kernarg_va_plan job_plan, frame_plan, draws_plan;
    struct opengpu_tessellation_job_view view;
    u64 draw_size = 0, limit;
    u32 count, stride;
    int ret;

    if (!input || !input->job || !input->frame ||
        input->job_size < OPENGPU_NATIVE_TESSELLATION_JOB_BYTES ||
        input->frame_size < OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES)
        return -EINVAL;
    if (!job->gpu->hw.tessellation_transport || !context->vm.enabled)
        return -EOPNOTSUPP;
    if (input->scope.flags & ~(GPU_UCMD_FLAG_RASTER_DISCARD |
                               GPU_UCMD_FLAG_FEEDBACK_CAPTURE))
        return -EINVAL;
    if (!job->gpu->hw.tessellation_feedback_transport &&
        (input->scope.flags || input->scope.program || input->scope.cookie))
        return -EOPNOTSUPP;

    ret = opengpu_buffer_alloc(job->gpu, &job->tess_job,
                               OPENGPU_NATIVE_TESSELLATION_JOB_BYTES);
    if (ret)
        return ret;
    memcpy(job->tess_job.cpu, input->job, OPENGPU_NATIVE_TESSELLATION_JOB_BYTES);
    count = opengpu_tessellation_job_le32((const unsigned char *)job->tess_job.cpu +
        OPENGPU_NATIVE_TESSELLATION_JOB_JOB_DRAWCOUNT_WORD * 4u);
    stride = opengpu_tessellation_job_le32((const unsigned char *)job->tess_job.cpu +
        OPENGPU_NATIVE_TESSELLATION_JOB_JOB_DRAWSTRIDE_WORD * 4u);
    if (count) {
        if (!input->draws || stride < OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES ||
            (stride & 63u) ||
            (u64)(count - 1u) > (OPENGPU_TESS_DRAWS_VA_SIZE -
                OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES) / stride) {
            ret = -EINVAL;
            goto fail;
        }
        draw_size = (u64)(count - 1u) * stride +
                    OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES;
        if (draw_size > input->draws_size) {
            ret = -EINVAL;
            goto fail;
        }
    }
    ret = opengpu_buffer_alloc(job->gpu, &job->tess_frame,
                               OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES);
    if (ret)
        goto fail;
    memcpy(job->tess_frame.cpu, input->frame, OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES);
    if (count) {
        ret = opengpu_buffer_alloc(job->gpu, &job->tess_draws, (size_t)draw_size);
        if (ret)
            goto fail;
        memcpy(job->tess_draws.cpu, input->draws, (size_t)draw_size);
    }
    ret = tess_snapshot_plan(&job->tess_job, OPENGPU_TESS_JOB_SLOT, &job_plan);
    if (!ret)
        ret = tess_snapshot_plan(&job->tess_frame, OPENGPU_TESS_FRAME_SLOT, &frame_plan);
    if (!ret && count)
        ret = tess_snapshot_plan(&job->tess_draws, OPENGPU_TESS_DRAWS_SLOT, &draws_plan);
    if (ret)
        goto fail;

    /* The scheduler chooses private addresses; never publish caller addresses
     * for these copied descriptors. All limits are exclusive. */
    tess_put32(job->tess_job.cpu, OPENGPU_NATIVE_TESSELLATION_JOB_JOB_DRAWBASE_WORD,
               count ? (u32)draws_plan.va : 0);
    limit = count ? draws_plan.va + draw_size : 0;
    tess_put32(job->tess_job.cpu, OPENGPU_NATIVE_TESSELLATION_JOB_JOB_DRAWLIMIT_WORD, (u32)limit);
    tess_put32(job->tess_job.cpu, OPENGPU_NATIVE_TESSELLATION_JOB_JOB_DRAWLIMIT_WORD + 1, (u32)(limit >> 32));
    if (!count)
        tess_put32(job->tess_job.cpu, OPENGPU_NATIVE_TESSELLATION_JOB_JOB_DRAWSTRIDE_WORD, 0);
    tess_put32(job->tess_job.cpu, OPENGPU_NATIVE_TESSELLATION_JOB_JOB_FRAMEBASE_WORD, (u32)frame_plan.va);
    limit = frame_plan.va + OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES;
    tess_put32(job->tess_job.cpu, OPENGPU_NATIVE_TESSELLATION_JOB_JOB_FRAMELIMIT_WORD, (u32)limit);
    tess_put32(job->tess_job.cpu, OPENGPU_NATIVE_TESSELLATION_JOB_JOB_FRAMELIMIT_WORD + 1, (u32)(limit >> 32));
    if (!opengpu_tessellation_snapshots_precheck(
            job->tess_job.cpu, job->tess_job.size, job_plan.va,
            job_plan.va + OPENGPU_NATIVE_TESSELLATION_JOB_BYTES,
            job->tess_frame.cpu, job->tess_frame.size,
            job->tess_draws.cpu, job->tess_draws.size,
            job->gpu->hw.tessellation_engines, &view) ||
        view.context != context->id) {
        ret = -EINVAL;
        goto fail;
    }
    ret = tess_input_resources_check(context, job->tess_draws.cpu, &view,
                                     deps, dep_count);
    if (ret)
        goto fail;
    job->context = context;
    job->vm = &context->vm;
    job->tess_job_va = (u32)job_plan.va;
    job->tess_frame_va = (u32)frame_plan.va;
    job->tess_draws_va = count ? (u32)draws_plan.va : 0;
    job->tess_scope = input->scope;
    job->type = OPENGPU_SCHED_TESSELLATION;
    return 0;
fail:
    return ret;
}

static int tess_map_snapshot(struct opengpu_sched_job *job,
                             const struct opengpu_buffer *buffer,
                             u32 slot, u32 expected_va)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = tess_snapshot_plan(buffer, slot, &plan);
    if (ret)
        return ret;
    if (plan.va != expected_va)
        return -EINVAL;
    ret = opengpu_mmu_vm_map_snapshot(job->gpu, &job->context->vm,
                                      (dma_addr_t)plan.va_page,
                                      (dma_addr_t)plan.pa_page,
                                      (size_t)plan.span,
                                      OPENGPU_MMU_POLICY_UNCACHED);
    if (ret)
        return ret;
    return opengpu_job_track_mapping(job, (dma_addr_t)plan.va, buffer->size);
}

struct dma_fence *opengpu_tessellation_job_run(struct opengpu_sched_job *job)
{
    const struct opengpu_tessellation_descriptors descriptors = {
        .job = &job->tess_job, .frame = &job->tess_frame,
        .draws = job->tess_draws.cpu ? &job->tess_draws : NULL,
    };
    struct dma_fence *fence = NULL;
    int ret;

    if (!job->context || job->vm != &job->context->vm || !job->vm->enabled)
        return ERR_PTR(-EINVAL);
    ret = tess_map_snapshot(job, &job->tess_job, OPENGPU_TESS_JOB_SLOT,
                            job->tess_job_va);
    if (!ret)
        ret = tess_map_snapshot(job, &job->tess_frame, OPENGPU_TESS_FRAME_SLOT,
                                job->tess_frame_va);
    if (!ret && descriptors.draws)
        ret = tess_map_snapshot(job, &job->tess_draws, OPENGPU_TESS_DRAWS_SLOT,
                                job->tess_draws_va);
    if (!ret)
        ret = opengpu_hw_tessellation_async(job->gpu, &descriptors,
                job->tess_job_va,
                (u64)job->tess_job_va + OPENGPU_NATIVE_TESSELLATION_JOB_BYTES,
                NULL, 0, &job->tess_scope, job->vm, &fence);
    if (ret)
        return ERR_PTR(ret);
    return fence ? fence : ERR_PTR(-EIO);
}

/* Called only after a trusted producer has validated every referenced resource
 * and locked the context and all dependency reservations. No UAPI uses this
 * until those requirements can be enforced for userspace submissions. */
int opengpu_tessellation_sched_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno)
{
    struct opengpu_sched_job *job;
    u32 i;
    int ret;

    if (!render_file || !file || !context || !context->gpu ||
        render_file->gpu != context->gpu || (dep_count && !deps))
        return -EINVAL;
    lockdep_assert_held(&render_file->lock);
    for (i = 0; i < dep_count; ++i) {
        if (!deps[i].object || !deps[i].object->resv)
            return -EINVAL;
        dma_resv_assert_held(deps[i].object->resv);
    }
    job = kzalloc(sizeof(*job), GFP_KERNEL);
    if (!job)
        return -ENOMEM;
    job->gpu = context->gpu;
    ret = opengpu_tessellation_job_prepare(job, context, input, deps, dep_count);
    if (!ret)
        ret = opengpu_sched_job_submit(render_file, file, context, job,
                in_syncobj, out_sync, deps, dep_count, fence_seqno);
    if (ret) {
        opengpu_tessellation_job_release(job);
        kfree(job);
    }
    return ret;
}
