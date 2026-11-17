/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GALLIUM_IMAGE_LAYOUT_H
#define OPENGPU_GALLIUM_IMAGE_LAYOUT_H
#include "opengpu_buffer_views.h"

/* Private linear image storage. These layouts do not enable a GL capability
 * or reinterpret a render/scanout allocation. The retained image window is
 * the existing 128-MiB compute backing window. */
struct opengpu_image_level {
    uint32_t offset, width, height, depth, layers, planes, row_pitch, plane_pitch;
};
struct opengpu_image_layout {
    uint32_t bytes, format, texel_bytes, target, levels;
    struct opengpu_image_level level[16];
};

static inline int opengpu_gallium_image_layout(const struct pipe_resource *r,
    struct opengpu_image_layout *out)
{
    struct opengpu_image_layout layout = {0};
    uint32_t target, maximum, last = 0;
    uint64_t cursor = 0;
    int format;
    if (!r || !out || !(r->bind & PIPE_BIND_SHADER_IMAGE) ||
        !r->width0 || !r->height0 || !r->depth0 || !r->array_size ||
        r->last_level >= 16 || r->nr_samples > 1 || r->nr_storage_samples > 1 ||
        (format = opengpu_buffer_image_format(r->format)) < 0) return 0;
    switch (r->target) {
    case PIPE_TEXTURE_2D:
        target = 0; if (r->depth0 != 1 || r->array_size != 1) return 0; break;
    case PIPE_TEXTURE_2D_ARRAY:
        target = 1; if (r->depth0 != 1 || r->array_size > 256) return 0; break;
    case PIPE_TEXTURE_CUBE:
        target = 2; if (r->width0 != r->height0 || r->depth0 != 1 || r->array_size != 6) return 0; break;
    case PIPE_TEXTURE_3D:
        target = 3; if (r->array_size != 1 || r->width0 > 256 || r->height0 > 256 || r->depth0 > 256) return 0; break;
    default: return 0;
    }
    if (r->width0 > 2048 || r->height0 > 2048) return 0;
    maximum = r->width0 > r->height0 ? r->width0 : r->height0;
    if (r->depth0 > maximum) maximum = r->depth0;
    while (maximum > 1) { maximum >>= 1; ++last; }
    if (r->last_level > last) return 0;
    layout.format = (uint32_t)format;
    layout.texel_bytes = opengpu_compute_image_format_bytes((unsigned)format);
    layout.target = target; layout.levels = r->last_level + 1;
    for (unsigned i = 0; i < layout.levels; ++i) {
        uint32_t w = r->width0 >> i, h = r->height0 >> i, d = r->depth0 >> i;
        uint32_t layers = target == 1 ? r->array_size : 1;
        if (!w) w = 1;
        if (!h) h = 1;
        if (!d) d = 1;
        uint32_t planes = target == 2 ? 6 : target == 1 ? layers : target == 3 ? d : 1;
        uint64_t row = ((uint64_t)w * layout.texel_bytes + 63) & ~UINT64_C(63);
        uint64_t plane = row * h, bytes = plane * planes;
        if (bytes > 128u * 1024u * 1024u - cursor) return 0;
        layout.level[i] = (struct opengpu_image_level){(uint32_t)cursor, w, h, d, layers, planes,
                                                     (uint32_t)row, (uint32_t)plane};
        cursor += bytes;
    }
    layout.bytes = (uint32_t)cursor;
    *out = layout; return 1;
}
#endif
