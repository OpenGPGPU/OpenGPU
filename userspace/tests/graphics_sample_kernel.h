/* SPDX-License-Identifier: MIT */
#ifndef GRAPHICS_SAMPLE_KERNEL_H
#define GRAPHICS_SAMPLE_KERNEL_H
#include "graphics_native_kernel.h"
#include "opengpu_native_sample_graphics.h"
int graphics_sample_profile_read(const char *,struct opengpu_native_sample_runtime *);
struct native_kernel_session *graphics_sample_session_create(unsigned,const struct opengpu_native_sample_runtime *);
/* Explicit CPU modeled control peer. Produces diagnostic captures, performs no
 * shader execution and cannot supply a held/retired GPU qualification receipt. */
int graphics_sample_cpu_capture(struct native_kernel_session *,const char *,struct native_graphics_source_record [2]);
int graphics_sample_private_code(struct native_kernel_session *,unsigned,const struct drm_opengpu_native_program_view [2]);
#endif
