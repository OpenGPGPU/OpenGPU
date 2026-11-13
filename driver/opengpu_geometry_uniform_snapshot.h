/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_GEOMETRY_UNIFORM_SNAPSHOT_H
#define OPENGPU_GEOMETRY_UNIFORM_SNAPSHOT_H
#include <linux/types.h>
#include "opengpu_drm.h"
struct opengpu_private_snapshot;
struct opengpu_geometry_uniform_snapshot {
    u32 segment, root_offset;
    bool present;
};
/* A separate stage-three GUR2/GUB2 provider; this does not enable native GS
 * admission or change the VS/FS/compute version-one contract. The caller pins
 * original GEM references, checks complete source extents and waits producer
 * fences before copy_source reads exactly the checked view. Source offsets
 * may be four-byte aligned; the private UBO data is realigned to 256 bytes.
 * Output is unchanged on failure. Retire the unpublished owner after any
 * failure, including a failure to register a relocation after append.
 */
int opengpu_geometry_uniform_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_uniform_stage *stage,
    u32 context,u32 pass,u32 generation,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_geometry_uniform_snapshot *output);
#endif
