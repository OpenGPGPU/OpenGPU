/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_NATIVE_PROGRAM_SNAPSHOT_H
#define OPENGPU_NATIVE_PROGRAM_SNAPSHOT_H
#include <linux/types.h>
#include "opengpu_drm.h"
struct opengpu_private_snapshot;
struct opengpu_native_program_profile {
    u64 staging_bytes;
    u32 capacity, stage, version, attributes, varyings, colors;
    bool scalar_fpu, owned_uniforms;
};
struct opengpu_native_program_snapshot {
    u32 segment, entry_offset;
};
/* The caller retains the original local GEM, checks its full view bounds and
 * waits all producer reservations before calling. copy_source reads exactly
 * those stable original bytes. owned_uniforms requires this job's verified
 * private GUR, not merely advertised uniform hardware. The native validator
 * derives its precise SoA writable prefix from the verified stage layout;
 * staging tails and all input/control slices stay read-only.
 *
 * Validate the executable suffix beginning at entry_offset, retaining the
 * validator's branch bounds relative to that suffix. Copy the entire original
 * view unchanged into a private RX/no-W segment. The producer relocates PC to
 * segment+entry_offset. This helper does not wait, map, publish or retire an
 * owner. Output is unchanged on failure; retire the owner on any build failure.
 */
int opengpu_native_program_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_program_view *view,
    const struct opengpu_native_program_profile *profile,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_native_program_snapshot *output);
#endif
