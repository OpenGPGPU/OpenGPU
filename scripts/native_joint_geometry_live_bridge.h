#ifndef OPENGPU_NATIVE_JOINT_GEOMETRY_LIVE_BRIDGE_H
#define OPENGPU_NATIVE_JOINT_GEOMETRY_LIVE_BRIDGE_H

#include <stdbool.h>
#include "../driver/tests/opengpu_native_submit_fixture.h"

enum { JOINT_GEOMETRY_LIVE_RECORDS = 3 };
struct native_kernel_profile *joint_geometry_live_profile(const char *path);
void joint_geometry_live_export(struct native_kernel_session *,const char *directory);
void joint_geometry_live_run(struct native_kernel_session *,unsigned count,bool cpu_preflight);
#endif
