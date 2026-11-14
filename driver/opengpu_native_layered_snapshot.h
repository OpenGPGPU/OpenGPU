/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_NATIVE_LAYERED_SNAPSHOT_H
#define OPENGPU_NATIVE_LAYERED_SNAPSHOT_H
#include "opengpu_native_job_snapshot.h"
/* Resolve the exact original completion backing and reject cache-line aliases
 * with every original graph resource and all three stages' complete code/UBO
 * source windows before any private append/copy. No raw VA is accepted. */
int opengpu_native_layered_completion_check(const struct opengpu_native_job_source *,
    opengpu_native_frame_resolve,void *);
/* Bind that same retained original backing RW to a copied GNJ2. The owner/VM
 * lease must survive every GNY write ACK and the held coarse completion. */
int opengpu_native_layered_completion_patch(struct opengpu_private_snapshot *,
    u32 job_segment,const struct opengpu_native_job_source *,opengpu_native_frame_resolve,void *);
#endif
