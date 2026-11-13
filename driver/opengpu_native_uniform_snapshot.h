/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_NATIVE_UNIFORM_SNAPSHOT_H
#define OPENGPU_NATIVE_UNIFORM_SNAPSHOT_H
#include <linux/types.h>
#include "opengpu_drm.h"

struct opengpu_private_snapshot;
struct opengpu_native_uniform_roots {
    u32 segment, root_offset[2];
    bool present[2];
};
/* The production submitter resolves original local GEMs, checks each complete
 * source view, waits producer reservations and retains their references before
 * calling this encoder. copy_source copies exactly the checked stable bytes;
 * it must never return a live CPU pointer or copy before producer completion.
 * The private aggregate re-aligns source windows (four-byte alignment) to GUC's
 * 256-byte UBO data alignment. Root/table/data addresses use owner relocations.
 * Failure leaves output unchanged; retire the entire owner after any failure.
 */
int opengpu_native_uniform_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_uniform_stage stages[2],
    u32 context, u32 pass, u32 generation,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_native_uniform_roots *output);
#endif
