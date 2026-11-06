// SPDX-License-Identifier: GPL-2.0
/* Scheduler-owned snapshots for an already validated native tessellation job. */
#include <linux/err.h>
#include <linux/slab.h>
#include <linux/string.h>

#include "opengpu_scheduler.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_tessellation_job_validate.h"
#include "opengpu_tessellation_texture.h"

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

static bool tess_spans_alias(const struct tess_owned_span *a,
                            const struct tess_owned_span *b)
{
    return (a->binding->object == b->binding->object &&
        opengpu_tessellation_ranges_overlap(a->object_base, a->object_end,
                                             b->object_base, b->object_end)) ||
        opengpu_tessellation_ranges_overlap(a->dma_base, a->dma_end,
                                             b->dma_base, b->dma_end);
}

static bool tess_spans_conflict(const struct tess_owned_span *a,
                                const struct tess_owned_span *b)
{
    return (a->write || b->write) && tess_spans_alias(a, b);
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

/* Binding publication normally establishes these leaves. Check the complete
 * view against the actual Sv32 tables too: a nonzero binding VA alone is not
 * evidence of its mapping. This is the same private Sv32 format as mmu.c;
 * storage leaves may be writable/executable, but must be readable, private and
 * uncached. Do not mistake their X bit for code provenance. */
static int tess_uniform_mapping_check(struct opengpu_device *gpu,
    const struct opengpu_vm *vm, u64 base, u64 dma, u64 bytes)
{
    u64 va, pa;
    int ret = -EINVAL;

    if (!bytes || base >= OPENGPU_TESSELLATION_ADDRESS_END ||
        bytes > OPENGPU_TESSELLATION_ADDRESS_END - base ||
        dma >= OPENGPU_TESSELLATION_ADDRESS_END ||
        bytes > OPENGPU_TESSELLATION_ADDRESS_END - dma ||
        ((base ^ dma) & (OPENGPU_MMU_PAGE_SIZE - 1u)))
        return -EINVAL;
    mutex_lock(&gpu->hw.submit_lock);
    mutex_lock(&gpu->mmu.lock);
    if (!gpu->mmu.enabled || !vm->enabled || !vm->asid ||
        vm->asid > OPENGPU_ASID_MAX || !vm->root.cpu ||
        vm->root.size < OPENGPU_MMU_PAGE_SIZE ||
        (vm->root.dma & (OPENGPU_MMU_PAGE_SIZE - 1u)) ||
        (u64)vm->root.dma > OPENGPU_TESSELLATION_ADDRESS_END - OPENGPU_MMU_PAGE_SIZE ||
        vm->l1_count > OPENGPU_VM_MAX_TABLES)
        goto out;
    for (va = base & ~(u64)(OPENGPU_MMU_PAGE_SIZE - 1u),
         pa = dma & ~(u64)(OPENGPU_MMU_PAGE_SIZE - 1u);
         va < base + bytes; va += OPENGPU_MMU_PAGE_SIZE, pa += OPENGPU_MMU_PAGE_SIZE) {
        const struct opengpu_buffer *table = NULL;
        u32 root = ((u32 *)vm->root.cpu)[va >> 22], leaf, i;

        if ((root & 0x3ffu) != 1u)
            goto out;
        for (i = 0; i < vm->l1_count; ++i)
            if (vm->l1_region[i] == va >> 22) {
                table = &vm->l1[i];
                break;
            }
        if (!table || !table->cpu || table->size < OPENGPU_MMU_PAGE_SIZE ||
            ((u64)(root >> 10) << 12) != table->dma)
            goto out;
        leaf = ((u32 *)table->cpu)[(va >> 12) & 1023u];
        if ((leaf & 0x43u) != 0x43u || (leaf & 0x30u) ||
            ((leaf >> 8) & 3u) != OPENGPU_MMU_POLICY_UNCACHED ||
            ((u64)(leaf >> 10) << 12) != pa)
            goto out;
    }
    ret = 0;
out:
    mutex_unlock(&gpu->mmu.lock);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

static bool tess_uniform_code_conflict(const struct opengpu_sched_job *job,
    const struct opengpu_render_context *context, const struct tess_owned_span *span)
{
    const struct opengpu_buffer *buffers[] = {
        &job->shader, &job->vertex_shader, &job->commands, &job->render_desc,
    };
    u32 i;

    for (i = 0; i < sizeof(buffers) / sizeof(buffers[0]); ++i)
        if (buffers[i]->cpu && opengpu_tessellation_ranges_overlap(
                span->dma_base, span->dma_end, buffers[i]->dma,
                buffers[i]->dma + buffers[i]->size))
            return true;
    for (i = 0; i < OPENGPU_MAX_RESOURCE_BINDINGS; ++i) {
        const struct opengpu_resource_binding *binding = context->bindings[i];
        struct tess_owned_span code;

        if (!binding || (binding->type != OPENGPU_RESOURCE_SHADER &&
            binding->type != OPENGPU_RESOURCE_VERTEX_SHADER &&
            binding->type != OPENGPU_RESOURCE_COMPUTE_SHADER))
            continue;
        if (!binding->object || binding->object->import_attach ||
            binding->size > ~(u64)0 - binding->offset ||
            binding->size > ~(u64)0 - binding->dma)
            return true;
        code = (struct tess_owned_span) { .binding = binding,
            .object_base = binding->offset, .object_end = binding->offset + binding->size,
            .dma_base = binding->dma, .dma_end = binding->dma + binding->size };
        if (tess_spans_alias(span, &code))
            return true;
    }
    return false;
}

static void tess_uniform_header(unsigned char *record, u32 magic,
    const struct opengpu_tessellation_job_view *view)
{
    tess_put32(record, 0, magic); tess_put32(record, 1, 64);
    tess_put32(record, 2, OPENGPU_UNIFORM_VERSION);
    tess_put32(record, 3, view->context); tess_put32(record, 4, view->pass);
    tess_put32(record, 5, view->generation);
}

static int tess_uniform_view_encode(struct opengpu_sched_job *job,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_view *view,
    const struct opengpu_tessellation_uniform_view *input, u32 index,
    const struct tess_owned_span *frame, u32 frame_count,
    const struct opengpu_sched_dep *deps, u32 dep_count, unsigned char *record)
{
    const struct opengpu_resource_binding *binding;
    struct tess_frame_span range;
    struct tess_owned_span owned;
    u64 base, end;
    u32 i;

    if (!input->slot || input->slot > OPENGPU_MAX_RESOURCE_BINDINGS ||
        !input->bytes || input->bytes > (index == OPENGPU_UNIFORM_DEFAULT_BLOCK ?
            OPENGPU_UNIFORM_DEFAULT_FRAGMENT_COMPONENTS * 4u : OPENGPU_UNIFORM_BLOCK_BYTES))
        return -EINVAL;
    binding = context->bindings[input->slot - 1u];
    if (!binding || binding->type != OPENGPU_RESOURCE_COMPUTE_STORAGE ||
        !binding->object || binding->object->import_attach ||
        !binding->va || !binding->dma ||
        input->offset > binding->size || input->bytes > binding->size - input->offset ||
        binding->va >= OPENGPU_TESSELLATION_ADDRESS_END ||
        binding->size > OPENGPU_TESSELLATION_ADDRESS_END - binding->va)
        return -EINVAL;
    base = binding->va + input->offset; end = base + input->bytes;
    if (base & (index == OPENGPU_UNIFORM_DEFAULT_BLOCK ? 3u : 255u))
        return -EINVAL;
    range = (struct tess_frame_span) { .base = base & ~63ULL,
                                      .end = (end + 63u) & ~63ULL };
    if (tess_resolve_span(context, &range, OPENGPU_RESOURCE_COMPUTE_STORAGE,
                           deps, dep_count, &owned) || owned.binding != binding ||
        tess_snapshot_conflict(job, &owned) || tess_uniform_code_conflict(job, context, &owned) ||
        tess_uniform_mapping_check(job->gpu, &context->vm, range.base,
                                     owned.dma_base, range.end - range.base))
        return -EINVAL;
    for (i = 0; i < frame_count; ++i)
        if (tess_spans_conflict(&owned, &frame[i]))
            return -EINVAL;
    tess_uniform_header(record, OPENGPU_UNIFORM_BINDING_MAGIC, view);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_BINDINGINDEX_WORD, index);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_STAGEMASK_WORD, OPENGPU_UNIFORM_CAP_STAGE_FRAGMENT);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_BASE_WORD, (u32)base);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_LIMIT_WORD, (u32)end);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_LIMIT_WORD + 1u, end >> 32);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_ALLOCATIONBASE_WORD, (u32)range.base);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_ALLOCATIONLIMIT_WORD, (u32)range.end);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_ALLOCATIONLIMIT_WORD + 1u, range.end >> 32);
    tess_put32(record, OPENGPU_UNIFORM_BINDING_READONLY_WORD, 1);
    return 0;
}

