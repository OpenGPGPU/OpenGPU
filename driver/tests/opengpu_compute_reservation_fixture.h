/* Original Linux cross-context reservation admission. CPU evidence only. */
#ifndef OPENGPU_COMPUTE_RESERVATION_FIXTURE_H
#define OPENGPU_COMPUTE_RESERVATION_FIXTURE_H
#include "opengpu_compute_host_fixture.h"
#include <stdbool.h>
struct compute_reservation_session;
struct compute_reservation_info {
 uint32_t contexts[2],objects[2],dependencies[2],pending_dependencies[2];
 uint32_t shared_original,reservation_dependencies,producer_reservation_dependencies;
 uint32_t reservation_usage,queued,kernel_submissions;
 int32_t fence_status[2];
 uint64_t sequence[2],current_write_sequence,physical[2];
};
struct compute_reservation_session *compute_reservation_create(
 const struct compute_host_profile_word *,size_t,const uint32_t *,size_t,
 const void *,size_t,bool shared,uint32_t kernarg_flags);
int compute_reservation_admit(struct compute_reservation_session *);
int compute_reservation_snapshot(struct compute_reservation_session *,struct compute_reservation_info *);
/* Cancels only jobs that have never reached hardware, then consumes the real
 * cancelled fences through ordinary context destruction. No success is forged. */
int compute_reservation_destroy(struct compute_reservation_session *);

/* External CPU producer on an original GEM, outside GPU admission ordering.
 * Initialize input bytes before binding. This API never executes instructions
 * or completes a GPU fence; actual-DUT authorization belongs to the caller. */
struct compute_reservation_producer;
struct compute_reservation_wait_info {
 uint32_t context,handle,objects,dependencies,pending_dependencies;
 uint32_t reservation_dependencies,producer_dependencies,reservation_usage;
 uint32_t queued,kernel_submissions,blocked_attempts,original_identity;
 int32_t producer_status,consumer_status;
 uint64_t producer_context,producer_sequence,consumer_sequence,physical,bytes;
};
struct compute_reservation_producer *compute_reservation_producer_create(struct compute_host_session *,uint32_t handle);
int compute_reservation_producer_snapshot(struct compute_reservation_producer *,struct compute_reservation_wait_info *);
/* Calls the original execution entry and reports its actual -EBUSY. */
int compute_reservation_producer_attempt(struct compute_reservation_producer *);
/* Signals only the external CPU producer, after a genuine blocked attempt. */
int compute_reservation_producer_signal(struct compute_reservation_producer *);
int compute_reservation_producer_cancel(struct compute_reservation_producer *);
/* Pending producers cannot be discarded; signal or explicitly cancel first. */
int compute_reservation_producer_destroy(struct compute_reservation_producer *);
#endif
