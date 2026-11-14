/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_TESS_CONTEXT_H
#define OPENGPU_NATIVE_TESS_CONTEXT_H
#include "opengpu_native_tess_program.h"
#ifdef __cplusplus
extern "C" {
#endif
struct opengpu_native_tess_context;
/* Independent private Gallium callback adapter. No screen capability or
 * default/public context vtable is changed. capacity comes from verified
 * hardware; compiling sources alone neither verifies it nor grants submit.
 * Source pipe outlives the adapter and every returned program pair. */
struct opengpu_native_tess_context *opengpu_native_tess_context_create(struct pipe_context *,unsigned capacity);
struct pipe_context *opengpu_native_tess_context_pipe(struct opengpu_native_tess_context *);
struct opengpu_native_tess_constants *opengpu_native_tess_context_constants(struct opengpu_native_tess_context *);
void opengpu_native_tess_context_destroy(struct opengpu_native_tess_context *);
/* Compile captured bound TCS/TES and current patch state into a NEW owned pair.
 * Deleting a bound shader retains it; rebinding cannot change an older pair.
 * Caller releases the pair after protected snapshot acceptance/retirement as
 * appropriate. No legacy draw/submit/GNC path is used or widened. */
struct opengpu_native_tess_program_pair *opengpu_native_tess_context_program(struct opengpu_native_tess_context *);
/* Snapshot fixed-function default levels (outer4 then inner2 raw fp32), useful
 * to the future protected GNQ encoder. No-TCS execution is NOT implemented. */
int opengpu_native_tess_context_state(struct opengpu_native_tess_context *,unsigned *patch_vertices,unsigned levels[6]);
#ifdef __cplusplus
}
#endif
#endif