/* The root-reachable texture graph is generated from stable kernel plans.
 * Instruction coordinate packets are shader operands, not root pointers; the
 * trusted code producer still validates those and arbitrary executable data. */
static u64 tess_texture_bytes(const struct opengpu_tessellation_texture_plan *plan)
{
    u32 i;
    if (!plan->bindings || !plan->binding_count ||
        plan->binding_count > OPENGPU_NATIVE_TEXTURE_MAX_BINDINGS_PER_STAGE)
        return 0;
    for (i = 0; i < plan->binding_count; ++i)
        if (plan->bindings[i].present > 1u || (plan->bindings[i].present &&
            (!plan->bindings[i].levels || !plan->bindings[i].level_count ||
             plan->bindings[i].level_count > OPENGPU_TESS_TEXTURE_LEVELS)))
            return 0;
    return 128ULL + 2304ULL * plan->binding_count;
}

static u32 tess_texture_find(const struct opengpu_tessellation_texture_plan *plans,
                            u32 count, u32 handle)
{
    u32 lo = 0, hi = count;
    while (lo < hi) {
        u32 mid = lo + (hi - lo) / 2u;
        if (plans[mid].state_handle < handle) lo = mid + 1u;
        else hi = mid;
    }
    return lo < count && plans[lo].state_handle == handle ? lo : count;
}

