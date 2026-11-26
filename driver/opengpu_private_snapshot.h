/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_PRIVATE_SNAPSHOT_H
#define OPENGPU_PRIVATE_SNAPSHOT_H

#include <linux/types.h>

struct opengpu_device;
struct opengpu_render_context;
struct opengpu_private_snapshot;

/* Borrowed original-GEM pages; the enclosing job retains their references and
 * reservation fences. Register complete page-aligned envelopes and disjoint
 * mapped subranges while building. Unmapped gaps remain reserved. No resource
 * mapping is executable and none makes CPU-written GEM bytes immutable. */
int opengpu_private_snapshot_resource(struct opengpu_private_snapshot *owner,
    size_t envelope_bytes, u32 *resource);
int opengpu_private_snapshot_resource_map(struct opengpu_private_snapshot *owner,
    u32 resource, size_t offset, u64 physical, size_t bytes, bool writable);

/* Kernel-only private publication. No CPU pointer, DMA address, GEM handle or
 * mmap/export operation is returned. Segment IDs name copies, not caller
 * buffers. Append keeps kernel-only virtual staging with no GPU address; prepare
 * first seals physical pages, then copies staging into them. The owner retains
 * a context reference through successful retirement.
 * Number and size of segments follow checked allocation/address-space bounds;
 * there is no fixed draw/state/resource count smaller than the native ABI. */
struct opengpu_private_snapshot *opengpu_private_snapshot_create(
    struct opengpu_device *gpu, struct opengpu_render_context *context);
/* Typed builders may only append for their accepted context in BUILD. */
int opengpu_private_snapshot_build_context(const struct opengpu_private_snapshot *owner,u32 context);
int opengpu_private_snapshot_append(struct opengpu_private_snapshot *owner,
    const void *stable_kernel_bytes, size_t bytes, u32 *segment);
/* Instruction bytes are copied exactly and mapped R+X without W. Descriptor
 * and uniform segments keep the default R-only, non-executable permission. */
int opengpu_private_snapshot_append_code(struct opengpu_private_snapshot *owner,
    const void *stable_kernel_bytes, size_t bytes, u32 *segment);

enum opengpu_private_snapshot_relocation {
    OPENGPU_PRIVATE_SNAPSHOT_VA32,
    /* Two adjacent little-endian words, canonical upper word 0 or 1. */
    OPENGPU_PRIVATE_SNAPSHOT_LIMIT33,
};

/* Source offsets are byte offsets, aligned to four bytes. Addends are bounded
 * by the target segment's logical byte length. LIMIT33 may equal exactly 4GiB;
 * VA32 may not. All relocation writes precede publication. */
int opengpu_private_snapshot_relocate(struct opengpu_private_snapshot *owner,
    u32 source_segment, size_t source_offset, u32 target_segment, u64 addend,
    enum opengpu_private_snapshot_relocation kind);
/* Kernel producer chooses generated fields and validates the logical view;
 * userspace never supplies this relocation or an arbitrary patch offset. */
int opengpu_private_snapshot_relocate_resource(struct opengpu_private_snapshot *owner,
    u32 source_segment, size_t source_offset, u32 resource, u64 addend,
    enum opengpu_private_snapshot_relocation kind);
int opengpu_private_snapshot_read32(const struct opengpu_private_snapshot *owner,
    u32 segment, size_t byte_offset, u32 *value);

/* Called after producer dependencies have completed. Allocate free private VA
 * ranges, seal physical leases, install owner-only RO/NX mappings, then copy staged bytes and apply
 * checked relocations. The submitted packet cannot run before this succeeds.
 * Append/relocation registration is then permanently closed. */
int opengpu_private_snapshot_prepare(struct opengpu_private_snapshot *owner);
/* New GNQ4..6 only: exact prepared root/owner scope, bound private envelopes,
 * actual live leaves and all segment/resource VAs outside fixed LDS. Status
 * only, before publish. Any failure closes publication; retire whole owner. */
int opengpu_private_snapshot_check_prepared_joint(struct opengpu_private_snapshot *owner,u32 segment);
/* GNQ7..9/GGA1: prepared-owner and actual attachment/program link validation.
 * Process context; full-record scratch may sleep. ENOMEM also closes publish. */
int opengpu_private_snapshot_check_prepared_joint_geometry(struct opengpu_private_snapshot *owner,u32 segment);
int opengpu_private_snapshot_address(const struct opengpu_private_snapshot *owner,
    u32 segment, u32 *address);

/* Device publication freezes the owner; only the native verified enqueue path
 * may consume it. Retirement waits for all hardware ownership to drain, revokes
 * every RO mapping and releases every lease before freeing anything. A failed
 * idle wait/unmap/shootdown retains the entire owner for quarantine; the caller
 * must retain its job too. The same retirement handles pre-enqueue failures. */
int opengpu_private_snapshot_publish(struct opengpu_private_snapshot *owner);
/* Native enqueue calls this under submit_lock immediately before staging its
 * command. It accepts only this published owner/context and exact live leaves. */
int opengpu_private_snapshot_verify_locked(const struct opengpu_private_snapshot *owner,
    struct opengpu_device *gpu, const struct opengpu_render_context *context);
/* Internal verified publisher only. All owner mappings/leases are checked
 * before the first cache operation; no backing pointer or DMA address escapes
 * to the caller. Invalidations use the real synchronous hardware path. */
int opengpu_private_snapshot_invalidate_locked(const struct opengpu_private_snapshot *owner,
    struct opengpu_device *gpu, const struct opengpu_render_context *context);
/* Exact RO/NX GNJ1 view, after publication. This validates the root envelope;
 * the kernel producer separately rebuilds every referenced private record and
 * registers its resources. It is not admission of an untrusted record graph. */
int opengpu_private_snapshot_native_job_address(const struct opengpu_private_snapshot *owner,
    u32 segment, u32 *address);
/* Explicit GNJ2 only; additionally proves the complete GNY2 view is in this
 * published owner's writable resource mapping. Legacy entry rejects GNJ2. */
int opengpu_private_snapshot_native_layered_job_address(const struct opengpu_private_snapshot *owner,
    u32 segment,u32 *address);
/* Exact GNQ1/2/3 root, with owner-private frame/sequence RO envelopes. Old
 * GNJ accessors continue to reject it. New trusted builder owns full semantics. */
int opengpu_private_snapshot_protected_tessellation_job_address(const struct opengpu_private_snapshot *owner,
    u32 segment,u32 *address);
/* Protected GNQ4/5/6 launch parameters from a live, published private owner.
 * The enclosing job keeps the actual mappings through hardware retirement.
 * The owner envelope bounds address arithmetic and does not authorize holes. */
struct opengpu_private_snapshot_joint_request {
    u64 job_limit, job_allocation_limit, owner_limit;
    u32 job_base, job_allocation_base, owner_base;
    u32 domain, context, pass, generation;
};
/* Kernel-only, under submit_lock. The exact root must have passed the joint
 * prepare gate. Recheck every live leaf and leave request untouched on error. */
int opengpu_private_snapshot_joint_request_locked(
    const struct opengpu_private_snapshot *owner, struct opengpu_device *gpu,
    const struct opengpu_render_context *context, u32 segment,
    struct opengpu_private_snapshot_joint_request *request);
int opengpu_private_snapshot_retire(struct opengpu_private_snapshot *owner);

#endif
