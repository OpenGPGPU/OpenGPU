/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FRAME_INTERNAL_H
#define OPENGPU_NATIVE_FRAME_INTERNAL_H
#include "opengpu_native_frame.h"
/* Internal checked planner for the distinct GNC5/GNF3 ordinary one-layer
 * producer. This does not authorize a profile or change the frozen GNC2/3 API.
 * Callers bind their complete discovery contract before using this helper. */
int opengpu_native_frame_encode_gnc5(const struct opengpu_native_frame_request *,
    const uint64_t source_pitch[6], const uint64_t resolve_pitch[6],
    struct opengpu_native_frame_encoding *);
/* Pure metadata limits for the private joint GNF1 encoder. These are not a
 * discovery record or permission to BUILD/submit. No GNC magic is fabricated.
 * frame_version 1/2 selects the existing narrow/wide GNF1 tile-count contract. */
struct opengpu_native_frame_limits {
    unsigned width, height, samples, colors, engines, storage_bytes;
    unsigned tile8, tile16, frame_version;
};
int opengpu_native_frame_encode_joint(const struct opengpu_native_frame_request *,
    const struct opengpu_native_frame_limits *,struct opengpu_native_frame_encoding *);
#endif
