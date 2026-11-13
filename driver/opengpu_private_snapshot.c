// SPDX-License-Identifier: GPL-2.0
/* Unexported kernel copies for protected native descriptor publication. */
#include <linux/err.h>
#include <linux/overflow.h>
#include <linux/slab.h>
#include <linux/string.h>
#include "opengpu_scheduler.h"
#include "opengpu_private_snapshot.h"

enum snapshot_phase { SNAPSHOT_BUILD, SNAPSHOT_PREPARED, SNAPSHOT_PUBLISHED,
                      SNAPSHOT_FAILED };

struct snapshot_segment {
    struct snapshot_segment *next;
    struct opengpu_buffer backing;
    struct opengpu_snapshot_lease lease;
    dma_addr_t va;
    size_t bytes;
    u32 id;
    bool mapped;
};

struct snapshot_relocation {
    struct snapshot_relocation *next;
    struct snapshot_segment *source, *target;
    size_t offset;
    u64 addend;
    enum opengpu_private_snapshot_relocation kind;
};

struct opengpu_private_snapshot {
    struct opengpu_device *gpu;
    struct opengpu_render_context *context;
    struct snapshot_segment *segments, **tail;
    struct snapshot_relocation *relocations;
    enum snapshot_phase phase;
    u32 count;
};

static struct snapshot_segment *snapshot_segment_find(
    const struct opengpu_private_snapshot *owner, u32 id)
{
    struct snapshot_segment *segment;
    for (segment = owner->segments; segment && segment->id != id;
         segment = segment->next)
        ;
    return segment;
}

static void snapshot_put32(unsigned char *p, u32 value)
{
    p[0] = value; p[1] = value >> 8; p[2] = value >> 16; p[3] = value >> 24;
}

static int snapshot_apply_relocation(const struct snapshot_relocation *relocation)
{
    u64 value = (u64)relocation->target->va + relocation->addend;
    unsigned char *p = (unsigned char *)relocation->source->backing.cpu + relocation->offset;
    if (value > (1ULL << 32) ||
        (value == (1ULL << 32) && relocation->kind == OPENGPU_PRIVATE_SNAPSHOT_VA32))
        return -ERANGE;
    snapshot_put32(p, (u32)value);
    if (relocation->kind == OPENGPU_PRIVATE_SNAPSHOT_LIMIT33)
        snapshot_put32(p + 4u, (u32)(value >> 32));
    return 0;
}

struct opengpu_private_snapshot *opengpu_private_snapshot_create(
    struct opengpu_device *gpu, struct opengpu_render_context *context)
{
    struct opengpu_private_snapshot *owner;
    if (!gpu || !context || context->gpu != gpu || !context->vm.enabled ||
        !context->vm.asid)
        return ERR_PTR(-EINVAL);
    owner = kzalloc(sizeof(*owner), GFP_KERNEL);
    if (!owner)
        return ERR_PTR(-ENOMEM);
    owner->gpu = gpu;
    owner->context = context;
    owner->tail = &owner->segments;
    opengpu_context_get(context);
    return owner;
}

int opengpu_private_snapshot_append(struct opengpu_private_snapshot *owner,
    const void *stable_kernel_bytes, size_t bytes, u32 *id)
{
    struct snapshot_segment *segment;
    size_t rounded;
    int ret;
    if (!owner || owner->phase != SNAPSHOT_BUILD || !stable_kernel_bytes ||
        !bytes || !id || owner->count == (u32)~0u)
        return -EINVAL;
    if (check_add_overflow(bytes, (size_t)OPENGPU_MMU_PAGE_SIZE - 1u, &rounded))
        return -ERANGE;
    rounded &= ~((size_t)OPENGPU_MMU_PAGE_SIZE - 1u);
    if ((u64)rounded > (1ULL << 32))
        return -ERANGE;
    segment = kzalloc(sizeof(*segment), GFP_KERNEL);
    if (!segment)
        return -ENOMEM;
    ret = opengpu_buffer_alloc(owner->gpu, &segment->backing, rounded);
    if (ret)
        goto fail;
    if ((segment->backing.dma & (OPENGPU_MMU_PAGE_SIZE - 1u)) ||
        (u64)segment->backing.dma > (1ULL << 32) - rounded) {
        ret = -ERANGE;
        goto fail_backing;
    }
    memset(segment->backing.cpu, 0, rounded);
    memcpy(segment->backing.cpu, stable_kernel_bytes, bytes);
    segment->bytes = bytes;
    segment->id = owner->count++;
    *owner->tail = segment;
    owner->tail = &segment->next;
    *id = segment->id;
    return 0;
fail_backing:
    opengpu_buffer_free(owner->gpu, &segment->backing);
fail:
    kfree(segment);
    return ret;
}

