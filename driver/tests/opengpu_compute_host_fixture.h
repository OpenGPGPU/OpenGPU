/* CPU OS boundary for the actual Linux compute/MMU submission path.
 * Raw profile words must originate from the executing DUT in qualification.
 * These functions never execute a shader or manufacture output bytes. */
#ifndef OPENGPU_COMPUTE_HOST_FIXTURE_H
#define OPENGPU_COMPUTE_HOST_FIXTURE_H
#include <stddef.h>
#include <stdint.h>
#include "../opengpu_drm.h"
struct compute_host_session;
struct compute_host_profile_word { uint32_t address,value; };
/* Strict OPENGPU_COMPUTE_HOST_TIMEOUT seconds, default300, maximum7days.
 * Zero reports invalid configuration; active launchers pass their CLI bound. */
unsigned compute_host_timeout_seconds(void);
struct compute_host_session *compute_host_create(uint32_t context,
    const struct compute_host_profile_word *,size_t count);
/* A real render-file open has no context until the caller creates one. */
struct compute_host_session *compute_host_open(const struct compute_host_profile_word *,size_t count);
/* Explicit GCI1 platform declaration, using the same complete112-word factory
 * and ten auxiliary observations. Legacy opens never enable this transport. */
struct compute_host_session *compute_host_open_indirect(const struct compute_host_profile_word *,size_t count);
/* Select a private CPU allocator namespace before any owner allocation. Actual
 * GEM/MMU addresses still come from the original buffer-allocation primitive. */
int compute_host_allocation_base(uint64_t physical);
int compute_host_query(struct compute_host_session *,uint32_t param,uint64_t *value);
int compute_host_original_close(struct compute_host_session *,uint32_t handle);
int compute_host_context_destroy(struct compute_host_session *,uint32_t id);
int compute_host_context_create(struct compute_host_session *,uint32_t *id);
int compute_host_submit(struct compute_host_session *,const struct drm_opengpu_compute *);
int compute_host_submit_storage(struct compute_host_session *,const struct drm_opengpu_compute_storage *);
int compute_host_submit_images(struct compute_host_session *,const struct drm_opengpu_compute_images *);
int compute_host_submit_image_resources(struct compute_host_session *,const struct drm_opengpu_compute_image_resources *);
int compute_host_submit_resources(struct compute_host_session *,const struct drm_opengpu_compute_resources *);
/* Texture COMPUTE_RESOURCES burns one extra unified DMA before the compute
 * kick versus IMAGE_RESOURCES. Advance the allocator so a later image producer
 * on the same publication matrix keeps the warm/reload command identity. */
int compute_host_align_texture_publication_command(struct compute_host_session *);
uint32_t compute_host_context_id(const struct compute_host_session *);
void *compute_host_original_allocate(struct compute_host_session *,uint32_t handle,uint64_t page_bytes);
int compute_host_original_physical(struct compute_host_session *,uint32_t handle,uint64_t *physical,uint64_t *bytes);
int compute_host_bind(struct compute_host_session *,const struct drm_opengpu_resource *);
int compute_host_unbind(struct compute_host_session *,uint32_t slot);
/* Admission only: the real Linux handler snapshots source bytes and retains
 * dependencies. No instruction execution or completion occurs here. */
struct compute_host_snapshot {
    const void *code,*storage,*uniforms,*images,*textures;
    uint64_t code_bytes,storage_bytes,uniform_bytes,image_bytes,texture_bytes;
    uint64_t code_physical,storage_physical,uniform_physical,image_physical,texture_physical;
    uint32_t storage_root,uniform_root,retained_objects,temporary_mappings;
    uint64_t counter_physical,counter_bytes,counter_descriptor_physical,kernarg_physical,kernarg_bytes;
    uint32_t counter_root,context,image_root,texture_root;
};
int compute_host_submit_counter_source(struct compute_host_session *,const struct drm_opengpu_compute_counter_source_resources *);
int compute_host_submit_uniforms(struct compute_host_session *,const struct drm_opengpu_compute_uniform_resources *);
int compute_host_submit_indirect(struct compute_host_session *,const struct drm_opengpu_compute_indirect_resources *);
struct compute_host_indirect_snapshot {
    struct compute_host_snapshot resources;
    const void *arguments;
    uint64_t source_physical,source_bytes,source_offset;
    uint64_t snapshot_physical,snapshot_bytes;
    uint64_t original_kernarg_physical,original_kernarg_bytes;
    uint32_t source_handle,context,snapshot_va,snapshot_offset;
    uint32_t max_groups[3],grid[3];
};
int compute_host_indirect_snapshot_get(struct compute_host_session *,struct compute_host_indirect_snapshot *);
/* Map the real job windows and record the actual hardware submission. The
 * CPU transport leaves the selected kernel pending until an actual-DUT receipt arrives. */
