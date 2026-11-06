/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_TEXTURE_SNAPSHOT_H
#define OPENGPU_COMPUTE_TEXTURE_SNAPSHOT_H
#include "opengpu_compute_texture_mips.h"
#include "opengpu_compute_texture_sampler_validation.h"

enum opengpu_compute_texture_operation {
    OPENGPU_COMPUTE_TEXTURE_SIZE,
    OPENGPU_COMPUTE_TEXTURE_PACKET_FETCH,
    OPENGPU_COMPUTE_TEXTURE_CAPTURED_FETCH,
    OPENGPU_COMPUTE_TEXTURE_SAMPLE,
};

/* All pointers must refer to kernel-owned snapshots. Addresses identify the
 * GPU locations those snapshots will be published to; the caller must ensure
 * publication, mappings, ownership and immutability through completion. */
struct opengpu_compute_texture_snapshot {
    const struct opengpu_compute_texture_root_config *root;
    const struct opengpu_compute_texture_binding_config *binding;
    const struct opengpu_compute_texture_image_config *image;
    const struct opengpu_compute_texture_sampler_config *sampler;
    const struct opengpu_compute_texture_packet_config *packet;
    const struct opengpu_compute_texture_level_config *levels;
    const u32 *level_addresses;
    u32 root_address, binding_address, image_address, sampler_address, packet_address;
    u32 level_count, selected_level;
    enum opengpu_compute_texture_operation operation;
    bool shadow;
};

static inline bool opengpu_compute_texture_record_address(u32 address, u32 base,
    unsigned long long limit, u32 handle, u32 bytes)
{
    unsigned long long start = (unsigned long long)base + (unsigned long long)handle * bytes;
    return start == address && start + bytes <= limit;
}

/* selected_level is absolute; captured-fetch callers resolve relative LOD
 * first. Packet-fetch coordinates still require the hardware's image/target
 * checks. This validates structure, not the contents of GPU memory or a VM. */
static inline bool opengpu_validate_compute_texture_snapshot(
    const struct opengpu_compute_texture_snapshot *s)
{
    u32 words[OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS];
    bool size, packet_fetch, mip;
    if (!s || !s->root || !s->binding || !s->image || !s->levels || !s->level_addresses ||
        s->operation > OPENGPU_COMPUTE_TEXTURE_SAMPLE || (int)s->operation < 0 ||
        !s->level_count || s->level_count > 16u || (s->root_address & 63u) ||
        (unsigned long long)s->root_address + 128u > (1ULL << 32) ||
        !opengpu_encode_compute_texture_root(words, s->root) ||
        !opengpu_encode_compute_texture_binding(words, s->binding) ||
        !opengpu_encode_compute_texture_image(words, s->image))
        return false;
    size = s->operation == OPENGPU_COMPUTE_TEXTURE_SIZE;
    packet_fetch = s->operation == OPENGPU_COMPUTE_TEXTURE_PACKET_FETCH;
    if (s->binding->unit >= s->root->count || s->binding->context != s->root->context ||
        s->binding->pass != s->root->pass || s->binding->generation != s->root->generation ||
        s->image->context != s->root->context || s->image->pass != s->root->pass ||
        s->image->generation != s->root->generation || s->image->handle != s->binding->image_handle ||
        s->image->target != s->binding->target ||
        s->binding->expected_type != opengpu_compute_texture_formats[s->image->format].kind ||
        !opengpu_compute_texture_record_address(s->binding_address, s->root->bindings_base,
            s->root->bindings_limit, s->binding->unit, 64) ||
        !opengpu_compute_texture_record_address(s->image_address, s->root->images_base,
            s->root->images_limit, s->binding->image_handle, 128))
        return false;
    if (size || s->image->target == OPENGPU_TEXTURE_BUFFER_TARGET) {
        if (!size && !opengpu_validate_compute_texture_sampler(s->binding, s->image, NULL,
                s->operation == OPENGPU_COMPUTE_TEXTURE_SAMPLE, s->shadow))
            return false;
        if (!opengpu_validate_compute_texture_levels(s->image, s->levels, s->level_count,
                                                     s->selected_level, false))
            return false;
    } else {
        if (!s->sampler || !opengpu_compute_texture_record_address(s->sampler_address,
                s->root->samplers_base, s->root->samplers_limit, s->binding->sampler_handle, 64) ||
            !opengpu_validate_compute_texture_sampler(s->binding, s->image, s->sampler,
                s->operation == OPENGPU_COMPUTE_TEXTURE_SAMPLE, s->shadow))
            return false;
        mip = s->sampler->min_filter >= 2u;
        if (mip) {
            if (!opengpu_validate_compute_texture_levels(s->image, s->levels, s->level_count,
                                                         s->selected_level, true)) return false;
        } else if (s->selected_level != s->image->base_level) {
            if (s->level_count != 2u ||
                !opengpu_validate_compute_texture_levels(s->image, s->levels, 1,
                    s->image->base_level, false) ||
                !opengpu_validate_compute_texture_levels(s->image, s->levels + 1, 1,
                    s->selected_level, false)) return false;
        } else if (!opengpu_validate_compute_texture_levels(s->image, s->levels,
                        s->level_count, s->selected_level, false)) return false;
    }
    for (u32 i = 0; i < s->level_count; ++i)
        if (!opengpu_compute_texture_record_address(s->level_addresses[i], s->image->levels_base,
                s->image->levels_limit, s->levels[i].handle, 128)) return false;
    if (packet_fetch && (!s->packet || (s->packet_address & 63u) ||
        (unsigned long long)s->packet_address + 64u > (1ULL << 32) ||
        !opengpu_encode_compute_texture_packet(words, s->packet) ||
        s->packet->context != s->root->context || s->packet->pass != s->root->pass ||
        s->packet->generation != s->root->generation || s->packet->level != s->selected_level))
        return false;
    return true;
}
#endif
