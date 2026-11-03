/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_SCHEDULER_H
#define OPENGPU_SCHEDULER_H
#include <linux/dma-resv.h>
#include <linux/idr.h>
#include <linux/kref.h>
#include <drm/drm_file.h>
#include <drm/drm_gem.h>
#include <drm/drm_syncobj.h>
#include "opengpu_device.h"
#include "opengpu_drm.h"

struct opengpu_render_context {
    struct kref refcount;
    struct opengpu_device *gpu;
    u32 id;
    struct drm_sched_entity entity;
    struct dma_fence *last_fence;
    struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_BINDINGS];
    /* Address space every job from this context runs in.  Disabled on
     * builds without the MMU control path (Bare), which then use ASID 0. */
    struct opengpu_vm vm;
};

struct opengpu_resource_binding {
    struct drm_gem_object *object;
    dma_addr_t dma;
    u64 offset;
    u64 size;
    u32 type;
    u32 width;
    u32 height;
    u32 flags;
    /* Private virtual address in this context's VM that `dma` is mapped at;
     * 0 means the binding is only reachable at its physical address. */
    dma_addr_t va;
};

enum opengpu_sched_job_type {
    OPENGPU_SCHED_RENDER,
    OPENGPU_SCHED_COMPUTE,
    OPENGPU_SCHED_BLIT,
    OPENGPU_SCHED_FILL,
    OPENGPU_SCHED_STRIDED_BLIT,
    OPENGPU_SCHED_RESOLVE,
    OPENGPU_SCHED_INVALIDATE,
    OPENGPU_SCHED_TESSELLATION,
};

/* A render job maps command, code, framebuffer and descriptor windows; a DMA
 * job maps source and destination windows. The scheduler's ordered free-job
 * worker drains all of them before the next run-job worker reuses the same
 * fixed VA. */
#define OPENGPU_MAX_TEMP_MAPPINGS 8

struct opengpu_job_temporary_mapping {
    dma_addr_t va;
    size_t size;
};

struct opengpu_sched_job {
    struct drm_sched_job base;
    struct opengpu_device *gpu;
    enum opengpu_sched_job_type type;
    /* Address space of the submitting context, or NULL for the global ASID-0
     * identity map (bring-up self-test). */
    const struct opengpu_vm *vm;
    /* Owning context, for mappings that must be (re)established at run time
     * once the previous job has drained (per-job command snapshot). */
    struct opengpu_render_context *context;
    dma_addr_t dma_source;
    dma_addr_t dma_destination;
    u32 dma_bytes;
    u32 dma_height;
    u32 dma_source_stride;
    u32 dma_destination_stride;
    u32 dma_sample_mode;
    u32 fill_pattern;
    struct opengpu_command_events events;
    struct opengpu_kernel_launch kernel;
    struct opengpu_job hw;
    struct opengpu_buffer commands;
    /* 16-word unified render descriptor, filled at run time and submitted by
     * virtual address so the device fetches it through the context VM. */
    struct opengpu_buffer render_desc;
    struct opengpu_buffer shader;
    /* Kernel-owned compute texture records, retained through retirement. */
    struct opengpu_buffer texture_records;
    /* Job-owned GSB1 table, retained until completion and VM unmap. */
    struct opengpu_buffer storage_records;
    /* Job-owned GIU1 records; mappings and allocation outlive execution. */
    struct opengpu_buffer image_records;
    /* Job-owned GAM1 table; retained until VM revocation completes. */
    struct opengpu_buffer counter_records;
    /* GNQ/GNF/GNT snapshots and their fixed private VM addresses. */
    struct opengpu_buffer tess_job;
    struct opengpu_buffer tess_frame;
    struct opengpu_buffer tess_draws;
    u32 tess_job_va;
    u32 tess_frame_va;
    u32 tess_draws_va;
    struct opengpu_tessellation_scope tess_scope;
    struct opengpu_buffer vertex_shader;
    struct opengpu_buffer depth;
    /* Depth-plane clear staging. A user-supplied persistent attachment is not
     * owned by the job (so `depth` stays empty), but the pass-start clear and
     * the CPU fallback still target its range. */
    bool depth_user;
    bool depth_load;
    bool depth_discard;
    dma_addr_t depth_clear_dma;
    size_t depth_clear_bytes;
    u8 *depth_clear_cpu;
    /* One retained reference per scheduler dependency, released on retirement. */
    struct drm_gem_object **objects;
    u32 object_count;
    /* Private VM windows mapped at run time for this job only; revoked when
     * the scheduler retires the job, before the next job reuses the window. */
    struct opengpu_job_temporary_mapping
        temporary_mappings[OPENGPU_MAX_TEMP_MAPPINGS];
    u32 temporary_mapping_count;
};

struct opengpu_file {
    struct opengpu_device *gpu;
    struct mutex lock;
    struct idr contexts;
};

/* One scheduler job dependency: the object the job waits on, the usage it
 * waits for and the usage its completion fence is published under.  The wait
 * and signal usages differ deliberately - a blit waits on source writes yet
 * publishes a source read fence, and waits on destination reads yet publishes
 * a destination write fence. */
struct opengpu_sched_dep {
    struct drm_gem_object *object;
    enum dma_resv_usage wait_usage;
    enum dma_resv_usage signal_usage;
};

/* Trusted kernel snapshots, never userspace pointers. The caller must validate
 * every referenced resource and supply all of its reservation dependencies;
 * those reservations and the context must remain locked through enqueue. */
struct opengpu_tessellation_job_input {
    const void *job;
    size_t job_size;
    const void *frame;
    size_t frame_size;
    const void *draws;
    size_t draws_size;
    struct opengpu_tessellation_scope scope;
};

void opengpu_context_get(struct opengpu_render_context *context);
void opengpu_context_put(struct opengpu_render_context *context);
int opengpu_sched_init(struct opengpu_device *gpu);
void opengpu_sched_fini(struct opengpu_device *gpu);
struct dma_fence *opengpu_job_run(struct opengpu_sched_job *job);
int opengpu_job_track_mapping(struct opengpu_sched_job *job,
                              dma_addr_t va, size_t size);
struct dma_fence *opengpu_tessellation_job_run(struct opengpu_sched_job *job);
void opengpu_tessellation_job_release(struct opengpu_sched_job *job);
int opengpu_tessellation_sched_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context,
    const struct opengpu_tessellation_job_input *input,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno);
int opengpu_sched_job_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context, struct opengpu_sched_job *job,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno);
#endif