static int tess_texture_allocation(struct opengpu_render_context *context,
    const struct opengpu_tessellation_uniform_view *data,
    const struct opengpu_resource_binding **binding, u64 *base, u64 *end)
{
    const struct opengpu_resource_binding *b;
    if (!data->slot || data->slot > OPENGPU_MAX_RESOURCE_BINDINGS || !data->bytes)
        return -EINVAL;
    b = context->bindings[data->slot - 1u];
    if (!b || b->type != OPENGPU_RESOURCE_COMPUTE_STORAGE || !b->object ||
        b->object->import_attach || !b->va || !b->dma ||
        data->offset > b->size || data->bytes > b->size - data->offset ||
        b->va >= OPENGPU_TESSELLATION_ADDRESS_END ||
        b->size > OPENGPU_TESSELLATION_ADDRESS_END - b->va)
        return -EINVAL;
    *binding = b; *base = b->va + data->offset; *end = *base + data->bytes;
    return 0;
}

static u64 tess_texture_level_end(const struct opengpu_compute_texture_level_config *v)
{
    if (v->target == OPENGPU_TEXTURE_BUFFER_TARGET)
        return (u64)v->base + v->buffer_bytes;
    u64 columns = v->block_4x4 ? (v->width + 3u) / 4u : v->width;
    u64 rows = v->block_4x4 ? (v->height + 3u) / 4u : v->height;
    u64 planes = v->target == 2u ? 6u : (v->target == 1u ? v->layers : 1u);
    /* Call only after the canonical encoder checked all widths and pitches. */
    return (u64)v->base + (rows - 1u) * v->row_pitch + columns * v->block_bytes +
        (v->depth - 1u) * v->slice_pitch + (planes - 1u) * v->layer_pitch;
}

static int tess_texture_read_check(struct opengpu_sched_job *job,
    struct opengpu_render_context *context, const struct opengpu_resource_binding *binding,
    u64 base, u64 end, const struct tess_owned_span *frame, u32 count,
    const struct opengpu_sched_dep *deps, u32 dep_count)
{
    struct tess_frame_span range = { .base = base & ~63ULL, .end = (end + 63u) & ~63ULL };
    struct tess_owned_span owned;
    u32 i;
    if (tess_resolve_span(context, &range, OPENGPU_RESOURCE_COMPUTE_STORAGE,
                           deps, dep_count, &owned) || owned.binding != binding ||
        tess_snapshot_conflict(job, &owned) || tess_uniform_code_conflict(job, context, &owned) ||
        tess_uniform_mapping_check(job->gpu, &context->vm, range.base,
                                     owned.dma_base, range.end - range.base))
        return -EINVAL;
    for (i = 0; i < count; ++i)
        if (tess_spans_conflict(&owned, &frame[i])) return -EINVAL;
    return 0;
}

static void tess_texture_store(unsigned char *dst, const u32 *words, u32 count)
{
    u32 i;
    for (i = 0; i < count; ++i) tess_put32(dst, i, words[i]);
}

