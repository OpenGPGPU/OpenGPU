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
#endif
