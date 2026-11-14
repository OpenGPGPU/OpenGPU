/* SPDX-License-Identifier: MIT */
#include "opengpu_native_frame_internal.h"
#include "opengpu_native_layered_abi.h"
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

static int caps_validate(const struct opengpu_native_binned_host_caps *c, int layered)
{
    if (!c) return -EINVAL;
    if (layered ? FIELD(c, MAGIC) != OPENGPU_NATIVE_LAYERED_CAP_MAGIC :
        (FIELD(c, MAGIC) != OPENGPU_NATIVE_BINNED_HOST_MAGIC &&
         FIELD(c, MAGIC) != OPENGPU_NATIVE_BINNED_HOST_WIDE_MAGIC)) return -EOPNOTSUPP;
    if (FIELD(c, WORD_COUNT) != OPENGPU_NATIVE_BINNED_HOST_WORDS ||
        FIELD(c, JOB_MAGIC) != (layered ? OPENGPU_NATIVE_LAYERED_JOB_MAGIC : OPENGPU_NATIVE_BINNED_JOB_MAGIC) ||
        FIELD(c, JOB_BYTES) != OPENGPU_NATIVE_BINNED_JOB_BYTES ||
        FIELD(c, FRAME_BYTES) != OPENGPU_NATIVE_BINNED_FRAME_BYTES ||
        FIELD(c, DRAW_BYTES) != OPENGPU_NATIVE_BINNED_DRAW_BYTES ||
        !FIELD(c, ATTRIBUTES) || FIELD(c, ATTRIBUTES) > 16 ||
        !FIELD(c, BINDINGS) || FIELD(c, BINDINGS) > 16 ||
        !FIELD(c, VARYINGS) || FIELD(c, VARYINGS) > 16 ||
        !FIELD(c, COLORS) || FIELD(c, COLORS) > 4 ||
        !FIELD(c, ENGINES) || FIELD(c, ENGINES) > 4 ||
        !FIELD(c, FRAME_WIDTH) || !FIELD(c, FRAME_HEIGHT) ||
        !FIELD(c, CUS) || !FIELD(c, NATIVE_ID_BITS) ||
        (!FIELD(c, TILE8) && !FIELD(c, TILE16))) return -EINVAL;
    const unsigned storage = FIELD(c, STORAGE_BYTES), samples = FIELD(c, SAMPLES);
    if (storage < 1024 || storage > 65536 || (storage & (storage - 1)) ||
        (samples != 1 && samples != 2 && samples != 4)) return -EINVAL;
    const unsigned owners = FIELD(c, PAYLOAD_OWNERS), capacity_log2 = FIELD(c, PAYLOAD_CAPACITY_LOG2);
    const unsigned lines = FIELD(c, PAYLOAD_SLOT_LINES);
    if (FIELD(c, LOCAL_PAYLOAD)) {
        /* ShaderPayloadConfig has an Int-sized positive power-of-two capacity,
         * one owner for VS plus each fragment engine, and at least one slot.
         */
        if (owners < FIELD(c, ENGINES) + 1 || capacity_log2 < 6 || capacity_log2 > 30 ||
            !lines || (uint64_t)lines * 64 > (UINT64_C(1) << capacity_log2)) return -EINVAL;
    } else if (owners || capacity_log2 || lines) return -EINVAL;
    return 0;
}

int opengpu_native_frame_caps_validate(const struct opengpu_native_binned_host_caps *c)
{ return caps_validate(c, 0); }

static int layout_for(const struct opengpu_native_tile_request *r,
                      unsigned log2, unsigned storage, unsigned mode,
                      struct opengpu_native_tile_layout *out, int layered)
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
    const unsigned maximum = (layered || FIELD(r->caps, MAGIC) == OPENGPU_NATIVE_BINNED_HOST_WIDE_MAGIC) ?
        OPENGPU_NATIVE_BINNED_FRAME_V2_MAX_TILES : 65535;
    if (layout.tile_count > maximum) return -ENOSPC;
    layout.storage_bytes = storage; layout.used_bytes = cursor;
    *out = layout;
    return 0;
}