static int tess_texture_binding_encode(struct opengpu_sched_job *job,
    struct opengpu_render_context *context, const struct opengpu_tessellation_job_view *view,
    const struct opengpu_tessellation_texture_binding *input, u32 index,
    u64 levels_va, unsigned char *binding_dst, unsigned char *image_dst,
    unsigned char *sampler_dst, unsigned char *level_dst,
    const struct tess_owned_span *frame, u32 frame_count,
    const struct opengpu_sched_dep *deps, u32 dep_count)
{
    struct opengpu_compute_texture_image_config image = input->image;
    struct opengpu_compute_texture_sampler_config sampler = input->sampler;
    struct opengpu_compute_texture_binding_config record;
    struct opengpu_compute_texture_level_config *levels;
    const struct opengpu_resource_binding *binding;
    u64 base, end, starts[16], ends[16];
    u32 words[OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS], mask = 0, i, j;
    int ret = -EINVAL;

    if (tess_texture_allocation(context, &input->data, &binding, &base, &end))
        return -EINVAL;
    image.context = view->context; image.pass = view->pass; image.generation = view->generation;
    image.handle = index; image.allocation_base = (u32)base; image.allocation_limit = end;
    image.levels_base = (u32)levels_va; image.levels_limit = levels_va + 2048u;
    image.first_level_handle = 0;
    sampler.context = view->context; sampler.pass = view->pass; sampler.generation = view->generation;
    sampler.handle = index;
    if (!opengpu_encode_compute_texture_image(words, &image) ||
        image.format >= OPENGPU_TEXTURE_FORMAT_COUNT ||
        (image.target != OPENGPU_TEXTURE_BUFFER_TARGET &&
         !opengpu_encode_compute_texture_sampler(words, &sampler))) return -EINVAL;
    record = (struct opengpu_compute_texture_binding_config) {
        .context = view->context, .pass = view->pass, .generation = view->generation,
        .unit = index, .image_handle = index, .sampler_handle = index,
        .target = image.target, .expected_type = opengpu_compute_texture_formats[image.format].kind,
    };
    levels = kvcalloc(input->level_count, sizeof(*levels), GFP_KERNEL);
    if (!levels) return -ENOMEM;
    for (i = 0; i < input->level_count; ++i) {
        const struct opengpu_tessellation_texture_level *source = &input->levels[i];
        struct opengpu_compute_texture_level_config *level = &levels[i];
        if (source->view.level >= 16u || (mask & (1u << source->view.level)) ||
            source->offset >= input->data.bytes || base + source->offset >= OPENGPU_TESSELLATION_ADDRESS_END)
            goto out;
        mask |= 1u << source->view.level; *level = source->view;
        level->context = view->context; level->pass = view->pass; level->generation = view->generation;
        level->handle = level->level; level->allocation_base = (u32)base; level->allocation_limit = end;
        level->base = (u32)(base + source->offset);
        if (!opengpu_validate_compute_texture_levels(&image, level, 1, level->level, false)) goto out;
        starts[i] = level->base; ends[i] = tess_texture_level_end(level);
        for (j = 0; j < i; ++j)
            if (starts[i] < ends[j] && ends[i] > starts[j]) goto out;
        ret = tess_texture_read_check(job, context, binding, starts[i], ends[i],
                                      frame, frame_count, deps, dep_count);
        if (ret) goto out;
        ret = -EINVAL;
    }
    if (!(mask & (1u << image.base_level))) goto out;
    for (i = 1; i < input->level_count; ++i) {
        struct opengpu_compute_texture_level_config value = levels[i];
        j = i;
        while (j && levels[j - 1u].level > value.level) { levels[j] = levels[j - 1u]; --j; }
        levels[j] = value;
    }
    if (image.target != OPENGPU_TEXTURE_BUFFER_TARGET && sampler.min_filter >= 2u && !opengpu_validate_compute_texture_levels(
            &image, levels, input->level_count, image.base_level, true)) goto out;
    /* These common encoders differ only in GXR/GXB stage width/value. Use the
     * canonical graphics index and generate stage1, never publish stage2. */
    if (!opengpu_encode_compute_texture_binding(words, &record)) goto out;
    words[OPENGPU_NATIVE_TEXTURE_BINDING_STAGE_WORD] = OPENGPU_NATIVE_TEXTURE_STAGE_FRAGMENT;
    tess_texture_store(binding_dst, words, 16);
    (void)opengpu_encode_compute_texture_image(words, &image); tess_texture_store(image_dst, words, 32);
    if (image.target == OPENGPU_TEXTURE_BUFFER_TARGET)
        for (i = 0; i < 16u; ++i) words[i] = 0;
    else
        (void)opengpu_encode_compute_texture_sampler(words, &sampler);
    tess_texture_store(sampler_dst, words, 16);
    for (i = 0; i < input->level_count; ++i) {
        (void)opengpu_encode_compute_texture_level(words, &levels[i]);
        tess_texture_store(level_dst + 128u * levels[i].level, words, 32);
    }
    ret = 0;
out:
    kvfree(levels);
    return ret;
}

