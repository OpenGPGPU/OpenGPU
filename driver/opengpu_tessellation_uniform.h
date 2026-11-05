/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_TESSELLATION_UNIFORM_H
#define OPENGPU_TESSELLATION_UNIFORM_H

#ifdef __KERNEL__
#include <linux/types.h>
typedef u64 opengpu_tess_uniform_u64;
#else
#include <stdint.h>
typedef uint64_t opengpu_tess_uniform_u64;
#endif
#include "opengpu_uniform_abi.h"

/* Internal stable kernel plans, not userspace pointers or a new UAPI.
 * Slots are one-based context COMPUTE_STORAGE binding slots. Offset and bytes
 * describe the logical view; the driver derives its complete line footprint. */
struct opengpu_tessellation_uniform_view {
    unsigned int slot;
    opengpu_tess_uniform_u64 offset, bytes;
};

struct opengpu_tessellation_uniform_plan {
    unsigned int state_handle;
    unsigned int active_mask;
    unsigned int block_map[OPENGPU_UNIFORM_BLOCKS_PER_STAGE];
    struct opengpu_tessellation_uniform_view bindings[OPENGPU_UNIFORM_BINDINGS];
    unsigned int default_present;
    struct opengpu_tessellation_uniform_view default_view;
};

struct opengpu_file;
struct drm_file;
struct opengpu_render_context;
struct opengpu_tessellation_job_input;
struct drm_syncobj;
struct opengpu_sched_dep;

/* Plans are sorted by unique state_handle and stable through this call. The
 * scheduler owns generated GUR/GUB metadata afterwards. Every reachable data
 * view needs its retained read reservation dependency. File/reservation locks,
 * data publication and all remaining texture/code/producer obligations of the
 * legacy trusted entry still apply. No arbitrary code admission is implied. */
int opengpu_tessellation_uniform_sched_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    const struct opengpu_tessellation_uniform_plan *plans, unsigned int plan_count,
    unsigned int in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, unsigned int dep_count,
    opengpu_tess_uniform_u64 *fence_seqno);

#endif
