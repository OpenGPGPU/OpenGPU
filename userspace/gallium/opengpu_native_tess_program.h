/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_TESS_PROGRAM_H
#define OPENGPU_NATIVE_TESS_PROGRAM_H
#include "opengpu_native_tess_uniform_emit.h"
#include "opengpu_native_tess_constants.h"
#ifdef __cplusplus
extern "C" {
#endif

struct pipe_context;struct opengpu_native_tess_program_pair;
struct opengpu_native_tess_program_sources {
    struct drm_opengpu_native_program_view code[2];
    struct drm_opengpu_native_uniform_stage uniforms[2];
    struct opengpu_native_tess_program shaders[2];
};
/* Explicit TCS/TES-only private compiler source path. Does not authorize a
 * submit, install public Gallium shader callbacks or report discovery. Runtime
 * protected admission MUST use P16 verified profile and original code/GUR3
 * copies, then retain them through actual CU/drain/translation retirement.
 * Caller retains source pipe/constants through sources and snapshot acceptance.
 */
struct opengpu_native_tess_program_pair *opengpu_native_tess_program_create(struct pipe_context *,
    const void *tokens[2],const unsigned token_words[2],const struct opengpu_native_tess_layout layout[2]);
void opengpu_native_tess_program_destroy(struct opengpu_native_tess_program_pair *);
int opengpu_native_tess_program_sources(struct opengpu_native_tess_program_pair *,
    struct opengpu_native_tess_constants *,struct opengpu_native_tess_program_sources *);
#ifdef __cplusplus
}
#endif

#endif
