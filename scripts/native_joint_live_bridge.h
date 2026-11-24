/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_JOINT_LIVE_BRIDGE_H
#define OPENGPU_NATIVE_JOINT_LIVE_BRIDGE_H
#include <stdbool.h>
#include <stdint.h>
#include "../driver/tests/opengpu_native_submit_fixture.h"

enum {
    JOINT_LIVE_BASE_RECORDS = 6,
    JOINT_LIVE_WRITE_FAULT_RECORD = JOINT_LIVE_BASE_RECORDS,
    JOINT_LIVE_SOURCE_CANCEL_RECORD = JOINT_LIVE_WRITE_FAULT_RECORD + 2,
    JOINT_LIVE_RECORDS = JOINT_LIVE_SOURCE_CANCEL_RECORD + 2,
};
struct native_kernel_profile *joint_live_profile(const char *path);
void joint_live_export(struct native_kernel_session *,const char *directory);
void joint_live_run(struct native_kernel_session *,unsigned count,bool cpu_preflight);
#endif