int opengpu_private_snapshot_read32(const struct opengpu_private_snapshot *owner,
    u32 id, size_t offset, u32 *value)
{
    const struct snapshot_segment *segment;
    const unsigned char *p;
    if (!owner || !value || (offset & 3u))
        return -EINVAL;
    segment = snapshot_segment_find(owner, id);
    if (!segment || segment->bytes < 4u || offset > segment->bytes - 4u)
        return -ERANGE;
    p = (const unsigned char *)segment->backing.cpu + offset;
    *value = (u32)p[0] | ((u32)p[1] << 8) | ((u32)p[2] << 16) | ((u32)p[3] << 24);
    return 0;
}

int opengpu_private_snapshot_relocate(struct opengpu_private_snapshot *owner,
    u32 source_id, size_t offset, u32 target_id, u64 addend,
    enum opengpu_private_snapshot_relocation kind)
{
    struct snapshot_segment *source, *target;
    struct snapshot_relocation *relocation, *other;
    size_t width;
    if (!owner || owner->phase != SNAPSHOT_BUILD || (offset & 3u) ||
        (kind != OPENGPU_PRIVATE_SNAPSHOT_VA32 && kind != OPENGPU_PRIVATE_SNAPSHOT_LIMIT33))
        return -EINVAL;
    source = snapshot_segment_find(owner, source_id);
    target = snapshot_segment_find(owner, target_id);
    width = kind == OPENGPU_PRIVATE_SNAPSHOT_LIMIT33 ? 8u : 4u;
    if (!source || !target || source->bytes < width ||
        offset > source->bytes - width || addend > target->bytes)
        return -ERANGE;
    for (other = owner->relocations; other; other = other->next) {
        size_t other_width = other->kind == OPENGPU_PRIVATE_SNAPSHOT_LIMIT33 ? 8u : 4u;
        if (other->source == source && offset < other->offset + other_width &&
            other->offset < offset + width)
            return -EINVAL;
    }
    relocation = kzalloc(sizeof(*relocation), GFP_KERNEL);
    if (!relocation)
        return -ENOMEM;
    relocation->source = source; relocation->target = target;
    relocation->offset = offset; relocation->addend = addend; relocation->kind = kind;
    relocation->next = owner->relocations;
    owner->relocations = relocation;
    return 0;
}

int opengpu_private_snapshot_prepare(struct opengpu_private_snapshot *owner)
{
    struct snapshot_segment *segment;
    struct snapshot_relocation *relocation;
    struct opengpu_device *gpu;
    int ret;
    if (!owner || owner->phase != SNAPSHOT_BUILD || !owner->segments)
        return -EINVAL;
    gpu = owner->gpu;
    /* Close mutation even on failure; the caller must retire partial state. */
    owner->phase = SNAPSHOT_FAILED;
    for (segment = owner->segments; segment; segment = segment->next) {
        if (segment == owner->segments) {
            ret = opengpu_hw_snapshot_submission_lock(gpu);
            if (ret)
                return ret;
        } else {
            mutex_lock(&gpu->hw.submit_lock);
        }
        ret = opengpu_mmu_snapshot_acquire_locked(gpu, &segment->lease,
            &owner->context->vm, segment->backing.dma, segment->backing.size);
        mutex_unlock(&gpu->hw.submit_lock);
        if (ret)
            return ret;
        ret = opengpu_mmu_vm_map_snapshot_anywhere(gpu, &owner->context->vm,
            segment->backing.dma, segment->backing.size, OPENGPU_MMU_POLICY_UNCACHED,
            &segment->va);
        if (ret)
            return ret;
        segment->mapped = true;
    }
    for (relocation = owner->relocations; relocation; relocation = relocation->next) {
        ret = snapshot_apply_relocation(relocation);
        if (ret)
            return ret;
    }
    dma_wmb();
    owner->phase = SNAPSHOT_PREPARED;
    return 0;
}

