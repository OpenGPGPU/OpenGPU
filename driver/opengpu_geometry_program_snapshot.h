/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_GEOMETRY_PROGRAM_SNAPSHOT_H
#define OPENGPU_GEOMETRY_PROGRAM_SNAPSHOT_H
#include "opengpu_native_program_snapshot.h"
struct opengpu_geometry_program_profile {
    u32 version;
    bool geometry_enabled,scalar_fpu,owned_uniforms;
};
/* Opt-in scalar GS program transport. This initial dialect is not the full
 * geometry stage resource/API contract. It has no writable ordinary memory;
 * captured geometry and output events use the task-owned scalar sideband.
 * owned_uniforms requires this owner's verified immutable GUR2/GUB2 data.
 * The caller retains original local GEM references and waits producers before
 * copy_source. Validate only the executable suffix but preserve all source
 * bytes in an RX/no-W private segment, exactly as the VS/FS helper does.
 * Output is unchanged on failure; retire the entire unpublished owner.
 */
int opengpu_geometry_program_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_program_view *view,
    const struct opengpu_geometry_program_profile *profile,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_native_program_snapshot *output);
#endif
