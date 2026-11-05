// SPDX-License-Identifier: GPL-2.0
/* Scheduler-owned snapshots for an already validated native tessellation job. */
#include <linux/err.h>
#include <linux/slab.h>
#include <linux/string.h>

#include "opengpu_scheduler.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_tessellation_job_validate.h"

#if OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD != \
    OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_COUNT_WORD || \
    OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD != \
    OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_COUNT_WORD || \
    OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD != \
    OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD || \
    OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD != \
    OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD
#error "tessellation count or state-handle word differs between domains"
#endif

/* Draw arrays use a separate, larger private-VM region rather than one 4 MiB
 * command slot. The ordered scheduler revokes these mappings before another
 * job can reuse a slot. */
enum {
    OPENGPU_TESS_JOB_SLOT = 5,
    OPENGPU_TESS_FRAME_SLOT = 6,
    OPENGPU_TESS_DRAWS_SLOT = 7,
    OPENGPU_TESS_STATES_SLOT = 8,
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

    u64 window_size = slot == OPENGPU_TESS_DRAWS_SLOT ? OPENGPU_TESS_DRAWS_VA_SIZE :
        slot == OPENGPU_TESS_STATES_SLOT ? OPENGPU_TESS_STATES_VA_SIZE : OPENGPU_COMMAND_VA_STRIDE;

    if (!buffer->cpu || !buffer->size || buffer->size > window_size ||
        (buffer->dma & 63u))
        return -EINVAL;
    ret = slot == OPENGPU_TESS_DRAWS_SLOT ?
        opengpu_tessellation_draw_va_plan(buffer->dma, buffer->size,
                                          OPENGPU_MMU_PAGE_SIZE, plan) :
        slot == OPENGPU_TESS_STATES_SLOT ?
        opengpu_tessellation_state_va_plan(buffer->dma, buffer->size,
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
    opengpu_buffer_free(job->gpu, &job->tess_states);
    opengpu_buffer_free(job->gpu, &job->tess_draws);
    opengpu_buffer_free(job->gpu, &job->tess_frame);
    opengpu_buffer_free(job->gpu, &job->tess_job);
}

/* All ranges are exclusive and include the complete 64-byte bus lines. */
#define TESS_MAX_FRAME_SPANS 19u
struct tess_frame_span {
    u64 base, end;
    bool write, physical;
};

struct tess_owned_span {
    const struct opengpu_resource_binding *binding;
    u64 object_base, object_end, dma_base, dma_end;
    bool write;
};

static u32 tess_word(const unsigned char *bytes, u32 word)
{
    return opengpu_tessellation_job_le32(bytes + 4u * word);
}

static int tess_add_frame_span(const unsigned char *frame, u32 base_word,
    u32 limit_word, bool write, bool physical, bool required,
    struct tess_frame_span *spans, u32 *count)
{
    u64 base = tess_word(frame, base_word);
    u32 high = tess_word(frame, limit_word + 1u);
    u64 end = tess_word(frame, limit_word) | ((u64)high << 32);

