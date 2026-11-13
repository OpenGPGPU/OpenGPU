/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_PRIVATE_SNAPSHOT_H
#define OPENGPU_PRIVATE_SNAPSHOT_H

#include <linux/types.h>

struct opengpu_device;
struct opengpu_render_context;
struct opengpu_private_snapshot;

/* Kernel-only private publication. No CPU pointer, DMA address, GEM handle or
 * mmap/export operation is returned. Segment IDs name copies, not caller
 * buffers. The owner retains a context reference through successful retirement.
 * Number and size of segments follow checked allocation/address-space bounds;
 * there is no fixed draw/state/resource count smaller than the native ABI. */
struct opengpu_private_snapshot *opengpu_private_snapshot_create(
    struct opengpu_device *gpu, struct opengpu_render_context *context);
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
int opengpu_private_snapshot_read32(const struct opengpu_private_snapshot *owner,
    u32 segment, size_t byte_offset, u32 *value);

/* Called after producer dependencies have completed. Allocate free private VA
 * ranges, seal physical leases, install owner-only RO/NX mappings, then apply
 * checked relocations. The submitted packet cannot run before this succeeds.
 * Append/relocation registration is then permanently closed. */
int opengpu_private_snapshot_prepare(struct opengpu_private_snapshot *owner);
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
int opengpu_private_snapshot_retire(struct opengpu_private_snapshot *owner);

#endif
