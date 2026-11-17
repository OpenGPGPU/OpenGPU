/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GALLIUM_IMAGE_VIEWS_H
#define OPENGPU_GALLIUM_IMAGE_VIEWS_H
#include "opengpu_internal.h"

static inline int opengpu_gallium_image_init(struct drm_opengpu_compute_image_view *out,
    unsigned slot, const struct pipe_image_view *view)
{
    struct drm_opengpu_compute_image_view native = {0};
    int format;
    if (!view || !view->resource) return 0;
    if (view->resource->target == PIPE_BUFFER)
        return opengpu_gallium_buffer_image_init(out, slot, view);
    const struct opengpu_image_layout *layout = &opengpu_resource(view->resource)->image;
    if (!out || !slot || slot > OPENGPU_MAX_RESOURCE_BINDINGS || !layout->levels ||
        view->u.tex.level >= layout->levels ||
        !(view->access & PIPE_IMAGE_ACCESS_READ_WRITE) ||
        (view->access & ~PIPE_IMAGE_ACCESS_READ_WRITE) ||
        (view->shader_access & ~PIPE_IMAGE_ACCESS_READ_WRITE) ||
        (format = opengpu_buffer_image_format(view->format)) < 0 ||
        opengpu_compute_image_format_bytes((unsigned)format) != layout->texel_bytes) return 0;
    const struct opengpu_image_level *level = &layout->level[view->u.tex.level];
    unsigned first = view->u.tex.first_layer, last = view->u.tex.last_layer;
    if (first > last || last >= level->planes ||
        (layout->target == 2 && first != last && (first != 0 || last != 5))) return 0;
    native.slot = slot; native.target = layout->target;
    native.storage_format = layout->format; native.image_format = (uint32_t)format;
    native.level = view->u.tex.level; native.offset = level->offset;
    native.width = level->width; native.height = level->height; native.depth = level->depth;
    native.layers = level->layers; native.row_pitch = level->row_pitch;
    if (layout->target == 3) native.slice_pitch = level->plane_pitch;
    if (layout->target == 1 || layout->target == 2) native.layer_pitch = level->plane_pitch;
    if (view->access & PIPE_IMAGE_ACCESS_READ) native.flags |= OPENGPU_COMPUTE_IMAGE_READ;
    if (view->access & PIPE_IMAGE_ACCESS_WRITE) native.flags |= OPENGPU_COMPUTE_IMAGE_WRITE;
    /* Gallium views of these privately allocated immutable levels use the
     * by-size image compatibility policy, retaining the original storage ID. */
    native.flags |= OPENGPU_COMPUTE_IMAGE_BY_SIZE;
    if (first != last) {
        native.flags |= OPENGPU_COMPUTE_IMAGE_LAYERED;
        if (layout->target != 2) {
            native.offset += (uint64_t)first * level->plane_pitch;
            if (layout->target == 1) native.layers = last - first + 1;
            if (layout->target == 3) native.depth = last - first + 1;
        }
    } else native.layer = first;
    *out = native; return 1;
}
/* Gallium carries only a layer range. The compiled image type disambiguates
 * a one-layer array/volume from an unlayered 2D access to one selected plane. */
static inline int opengpu_gallium_image_for_shader(struct drm_opengpu_compute_image_view *out,
    unsigned slot, const struct pipe_image_view *view, unsigned shader_target)
{
    struct drm_opengpu_compute_image_view native;
    if (!opengpu_gallium_image_init(&native,slot,view)) return 0;
    if (native.target==shader_target) {
        if (native.target!=0 && native.target!=5 && !(native.flags&OPENGPU_COMPUTE_IMAGE_LAYERED)) {
            if (native.target==2) return 0;
            native.offset+=(uint64_t)native.layer*(native.target==3?native.slice_pitch:native.layer_pitch);
            native.layer=0;native.flags|=OPENGPU_COMPUTE_IMAGE_LAYERED;
            if (native.target==1) native.layers=1;
            else native.depth=1;
        }
    } else if (shader_target!=0 || native.target==0 || native.target==5 ||
               (native.flags&OPENGPU_COMPUTE_IMAGE_LAYERED)) return 0;
    *out=native;return 1;
}
#endif
