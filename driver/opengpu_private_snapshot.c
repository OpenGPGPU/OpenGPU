// SPDX-License-Identifier: GPL-2.0
/* Unexported kernel copies for protected native descriptor publication. */
#include <linux/err.h>
#include <linux/overflow.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/vmalloc.h>
#include "opengpu_scheduler.h"
#include "opengpu_private_snapshot.h"
#include "opengpu_native_binned_abi.h"

enum snapshot_phase { SNAPSHOT_BUILD, SNAPSHOT_PREPARED, SNAPSHOT_PUBLISHED,
                      SNAPSHOT_FAILED };

struct snapshot_segment {
    struct snapshot_segment *next;
    struct opengpu_buffer backing;
    void *staging;
    size_t allocation_bytes;
    struct opengpu_snapshot_lease lease;
    dma_addr_t va;
    size_t bytes;
    u32 id;
    bool mapped, executable;
};

struct snapshot_relocation {
    struct snapshot_relocation *next;
    struct snapshot_segment *source, *target;
    struct snapshot_resource *resource;
    size_t offset;
    u64 addend;
    enum opengpu_private_snapshot_relocation kind;
};

struct snapshot_resource_map {
    struct snapshot_resource_map *next;
    size_t offset, bytes;
    dma_addr_t physical;
    bool writable, mapped;
};

struct snapshot_resource {
    struct snapshot_resource *next;
    struct opengpu_vm_reservation reservation;
    struct snapshot_resource_map *maps;
    size_t bytes;
    u32 id;
};

struct opengpu_private_snapshot {
    struct opengpu_device *gpu;
    struct opengpu_render_context *context;
    struct snapshot_segment *segments, **tail;
    struct snapshot_relocation *relocations;
    struct snapshot_resource *resources;
    enum snapshot_phase phase;
    u32 count, resource_count;
};

static struct snapshot_resource *snapshot_resource_find(
    const struct opengpu_private_snapshot *owner, u32 id)
{
    struct snapshot_resource *resource;
    for (resource = owner->resources; resource && resource->id != id;
         resource = resource->next)
        ;
    return resource;
}

int opengpu_private_snapshot_resource(struct opengpu_private_snapshot *owner,
    size_t bytes, u32 *id)
{
    struct snapshot_resource *resource;
    if (!owner || owner->phase != SNAPSHOT_BUILD || !id || !bytes ||
        (bytes & (OPENGPU_MMU_PAGE_SIZE - 1u)) ||
        (u64)bytes > (1ULL << 32) - OPENGPU_MMU_PAGE_SIZE ||
        owner->resource_count == (u32)~0u)
        return -EINVAL;
    resource = kzalloc(sizeof(*resource), GFP_KERNEL);
    if (!resource)
        return -ENOMEM;
    resource->bytes = bytes;
    resource->id = owner->resource_count++;
    resource->next = owner->resources;
    owner->resources = resource;
    *id = resource->id;
    return 0;
}

int opengpu_private_snapshot_resource_map(struct opengpu_private_snapshot *owner,
    u32 id, size_t offset, u64 physical, size_t bytes, bool writable)
{
    struct snapshot_resource *resource;
    struct snapshot_resource_map *map, *other;
    if (!owner || owner->phase != SNAPSHOT_BUILD || !bytes ||
        ((offset | physical | bytes) & (OPENGPU_MMU_PAGE_SIZE - 1u)) ||
        (u64)bytes > (1ULL << 32) || physical > (1ULL << 32) - bytes)
        return -EINVAL;
    resource = snapshot_resource_find(owner, id);
    if (!resource || offset > resource->bytes || bytes > resource->bytes - offset)
        return -ERANGE;
    for (other = resource->maps; other; other = other->next)
        if (offset < other->offset + other->bytes && other->offset < offset + bytes)
            return -EINVAL;
    map = kzalloc(sizeof(*map), GFP_KERNEL);
    if (!map)
        return -ENOMEM;
    map->offset = offset; map->bytes = bytes; map->physical = physical;
    map->writable = writable;
    map->next = resource->maps;
    resource->maps = map;
    return 0;
}

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
    u64 value = (relocation->resource ? (u64)relocation->resource->reservation.va :
                 (u64)relocation->target->va) + relocation->addend;
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

