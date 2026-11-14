/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_TESSELLATION_UNIFORM_SNAPSHOT_H
#define OPENGPU_TESSELLATION_UNIFORM_SNAPSHOT_H
#include <linux/types.h>
#include "opengpu_drm.h"
struct opengpu_private_snapshot;
struct opengpu_tessellation_uniform_roots {
    u32 segment, root_offset[2], bytes;
    bool present[2];
};
/* Independent GUR3/GUB3 publication: stages[0] is TCS (4), stages[1] is TES
 * (5), never a caller-selected stage/magic/version. Capture both kernel plans
 * once before callbacks. Exact (handle,offset,bytes) block views share a private
 * table slot with the union of stage bits; defaults remain stage-specific.
 * Disabled roots/unused metadata are canonical zero. Full capacities remain
 * 12 logical blocks per stage, 24 table entries, 16KiB blocks/1024 defaults.
 *
 * The future protected GNQ submitter MUST resolve/retain original local GEMs,
 * check their complete logical extents and physical aliases, hold reservation
 * locks and wait producer fences BEFORE copy_source reads those stable bytes.
 * It must retain those refs through physical execution/translation retirement.
 * This encoder has no GEM lookup/fence/reference authority; callback errors
 * propagate. No source buffer may race the copy. Later source/plan mutations
 * cannot affect the private copy. Copied UBOs are realigned to 256 bytes; all
 * metadata/data uses the owner's ordinary R-only/NX segments and PA leases.
 * No raw GPU address, global API binding, writable view or sampler is accepted.
 *
 * Output is unchanged on failure. Retire the entire unpublished owner after
 * ANY failure, including a late relocation allocation failure after append.
 * This helper alone does not validate programs, admit GNQ/GNT, advertise a
 * capability, publish mappings, enqueue a job or authorize an ioctl.
 */
int opengpu_tessellation_uniform_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_uniform_stage stages[2],
    u32 context,u32 pass,u32 generation,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_tessellation_uniform_roots *output);
#endif
