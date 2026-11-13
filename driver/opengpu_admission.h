/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_ADMISSION_H
#define OPENGPU_ADMISSION_H
struct opengpu_device;
struct opengpu_sched_job;
void opengpu_admission_init(struct opengpu_device *gpu);
void opengpu_admission_fini(struct opengpu_device *gpu);
/* No submit/fence lock is held during a dependency/retirement wait. Success
 * returns with the enqueue-only admission mutex held; retire never takes it. */
int opengpu_admission_native_lock(struct opengpu_device *gpu);
void opengpu_admission_unlock(struct opengpu_device *gpu);
int opengpu_admission_job_prepare(struct opengpu_sched_job *job);
void opengpu_admission_job_abort(struct opengpu_sched_job *job);
void opengpu_admission_job_armed(struct opengpu_sched_job *job);
void opengpu_admission_job_retired(struct opengpu_sched_job *job);
#endif