    if (high > 1u || end > OPENGPU_TESSELLATION_ADDRESS_END || end < base)
        return -EINVAL;
    if (base == end)
        return required ? -EINVAL : 0;
    if (*count >= TESS_MAX_FRAME_SPANS)
        return -EINVAL;
    spans[*count] = (struct tess_frame_span) {
        .base = base & ~63ULL, .end = (end + 63u) & ~63ULL,
        .write = write, .physical = physical,
    };
    ++*count;
    return 0;
}

/* Enumerate the allocations touched by the native frame owner. Image views
 * use the discovered virtual/physical mode; binning and scratch use the
 * context VM. The state arena has already been replaced by a private copy.
 * Full image allocations cover line reads and stores. */
static int tess_frame_spans(const unsigned char *frame, u32 engines,
    bool virtual_images, struct tess_frame_span *spans, u32 *count)
{
    static const u32 bases[] = { OPENGPU_TESS_FRAME_ARENA_BASE,
        OPENGPU_TESS_FRAME_POOL_BASE, OPENGPU_TESS_FRAME_DIRECTORY_BASE };
    static const u32 limits[] = { OPENGPU_TESS_FRAME_ARENA_LIMIT,
        OPENGPU_TESS_FRAME_POOL_LIMIT, OPENGPU_TESS_FRAME_DIRECTORY_LIMIT };
#define RESOLVE_BASE(i, b, l, e) b,
#define RESOLVE_LIMIT(i, b, l, e) l,
#define RESOLVE_ENABLE(i, b, l, e) e,
    static const u32 resolve_bases[] = { OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(RESOLVE_BASE) };
    static const u32 resolve_limits[] = { OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(RESOLVE_LIMIT) };
    static const u32 resolve_enables[] = { OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(RESOLVE_ENABLE) };
#undef RESOLVE_BASE
#undef RESOLVE_LIMIT
#undef RESOLVE_ENABLE
    u32 i;
    int ret;

    if (!frame || !spans || !count || !engines || engines > 4u)
        return -EINVAL;
    *count = 0;
    for (i = 0; i < 3; ++i) {
        ret = tess_add_frame_span(frame, bases[i], limits[i], true, false,
                                  false, spans, count);
        if (ret) return ret;
    }
    for (i = 0; i < engines; ++i) {
        u32 base = OPENGPU_TESS_FRAME_SCRATCH_BASE +
                   i * OPENGPU_TESS_FRAME_SCRATCH_STRIDE;
        ret = tess_add_frame_span(frame, base, base + 1u, true, false,
                                  false, spans, count);
        if (ret) return ret;
    }
    for (i = 0; i < 6; ++i) {
        u32 enabled_word = i < 4 ? OPENGPU_TESS_FRAME_COLOR_ENABLED +
            i * OPENGPU_TESS_FRAME_COLOR_STRIDE : i == 4 ?
            OPENGPU_TESS_FRAME_DEPTH_FORMAT : OPENGPU_TESS_FRAME_STENCIL_FORMAT;
        u32 load_word = i < 4 ? OPENGPU_TESS_FRAME_COLOR_LOAD +
            i * OPENGPU_TESS_FRAME_COLOR_STRIDE : i == 4 ?
            OPENGPU_TESS_FRAME_DEPTH_LOAD : OPENGPU_TESS_FRAME_STENCIL_LOAD;
        bool enabled = tess_word(frame, enabled_word) != 0;
        bool store = tess_word(frame, OPENGPU_TESS_FRAME_VIEW_STORE +
                              i * OPENGPU_TESS_FRAME_VIEW_STRIDE) != 0;

        /* AttachmentLoadOp.load is zero. */
        if (enabled && (store || tess_word(frame, load_word) == 0)) {
            ret = tess_add_frame_span(frame, OPENGPU_TESS_FRAME_VIEW_BASE +
                i * OPENGPU_TESS_FRAME_VIEW_STRIDE, OPENGPU_TESS_FRAME_VIEW_LIMIT +
                i * OPENGPU_TESS_FRAME_VIEW_STRIDE, store, !virtual_images,
                true, spans, count);
            if (ret) return ret;
        }
        if (tess_word(frame, resolve_enables[i])) {
            ret = tess_add_frame_span(frame, resolve_bases[i], resolve_limits[i],
                                      true, !virtual_images, true, spans, count);
            if (ret) return ret;
        }
    }
    return 0;
}

/* Only driver-allocated GEM backing can currently prove physical disjointness.
 * An import may alias a different DMA address, including a snapshot. */
static int tess_resolve_span(struct opengpu_render_context *context,
    const struct tess_frame_span *range, u32 type,
    const struct opengpu_sched_dep *deps, u32 dep_count,
    struct tess_owned_span *out)
{
    u32 slot, d;