static int tess_texture_encode(struct opengpu_sched_job *job,
    struct opengpu_render_context *context, const struct opengpu_tessellation_job_view *view,
    const struct opengpu_tessellation_texture_plan *plan, u64 offset, u64 job_va,
    const struct tess_owned_span *frame, u32 frame_count,
    const struct opengpu_sched_dep *deps, u32 dep_count)
{
    u64 binding_off = offset + 128u, image_off = binding_off + 64u * plan->binding_count;
    u64 sampler_off = image_off + 128u * plan->binding_count;
    u64 level_off = sampler_off + 64u * plan->binding_count;
    struct opengpu_compute_texture_root_config root = {
        .context = view->context, .pass = view->pass, .generation = view->generation,
        .count = plan->binding_count, .bindings_base = (u32)(job_va + binding_off),
        .bindings_limit = job_va + image_off, .images_base = (u32)(job_va + image_off),
        .images_limit = job_va + sampler_off, .samplers_base = (u32)(job_va + sampler_off),
        .samplers_limit = job_va + level_off,
    };
    u32 words[32], i;
    unsigned char *cpu = job->tess_job.cpu;
    root.buffer_only = true;
    for (i = 0; i < plan->binding_count; ++i)
        if (plan->bindings[i].present && plan->bindings[i].image.target != OPENGPU_TEXTURE_BUFFER_TARGET)
            root.buffer_only = false;
    if (root.buffer_only) root.samplers_base = 0, root.samplers_limit = 0;
    if (!opengpu_encode_compute_texture_root(words, &root)) return -EINVAL;
    words[OPENGPU_NATIVE_TEXTURE_ROOT_STAGE_WORD] = OPENGPU_NATIVE_TEXTURE_STAGE_FRAGMENT;
    tess_texture_store(cpu + offset, words, 32);
    for (i = 0; i < plan->binding_count; ++i)
        if (plan->bindings[i].present) {
            int ret = tess_texture_binding_encode(job, context, view, &plan->bindings[i], i,
                job_va + level_off + 2048u * i, cpu + binding_off + 64u * i,
                cpu + image_off + 128u * i, cpu + sampler_off + 64u * i,
                cpu + level_off + 2048u * i, frame, frame_count, deps, dep_count);
            if (ret) return ret;
        }
    return 0;
}

static u32 tess_uniform_find(const struct opengpu_tessellation_uniform_plan *plans,
    u32 count, u32 handle)
{
    u32 lo = 0, hi = count;

    while (lo < hi) {
        u32 mid = lo + (hi - lo) / 2u;
        if (plans[mid].state_handle < handle) lo = mid + 1u;
        else hi = mid;
    }
    return lo < count && plans[lo].state_handle == handle ? lo : count;
}

/* Capture reachable uniform and optional texture metadata in one allocation,
 * not just declared table spans. Plans are stable kernel bytes through submit;
 * no GPU-produced root is read on the CPU. Only generated metadata survives. */
