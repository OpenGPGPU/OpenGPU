/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FIVE_H
#define OPENGPU_NATIVE_FIVE_H
#include "opengpu_native_joint.h"
#include "opengpu_native_geometry_emit.h"
#include "opengpu_native_fragment_v3.h"
#ifdef __cplusplus
extern "C" {
#endif
/* Independent five-stage opaque family, VS/TC/TE/GS/FS in original linked
 * order. No four-stage object, new permission, getter or submit is accepted. */
struct opengpu_native_five_link;
struct opengpu_native_five_program;
struct opengpu_native_five_constants;
struct opengpu_native_five_source;
struct opengpu_native_five_description {
    unsigned context_id;
    struct opengpu_native_uniform_layout vertex_layout;
    struct opengpu_native_uniform_program vertex;
    struct opengpu_native_tess_program tess[2];
    struct opengpu_native_geometry_program geometry;
    struct opengpu_native_fragment_program_v3 fragment;
    struct drm_opengpu_native_program_view code[5];
    struct drm_opengpu_native_uniform_stage uniforms[5];
    struct opengpu_native_joint_component vertex_components[64];
    struct opengpu_native_joint_mapping evaluation_components[64];
    struct opengpu_native_joint_mapping fragment_components[68];
};
struct opengpu_native_five_link *opengpu_native_five_link_create(const char *const [5]);
void opengpu_native_five_link_destroy(struct opengpu_native_five_link *);
unsigned opengpu_native_five_blocks(const struct opengpu_native_five_link *,unsigned);
unsigned opengpu_native_five_binding(const struct opengpu_native_five_link *,unsigned,unsigned);
unsigned opengpu_native_five_defaults(const struct opengpu_native_five_link *,unsigned);
int opengpu_native_five_bind_ubos(struct opengpu_native_five_link *,struct pipe_context *,struct pipe_resource *const [72],const unsigned [72],const unsigned [72]);
int opengpu_native_five_bind_defaults(struct opengpu_native_five_link *,struct pipe_context *,unsigned,const unsigned *,unsigned);
struct opengpu_native_five_program *opengpu_native_five_program_create(struct pipe_context *,const struct opengpu_native_five_link *,unsigned);
void opengpu_native_five_program_destroy(struct opengpu_native_five_program *);
struct opengpu_native_five_constants *opengpu_native_five_constants_create(struct pipe_context *);
struct pipe_context *opengpu_native_five_constants_pipe(struct opengpu_native_five_constants *);
void opengpu_native_five_constants_destroy(struct opengpu_native_five_constants *);
/* Holds all actually required views (up to65 uniform refs plus5code objects),
 * including distinct old defaults copies, across rebind/caller destruction.
 * The source pipe_context/screen must outlive every program/source object.
 * Data/fence synchronization and verified permission remain kernel concerns. */
struct opengpu_native_five_source *opengpu_native_five_source_create(struct opengpu_native_five_program *,struct opengpu_native_five_constants *);
void opengpu_native_five_source_destroy(struct opengpu_native_five_source *);
int opengpu_native_five_source_describe(const struct opengpu_native_five_source *,struct opengpu_native_five_description *);
#ifdef __cplusplus
}
#endif
#endif
