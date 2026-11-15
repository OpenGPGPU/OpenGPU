/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FIVE_INTERNAL_H
#define OPENGPU_NATIVE_FIVE_INTERNAL_H
#include "opengpu_native_five.h"
/* Owned link object owns original token packets; compile copies all of them
 * before any resource callback. Order: VSout,TCin,TCout,TEin,TEout,GSin,GSout,FSin. */
struct opengpu_native_five_input {
    const void *tokens[5];unsigned words[5];
    struct opengpu_native_fragment_reflection_v3 fragment;
    struct opengpu_native_tess_input_map maps[8];
};
#ifdef __cplusplus
extern "C" {
#endif
int opengpu_native_five_link_capture(const struct opengpu_native_five_link *,struct opengpu_native_five_input *);
struct opengpu_native_five_program *opengpu_native_five_compile_options(struct pipe_context *,
    const struct opengpu_native_five_input *,unsigned,const struct opengpu_native_five_compile_options *);
struct opengpu_native_five_program *opengpu_native_five_compile(struct pipe_context *,const struct opengpu_native_five_input *,unsigned);
struct opengpu_native_five_bindings;
struct opengpu_native_five_bindings *opengpu_native_five_capture(struct opengpu_native_five_constants *,struct pipe_context *,const unsigned [5][13],struct drm_opengpu_native_uniform_stage [5]);
void opengpu_native_five_release(struct opengpu_native_five_bindings *);
#ifdef __cplusplus
}
#endif
#endif