int compute_host_begin_execution(struct compute_host_session *);
int compute_host_snapshot_get(struct compute_host_session *,struct compute_host_snapshot *);
/* Scheduler cancellation before a captured hardware submission only. */
int compute_host_cancel_unexecuted(struct compute_host_session *);
struct compute_host_held_receipt {
    uint32_t command_id,completion;
    uint64_t processed_bytes;
    uint32_t legacy_status,status,irq,irq_asserted;
};
struct compute_host_retired_receipt {
    struct compute_host_held_receipt held;
    uint32_t status,irq,irq_asserted,external_drained;
};
/* Reset cancellation has no owned kernel completion slot and must never be
 * represented by a success or execution-failed completion receipt. */
struct compute_host_reset_receipt {
    uint32_t command_id,legacy_status,status,irq,irq_asserted,external_drained;
};
int compute_host_abort_captured(struct compute_host_session *,const struct compute_host_reset_receipt *,const char *directory);
int compute_host_drain_reset(struct compute_host_session *,const struct compute_host_reset_receipt *,const char *retire_trace);
int compute_host_observe_reset_retired(struct compute_host_session *,const struct compute_host_reset_receipt *);
int compute_host_capture(struct compute_host_session *,const char *path,uint64_t budget,uint32_t hold);
/* Distinct OGCI0001 capture; the legacy OGCMP writer refuses indirect jobs. */
int compute_host_capture_indirect(struct compute_host_session *,const char *path,uint64_t budget,uint32_t hold);
int compute_host_drain_held(struct compute_host_session *,const struct compute_host_held_receipt *,const char *retire_trace);
int compute_host_observe_retired(struct compute_host_session *,const struct compute_host_retired_receipt *);
/* Distinct execution-failed path. Only an exact opcode-0 execution-failure
 * receipt may signal an EIO fence; ordinary success entry points reject it. */
int compute_host_drain_failed(struct compute_host_session *,const struct compute_host_held_receipt *,const char *retire_trace);
int compute_host_observe_failed_retired(struct compute_host_session *,const struct compute_host_retired_receipt *);
/* result_status is exactly0, execution-failed1, GCI bounds12 or GCI read13.
 * These entry points require opcode14's extended completion encoding. */
int compute_host_drain_indirect(struct compute_host_session *,const struct compute_host_held_receipt *,const char *retire_trace,uint32_t result_status);
int compute_host_observe_indirect_retired(struct compute_host_session *,const struct compute_host_retired_receipt *,uint32_t result_status);
/* Observed hardware and scheduler fence status, before source cleanup. */
int compute_host_completion_status(struct compute_host_session *,int32_t *status);
/* Separate explicit all-byte-oracle authorization, not a completion receipt. */
int compute_host_begin_cleanup(struct compute_host_session *,const char *session_directory);
int compute_host_finish_observed(struct compute_host_session *);
int compute_host_finish_failed_observed(struct compute_host_session *);
int compute_host_finish_cancelled_observed(struct compute_host_session *);
int compute_host_finish_indirect_observed(struct compute_host_session *,uint32_t result_status);
int compute_host_end_cleanup(struct compute_host_session *);
/* Continuous opcode0/14 owners retain the original render file/GEM/fences.
 * Import only externally observed, independently checked DUT bytes. The input
 * must name every live original exactly, preserving all read-only bytes. */
int compute_host_export_originals(struct compute_host_session *,const char *path);
int compute_host_import_originals(struct compute_host_session *,const char *path);
int compute_host_sync_status(struct compute_host_session *,uint32_t handle,int32_t *status);
/* A distinct phase protocol: temporary-job retirement, ordinary caller-side
 * publication/context destruction, then native ACK. Whole-session cleanup
 * continues to require its original bare-SATP/MMU shutdown contract. */
int compute_host_begin_phase_cleanup(struct compute_host_session *,const char *directory);
int compute_host_release_phase_job(struct compute_host_session *);
int compute_host_phase_invalidate(struct compute_host_session *,const struct drm_opengpu_invalidate *);
int compute_host_end_phase_cleanup(struct compute_host_session *);
struct compute_host_phase_receipt {
 uint32_t command_id,context_id,epoch,batches;
 uint32_t status,irq,irq_asserted,external_drained,quiet_cycles,read_only_guards;
};
int compute_host_resume_phase(struct compute_host_session *,const struct compute_host_phase_receipt *);
int compute_host_export_memory(struct compute_host_session *,const char *path);
int compute_host_destroy(struct compute_host_session *);
#endif
