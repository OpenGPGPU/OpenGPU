/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_JOINT_INTERNAL_H
#define OPENGPU_NATIVE_JOINT_INTERNAL_H
#include "opengpu_native_joint.h"
/* Internal same-link compiler snapshot; never a permission or UAPI. */
struct opengpu_native_joint_input {
    const void *tokens[4];unsigned words[4];
    struct opengpu_native_fragment_reflection_v2 fragment;
    /* VSout, TCin, TCout, TEin, TEout, FSin (original NIR spans). */
    struct opengpu_native_tess_input_map maps[6];
};
#ifdef __cplusplus
extern "C" {
#endif
int opengpu_native_joint_link_capture(const struct opengpu_native_joint_link *,struct opengpu_native_joint_input *);
struct opengpu_native_joint_program *opengpu_native_joint_compile(struct pipe_context *,const struct opengpu_native_joint_input *,unsigned);
struct opengpu_native_joint_bindings;
struct opengpu_native_joint_bindings *opengpu_native_joint_capture(struct opengpu_native_joint_constants *,struct pipe_context *,const unsigned [4][13],struct drm_opengpu_native_uniform_stage [4]);
void opengpu_native_joint_release(struct opengpu_native_joint_bindings *);
int opengpu_native_joint_source_matches(const struct opengpu_native_joint_source *,struct pipe_context *);
struct opengpu_native_joint_source *opengpu_native_joint_source_retain(struct opengpu_native_joint_source *);
#ifdef __cplusplus
}
#endif
#endif
