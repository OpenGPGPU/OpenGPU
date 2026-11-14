/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_TESS_FRONTEND_H
#define OPENGPU_TESS_FRONTEND_H
#ifdef __cplusplus
extern "C" {
#endif
struct pipe_context; struct pipe_resource; struct gl_context;
struct opengpu_native_tess_input_map;
/* Compiler/private-source path only. Stage arguments are GPU resource tags,
 * never Mesa enums: VS=0, FS=1, GS=3, TCS=4, TES=5. No public caps change.
 * filenames order VS,TCS,TES,GS(optional NULL),FS. Linked limits: API72,
 * graphics60 references, 12 blocks/stage, 1024 default components, 16KiB UBOs.
 */
void opengpu_tess_frontend_limits(struct gl_context *);
void *opengpu_tess_frontend_compile(const char *const filenames[5], const char *dump_prefix);
void opengpu_tess_frontend_destroy(void *);
const void *opengpu_tess_frontend_tokens(void *, unsigned gpu_stage);
unsigned opengpu_tess_frontend_blocks(void *, unsigned gpu_stage);
unsigned opengpu_tess_frontend_binding(void *, unsigned gpu_stage, unsigned logical_block);
unsigned opengpu_tess_frontend_components(void *, unsigned gpu_stage);
int opengpu_tess_frontend_input_map(void *,struct opengpu_native_tess_input_map *);
int opengpu_tess_frontend_control_output_map(void *,struct opengpu_native_tess_input_map *);
int opengpu_tess_frontend_ubos(void *, struct pipe_context *,
    struct pipe_resource *const resources[72], const unsigned offsets[72], const unsigned sizes[72]);
int opengpu_tess_frontend_defaults(void *, struct pipe_context *, unsigned gpu_stage,
    const unsigned *values, unsigned components);
#ifdef __cplusplus
}
#endif
#endif