static int tess_metadata_build(struct opengpu_sched_job *job,
    struct opengpu_render_context *context,
    struct opengpu_tessellation_job_view *view, struct opengpu_kernarg_va_plan *job_plan,
    const struct opengpu_tessellation_uniform_plan *plans, u32 plan_count,
    const struct opengpu_sched_dep *deps, u32 dep_count, u32 flags,
    const struct opengpu_tessellation_texture_plan *textures, u32 texture_count, bool texture_owned)
{
    struct tess_frame_span ranges[TESS_MAX_FRAME_SPANS];
    struct tess_owned_span *frame = NULL;
    struct opengpu_buffer expanded = {0}, old;
    u64 bytes = OPENGPU_NATIVE_TESSELLATION_JOB_BYTES, *offsets, *texture_offsets = NULL;
    u32 i, j, count = 0;
    int ret = -EINVAL;

    if ((flags & GPU_UCMD_FLAG_RASTER_DISCARD) || !view->draw_count)
        return 0;
    if ((plan_count && !plans) || plan_count >
        (OPENGPU_COMMAND_VA_STRIDE - OPENGPU_NATIVE_TESSELLATION_JOB_BYTES) / 64u)
        return -EINVAL;
    for (i = 1; i < plan_count; ++i)
        if (plans[i - 1u].state_handle >= plans[i].state_handle)
            return -EINVAL;
    if (texture_owned) {
        if ((texture_count && !textures) || texture_count >
            (OPENGPU_COMMAND_VA_STRIDE - OPENGPU_NATIVE_TESSELLATION_JOB_BYTES) / 128u)
            return -EINVAL;
        for (i = 1; i < texture_count; ++i)
            if (textures[i - 1u].state_handle >= textures[i].state_handle) return -EINVAL;
    }
    offsets = kvcalloc(plan_count ? plan_count : 1u, sizeof(*offsets), GFP_KERNEL);
    if (!offsets)
        return -ENOMEM;
    if (texture_owned) {
        texture_offsets = kvcalloc(texture_count ? texture_count : 1u, sizeof(*texture_offsets), GFP_KERNEL);
        if (!texture_offsets) { ret = -ENOMEM; goto out; }
    }
    for (i = 0; i < view->draw_count; ++i) {
        unsigned char *state, *draw = (unsigned char *)job->tess_draws.cpu + (u64)i * view->draw_stride;
        u32 handle, p;
        const struct opengpu_tessellation_uniform_plan *plan;

        if (!tess_word(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD)) continue;
        handle = tess_word(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD);
        if (!job->tess_states.cpu || (u64)handle * 512u + 512u > job->tess_states.size)
            goto out;
        state = (unsigned char *)job->tess_states.cpu + (u64)handle * 512u;
        if (!tess_word(state, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD)) {
            if (tess_word(state, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD)) goto out;
            continue;
        }
        if (tess_word(state, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD) != 1u) goto out;
        p = tess_uniform_find(plans, plan_count, handle);
        if (p == plan_count) goto out;
        if (offsets[p]) continue;
        plan = &plans[p];
        if (plan->active_mask >> OPENGPU_UNIFORM_BLOCKS_PER_STAGE || plan->default_present > 1u)
            goto out;
        for (j = 0; j < OPENGPU_UNIFORM_BLOCKS_PER_STAGE; ++j)
            if ((plan->active_mask & (1u << j)) ? plan->block_map[j] >= OPENGPU_UNIFORM_BINDINGS : plan->block_map[j] != 0u)
                goto out;
        offsets[p] = bytes;
        bytes += 64u + (plan->active_mask ? OPENGPU_UNIFORM_BINDINGS * 64u : 0u) +
                 (plan->default_present ? 64u : 0u);
        if (bytes > OPENGPU_COMMAND_VA_STRIDE) goto out;
    }
    if (texture_owned) for (i = 0; i < view->draw_count; ++i) {
        unsigned char *state, *draw = (unsigned char *)job->tess_draws.cpu + (u64)i * view->draw_stride;
        u32 handle, index; u64 size;
        if (!tess_word(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD)) continue;
        handle = tess_word(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD);
        if (!job->tess_states.cpu || (u64)handle * 512u + 512u > job->tess_states.size) goto out;
        state = (unsigned char *)job->tess_states.cpu + (u64)handle * 512u;
        if (!tess_word(state, OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD)) {
            if (tess_word(state, OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD)) goto out;
            continue;
        }
        if (tess_word(state, OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD) != 1u) goto out;
        index = tess_texture_find(textures, texture_count, handle);
        if (index == texture_count) goto out;
        if (texture_offsets[index]) continue;
        size = tess_texture_bytes(&textures[index]);
        if (!size || size > OPENGPU_COMMAND_VA_STRIDE - bytes) goto out;
        texture_offsets[index] = bytes; bytes += size;
    }
    if (bytes == OPENGPU_NATIVE_TESSELLATION_JOB_BYTES) { ret = 0; goto out; }
    frame = kvcalloc(TESS_MAX_FRAME_SPANS, sizeof(*frame), GFP_KERNEL);
    if (!frame) { ret = -ENOMEM; goto out; }
    ret = opengpu_buffer_alloc(job->gpu, &expanded, (size_t)bytes);
    if (ret) goto out;
    memset(expanded.cpu, 0, (size_t)bytes);
    memcpy(expanded.cpu, job->tess_job.cpu, OPENGPU_NATIVE_TESSELLATION_JOB_BYTES);
    ret = tess_snapshot_plan(&expanded, OPENGPU_TESS_JOB_SLOT, job_plan);
    if (ret) { opengpu_buffer_free(job->gpu, &expanded); goto out; }
    old = job->tess_job; job->tess_job = expanded;
    opengpu_buffer_free(job->gpu, &old);
    job->tess_job_va = (u32)job_plan->va;
    view->descriptor_base = job->tess_job_va;
    ret = tess_frame_spans(job->tess_frame.cpu, job->gpu->hw.tessellation_engines,
                           job->gpu->hw.tessellation_virtual_images, ranges, &count);
    if (ret) goto out;
    for (i = 0; i < count; ++i) {
        ret = tess_resolve_span(context, &ranges[i], OPENGPU_RESOURCE_COMPUTE_STORAGE,
                                deps, dep_count, &frame[i]);
        if (ret) goto out;
    }
    for (i = 0; i < plan_count; ++i) {
        const struct opengpu_tessellation_uniform_plan *plan = &plans[i];
        unsigned char *root, *state;
        u64 next, table, map = 0;
        u32 used = 0;

        if (!offsets[i]) continue;
        root = (unsigned char *)job->tess_job.cpu + offsets[i];
        state = (unsigned char *)job->tess_states.cpu + (u64)plan->state_handle * 512u;
        tess_put32(state, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD, (u32)(job_plan->va + offsets[i]));
        tess_uniform_header(root, OPENGPU_UNIFORM_ROOT_MAGIC, view);
        tess_put32(root, OPENGPU_UNIFORM_ROOT_STAGE_WORD, OPENGPU_UNIFORM_STAGE_FRAGMENT);
        tess_put32(root, OPENGPU_UNIFORM_ROOT_ACTIVEMASK_WORD, plan->active_mask);
        for (j = 0; j < OPENGPU_UNIFORM_BLOCKS_PER_STAGE; ++j) {
            if (j == 6u) { tess_put32(root, OPENGPU_UNIFORM_ROOT_MAPLOW_WORD, (u32)map); map = 0; }
            map |= (u64)plan->block_map[j] << (5u * (j % 6u));
            if (plan->active_mask & (1u << j)) used |= 1u << plan->block_map[j];
        }
        tess_put32(root, OPENGPU_UNIFORM_ROOT_MAPHIGH_WORD, (u32)map);
        next = offsets[i] + 64u;
        if (plan->active_mask) {
            table = job_plan->va + next;
            tess_put32(root, OPENGPU_UNIFORM_ROOT_BINDINGSBASE_WORD, (u32)table);
            table += OPENGPU_UNIFORM_BINDINGS * 64u;
            tess_put32(root, OPENGPU_UNIFORM_ROOT_BINDINGSLIMIT_WORD, (u32)table);
            tess_put32(root, OPENGPU_UNIFORM_ROOT_BINDINGSLIMIT_WORD + 1u, table >> 32);
            for (j = 0; j < OPENGPU_UNIFORM_BINDINGS; ++j)
                if (used & (1u << j)) {
                    ret = tess_uniform_view_encode(job, context, view, &plan->bindings[j], j,
                        frame, count, deps, dep_count, (unsigned char *)job->tess_job.cpu + next + 64u * j);
                    if (ret) goto out;
                }
            next += OPENGPU_UNIFORM_BINDINGS * 64u;
        }
        if (plan->default_present) {
            tess_put32(root, OPENGPU_UNIFORM_ROOT_DEFAULTVIEW_WORD, (u32)(job_plan->va + next));
            tess_put32(root, OPENGPU_UNIFORM_ROOT_DEFAULTPRESENT_WORD, 1);
            ret = tess_uniform_view_encode(job, context, view, &plan->default_view, OPENGPU_UNIFORM_DEFAULT_BLOCK,
                frame, count, deps, dep_count, (unsigned char *)job->tess_job.cpu + next);
            if (ret) goto out;
        }
    }
    if (texture_owned) for (i = 0; i < texture_count; ++i) {
        unsigned char *state;
        if (!texture_offsets[i]) continue;
        state = (unsigned char *)job->tess_states.cpu + (u64)textures[i].state_handle * 512u;
        tess_put32(state, OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD,
                   (u32)(job_plan->va + texture_offsets[i]));
        ret = tess_texture_encode(job, context, view, &textures[i], texture_offsets[i],
                                  job_plan->va, frame, count, deps, dep_count);
        if (ret) goto out;
    }
    ret = 0;
out:
    kvfree(frame);
    kvfree(texture_offsets);
    kvfree(offsets);
    return ret;
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
    const struct opengpu_sched_dep *deps, u32 dep_count, u32 flags, bool uniform_owned, bool texture_owned)
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
            if ((root == 1u && uniform_owned) || (root == 0u && texture_owned)) {
                if (range.base < (u64)job->tess_job_va + OPENGPU_NATIVE_TESSELLATION_JOB_BYTES ||
                    range.end > (u64)job->tess_job_va + job->tess_job.size)
                    return -EINVAL;
                continue;
            }
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
    const struct opengpu_sched_dep *deps, u32 dep_count, u32 flags, bool uniform_owned, bool texture_owned)
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
    return tess_state_roots_check(job, context, view, owned, count, deps, dep_count, flags, uniform_owned, texture_owned);
}

