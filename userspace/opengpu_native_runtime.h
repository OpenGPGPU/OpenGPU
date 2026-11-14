/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_RUNTIME_H
#define OPENGPU_NATIVE_RUNTIME_H
#include "opengpu_native_layout.h"
#include "opengpu_native_frame.h"

struct opengpu_native_runtime {
    struct opengpu_native_shader_layout shader;
    struct opengpu_native_binned_host_caps frame;
};

/* Query only the protected native route. The driver freezes both discovery
 * blocks for this device; no GL capability or legacy profile is substituted.
 * On failure, output remains unchanged. */
int opengpu_native_runtime_query(int fd, struct opengpu_native_runtime *output);
#endif
