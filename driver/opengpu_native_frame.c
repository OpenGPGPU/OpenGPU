/* SPDX-License-Identifier: MIT */
#include "opengpu_native_frame.h"
#include "opengpu_native_binned_abi.h"
#include "opengpu_texture_image_format_abi.h"
#include <errno.h>
#include <string.h>

#define C(name) OPENGPU_TEXTURE_FORMAT_##name##_BYTES
static const unsigned char color_bytes[36] = {
    C(R8), C(RG8), C(RGB8), C(RGBA8), C(RGB565), C(RGBA4), C(RGB5_A1),
    C(RGB10_A2), C(SRGB8_ALPHA8), C(R8I), C(R8UI), C(R16I), C(R16UI),
    C(R32I), C(R32UI), C(RG8I), C(RG8UI), C(RG16I), C(RG16UI), C(RG32I),
    C(RG32UI), C(RGBA8I), C(RGBA8UI), C(RGBA16I), C(RGBA16UI),
    C(RGBA32I), C(RGBA32UI), C(RGB10_A2UI), C(R16F), C(R32F),
    C(RG16F), C(RG32F), C(RGB16F), C(RGB32F), C(RGBA16F), C(RGBA32F)
};
#undef C
static const unsigned char depth_bytes[7] = { 0, 2, 4, 4, 4, 8, 1 };

#define FIELD(c, name) (((c)->words[OPENGPU_NATIVE_BINNED_HOST_##name##_WORD] & \
    OPENGPU_NATIVE_BINNED_HOST_##name##_MASK) >> OPENGPU_NATIVE_BINNED_HOST_##name##_SHIFT)

static int layout_for(const struct opengpu_native_tile_request *r,
                      unsigned log2, unsigned storage, unsigned mode,
                      struct opengpu_native_tile_layout *out)
{
    struct opengpu_native_tile_layout layout;
    memset(&layout, 0, sizeof(layout));
    const unsigned edge = 1u << log2, texels = edge * edge * r->samples;
    unsigned cursor = depth_bytes[r->depth_format] * texels;
    layout.stencil_offset = r->stencil_format ? cursor : 0;
    cursor += depth_bytes[r->stencil_format] * texels;
    layout.reserved_bytes = cursor;
    for (unsigned i = 0; i < 4; ++i) if (r->color_mask & (1u << i)) {
        layout.color_offset[i] = cursor;
        cursor += color_bytes[r->color_format[i]] * texels;
    }
    if (cursor > storage) return -ENOSPC;
    layout.tile_log2 = log2; layout.sample_mode = mode;
    layout.columns = (r->width + edge - 1) / edge;
    layout.rows = (r->height + edge - 1) / edge;
    layout.tile_count = layout.columns * layout.rows;
    const unsigned maximum = FIELD(r->caps, MAGIC) == OPENGPU_NATIVE_BINNED_HOST_WIDE_MAGIC ?
        OPENGPU_NATIVE_BINNED_FRAME_V2_MAX_TILES : 65535;
    if (layout.tile_count > maximum) return -ENOSPC;
    layout.storage_bytes = storage; layout.used_bytes = cursor;
    *out = layout;
    return 0;
}

int opengpu_native_tile_plan(const struct opengpu_native_tile_request *r,
                            struct opengpu_native_tile_layout *out)
{
    if (!r || !r->caps || !out) return -EINVAL;
    const struct opengpu_native_binned_host_caps *c = r->caps;
    if (FIELD(c, MAGIC) != OPENGPU_NATIVE_BINNED_HOST_MAGIC &&
        FIELD(c, MAGIC) != OPENGPU_NATIVE_BINNED_HOST_WIDE_MAGIC) return -EOPNOTSUPP;
    if (FIELD(c, WORD_COUNT) != OPENGPU_NATIVE_BINNED_HOST_WORDS ||
        FIELD(c, JOB_MAGIC) != OPENGPU_NATIVE_BINNED_JOB_MAGIC ||
        FIELD(c, JOB_BYTES) != OPENGPU_NATIVE_BINNED_JOB_BYTES ||
        FIELD(c, FRAME_BYTES) != OPENGPU_NATIVE_BINNED_FRAME_BYTES ||
        FIELD(c, DRAW_BYTES) != OPENGPU_NATIVE_BINNED_DRAW_BYTES) return -EINVAL;
    if (!r->width || !r->height || r->width > FIELD(c, FRAME_WIDTH) ||
        r->height > FIELD(c, FRAME_HEIGHT) || r->color_mask > 15 ||
        FIELD(c, COLORS) > 4 || (r->color_mask >> FIELD(c, COLORS)) ||
        (r->tile_log2 && r->tile_log2 != 3 && r->tile_log2 != 4) ||
        r->depth_format > 6 || (r->stencil_format && r->stencil_format != 6) ||
        (r->stencil_format && r->depth_format >= 4)) return -EINVAL;
    unsigned mode;
    switch (r->samples) {
    case 1: mode = 0; break;
    case 2: mode = 1; break;
    case 4: mode = 2; break;
    default: return -EINVAL;
    }
    if (r->samples > FIELD(c, SAMPLES)) return -EOPNOTSUPP;
    for (unsigned i = 0; i < 4; ++i)
        if ((r->color_mask & (1u << i)) && r->color_format[i] >= 36) return -EOPNOTSUPP;
    const unsigned storage = FIELD(c, STORAGE_BYTES);
    if (storage < 1024 || storage > 65536 || (storage & (storage - 1))) return -EINVAL;
    int result = -EOPNOTSUPP;
    for (unsigned log2 = 4; log2 >= 3; --log2) {
        if (r->tile_log2 && log2 != r->tile_log2) continue;
        if (!(log2 == 4 ? FIELD(c, TILE16) : FIELD(c, TILE8))) continue;
        result = layout_for(r, log2, storage, mode, out);
        if (!result) return 0;
    }
    return result;
}
#undef FIELD
