/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_NATIVE_FRAME_SNAPSHOT_H
#define OPENGPU_NATIVE_FRAME_SNAPSHOT_H
#include <linux/types.h>
#include "opengpu_native_frame_abi.h"
struct opengpu_private_snapshot;

/* Resolve an already retained local original GEM. The caller owns the GEM,
 * producer waits and read/write reservation fences through job retirement. */
struct opengpu_native_frame_backing { u64 physical, bytes; };
typedef int (*opengpu_native_frame_resolve)(void *context, u32 handle,
    struct opengpu_native_frame_backing *backing);

/* All inputs are one-time kernel copies, never live userspace records. Address
 * words must be zero. Patch only generated fields selected by typed roles.
 * The referenced state segment is another private metadata copy, not a GEM.
 * Failure leaves output unchanged; discard/retire the unpublished owner after
 * any failure (including an allocation failure after partial registration).
 * This prepares publication, not full GNF semantic admission or submission. */
int opengpu_native_frame_snapshot_append(struct opengpu_private_snapshot *owner,
    const void *frame, u32 states_segment, size_t states_bytes,
    const struct opengpu_native_frame_resource *resources, u32 resource_count,
    u32 engines, u32 context, u32 pass, u32 generation,
    opengpu_native_frame_resolve resolve, void *resolve_context,
    u32 *frame_segment);

/* Complete GFS publication: read the already copied private state segment,
 * require its exact engine count and zero scratch addresses, then bind each
 * engine/state to its corresponding stride-sized slot in the same original
 * scratch resource used by GNF. The caller separately validates GFS semantics,
 * identity and the shader SoA prefix against scratchStride. Failure has the
 * same discard-unpublished-owner contract as append above. */
int opengpu_native_frame_snapshot_append_complete(struct opengpu_private_snapshot *owner,
    const void *frame, u32 states_segment, size_t states_bytes,
    const struct opengpu_native_frame_resource *resources, u32 resource_count,
    u32 engines, u32 context, u32 pass, u32 generation,
    opengpu_native_frame_resolve resolve, void *resolve_context,
    u32 *frame_segment);
/* Explicit GNF3 only. Admit all 256 layer intervals using original physical
 * ranges, preserving valid interleaved planes; otherwise the same complete
 * GFS/scratch/RO-RW owner contract as the legacy complete entry. */
int opengpu_native_frame_snapshot_append_layered_complete(struct opengpu_private_snapshot *owner,
    const void *frame,u32 states_segment,size_t states_bytes,
    const struct opengpu_native_frame_resource *resources,u32 resource_count,
    u32 engines,u32 context,u32 pass,u32 generation,
    opengpu_native_frame_resolve resolve,void *resolve_context,u32 *frame_segment);
#endif