int opengpu_private_snapshot_address(const struct opengpu_private_snapshot *owner,
    u32 id, u32 *address)
{
    const struct snapshot_segment *segment;
    if (!owner || !address ||
        (owner->phase != SNAPSHOT_PREPARED && owner->phase != SNAPSHOT_PUBLISHED))
        return -EINVAL;
    segment = snapshot_segment_find(owner, id);
    if (!segment || !segment->mapped)
        return -ERANGE;
    *address = (u32)segment->va;
    return 0;
}

int opengpu_private_snapshot_publish(struct opengpu_private_snapshot *owner)
{
    if (!owner || owner->phase != SNAPSHOT_PREPARED)
        return -EINVAL;
    dma_wmb();
    owner->phase = SNAPSHOT_PUBLISHED;
    return 0;
}

int opengpu_private_snapshot_verify_locked(const struct opengpu_private_snapshot *owner,
    struct opengpu_device *gpu, const struct opengpu_render_context *context)
{
    const struct snapshot_segment *segment;
    int ret;
    if (!owner || owner->gpu != gpu || owner->context != context ||
        owner->phase != SNAPSHOT_PUBLISHED)
        return -EINVAL;
    lockdep_assert_held(&gpu->hw.submit_lock);
    for (segment = owner->segments; segment; segment = segment->next) {
        if (!segment->mapped || !segment->lease.active || !segment->lease.mapped ||
            segment->lease.owner != &context->vm || segment->lease.va != segment->va ||
            segment->lease.base != segment->backing.dma ||
            segment->lease.size != segment->backing.size)
            return -EINVAL;
        ret = opengpu_mmu_verify_snapshot_locked(gpu, &context->vm, segment->va,
                                                segment->backing.dma, segment->backing.size);
        if (ret)
            return ret;
    }
    return 0;
}

int opengpu_private_snapshot_retire(struct opengpu_private_snapshot *owner)
{
    struct snapshot_segment *segment;
    struct snapshot_relocation *relocation;
    struct opengpu_device *gpu;
    int ret;
    if (!owner)
        return 0;
    gpu = owner->gpu;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    mutex_unlock(&gpu->hw.submit_lock);
    if (ret)
        goto quarantine;
    /* A failed prepare can leave an unleased allocation. Prove that no VM
     * still maps it before freeing, too; an unexpected pre-existing alias
     * must quarantine the owner rather than become a dangling mapping. */
    for (segment = owner->segments; segment; segment = segment->next) {
        if (segment->lease.active)
            continue;
        mutex_lock(&gpu->hw.submit_lock);
        ret = opengpu_mmu_snapshot_acquire_locked(gpu, &segment->lease,
            &owner->context->vm, segment->backing.dma, segment->backing.size);
        mutex_unlock(&gpu->hw.submit_lock);
        if (ret)
            goto quarantine;
    }
    /* Revoke every leaf before releasing any physical lease or allocation. */
    for (segment = owner->segments; segment; segment = segment->next) {
        if (!segment->mapped)
            continue;
        ret = opengpu_mmu_snapshot_unmap(gpu, &segment->lease);
        if (ret)
            goto quarantine;
        segment->mapped = false;
    }
    for (segment = owner->segments; segment; segment = segment->next) {
        if (!segment->lease.active)
            continue;
        mutex_lock(&gpu->hw.submit_lock);
        ret = opengpu_mmu_snapshot_release_locked(gpu, &segment->lease);
        mutex_unlock(&gpu->hw.submit_lock);
        if (ret)
            goto quarantine;
    }
    while ((segment = owner->segments)) {
        owner->segments = segment->next;
        opengpu_buffer_free(gpu, &segment->backing);
        kfree(segment);
    }
    while ((relocation = owner->relocations)) {
        owner->relocations = relocation->next;
        kfree(relocation);
    }
    opengpu_context_put(owner->context);
    kfree(owner);
    return 0;
quarantine:
    owner->phase = SNAPSHOT_FAILED;
    WRITE_ONCE(gpu->hw.wedged, true);
    return ret;
}
