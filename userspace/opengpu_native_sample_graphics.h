/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_SAMPLE_GRAPHICS_H
#define OPENGPU_NATIVE_SAMPLE_GRAPHICS_H
#include <stddef.h>
#include "../driver/opengpu_native_sample_transport_features.h"
#include "../driver/opengpu_native_layered_abi.h"
#include "../driver/opengpu_native_frame.h"
#include "../driver/opengpu_drm.h"

#define OPENGPU_NATIVE_SAMPLE_DISCOVERY_WORDS 112u
#define OPENGPU_NATIVE_SAMPLE_OBSERVATIONS 117u
struct opengpu_native_sample_word { uint32_t address, value; };
/* Private qualification observation, not public driver/API authorization.
 * Every producer operation revalidates the original ordered observations and
 * derived cache. No ordinary GET_PARAM or frozen GNC2/3 API is widened. */
struct opengpu_native_sample_runtime {
    struct opengpu_native_sample_word observed[OPENGPU_NATIVE_SAMPLE_OBSERVATIONS];
    struct opengpu_native_source_limits limits;
    uint32_t id, capabilities, shader_config;
};
int opengpu_native_sample_runtime_decode(const struct opengpu_native_sample_word *, size_t,
    struct opengpu_native_sample_runtime *);
int opengpu_native_sample_runtime_read(uint64_t register_bytes, void *context,
    int (*read)(void *, uint32_t, uint32_t *), struct opengpu_native_sample_runtime *);
int opengpu_native_sample_runtime_validate(const struct opengpu_native_sample_runtime *);

struct opengpu_native_sample_frame_request {
    const struct opengpu_native_sample_runtime *runtime;
    struct opengpu_native_frame_request frame;
    /* Explicit single-layer byte pitches, including canonical zero for unused
     * or non-advancing planes. No allocation extent or private VA is inferred. */
    uint64_t source_pitch[6], resolve_pitch[6];
};
int opengpu_native_sample_frame_encode(const struct opengpu_native_sample_frame_request *,
    struct opengpu_native_frame_encoding *);
struct opengpu_native_sample_job_encoding {
    struct opengpu_native_layered_job job;
    struct drm_opengpu_native_completion_view completion;
};
int opengpu_native_sample_job_encode(const struct opengpu_native_sample_runtime *,
    uint32_t context, uint32_t pass, uint32_t generation, uint32_t draws,
    const struct drm_opengpu_compute_uniform_view *, struct opengpu_native_sample_job_encoding *);
/* Preserve exact original code/GUR/input views and zero the entire GS extension.
 * The protected ioctl still owns descriptor admission, original GEM bounds,
 * producer waits, private RX/RO snapshots, aliases and completion lifetime. */
int opengpu_native_sample_ordinary_plan(const struct opengpu_native_sample_runtime *,
    const struct drm_opengpu_native_draw_plan *, struct drm_opengpu_native_layered_draw_plan *);
/* All functions return negative errno on rejection and leave output unchanged. */
#endif
