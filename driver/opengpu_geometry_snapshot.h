/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_GEOMETRY_SNAPSHOT_H
#define OPENGPU_GEOMETRY_SNAPSHOT_H
#include <linux/types.h>
#include "opengpu_drm.h"
struct opengpu_private_snapshot;
struct opengpu_geometry_source {
    const void *metadata;
    size_t metadata_bytes;
    const struct drm_opengpu_native_program_view *program;
    const struct drm_opengpu_native_uniform_stage *uniforms;
    u32 context,pass,generation,topology,vertex_varyings,fragment_varyings;
    bool geometry_enabled,scalar_fpu;
};
/* Append a canonical GNG1 graph with actual private RX code and GUR2 data.
 * All raw GPU addresses (including uniform version/address) must be zero.
 * Expected identity/topology/layout comes from the accepted GND/FS state, not
 * from an unrelated request. Metadata and original source references remain
 * stable, producer-complete and retained by the caller; no GPU address escapes.
 * Output is unchanged on failure; retire the entire unpublished owner.
 * This component does not admit a GNJ, execute a GS or advertise a capability.
 */
int opengpu_geometry_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct opengpu_geometry_source *source,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,u32 *segment);
#endif
