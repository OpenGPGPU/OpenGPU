/* SPDX-License-Identifier: MIT */
#ifndef GRAPHICS_SAMPLE_KERNEL_H
#define GRAPHICS_SAMPLE_KERNEL_H
#include "graphics_native_kernel.h"
#include "opengpu_native_sample_graphics.h"
/* Separate new112 source proof; no legacy source JSON field is changed. */
struct graphics_sample_source_plan {
    struct drm_opengpu_native_draw_plan draw;
    struct drm_opengpu_native_completion_view completion;
    struct opengpu_native_frame_resource resources[OPENGPU_NATIVE_FRAME_MAX_RESOURCES];
    unsigned resource_count, bindings[2][12], layout[2][5];
};
int graphics_sample_source_plan_write(struct native_kernel_session *,unsigned,const char *,
    const struct graphics_sample_source_plan *);
int graphics_sample_profile_read(const char *,struct opengpu_native_sample_runtime *);
struct native_kernel_session *graphics_sample_session_create(unsigned,const struct opengpu_native_sample_runtime *);
/* Explicit CPU modeled control peer. Produces diagnostic captures, performs no
 * shader execution and cannot supply a held/retired GPU qualification receipt. */
#ifndef OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE
int graphics_sample_cpu_capture(struct native_kernel_session *,const char *,struct native_graphics_source_record [2]);
#else
/* Explicit ordinary112 external transport only. Caller prepares exactly two
 * real protected owners and complete source-plan/compiler inputs first. No
 * modeled held/cleanup peer is present in this compilation. A return of zero
 * requires separately received execution, image/GNY2 authorization and actual
 * cleanup acknowledgments; the controller owns independent GPU qualification. */
int graphics_sample_graphics_host_execute(struct native_kernel_session *,const char *,
    const struct drm_opengpu_native_program_view programs[2]);
#endif
int graphics_sample_private_code(struct native_kernel_session *,unsigned,const struct drm_opengpu_native_program_view [2]);
#endif