static int opengpu_tessellation_job_prepare(
    struct opengpu_sched_job *job, struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    const struct opengpu_sched_dep *deps, u32 dep_count,
    const struct opengpu_tessellation_uniform_plan *plans, u32 plan_count, bool uniform_owned,
    const struct opengpu_tessellation_texture_plan *textures, u32 texture_count, bool texture_owned)
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
    if (!ret && uniform_owned)
        ret = tess_metadata_build(job, context, &view, &job_plan, plans, plan_count,
                                 deps, dep_count, input->scope.flags, textures, texture_count, texture_owned);
    if (!ret && uniform_owned && !opengpu_tessellation_snapshots_precheck(
            job->tess_job.cpu, job->tess_job.size, job_plan.va,
            job_plan.va + OPENGPU_NATIVE_TESSELLATION_JOB_BYTES,
            job->tess_frame.cpu, job->tess_frame.size,
            job->tess_draws.cpu, job->tess_draws.size,
            job->gpu->hw.tessellation_engines, &view))
        ret = -EINVAL;
    if (!ret)
        ret = tess_resources_check(job, context, &view, deps, dep_count, input->scope.flags, uniform_owned, texture_owned);
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
    if (!ret && job->tess_job.size > OPENGPU_NATIVE_TESSELLATION_JOB_BYTES) {
        /* The common transport verifies the 512-byte GNQ prefix. Our owned
         * metadata can span further pages: verify every leaf/alias as well.
         * The held context fence excludes binding replacement until retire. */
        mutex_lock(&job->gpu->hw.submit_lock);
        ret = opengpu_mmu_verify_snapshot_locked(job->gpu, job->vm,
                job->tess_job_va, job->tess_job.dma, job->tess_job.size);
        mutex_unlock(&job->gpu->hw.submit_lock);
    }
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
static int tess_sched_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno,
    const struct opengpu_tessellation_uniform_plan *plans, u32 plan_count, bool uniform_owned,
    const struct opengpu_tessellation_texture_plan *textures, u32 texture_count, bool texture_owned)
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
    ret = opengpu_tessellation_job_prepare(job, context, input, deps, dep_count,
                                           plans, plan_count, uniform_owned, textures, texture_count, texture_owned);
    if (!ret)
        ret = opengpu_sched_job_submit(render_file, file, context, job,
                in_syncobj, out_sync, deps, dep_count, fence_seqno);
    if (ret) {
        opengpu_tessellation_job_release(job);
        kfree(job);
    }
    return ret;
}

int opengpu_tessellation_sched_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno)
{
    return tess_sched_submit(render_file, file, context, input, in_syncobj,
        out_sync, deps, dep_count, fence_seqno, NULL, 0, false, NULL, 0, false);
}

int opengpu_tessellation_uniform_sched_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    const struct opengpu_tessellation_uniform_plan *plans, u32 plan_count,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno)
{
    return tess_sched_submit(render_file, file, context, input, in_syncobj,
        out_sync, deps, dep_count, fence_seqno, plans, plan_count, true, NULL, 0, false);
}

int opengpu_tessellation_resources_sched_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    const struct opengpu_tessellation_uniform_plan *uniforms, u32 uniform_count,
    const struct opengpu_tessellation_texture_plan *textures, u32 texture_count,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno)
{
    return tess_sched_submit(render_file, file, context, input, in_syncobj,
        out_sync, deps, dep_count, fence_seqno, uniforms, uniform_count, true,
        textures, texture_count, true);
}
