/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_JOINT_BUILD_SOURCE_H
#define OPENGPU_NATIVE_JOINT_BUILD_SOURCE_H
#include "opengpu_native_joint.h"
#include "../../driver/opengpu_native_frame_internal.h"

/* Original Gallium object and exact window, retained independently of caller
 * bindings. Handles are derived from this object, never imported from a file. */
struct opengpu_native_joint_view {struct pipe_resource *resource;uint64_t offset,bytes;};
struct opengpu_native_joint_binding {struct opengpu_native_joint_view view;unsigned stride,divisor;};
struct opengpu_native_joint_catalog_draw {
    unsigned state_handle,token,count,instances,first_vertex,base_instance;
    unsigned indexed,index_format,first_index;int32_t base_vertex;
    struct opengpu_native_draw_attribute attributes[16];
    struct opengpu_native_joint_binding bindings[16];
    struct opengpu_native_joint_view indices,scratch;
};
struct opengpu_native_joint_invocation {
    uint64_t invocation_id;unsigned draw_index,patch_vertices;
};
struct opengpu_native_joint_range_request {
    unsigned sequence_index,invocation_index,draw_index,range_ordinal;
    unsigned flags,task_id_base,patch_count;
    unsigned first_ordinal,patches_per_instance,first_instance,instances;
};
struct opengpu_native_joint_range {
    unsigned sequence_index,invocation_index,original_draw_index,range_ordinal;
    unsigned flags,task_id_base,patch_count,reserved;
    unsigned slice_version,first_ordinal,patches_per_instance,first_instance,instances;
    struct opengpu_native_tess_patch_layout layout;
    struct opengpu_native_joint_component components[64];
};
struct opengpu_native_joint_image {struct opengpu_native_joint_view view;unsigned row_pitch,store;};
struct opengpu_native_joint_attachment {
    unsigned format,load_op,channel_mask,clear[4];
    struct opengpu_native_joint_image image,resolve;
};
struct opengpu_native_joint_frame {
    unsigned width,height,samples,color_mask,tile_log2,spill_count;
    struct opengpu_native_joint_attachment attachments[6];
    struct opengpu_native_joint_view primitives,directory,pool[9],scratch[4];
};
struct opengpu_native_joint_build_request {
    struct pipe_context *context;struct opengpu_native_joint_source *source;
    unsigned pass,generation,state_count;
    const struct opengpu_native_joint_catalog_draw *draws;unsigned draw_count;
    const struct opengpu_native_joint_invocation *invocations;unsigned invocation_count;
    const struct opengpu_native_joint_range_request *ranges;unsigned range_count;
    struct opengpu_native_joint_frame frame;
};
struct opengpu_native_joint_build_plan {
    unsigned sequence_index,state_handle;
    struct drm_opengpu_native_program_view control_program,evaluation_program;
    struct drm_opengpu_native_uniform_stage control_uniforms,evaluation_uniforms;
};
struct opengpu_native_joint_state_plan {
    unsigned state_handle,reserved;
    struct drm_opengpu_native_program_view fragment_program;
    struct drm_opengpu_native_uniform_stage fragment_uniforms;
};
struct opengpu_native_joint_build_description {
    unsigned version,context,pass,generation,domain;
    unsigned draw_count,invocation_count,range_count,state_count;
    const uint32_t *job,*draws,*sequences,*states;
    const struct drm_opengpu_native_draw_plan *draw_plans;
    const struct opengpu_native_joint_invocation *invocations;
    const struct opengpu_native_joint_range *ranges;
    const struct opengpu_native_joint_build_plan *plans;
    const struct opengpu_native_joint_state_plan *state_plans;
    const struct opengpu_native_frame_encoding *frame;
};
struct opengpu_native_joint_build_source;
#ifdef __cplusplus
extern "C" {
#endif
/* Pure source preparation. No verified profile, permission, ioctl, kernel
 * pointer, VA, CPU patch data or execution result is accepted or generated.
 * The context/screen outlive this lease. All arrays are captured before any
 * original-view callback; source program/uniforms and all extra views survive
 * caller rebind/destruction until this bundle is released. Data/fences still
 * require protected original-owner capture and producer exclusion. */
struct opengpu_native_joint_build_source *opengpu_native_joint_build_source_create(
    const struct opengpu_native_joint_build_request *);
void opengpu_native_joint_build_source_destroy(struct opengpu_native_joint_build_source *);
/* Borrowed const arrays remain valid only while the opaque bundle is retained.
 * Changing a returned value copy cannot alter a bundle or compiler metadata. */
int opengpu_native_joint_build_source_describe(const struct opengpu_native_joint_build_source *,
    struct opengpu_native_joint_build_description *);
/* Submit the retained original objects through the dedicated joint DRM route.
 * The originating context must have selected its verified joint profile. */
int opengpu_native_joint_build_source_submit(const struct opengpu_native_joint_build_source *,
    unsigned in_syncobj,unsigned out_syncobj,uint64_t *fence_sequence);
#ifdef __cplusplus
}
#endif
#endif
