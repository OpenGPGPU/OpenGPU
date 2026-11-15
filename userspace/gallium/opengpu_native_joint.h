/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_JOINT_H
#define OPENGPU_NATIVE_JOINT_H
#include "opengpu_native_graphics_v2.h"
#include "opengpu_native_tess_uniform_emit.h"
#ifdef __cplusplus
extern "C" {
#endif
/* Four stages in original linked order VS,TCS,TES,FS. No GS, public callback,
 * capability, runtime permission or submit path is introduced. */
struct pipe_resource;
struct opengpu_native_joint_link;
struct opengpu_native_joint_program;
struct opengpu_native_joint_constants;
struct opengpu_native_joint_source;
struct opengpu_native_joint_component {unsigned kind,component;};
struct opengpu_native_joint_mapping {unsigned valid,component;};
struct opengpu_native_joint_description {
    unsigned context_id;
    struct opengpu_native_uniform_layout vertex_layout;
    struct opengpu_native_uniform_program vertex;
    struct opengpu_native_tess_program tess[2];
    struct opengpu_native_fragment_program_v2 fragment;
    struct drm_opengpu_native_program_view code[4];
    struct drm_opengpu_native_uniform_stage uniforms[4];
    struct opengpu_native_joint_component vertex_components[64];
    struct opengpu_native_joint_mapping fragment_components[68];
};
/* files are VS,TCS,TES,FS. Linked once; the opaque link owns original token
 * packets, NIR variable spans, binding order and original HIR static facts. */
struct opengpu_native_joint_link *opengpu_native_joint_link_create(const char *const files[4]);
void opengpu_native_joint_link_destroy(struct opengpu_native_joint_link *);
unsigned opengpu_native_joint_blocks(const struct opengpu_native_joint_link *,unsigned stage);
unsigned opengpu_native_joint_binding(const struct opengpu_native_joint_link *,unsigned stage,unsigned block);
unsigned opengpu_native_joint_defaults(const struct opengpu_native_joint_link *,unsigned stage);
int opengpu_native_joint_bind_ubos(struct opengpu_native_joint_link *,struct pipe_context *,struct pipe_resource *const [72],const unsigned [72],const unsigned [72]);
int opengpu_native_joint_bind_defaults(struct opengpu_native_joint_link *,struct pipe_context *,unsigned stage,const unsigned *,unsigned);
struct opengpu_native_joint_program *opengpu_native_joint_program_create(struct pipe_context *,const struct opengpu_native_joint_link *,unsigned patch_vertices);
void opengpu_native_joint_program_destroy(struct opengpu_native_joint_program *);
/* Private real state-tracker sink. Explicit Mesa stage mapping, four distinct
 * original defaults and exact UBO views; source pipe/screen outlive objects. */
struct opengpu_native_joint_constants *opengpu_native_joint_constants_create(struct pipe_context *);
struct pipe_context *opengpu_native_joint_constants_pipe(struct opengpu_native_joint_constants *);
void opengpu_native_joint_constants_destroy(struct opengpu_native_joint_constants *);
/* Retains immutable backing objects and the program across rebind/destruction.
 * This freezes metadata/references, NOT data/fences or protected permissions. */
struct opengpu_native_joint_source *opengpu_native_joint_source_create(struct opengpu_native_joint_program *,struct opengpu_native_joint_constants *);
void opengpu_native_joint_source_destroy(struct opengpu_native_joint_source *);
int opengpu_native_joint_source_describe(const struct opengpu_native_joint_source *,struct opengpu_native_joint_description *);
#ifdef __cplusplus
}
#endif
#endif
