/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_USER_BUFFER_TEXTURE_H
#define OPENGPU_USER_BUFFER_TEXTURE_H
#include "../driver/opengpu_drm.h"
#include "../driver/opengpu_buffer_view.h"
#include "../driver/opengpu_image_format_policy.h"
#include <string.h>
#include <stdint.h>

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
/* Validate the copied UAPI view before retaining/binding its original BO.
 * Driver descriptor admission independently repeats format and span checks. */
static inline int opengpu_compute_image_view_valid(
    const struct drm_opengpu_compute_image_view *v, unsigned slot,
    unsigned long long binding_bytes)
{
    if (!v || v->slot != slot || !slot || slot > OPENGPU_MAX_RESOURCE_BINDINGS) return 0;
    if (v->target == OPENGPU_TEXTURE_BUFFER_TARGET) {
        struct drm_opengpu_compute_image_view expected;
        return opengpu_buffer_image_view_init(&expected, slot, v->flags, v->storage_format,
            v->image_format, binding_bytes, v->offset, v->buffer_bytes) &&
            !memcmp(v, &expected, sizeof(expected));
    }
    unsigned bytes = opengpu_compute_image_format_bytes(v->storage_format);
    unsigned r32 = v->storage_format == OPENGPU_TEXTURE_FORMAT_R32F ||
                   v->storage_format == OPENGPU_TEXTURE_FORMAT_R32I ||
                   v->storage_format == OPENGPU_TEXTURE_FORMAT_R32UI;
    unsigned image_r32 = v->image_format == OPENGPU_TEXTURE_FORMAT_R32F ||
                         v->image_format == OPENGPU_TEXTURE_FORMAT_R32I ||
                         v->image_format == OPENGPU_TEXTURE_FORMAT_R32UI;
    if (!binding_bytes || binding_bytes > (1ULL << 32) || v->offset >= binding_bytes ||
        v->offset > UINT32_MAX || v->pad || v->target > 3 || v->level > 15 ||
        !bytes || bytes != opengpu_compute_image_format_bytes(v->image_format) ||
        (!(v->flags & OPENGPU_COMPUTE_IMAGE_BY_SIZE) && bytes == 4 && r32 != image_r32) ||
        !(v->flags & (OPENGPU_COMPUTE_IMAGE_READ | OPENGPU_COMPUTE_IMAGE_WRITE)) ||
        (v->flags & ~(OPENGPU_COMPUTE_IMAGE_READ | OPENGPU_COMPUTE_IMAGE_WRITE |
                      OPENGPU_COMPUTE_IMAGE_BY_SIZE | OPENGPU_COMPUTE_IMAGE_LAYERED)) ||
        !v->width || !v->height || !v->depth || !v->layers ||
        v->width > 16383 || v->height > 16383 || v->depth > 16383 || v->layers > 65535 ||
        v->row_pitch >= (1ULL << 33) || v->slice_pitch >= (1ULL << 33) || v->layer_pitch >= (1ULL << 33) ||
        (v->target != 3 && v->depth != 1) || (v->target != 1 && v->layers != 1) ||
        (v->target == 2 && v->width != v->height) || (v->target != 3 && v->slice_pitch) ||
        (v->target != 1 && v->target != 2 && v->layer_pitch)) return 0;
    unsigned planes = v->target == 1 ? v->layers : v->target == 2 ? 6 : v->target == 3 ? v->depth : 1;
    if ((v->target == 0 && (v->layer || (v->flags & OPENGPU_COMPUTE_IMAGE_LAYERED))) ||
        (!(v->flags & OPENGPU_COMPUTE_IMAGE_LAYERED) && v->layer >= planes)) return 0;
    unsigned long long row = (unsigned long long)v->width * bytes;
    unsigned long long span = (unsigned long long)(v->height - 1) * v->row_pitch + row;
    unsigned long long step = v->target == 3 ? v->slice_pitch : v->layer_pitch;
    if (v->row_pitch < row || (v->target && step < span)) return 0;
    span += (unsigned long long)(planes - 1) * step;
    return span <= binding_bytes - v->offset;
}
#endif
