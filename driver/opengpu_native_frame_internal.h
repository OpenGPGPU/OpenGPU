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
#endif
