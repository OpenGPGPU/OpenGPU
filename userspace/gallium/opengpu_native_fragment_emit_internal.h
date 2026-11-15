/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FRAGMENT_EMIT_INTERNAL_H
#define OPENGPU_NATIVE_FRAGMENT_EMIT_INTERNAL_H
#include "opengpu_native_uniform_emit.h"
/* Internal emission primitive. Callers initialize AND independently validate
 * their own versioned metadata; this helper grants no provider or ABI. */
int opengpu_native_fragment_emit_metadata(const void *,unsigned,
    struct opengpu_native_fragment_metadata_v2 *,struct opengpu_native_uniform_program *);
#endif