static int tile_plan(const struct opengpu_native_tile_request *r,
                     struct opengpu_native_tile_layout *out, int layered)
{
    if (!r || !r->caps || !out) return -EINVAL;
    const struct opengpu_native_binned_host_caps *c = r->caps;
    int error = caps_validate(c, layered);
    if (error) return error;
    if (!r->width || !r->height || r->width > FIELD(c, FRAME_WIDTH) ||
        r->height > FIELD(c, FRAME_HEIGHT) || r->color_mask > 15 || (r->color_mask >> FIELD(c, COLORS)) ||
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
    int result = -EOPNOTSUPP;
    for (unsigned log2 = 4; log2 >= 3; --log2) {
        if (r->tile_log2 && log2 != r->tile_log2) continue;
        if (!(log2 == 4 ? FIELD(c, TILE16) : FIELD(c, TILE8))) continue;
        result = layout_for(r, log2, storage, mode, out, layered);
        if (!result) return 0;
    }
    return result;
}

int opengpu_native_tile_plan(const struct opengpu_native_tile_request *r,
                            struct opengpu_native_tile_layout *out)
{ return tile_plan(r, out, 0); }

#define W(name) OPENGPU_NATIVE_BINNED_FRAME_##name##_WORD
#define VIEW(prefix) { W(prefix##_IMAGEWIDTH), W(prefix##_IMAGEHEIGHT), \
    W(prefix##_ROWPITCH), W(prefix##_READABLE), W(prefix##_WRITABLE), W(prefix##_STORE) }
struct image_words { unsigned width, height, pitch, read, write, store; };
static const struct image_words image_words[6] = {
    VIEW(FRAME_IMAGE_VIEWS0), VIEW(FRAME_IMAGE_VIEWS1), VIEW(FRAME_IMAGE_VIEWS2),
    VIEW(FRAME_IMAGE_VIEWS3), VIEW(FRAME_IMAGE_VIEWS4), VIEW(FRAME_IMAGE_VIEWS5)
};
/* Generated line-contained multiword fields make resolve entries nonuniform;
 * explicitly name every generated field instead of inferring a vector stride.
 */
static const struct image_words resolve_words[6] = {
    VIEW(FRAME_IMAGE_RESOLVES0_DESTINATION), VIEW(FRAME_IMAGE_RESOLVES1_DESTINATION),
    VIEW(FRAME_IMAGE_RESOLVES2_DESTINATION), VIEW(FRAME_IMAGE_RESOLVES3_DESTINATION),
    VIEW(FRAME_IMAGE_RESOLVES4_DESTINATION), VIEW(FRAME_IMAGE_RESOLVES5_DESTINATION)
};
static const unsigned resolve_enable[6] = {
    W(FRAME_IMAGE_RESOLVES0_ENABLE), W(FRAME_IMAGE_RESOLVES1_ENABLE),
    W(FRAME_IMAGE_RESOLVES2_ENABLE), W(FRAME_IMAGE_RESOLVES3_ENABLE),
    W(FRAME_IMAGE_RESOLVES4_ENABLE), W(FRAME_IMAGE_RESOLVES5_ENABLE)
};
#define COLOR(n) { W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_ENABLED), \
    W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_FORMAT), W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_LOADOP), \
    W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_CHANNELMASK), W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_OFFSET), \
    { W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_CLEAR0), W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_CLEAR1), \
      W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_CLEAR2), W(FRAME_IMAGE_PIXELS_COLORS_COLORS##n##_CLEAR3) } }
static const struct { unsigned enabled, format, load, mask, offset, clear[4]; } color_words[4] = {
    COLOR(0), COLOR(1), COLOR(2), COLOR(3)
};
#undef COLOR
#undef VIEW

static int empty_gem(const struct opengpu_native_frame_gem *g)
{
    return !g->handle && !g->offset && !g->bytes;
}
static int empty_image(const struct opengpu_native_frame_image *v)
{
    return empty_gem(&v->gem) && !v->row_pitch && !v->store;
}
static int empty_attachment(const struct opengpu_native_frame_attachment *a)
{
    return !a->format && !a->load_op && !a->channel_mask &&
        !a->clear[0] && !a->clear[1] && !a->clear[2] && !a->clear[3] &&
        empty_image(&a->image) && empty_image(&a->resolve);
}
static int add_resource(struct opengpu_native_frame_encoding *e,
                        const struct opengpu_native_frame_gem *g,
                        unsigned role, unsigned index, unsigned access, unsigned alignment)
{
    if (!g->handle || !g->bytes || g->bytes > (UINT64_C(1) << 32) ||
        g->offset > UINT64_MAX - g->bytes ||
        (alignment && ((g->offset | g->bytes) & (alignment - 1)))) return -EINVAL;
    /* Compare encompassing bus lines in original GEM coordinates. Repeated
     * read-only image views are safe; any overlap involving a writer is not.
     * The kernel also checks physical aliases and actual object bounds.
     */
    const uint64_t first = g->offset & ~UINT64_C(63);
    const uint64_t last = g->offset + g->bytes;
    if (last > UINT64_MAX - 63) return -EINVAL;
    const uint64_t end = (last + 63) & ~UINT64_C(63);
    for (unsigned i = 0; i < e->resource_count; ++i) {
        const struct opengpu_native_frame_resource *p = &e->resources[i];
        if (p->gem_handle == g->handle && ((access | p->access) & OPENGPU_NATIVE_FRAME_RESOURCE_WRITE)) {
            const uint64_t other_first = p->offset & ~UINT64_C(63);
            const uint64_t other_end = (p->offset + p->bytes + 63) & ~UINT64_C(63);
            if (first < other_end && other_first < end) return -EINVAL;
        }
    }
    if (e->resource_count >= OPENGPU_NATIVE_FRAME_MAX_RESOURCES) return -E2BIG;
    e->resources[e->resource_count++] = (struct opengpu_native_frame_resource) {
        .role = role, .index = index, .gem_handle = g->handle, .access = access,
        .offset = g->offset, .bytes = g->bytes
    };
    return 0;
}
static int encode_image(struct opengpu_native_frame_encoding *e,
                        const struct opengpu_native_frame_image *v,
                        const struct image_words *words, unsigned width, unsigned height,
                        unsigned samples, unsigned pixel_bytes, unsigned read, unsigned write,
                        unsigned role, unsigned index)
{
    const uint64_t row = (uint64_t)width * samples * pixel_bytes;
    const uint64_t useful = (uint64_t)(height - 1) * v->row_pitch + row;
    if (v->store > 1 || v->store != write || row > v->row_pitch || useful > v->gem.bytes) return -EINVAL;
    int error = add_resource(e, &v->gem, role, index,
        (read ? OPENGPU_NATIVE_FRAME_RESOURCE_READ : 0) |
        (write ? OPENGPU_NATIVE_FRAME_RESOURCE_WRITE : 0), 0);
    if (error) return error;
    uint32_t *w = e->frame.words;
    w[words->width] = width; w[words->height] = height; w[words->pitch] = v->row_pitch;
    w[words->read] = read; w[words->write] = write; w[words->store] = write;
    return 0;
}

static int frame_encode(const struct opengpu_native_frame_request *r,
    struct opengpu_native_frame_encoding *out, int layered,
    const uint64_t source_pitch[6], const uint64_t resolve_pitch[6])
{
    if (!r || !out || !r->caps) return -EINVAL;
    struct opengpu_native_frame_encoding e;
    memset(&e, 0, sizeof(e));
    struct opengpu_native_tile_request tile = {
        .caps = r->caps, .width = r->width, .height = r->height, .samples = r->samples,
        .color_mask = r->color_mask, .tile_log2 = r->tile_log2,
        .depth_format = r->attachments[4].format, .stencil_format = r->attachments[5].format
    };
    for (unsigned i = 0; i < 4; ++i) tile.color_format[i] = r->attachments[i].format;
    int error = tile_plan(&tile, &e.tile, layered);
    if (error) return error;
    const unsigned engines = FIELD(r->caps, ENGINES);
    if (!r->state_count || (uint64_t)r->state_count * 512 > (UINT64_C(1) << 32) ||
        r->scratch_stride < 64 || (r->scratch_stride & 63) || r->spill_count > 8) return -EINVAL;
    uint32_t *w = e.frame.words;
    w[0] = layered ? OPENGPU_NATIVE_LAYERED_FRAME_MAGIC : OPENGPU_NATIVE_BINNED_FRAME_MAGIC;
    w[1] = OPENGPU_NATIVE_BINNED_FRAME_BYTES;
    w[2] = layered ? OPENGPU_NATIVE_LAYERED_FRAME_VERSION : OPENGPU_NATIVE_BINNED_VERSION;
    if (layered) {
        if (!source_pitch || !resolve_pitch) return -EINVAL;
        w[OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_VERSION_WORD] = 1;
        w[OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_LAYERS_WORD] = 1;
        for (unsigned i = 0; i < 6; ++i) {
            if (source_pitch[i] > (UINT64_C(1) << 32) || resolve_pitch[i] > (UINT64_C(1) << 32) ||
                (source_pitch[i] && !r->attachments[i].image.gem.handle) ||
                (resolve_pitch[i] && !r->attachments[i].resolve.gem.handle)) return -EINVAL;
            unsigned sw = OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_SOURCEPITCH0_WORD + 2*i;
            unsigned rw = OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_RESOLVEPITCH0_WORD + 2*i;
            w[sw] = (uint32_t)source_pitch[i]; w[sw+1] = (uint32_t)(source_pitch[i] >> 32);
            w[rw] = (uint32_t)resolve_pitch[i]; w[rw+1] = (uint32_t)(resolve_pitch[i] >> 32);
        }
    }
    w[W(HEADER_ATTRIBUTES)] = 16; w[W(HEADER_BINDINGS)] = 16; w[W(HEADER_VARYINGS)] = 16;
    w[W(HEADER_COLORS)] = 4; w[W(HEADER_ENGINEVIEWS)] = 4;
#define SET(name, value) (w[W(name)] = (value))
#define ID(prefix) do { SET(prefix##_CONTEXT, r->context); SET(prefix##_GENERATION, r->generation); \
    SET(prefix##_PASS, r->pass); } while (0)
    ID(FRAME_BINS_PRODUCER_ARENA_IDENTITY); ID(FRAME_BINS_BINS_POOL_IDENTITY);
    ID(FRAME_STATES_IDENTITY); ID(FRAME_IMAGE_PIXELS_COLORS_IDENTITY);
    SET(FRAME_VERSION, layered ? 3 : (FIELD(r->caps, MAGIC) == OPENGPU_NATIVE_BINNED_HOST_WIDE_MAGIC ? 2 : 1));
    SET(FRAME_BINS_PRODUCER_VERSION, 1); SET(FRAME_BINS_PRODUCER_ARENA_VERSION, 1);
    SET(FRAME_BINS_PRODUCER_SCREENWIDTH, r->width); SET(FRAME_BINS_PRODUCER_SCREENHEIGHT, r->height);
    SET(FRAME_BINS_PRODUCER_TILELOG2, e.tile.tile_log2);
    SET(FRAME_BINS_BINS_VERSION, 3); SET(FRAME_BINS_BINS_TILECOUNT, e.tile.tile_count);
    SET(FRAME_BINS_BINS_POOL_VERSION, r->spill_count ? 2 : 1);
    SET(FRAME_BINS_BINS_POOL_SPILLCOUNT, r->spill_count); SET(FRAME_BINS_BINS_POOL_PAGESIZELOG2, 12);
    SET(FRAME_STATES_VERSION, 1); SET(FRAME_SCRATCHSTRIDE, r->scratch_stride);
    SET(FRAME_IMAGE_VERSION, 1); SET(FRAME_IMAGE_PIXELS_VERSION, 1);
    SET(FRAME_IMAGE_PIXELS_COLORS_VERSION, 1);
    SET(FRAME_IMAGE_PIXELS_COLORS_TILELOG2, e.tile.tile_log2);
    SET(FRAME_IMAGE_PIXELS_COLORS_TILEWIDTH, 1u << e.tile.tile_log2);
    SET(FRAME_IMAGE_PIXELS_COLORS_TILEHEIGHT, 1u << e.tile.tile_log2);
    SET(FRAME_IMAGE_PIXELS_COLORS_SAMPLEMODE, e.tile.sample_mode);
    SET(FRAME_IMAGE_PIXELS_COLORS_RESERVEDBYTES, e.tile.reserved_bytes);
    for (unsigned i = 0; i < 6; ++i) {
        const struct opengpu_native_frame_attachment *a = &r->attachments[i];
        const unsigned enabled = i < 4 ? !!(r->color_mask & (1u << i)) : !!a->format;
        if (!enabled) { if (!empty_attachment(a)) return -EINVAL; continue; }
        if (a->load_op > 2 || a->channel_mask > (i < 4 ? 15u : 0u)) return -EINVAL;
        if (i < 4) {
            w[color_words[i].enabled] = 1; w[color_words[i].format] = a->format;
            w[color_words[i].load] = a->load_op; w[color_words[i].mask] = a->channel_mask;
            w[color_words[i].offset] = e.tile.color_offset[i];
            for (unsigned c = 0; c < 4; ++c) w[color_words[i].clear[c]] = a->clear[c];
        } else {
            if (a->clear[1] > 255 || a->clear[2] || a->clear[3] ||
                (a->format <= 5 && a->load_op == 1 &&
                 (a->clear[0] & 0x7f800000u) == 0x7f800000u && (a->clear[0] & 0x7fffffu))) return -EINVAL;
            if (i == 4) {
                SET(FRAME_IMAGE_PIXELS_DEPTH_FORMAT, a->format); SET(FRAME_IMAGE_PIXELS_DEPTH_LOADOP, a->load_op);
                SET(FRAME_IMAGE_PIXELS_DEPTH_CLEARDEPTH, a->clear[0]); SET(FRAME_IMAGE_PIXELS_DEPTH_CLEARSTENCIL, a->clear[1]);
                SET(FRAME_IMAGE_PIXELS_DEPTH_OFFSET, e.tile.depth_offset);
            } else {
                SET(FRAME_IMAGE_PIXELS_STENCIL_FORMAT, a->format); SET(FRAME_IMAGE_PIXELS_STENCIL_LOADOP, a->load_op);
                SET(FRAME_IMAGE_PIXELS_STENCIL_CLEARDEPTH, a->clear[0]); SET(FRAME_IMAGE_PIXELS_STENCIL_CLEARSTENCIL, a->clear[1]);
                SET(FRAME_IMAGE_PIXELS_STENCIL_OFFSET, e.tile.stencil_offset);
            }
        }
        const unsigned bytes = i < 4 ? color_bytes[a->format] : depth_bytes[a->format];
        if (a->image.store || a->load_op == 0) {
            error = encode_image(&e, &a->image, &image_words[i], r->width, r->height,
                r->samples, bytes, a->load_op == 0, a->image.store, OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE, i);
            if (error) return error;
        } else if (!empty_image(&a->image)) return -EINVAL;
        if (a->resolve.gem.handle) {
            error = encode_image(&e, &a->resolve, &resolve_words[i], r->width, r->height,
                1, bytes, 0, 1, OPENGPU_NATIVE_FRAME_RESOURCE_RESOLVE, i);
            if (error) return error;
            w[resolve_enable[i]] = 1;
        } else if (!empty_image(&a->resolve)) return -EINVAL;
    }
#undef ID
#undef SET
    error = add_resource(&e, &r->primitives, OPENGPU_NATIVE_FRAME_RESOURCE_PRIMITIVES, 0, 3, 64);
    if (error) return error;
    if (r->directory.bytes < (uint64_t)e.tile.tile_count * 64) return -ENOSPC;
    error = add_resource(&e, &r->directory, OPENGPU_NATIVE_FRAME_RESOURCE_DIRECTORY, 0, 3, 64);
    if (error) return error;
    uint64_t pool_bytes = 0;
    for (unsigned i = 0; i < 9; ++i) {
        if (i <= r->spill_count) {
            if (!i && r->spill_count && empty_gem(&r->pool[i])) continue;
            error = add_resource(&e, &r->pool[i], OPENGPU_NATIVE_FRAME_RESOURCE_POOL, i, 3, 4096);
            if (error) return error;
            pool_bytes += r->pool[i].bytes;
        } else if (!empty_gem(&r->pool[i])) return -EINVAL;
    }
    if (pool_bytes > (UINT64_C(1) << 32)) return -ENOSPC;
    for (unsigned i = 0; i < 4; ++i) {
        if (i < engines) {
            if (r->scratch[i].bytes < (uint64_t)r->state_count * r->scratch_stride) return -ENOSPC;
            error = add_resource(&e, &r->scratch[i], OPENGPU_NATIVE_FRAME_RESOURCE_SCRATCH, i, 3, 64);
            if (error) return error;
        } else if (!empty_gem(&r->scratch[i])) return -EINVAL;
    }
    *out = e;
    return 0;
}
int opengpu_native_frame_encode(const struct opengpu_native_frame_request *r,
                               struct opengpu_native_frame_encoding *out)
{ return frame_encode(r, out, 0, NULL, NULL); }

int opengpu_native_frame_encode_gnc5(const struct opengpu_native_frame_request *r,
    const uint64_t source_pitch[6], const uint64_t resolve_pitch[6],
    struct opengpu_native_frame_encoding *out)
{ return frame_encode(r, out, 1, source_pitch, resolve_pitch); }
#undef W
#undef FIELD
