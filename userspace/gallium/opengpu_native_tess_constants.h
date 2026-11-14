/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_TESS_CONSTANTS_H
#define OPENGPU_NATIVE_TESS_CONSTANTS_H
#include "opengpu.h"
#ifdef __cplusplus
extern "C" {
#endif

struct pipe_context;struct pipe_constant_buffer;struct opengpu_native_tess_constants;
/* Private state-tracker sink. Source context/screen outlive this object.
 * Accepts Mesa TCS/TES enums only in its Gallium callback, explicitly maps to
 * GPU4/5; no create/bind callback or public screen capability is enabled. */
struct opengpu_native_tess_constants *opengpu_native_tess_constants_create(struct pipe_context *);
struct pipe_context *opengpu_native_tess_constants_pipe(struct opengpu_native_tess_constants *);
struct pipe_context *opengpu_native_tess_constants_source(struct opengpu_native_tess_constants *);
void opengpu_native_tess_constants_destroy(struct opengpu_native_tess_constants *);
/* Explicit GPU stage4/5 binding. Fresh defaults are copied into owned original
 * GEMs. UBOs retain exact Gallium resource/offset/size and transfer take_owned
 * references on every path. Failed binding keeps old state and poisons export. */
int opengpu_native_tess_constant_set(struct opengpu_native_tess_constants *,unsigned gpu_stage,
    unsigned slot,int take_owned,const struct pipe_constant_buffer *);
/* Checked original source plans, no GPU VAs or admission. Pair orderTCS,TES;
 * only P16's stage4/5 GUR3 snapshotter may interpret this pair. Each logical
 * stage remains12 blocks; common private planner can dedup24 exact views.
 * Keep this object/resources alive through protected snapshot acceptance. */
int opengpu_native_tess_constant_sources(struct opengpu_native_tess_constants *,
    const unsigned required[2][13],struct drm_opengpu_native_uniform_stage out[2]);
#ifdef __cplusplus
}
#endif

#endif
