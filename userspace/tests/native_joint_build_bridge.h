/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_JOINT_BUILD_BRIDGE_H
#define OPENGPU_NATIVE_JOINT_BUILD_BRIDGE_H
#include <stdint.h>
/* Test-only original-object registry; no ioctl/permission or artifact import. */
struct opengpu_native_joint_build_description;
struct opengpu_native_joint_description;
struct native_joint_build_session;
struct native_joint_build_session *native_joint_build_session_create(unsigned context);
void *native_joint_build_original_allocate(struct native_joint_build_session *,unsigned handle,uint64_t bytes);
int native_joint_build_original_close(struct native_joint_build_session *,unsigned handle);
int native_joint_build_original_references(struct native_joint_build_session *,unsigned handle);
int native_joint_build_prove(struct native_joint_build_session *,
    const struct opengpu_native_joint_build_description *,const struct opengpu_native_joint_description *);
void native_joint_build_session_destroy(struct native_joint_build_session *);
#endif
