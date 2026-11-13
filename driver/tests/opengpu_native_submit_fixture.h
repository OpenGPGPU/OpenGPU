/* Test OS boundary only; implementation invokes the real native ioctl/owner. */
#ifndef OPENGPU_NATIVE_SUBMIT_FIXTURE_H
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_H
#include <stddef.h>
#include <stdint.h>
#include "../opengpu_drm.h"
struct native_kernel_session;
struct native_kernel_profile;
struct native_kernel_profile_word { uint32_t address,value; };
/* Qualification-only discovery cache: all words originate from the actual
 * DUT probe. This does not enable the production driver's reserved feature. */
struct native_kernel_profile *native_kernel_profile_create(
    const struct native_kernel_profile_word *words,size_t count,
    const struct native_kernel_profile_word *registers,size_t register_count);
void native_kernel_profile_destroy(struct native_kernel_profile *);
int native_kernel_profile_query(const struct native_kernel_profile *,uint32_t param,uint64_t *output);
struct native_kernel_session *native_kernel_session_create_profile(uint32_t context_id,
    const struct native_kernel_profile *);

typedef void (*native_kernel_source_observer)(void *record,uint32_t handle,
    uint64_t offset,uint32_t bytes,const void *stable_original_bytes);
struct native_kernel_session *native_kernel_session_create(uint32_t context_id);
void *native_kernel_original_allocate(struct native_kernel_session *,uint32_t handle,uint64_t page_bytes);
int native_kernel_original_close(struct native_kernel_session *,uint32_t handle);
int native_kernel_original_refcount(struct native_kernel_session *,uint32_t handle);
int native_kernel_original_physical(struct native_kernel_session *,uint32_t handle,uint64_t *physical,uint64_t *bytes);
int native_kernel_submit(struct native_kernel_session *,struct drm_opengpu_submit_native_binned *,
    native_kernel_source_observer observer,void *record);
/* Active qualification has two receipt phases. Fields are decoded explicitly
 * from LE wire records; C structure padding is never serialized. */
struct native_kernel_held_receipt {
    uint32_t record_index,command_id,completion,gnx[12];
    uint64_t append_read_bytes;
    uint32_t held_legacy_status,held_status,held_irq,held_irq_asserted;
};
struct native_kernel_observed_receipt {
    struct native_kernel_held_receipt held;
    uint32_t retired_status,retired_irq,retired_irq_asserted,external_drained;
};
int native_kernel_export_memory(struct native_kernel_session *,const char *path);
int native_kernel_capture(struct native_kernel_session *,unsigned record,const char *path,
    uint64_t cycle_budget,uint32_t hold_cycles);
int native_kernel_drain_held(struct native_kernel_session *,unsigned record,
    const struct native_kernel_held_receipt *,const char *retire_trace_path);
/* Confirms actual DUT application of the driver's traced drain/POP. Only then
 * may the scheduler's finished fence permit the next native execution. */
int native_kernel_observe_retired(struct native_kernel_session *,unsigned record,
    const struct native_kernel_observed_receipt *);
/* Final all-record oracle authorization; does not invent completion or drain. */
int native_kernel_finish_observed(struct native_kernel_session *,unsigned record);
/* Execution/capture and retirement stay separate: pending owners retain their
 * real mappings/leases and every original reference until explicit retirement. */
int native_kernel_run(struct native_kernel_session *,unsigned record);
int native_kernel_retire(struct native_kernel_session *,unsigned record,int error);
int native_kernel_session_destroy(struct native_kernel_session *);
#endif
