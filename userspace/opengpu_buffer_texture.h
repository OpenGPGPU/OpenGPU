/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_USER_BUFFER_TEXTURE_H
#define OPENGPU_USER_BUFFER_TEXTURE_H
#include "../driver/opengpu_drm.h"
#include "../driver/opengpu_buffer_view.h"
#include "../driver/opengpu_image_format_policy.h"

/* Construct copied ioctl metadata, not a hardware/API capability. The caller
 * still binds and retains the BO and must obtain native buffer-view support
 * before submission; these helpers do not enable any GLES capability. */
static inline int opengpu_buffer_texture_view_init(
    struct drm_opengpu_compute_texture_view *out, unsigned slot, unsigned format,
    unsigned long long binding_bytes, unsigned long long offset, unsigned long long bytes)
{
    struct drm_opengpu_compute_texture_view view = {0};
    unsigned count, stride=opengpu_texture_buffer_format_bytes(format);
    if (!out || !slot || slot > OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS ||
        !opengpu_buffer_view_count(0,binding_bytes,offset,bytes,stride,&count)) return 0;
    view.slot=slot; view.target=OPENGPU_TEXTURE_BUFFER_TARGET; view.format=format;
    view.buffer_texels=count; view.height=view.depth=view.layers=1; view.level_count=1;
    view.levels[0].offset=offset; view.levels[0].buffer_bytes=bytes;
    view.levels[0].buffer_texels=count; view.levels[0].height=view.levels[0].depth=view.levels[0].layers=1;
    view.levels[0].block_bytes=stride;
    *out=view; return 1;
}

static inline int opengpu_buffer_image_view_init(
    struct drm_opengpu_compute_image_view *out, unsigned slot, unsigned flags,
    unsigned storage_format, unsigned image_format, unsigned long long binding_bytes,
    unsigned long long offset, unsigned long long bytes)
{
    struct drm_opengpu_compute_image_view view = {0};
    unsigned count, stride=opengpu_texture_buffer_format_bytes(storage_format);
    if (!out || !slot || slot > OPENGPU_MAX_RESOURCE_BINDINGS ||
        !(flags & (OPENGPU_COMPUTE_IMAGE_READ | OPENGPU_COMPUTE_IMAGE_WRITE)) ||
        (flags & ~(OPENGPU_COMPUTE_IMAGE_READ | OPENGPU_COMPUTE_IMAGE_WRITE | OPENGPU_COMPUTE_IMAGE_BY_SIZE)) ||
        !stride || stride != opengpu_compute_image_format_bytes(image_format) ||
        (!(flags & OPENGPU_COMPUTE_IMAGE_BY_SIZE) &&
         opengpu_texture_format_component_class(storage_format) != opengpu_texture_format_component_class(image_format)) ||
        !opengpu_buffer_view_count(0,binding_bytes,offset,bytes,stride,&count)) return 0;
    view.slot=slot; view.flags=flags; view.target=OPENGPU_TEXTURE_BUFFER_TARGET;
    view.storage_format=storage_format; view.image_format=image_format;
    view.offset=offset; view.buffer_bytes=bytes; view.buffer_texels=count;
    view.height=view.depth=view.layers=1;
    *out=view; return 1;
}
#endif
