/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_TESSELLATION_JOINT_HOST_H
#define OPENGPU_TESSELLATION_JOINT_HOST_H

#include "opengpu_native_sources_features.h"
#include "opengpu_protected_tessellation_joint_abi.h"

struct opengpu_protected_tessellation_joint_profile {
    u32 version, domains, compute_units, lanes, warps, engines;
    bool available, scalar_fpu, advanced_blend;
    bool owned_vertex_source, patch_zero_initialized, owned_patch_reads;
    bool owned_tessellation_info, primitive_inputs, sample_inputs;
    bool joint_directory_route, whole_owner_retirement, transform_feedback;
};

/* GCR1 guarantees this separate read-only record. Verify it against the
 * already validated native source provider and preserve old-device probing. */
static inline bool opengpu_read_tessellation_joint_profile(u32 flags, u32 capabilities,
    unsigned long long aperture, const struct opengpu_native_source_limits *native,
    bool advanced_blend, bool sample_transport, void *registers,
    u32 (*read_reg)(void *, u32), struct opengpu_protected_tessellation_joint_profile *out)
{
    const u32 base = OPENGPU_TESS_JOINT_HOST_PROFILE_BASE;
    const u32 transport = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE;
    const u32 required = OPENGPU_TESS_JOINT_HOST_PROFILE_REQUIRED_FLAGS;
    const u32 scalar = 1u << OPENGPU_TESS_JOINT_HOST_PROFILE_SCALAR_FPU_BIT;
    const u32 blend = 1u << OPENGPU_TESS_JOINT_HOST_PROFILE_ADVANCED_BLEND_BIT;
    const u32 samples = 1u << OPENGPU_TESS_JOINT_HOST_PROFILE_SAMPLES_BIT;
    const u32 feedback = 1u << OPENGPU_TESS_JOINT_HOST_PROFILE_FEEDBACK_BIT;
    const u32 topology = (8u << 24) | (16u << 16) | (2u << 8) |
        OPENGPU_TESS_JOINT_HOST_PROFILE_DOMAINS;
    u32 features, engines;

    if (!out)
        return false;
    if (!(flags & OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_V1)) {
        *out = (struct opengpu_protected_tessellation_joint_profile){0};
        return true;
    }
    if (!read_reg || !native || !native->available || (capabilities & transport) != transport ||
        (flags & (OPENGPU_COMPUTE_RESOURCE_TESSELLATION_COMMANDS |
                  OPENGPU_COMPUTE_RESOURCE_TESSELLATION_FEEDBACK)) ||
        !(flags & OPENGPU_COMPUTE_RESOURCE_NATIVE_PROTECTED_SOURCES_V1) ||
        aperture < base + OPENGPU_TESS_JOINT_HOST_PROFILE_WORDS * 4u ||
        read_reg(registers, base) != OPENGPU_TESS_JOINT_HOST_PROFILE_MAGIC)
        return false;
    features = read_reg(registers, base + 4u);
    if ((features & required) != required ||
        (features & ~(required | scalar | blend | samples | feedback)) ||
        ((features & scalar) && !(capabilities & GPU_CAP_COMPUTE_SCALAR_FPU)) ||
        ((features & blend) && !advanced_blend) ||
        ((features & samples) && !sample_transport) ||
        read_reg(registers, base + 8u) != topology)
        return false;
    engines = read_reg(registers, base + 12u);
#define JOINT_SOURCE_FIELD(name) \
    ((native->frame.words[OPENGPU_NATIVE_BINNED_HOST_##name##_WORD] & \
        OPENGPU_NATIVE_BINNED_HOST_##name##_MASK) >> OPENGPU_NATIVE_BINNED_HOST_##name##_SHIFT)
    if (!engines || engines > 4 || engines != native->engines ||
        native->lanes != 16 || native->warps != 8 || JOINT_SOURCE_FIELD(CUS) != 2 ||
        JOINT_SOURCE_FIELD(ATTRIBUTES) != 16 || JOINT_SOURCE_FIELD(BINDINGS) != 16 ||
        JOINT_SOURCE_FIELD(VARYINGS) != 16 || JOINT_SOURCE_FIELD(COLORS) != 4 ||
        JOINT_SOURCE_FIELD(SAMPLES) != 4)
        return false;
#undef JOINT_SOURCE_FIELD
    *out = (struct opengpu_protected_tessellation_joint_profile) {
        .version = 1, .domains = OPENGPU_TESS_JOINT_HOST_PROFILE_DOMAINS,
        .compute_units = 2, .lanes = native->lanes, .warps = native->warps,
        .engines = engines, .available = true, .scalar_fpu = !!(features & scalar),
        .advanced_blend = !!(features & blend), .sample_inputs = !!(features & samples),
        .owned_vertex_source = true, .patch_zero_initialized = true,
        .owned_patch_reads = true, .owned_tessellation_info = true, .primitive_inputs = true,
        .joint_directory_route = true, .whole_owner_retirement = true,
        .transform_feedback = !!(features & feedback),
    };
    return true;
}

struct opengpu_tessellation_joint_completion {
    u32 status, fault_stage, fault_address, context, pass, generation;
    u32 captured_entries, retired_ranges;
    unsigned long long read_bytes, retired_patches, published, stored_bytes, resolved_bytes;
    bool clear_only, valid;
};

/* Called under the fence lock for a matching held command, before POP. The
 * detail tail is separate from the first twelve words and the GNZ1 window. */
static inline bool opengpu_capture_tessellation_joint_completion(u32 status,
    unsigned long long bytes, unsigned long long aperture, u32 context, u32 pass,
    u32 generation, void *registers, u32 (*read_reg)(void *, u32),
    struct opengpu_tessellation_joint_completion *out)
{
    const u32 base = OPENGPU_TESS_JOINT_HOST_COMPLETION_HEAD_BASE;
    const u32 tail = OPENGPU_TESS_JOINT_HOST_COMPLETION_TAIL_BASE;
    const unsigned head_words = OPENGPU_TESS_JOINT_HOST_COMPLETION_HEAD_WORDS;
    u32 words[OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS];
    unsigned i;

    if (!read_reg || !out || status > 7 || (bytes & 63ull) ||
        aperture < tail + (OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS - head_words) * 4u ||
        read_reg(registers, base) != OPENGPU_TESS_JOINT_HOST_COMPLETION_MAGIC)
        return false;
    for (i = 1; i < head_words; ++i)
        words[i] = read_reg(registers, base + 4u * i);
    if ((words[1] & ~0x7fu) || (words[1] & 7u) != status ||
        words[3] != context || words[4] != pass || words[5] != generation ||
        ((!status || status == 4u) && ((words[1] & 0x38u) || words[2])))
        return false;
    for (; i < OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS; ++i)
        words[i] = read_reg(registers, tail + 4u * (i - head_words));

    *out = (struct opengpu_tessellation_joint_completion) {
        .status = status, .fault_stage = (words[1] >> 3) & 7u, .fault_address = words[2],
        .context = context, .pass = pass, .generation = generation,
        .captured_entries = words[6], .retired_ranges = words[7],
        .read_bytes = bytes, .retired_patches = words[8] | ((unsigned long long)words[9] << 32),
        .published = words[10] | ((unsigned long long)words[11] << 32),
        .stored_bytes = words[12] | ((unsigned long long)words[13] << 32),
        .resolved_bytes = words[14] | ((unsigned long long)words[15] << 32),
        .clear_only = !!(words[1] & 0x40u), .valid = true,
    };
    return true;
}

#endif