static int snapshot_append(struct opengpu_private_snapshot *owner,
    const void *stable_kernel_bytes, size_t bytes, u32 *id, bool executable)
{
    struct snapshot_segment *segment;
    size_t rounded;
    int ret;
    if (!owner || owner->phase != SNAPSHOT_BUILD || !stable_kernel_bytes ||
        !bytes || !id || owner->count == (u32)~0u || (executable && (bytes & 3u)))
        return -EINVAL;
    if (check_add_overflow(bytes, (size_t)OPENGPU_MMU_PAGE_SIZE - 1u, &rounded))
        return -ERANGE;
    rounded &= ~((size_t)OPENGPU_MMU_PAGE_SIZE - 1u);
    if ((u64)rounded > (1ULL << 32))
        return -ERANGE;
    segment = kzalloc(sizeof(*segment), GFP_KERNEL);
    if (!segment)
        return -ENOMEM;
    /* No GPU address exists while producer waits and CPU encoding continue.
     * prepare allocates/seals DMA pages before this trusted copy reaches them. */
    segment->staging=kvzalloc(rounded,GFP_KERNEL);
    if(!segment->staging){ret=-ENOMEM;goto fail;}
    memcpy(segment->staging,stable_kernel_bytes,bytes);
    segment->allocation_bytes=rounded;
    segment->bytes = bytes;
    segment->executable = executable;
    segment->id = owner->count++;
    *owner->tail = segment;
    owner->tail = &segment->next;
    *id = segment->id;
    return 0;
fail:
    kfree(segment);
    return ret;
}

int opengpu_private_snapshot_append(struct opengpu_private_snapshot *owner,
    const void *stable_kernel_bytes, size_t bytes, u32 *id)
{
    return snapshot_append(owner, stable_kernel_bytes, bytes, id, false);
}

int opengpu_private_snapshot_append_code(struct opengpu_private_snapshot *owner,
    const void *stable_kernel_bytes, size_t bytes, u32 *id)
{
    return snapshot_append(owner, stable_kernel_bytes, bytes, id, true);
}

static int snapshot_acquire(struct opengpu_private_snapshot *owner,
    struct snapshot_segment *segment)
{
    if (segment->executable)
        return opengpu_mmu_code_snapshot_acquire_locked(owner->gpu, &segment->lease,
            &owner->context->vm, segment->backing.dma, segment->backing.size);
    return opengpu_mmu_snapshot_acquire_locked(owner->gpu, &segment->lease,
        &owner->context->vm, segment->backing.dma, segment->backing.size);
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
    p = (const unsigned char *)(segment->staging ? segment->staging : segment->backing.cpu) + offset;
    *value = (u32)p[0] | ((u32)p[1] << 8) | ((u32)p[2] << 16) | ((u32)p[3] << 24);
    return 0;
}

static int snapshot_relocate(struct opengpu_private_snapshot *owner,
    u32 source_id, size_t offset, u32 target_id, u64 addend,
    enum opengpu_private_snapshot_relocation kind, bool external)
{
    struct snapshot_segment *source, *target;
    struct snapshot_resource *resource;
    struct snapshot_relocation *relocation, *other;
    size_t width;
    if (!owner || owner->phase != SNAPSHOT_BUILD || (offset & 3u) ||
        (kind != OPENGPU_PRIVATE_SNAPSHOT_VA32 && kind != OPENGPU_PRIVATE_SNAPSHOT_LIMIT33))
        return -EINVAL;
    source = snapshot_segment_find(owner, source_id);
    target = external ? NULL : snapshot_segment_find(owner, target_id);
    resource = external ? snapshot_resource_find(owner, target_id) : NULL;
    width = kind == OPENGPU_PRIVATE_SNAPSHOT_LIMIT33 ? 8u : 4u;
    if (!source || (!target && !resource) || source->bytes < width ||
        offset > source->bytes - width || addend > (external ? resource->bytes : target->bytes))
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
    relocation->resource = resource;
    relocation->offset = offset; relocation->addend = addend; relocation->kind = kind;
    relocation->next = owner->relocations;
    owner->relocations = relocation;
    return 0;
}

int opengpu_private_snapshot_relocate(struct opengpu_private_snapshot *owner,
    u32 source_id, size_t offset, u32 target_id, u64 addend,
    enum opengpu_private_snapshot_relocation kind)
{
    return snapshot_relocate(owner, source_id, offset, target_id, addend, kind, false);
}

int opengpu_private_snapshot_relocate_resource(struct opengpu_private_snapshot *owner,
    u32 source_id, size_t offset, u32 target_id, u64 addend,
    enum opengpu_private_snapshot_relocation kind)
{
    return snapshot_relocate(owner, source_id, offset, target_id, addend, kind, true);
}

