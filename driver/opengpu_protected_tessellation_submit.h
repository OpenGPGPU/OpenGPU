/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_PROTECTED_TESSELLATION_SUBMIT_H
#define OPENGPU_PROTECTED_TESSELLATION_SUBMIT_H
#include "opengpu_protected_tessellation.h"
/* Internal request layout only: no ioctl number is registered. Source pointers
 * are copied exactly once by the Linux admission body. Strides freeze the typed
 * plans independently of userspace compiler padding or a legacy GNQ submit. */
struct opengpu_protected_tessellation_request {
    u32 version, context_id, flags, in_syncobj, out_syncobj, pad;
    u64 job, frame, sequences, sequences_bytes, states, states_bytes;
    u64 plans, state_plans, resources;
    u32 plan_count, plan_stride, state_plan_count, state_plan_stride;
    u32 resource_count, resource_stride;
    u64 fence_seqno, reserved[2];
};
struct drm_device;
struct drm_file;
struct opengpu_sched_job;
/* Current production getter always fails without changing the output. A future
 * implementation needs a verified exact provider/dialect/route/MMU contract. */
int opengpu_hw_protected_tessellation_profile(struct opengpu_device *gpu,
    struct opengpu_protected_tessellation_profile *profile);
int opengpu_protected_tessellation_ioctl(struct drm_device *drm,void *data,struct drm_file *file);
struct dma_fence *opengpu_protected_tessellation_job_run(struct opengpu_sched_job *job);
#endif