    for (slot = 0; slot < OPENGPU_MAX_RESOURCE_BINDINGS; ++slot) {
        const struct opengpu_resource_binding *binding = context->bindings[slot];
        u64 address, offset, length = range->end - range->base;

        if (!binding || binding->type != type || !binding->object ||
            binding->object->import_attach || !binding->va || !binding->dma ||
            !binding->size)
            continue;
        address = range->physical ? binding->dma : binding->va;
        if (address >= OPENGPU_TESSELLATION_ADDRESS_END ||
            binding->size > OPENGPU_TESSELLATION_ADDRESS_END - address ||
            binding->dma >= OPENGPU_TESSELLATION_ADDRESS_END ||
            binding->size > OPENGPU_TESSELLATION_ADDRESS_END - binding->dma ||
            range->base < address || range->end > address + binding->size)
            continue;
        for (d = 0; d < dep_count; ++d) {
            if (deps[d].object != binding->object)
                continue;
            if (range->write ?
                (deps[d].wait_usage != DMA_RESV_USAGE_READ ||
                 deps[d].signal_usage != DMA_RESV_USAGE_WRITE) :
                ((deps[d].wait_usage != DMA_RESV_USAGE_WRITE &&
                  deps[d].wait_usage != DMA_RESV_USAGE_READ) ||
                 (deps[d].signal_usage != DMA_RESV_USAGE_READ &&
                  deps[d].signal_usage != DMA_RESV_USAGE_WRITE)))
                continue;
            offset = range->base - address;
            if (binding->offset > ~(u64)0 - offset ||
                binding->offset + offset > ~(u64)0 - length)
                return -EINVAL;
            *out = (struct tess_owned_span) {
                .binding = binding, .object_base = binding->offset + offset,
                .object_end = binding->offset + offset + length,
                .dma_base = binding->dma + offset,
                .dma_end = binding->dma + offset + length,
                .write = range->write,
            };
            return 0;
        }
    }
    return -EINVAL;
}

static bool tess_spans_conflict(const struct tess_owned_span *a,
                                const struct tess_owned_span *b)
{
    if (!a->write && !b->write)
        return false;
    return (a->binding->object == b->binding->object &&
        opengpu_tessellation_ranges_overlap(a->object_base, a->object_end,
                                             b->object_base, b->object_end)) ||
        opengpu_tessellation_ranges_overlap(a->dma_base, a->dma_end,
                                             b->dma_base, b->dma_end);
}

static bool tess_snapshot_conflict(const struct opengpu_sched_job *job,
                                   const struct tess_owned_span *span)
{
    const struct opengpu_buffer *buffers[] = {
        &job->tess_job, &job->tess_frame, &job->tess_draws, &job->tess_states,
    };
    u32 i;

    for (i = 0; i < sizeof(buffers) / sizeof(buffers[0]); ++i)
        if (buffers[i]->cpu && opengpu_tessellation_ranges_overlap(
                span->dma_base, span->dma_end, buffers[i]->dma,
                buffers[i]->dma + buffers[i]->size))
            return true;
    return false;
}

/* Only draw-referenced entries are read: unused table slots may be empty.
 * A nonempty rasterized sequence requires a copied state even when it has no
 * patch inputs. Reject missing state before enqueue instead of deferring the
 * failed admission to the image owner. Discard-only passes retain their
 * trusted-producer validation contract. */
static int tess_fragment_states_check(const struct opengpu_sched_job *job,
    const struct opengpu_tessellation_job_view *view, u32 engines, u32 flags)
{
    u32 i;

    if (flags & GPU_UCMD_FLAG_RASTER_DISCARD)
        return 0;
    for (i = 0; i < view->draw_count; ++i) {
        const unsigned char *draw = (const unsigned char *)job->tess_draws.cpu +
                                    (u64)i * view->draw_stride;
        u32 handle;

        if (!tess_word(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD))
            continue;
        handle = tess_word(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD);
        if (!job->tess_states.cpu ||
            !opengpu_tessellation_fragment_state_precheck(job->tess_frame.cpu,
                job->tess_frame.size, job->tess_states.cpu, job->tess_states.size,
                view, engines, handle))
            return -EINVAL;
    }
    return 0;
}

/* Match ShaderTextureResources/ShaderUniformResources: only (0,0) is
 * disabled; version one owns its entire aligned root, including address zero
 * and an exclusive limit at 4 GiB. Return one for an enabled root. */
static int tess_state_root_span(const unsigned char *state, u32 version_word,
    u32 address_word, u32 bytes, struct tess_frame_span *range)
{
    u32 version = tess_word(state, version_word);
    u64 base = tess_word(state, address_word);

    if (!version)
        return base ? -EINVAL : 0;
    if (version != 1u || (base & 63u) ||
        base + bytes > OPENGPU_TESSELLATION_ADDRESS_END)
        return -EINVAL;
    *range = (struct tess_frame_span) { .base = base, .end = base + bytes };
    return 1;
}

/* State snapshots were checked above. Pin the GXR1/GUR1 root allocations,
 * irrespective of whether the draw reads patch inputs. Root bytes and their
 * indirect tables/data still belong to the trusted producer's contract. */
static int tess_state_roots_check(const struct opengpu_sched_job *job,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_view *view,
    const struct tess_owned_span *frame, u32 frame_count,
    const struct opengpu_sched_dep *deps, u32 dep_count, u32 flags)
{
    static const u32 version_words[] = { OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD,
                                        OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD };
    static const u32 address_words[] = { OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD,
                                        OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD };
    static const u32 sizes[] = { OPENGPU_TESS_TEXTURE_ROOT_BYTES,
                                 OPENGPU_TESS_UNIFORM_ROOT_BYTES };
    u32 i, root, j;

    if (flags & GPU_UCMD_FLAG_RASTER_DISCARD)
        return 0;
    for (i = 0; i < view->draw_count; ++i) {
        const unsigned char *draw = (const unsigned char *)job->tess_draws.cpu +
                                    (u64)i * view->draw_stride;
        const unsigned char *state;
        u64 offset;

        if (!tess_word(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD))
            continue;
        offset = (u64)tess_word(draw,
            OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD) * 512u;
        if (!job->tess_states.cpu || offset + 512u > job->tess_states.size)
            return -EINVAL;
        state = (const unsigned char *)job->tess_states.cpu + offset;
        for (root = 0; root < 2u; ++root) {
            struct tess_frame_span range;
            struct tess_owned_span owned;
            int ret = tess_state_root_span(state, version_words[root],
                                           address_words[root], sizes[root], &range);

            if (ret < 0)
                return ret;
            if (!ret)
                continue;
            ret = tess_resolve_span(context, &range, OPENGPU_RESOURCE_COMPUTE_STORAGE,
                                    deps, dep_count, &owned);
            if (ret || tess_snapshot_conflict(job, &owned))
                return -EINVAL;
            for (j = 0; j < frame_count; ++j)
                if (tess_spans_conflict(&owned, &frame[j]))
                    return -EINVAL;
        }
    }
    return 0;
}

/* The caller holds render_file->lock and every dependency reservation.
 * Context bindings remain stable through the published context fence. */
static int tess_resources_check(struct opengpu_sched_job *job,
    struct opengpu_render_context *context, const struct opengpu_tessellation_job_view *view,
    const struct opengpu_sched_dep *deps, u32 dep_count, u32 flags)
{
    struct tess_frame_span ranges[TESS_MAX_FRAME_SPANS];
    struct tess_owned_span owned[TESS_MAX_FRAME_SPANS];
    u32 count, i, j;
    int ret;

    ret = tess_frame_spans(job->tess_frame.cpu, job->gpu->hw.tessellation_engines,
                           job->gpu->hw.tessellation_virtual_images, ranges, &count);
    if (ret) return ret;
    for (i = 0; i < count; ++i) {
        ret = tess_resolve_span(context, &ranges[i], OPENGPU_RESOURCE_COMPUTE_STORAGE,
                                deps, dep_count, &owned[i]);
        if (ret || tess_snapshot_conflict(job, &owned[i]))
            return -EINVAL;
        for (j = 0; j < i; ++j)
            if (tess_spans_conflict(&owned[i], &owned[j]))
                return -EINVAL;
    }
    for (i = 0; i < view->draw_count; ++i) {
        const unsigned char *draw = (const unsigned char *)job->tess_draws.cpu +
                                    (u64)i * view->draw_stride;
        struct opengpu_tessellation_input_view input;
        struct tess_frame_span range;
        struct tess_owned_span patch;

        if (!opengpu_tessellation_draw_input_view_precheck(draw,
                OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES, view, i, &input))
            return -EINVAL;
        if (!input.active)
            continue;
        range = (struct tess_frame_span) {
            .base = input.allocation_base, .end = input.allocation_limit,
        };
        ret = tess_resolve_span(context, &range, OPENGPU_RESOURCE_VERTEX_BUFFER,
                                deps, dep_count, &patch);
        if (ret || tess_snapshot_conflict(job, &patch))
            return -EINVAL;
        for (j = 0; j < count; ++j)
            if (tess_spans_conflict(&patch, &owned[j]))
                return -EINVAL;
    }
    return tess_state_roots_check(job, context, view, owned, count, deps, dep_count, flags);
}

static int opengpu_tessellation_job_prepare(
    struct opengpu_sched_job *job, struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    const struct opengpu_sched_dep *deps, u32 dep_count)
{
    struct opengpu_kernarg_va_plan job_plan, frame_plan, draws_plan, states_plan;
    struct opengpu_tessellation_job_view view;
    u64 draw_size = 0, state_base, state_limit, state_size, limit;
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
    state_base = tess_word(job->tess_frame.cpu, OPENGPU_TESS_FRAME_STATE_BASE);
    state_limit = tess_word(job->tess_frame.cpu, OPENGPU_TESS_FRAME_STATE_LIMIT) |
        ((u64)tess_word(job->tess_frame.cpu, OPENGPU_TESS_FRAME_STATE_LIMIT + 1u) << 32);
    if (state_limit > OPENGPU_TESSELLATION_ADDRESS_END || state_limit < state_base ||
        (state_base & 63u) || (state_limit & 63u)) {
        ret = -EINVAL;
        goto fail;
    }
    state_size = state_limit - state_base;
    if (state_size && (!input->states || state_size > input->states_size ||
                       state_size > OPENGPU_TESS_STATES_VA_SIZE)) {
        ret = -EINVAL;
        goto fail;
    }
    if (state_size) {
        ret = opengpu_buffer_alloc(job->gpu, &job->tess_states, (size_t)state_size);
        if (ret)
            goto fail;
        memcpy(job->tess_states.cpu, input->states, (size_t)state_size);
    }
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
    if (!ret && state_size)
        ret = tess_snapshot_plan(&job->tess_states, OPENGPU_TESS_STATES_SLOT, &states_plan);
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
    if (state_size) {
        tess_put32(job->tess_frame.cpu, OPENGPU_TESS_FRAME_STATE_BASE, (u32)states_plan.va);
        limit = states_plan.va + state_size;
        tess_put32(job->tess_frame.cpu, OPENGPU_TESS_FRAME_STATE_LIMIT, (u32)limit);
        tess_put32(job->tess_frame.cpu, OPENGPU_TESS_FRAME_STATE_LIMIT + 1, (u32)(limit >> 32));
    }
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
    ret = tess_fragment_states_check(job, &view, job->gpu->hw.tessellation_engines,
                                     input->scope.flags);
    if (!ret)
        ret = tess_resources_check(job, context, &view, deps, dep_count, input->scope.flags);
    if (ret)
        goto fail;
    job->context = context;
    job->vm = &context->vm;
    job->tess_job_va = (u32)job_plan.va;
    job->tess_frame_va = (u32)frame_plan.va;
    job->tess_draws_va = count ? (u32)draws_plan.va : 0;
    job->tess_states_va = state_size ? (u32)states_plan.va : 0;
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
        .states = job->tess_states.cpu ? &job->tess_states : NULL,
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
    if (!ret && descriptors.states)
        ret = tess_map_snapshot(job, &job->tess_states, OPENGPU_TESS_STATES_SLOT,
                                job->tess_states_va);
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