int opengpu_private_snapshot_prepare(struct opengpu_private_snapshot *owner)
{
    struct snapshot_segment *segment;
    struct snapshot_relocation *relocation;
    struct snapshot_resource *resource;
    struct opengpu_device *gpu;
    int ret;
    if (!owner || owner->phase != SNAPSHOT_BUILD || !owner->segments)
        return -EINVAL;
    gpu = owner->gpu;
    /* Close mutation even on failure; the caller must retire partial state. */
    owner->phase = SNAPSHOT_FAILED;
    /* Producer dependencies have completed. Reserve every envelope, including
     * its gaps, before installing any descriptor address. These mappings borrow
     * mutable GEM pages; only the private segments below obtain PA leases. */
    for (resource = owner->resources; resource; resource = resource->next) {
        struct snapshot_resource_map *map;
        if (!resource->maps)
            return -EINVAL;
        ret = opengpu_mmu_vm_reserve(gpu, &owner->context->vm,
            resource->bytes, &resource->reservation);
        if (ret)
            return ret;
        for (map = resource->maps; map; map = map->next) {
            ret = opengpu_mmu_vm_map_reserved(gpu, &resource->reservation,
                map->offset, map->physical, map->bytes,
                OPENGPU_MMU_POLICY_UNCACHED, map->writable);
            if (ret)
                return ret;
            map->mapped = true;
        }
    }
    for (segment = owner->segments; segment; segment = segment->next) {
        /* This trusted path allocates fresh pages, not an ordinary/Bare
         * writer. Existing owners may remain leased: their mappings and PA
         * leases still reject every alias to these new allocations. All source
         * waits have already completed, and wait_idle excludes older writers
         * before acquiring the new lease and copying any private bytes. */
        mutex_lock(&gpu->hw.submit_lock);
        ret = opengpu_hw_wait_idle_locked(gpu);
        if (ret) {
            mutex_unlock(&gpu->hw.submit_lock);
            return ret;
        }
        ret=opengpu_buffer_alloc(gpu,&segment->backing,segment->allocation_bytes);
        if(!ret && ((segment->backing.dma&(OPENGPU_MMU_PAGE_SIZE-1u)) ||
            (u64)segment->backing.dma>(1ULL<<32)-segment->allocation_bytes))ret=-ERANGE;
        if(!ret)ret=snapshot_acquire(owner,segment);
        if(!ret){
            // Existing writers have drained and all new ordinary writers are
            // excluded before any trusted descriptor/code/uniform bytes copy.
            memcpy(segment->backing.cpu,segment->staging,segment->allocation_bytes);
            dma_wmb();kvfree(segment->staging);segment->staging=NULL;
        }
        mutex_unlock(&gpu->hw.submit_lock);
        if(ret)return ret;
        if (segment->executable)
            ret = opengpu_mmu_vm_map_code_snapshot_anywhere(gpu, &owner->context->vm,
                segment->backing.dma, segment->backing.size, OPENGPU_MMU_POLICY_UNCACHED,
                &segment->va);
        else
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
    const struct snapshot_resource *resource;
    int ret;
    if (!owner || owner->gpu != gpu || owner->context != context ||
        owner->phase != SNAPSHOT_PUBLISHED)
        return -EINVAL;
    lockdep_assert_held(&gpu->hw.submit_lock);
    for (resource = owner->resources; resource; resource = resource->next) {
        const struct snapshot_resource_map *map;
        if (!resource->reservation.active ||
            resource->reservation.owner != &context->vm ||
            resource->reservation.size != resource->bytes)
            return -EINVAL;
        for (map = resource->maps; map; map = map->next) {
            if (!map->mapped)
                return -EINVAL;
            ret = opengpu_mmu_vm_verify_reserved_locked(gpu,
                &resource->reservation, map->offset, map->physical, map->bytes,
                OPENGPU_MMU_POLICY_UNCACHED, map->writable);
            if (ret)
                return ret;
        }
    }
    for (segment = owner->segments; segment; segment = segment->next) {
        if (!segment->mapped || !segment->lease.active || !segment->lease.mapped ||
            segment->lease.owner != &context->vm || segment->lease.va != segment->va ||
            segment->lease.base != segment->backing.dma ||
            segment->lease.size != segment->backing.size ||
            segment->lease.executable != segment->executable)
            return -EINVAL;
        if (segment->executable)
            ret = opengpu_mmu_verify_code_snapshot_locked(gpu, &context->vm, segment->va,
                segment->backing.dma, segment->backing.size);
        else
            ret = opengpu_mmu_verify_snapshot_locked(gpu, &context->vm, segment->va,
                segment->backing.dma, segment->backing.size);
        if (ret)
            return ret;
    }
    return 0;
}

int opengpu_private_snapshot_invalidate_locked(const struct opengpu_private_snapshot *owner,
    struct opengpu_device *gpu, const struct opengpu_render_context *context)
{
    const struct snapshot_segment *segment;
    int ret = opengpu_private_snapshot_verify_locked(owner, gpu, context);
    if (ret)
        return ret;
    for (segment = owner->segments; segment; segment = segment->next) {
        ret = opengpu_hw_invalidate_buffer_locked(gpu, &segment->backing);
        if (ret)
            return ret;
    }
    return 0;
}

int opengpu_private_snapshot_native_job_address(const struct opengpu_private_snapshot *owner,
    u32 id, u32 *address)
{
    static const u32 header[] = { OPENGPU_NATIVE_BINNED_JOB_MAGIC,
        OPENGPU_NATIVE_BINNED_JOB_BYTES, OPENGPU_NATIVE_BINNED_VERSION,
        16, 16, 16, 4, 4 };
    const struct snapshot_segment *segment;
    u32 word, i;
    if (!owner || !address || owner->phase != SNAPSHOT_PUBLISHED)
        return -EINVAL;
    segment = snapshot_segment_find(owner, id);
    if (!segment || segment->executable || !segment->mapped ||
        segment->bytes != OPENGPU_NATIVE_BINNED_JOB_BYTES)
        return -EINVAL;
    for (i = 0; i < OPENGPU_NATIVE_BINNED_JOB_WORDS; ++i) {
        int ret = opengpu_private_snapshot_read32(owner, id, i * 4u, &word);
        if (ret)
            return ret;
        if ((i < 8 && word != header[i]) || ((i >= 8 && i < 16) && word) ||
            (i >= 27 && word) ||
            (i == OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_CONTEXT_WORD &&
             word != owner->context->id))
            return -EINVAL;
    }
    return opengpu_private_snapshot_address(owner, id, address);
}

int opengpu_private_snapshot_retire(struct opengpu_private_snapshot *owner)
{
    struct snapshot_segment *segment;
    struct snapshot_relocation *relocation;
    struct snapshot_resource *resource;
    struct opengpu_device *gpu;
    int ret;
    if (!owner)
        return 0;
    gpu = owner->gpu;
    for(segment=owner->segments;segment && !segment->backing.cpu;segment=segment->next){}
    for (resource = owner->resources; resource && !resource->reservation.active;
         resource = resource->next) {}
    if(segment || resource){
        mutex_lock(&gpu->hw.submit_lock);
        ret = opengpu_hw_wait_idle_locked(gpu);
        mutex_unlock(&gpu->hw.submit_lock);
        if(ret)goto quarantine;
    }
    /* A failed prepare can leave an unleased allocation. Prove that no VM
     * still maps it before freeing, too; an unexpected pre-existing alias
     * must quarantine the owner rather than become a dangling mapping. */
    for (segment = owner->segments; segment; segment = segment->next) {
        if (segment->lease.active || !segment->backing.cpu)
            continue;
        mutex_lock(&gpu->hw.submit_lock);
        ret = snapshot_acquire(owner, segment);
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
    for (resource = owner->resources; resource; resource = resource->next) {
        struct snapshot_resource_map *map;
        for (map = resource->maps; map; map = map->next) {
            if (!map->mapped)
                continue;
            ret = opengpu_mmu_vm_unmap_reserved(gpu, &resource->reservation,
                map->offset, map->bytes);
            if (ret)
                goto quarantine;
            map->mapped = false;
        }
    }
    /* A resource unmap failure must retain every private lease and backing too.
     * Release reservations only after all mappings in the owner are revoked. */
    for (resource = owner->resources; resource; resource = resource->next) {
        if (!resource->reservation.active)
            continue;
        ret = opengpu_mmu_vm_release_reservation(gpu, &resource->reservation);
        if (ret)
            goto quarantine;
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
        kvfree(segment->staging);
        kfree(segment);
    }
    while ((relocation = owner->relocations)) {
        owner->relocations = relocation->next;
        kfree(relocation);
    }
    while ((resource = owner->resources)) {
        struct snapshot_resource_map *map;
        owner->resources = resource->next;
        while ((map = resource->maps)) {
            resource->maps = map->next;
            kfree(map);
        }
        kfree(resource);
    }
    opengpu_context_put(owner->context);
    kfree(owner);
    return 0;
quarantine:
    owner->phase = SNAPSHOT_FAILED;
    WRITE_ONCE(gpu->hw.wedged, true);
    return ret;
}
