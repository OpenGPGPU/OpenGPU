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
struct compute_host_session *compute_host_create(uint32_t context,
    const struct compute_host_profile_word *,size_t count);
void *compute_host_original_allocate(struct compute_host_session *,uint32_t handle,uint64_t page_bytes);
int compute_host_original_physical(struct compute_host_session *,uint32_t handle,uint64_t *physical,uint64_t *bytes);
int compute_host_bind(struct compute_host_session *,const struct drm_opengpu_resource *);
int compute_host_unbind(struct compute_host_session *,uint32_t slot);
/* Admission only: the real Linux handler snapshots source bytes and retains
 * dependencies. No instruction execution or completion occurs here. */
struct compute_host_snapshot {
    const void *code,*storage,*uniforms;
    uint64_t code_bytes,storage_bytes,uniform_bytes;
    uint64_t code_physical,storage_physical,uniform_physical;
    uint32_t storage_root,uniform_root,retained_objects,temporary_mappings;
};
int compute_host_submit_uniforms(struct compute_host_session *,const struct drm_opengpu_compute_uniform_resources *);
/* Map the real job windows and record the actual hardware submission. The
 * CPU transport leaves opcode0 pending until an actual-DUT receipt arrives. */
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
int compute_host_capture(struct compute_host_session *,const char *path,uint64_t budget,uint32_t hold);
int compute_host_drain_held(struct compute_host_session *,const struct compute_host_held_receipt *,const char *retire_trace);
int compute_host_observe_retired(struct compute_host_session *,const struct compute_host_retired_receipt *);
/* Separate explicit all-byte-oracle authorization, not a completion receipt. */
int compute_host_begin_cleanup(struct compute_host_session *,const char *session_directory);
int compute_host_finish_observed(struct compute_host_session *);
int compute_host_end_cleanup(struct compute_host_session *);
int compute_host_export_memory(struct compute_host_session *,const char *path);
int compute_host_destroy(struct compute_host_session *);
#endif
