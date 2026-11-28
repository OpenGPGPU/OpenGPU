/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_TESSELLATION_JOINT_HOST_H
#define OPENGPU_TESSELLATION_JOINT_HOST_H

#ifndef __KERNEL__
#include <stddef.h>
#endif
#include "opengpu_native_sources_features.h"
#include "opengpu_native_geometry_transport_abi.h"
#include "opengpu_protected_tessellation_joint_abi.h"
#include "opengpu_protected_tessellation_joint_geometry_abi.h"

enum opengpu_tessellation_joint_report_kind {
    OPENGPU_TESSELLATION_JOINT_REPORT_NONE,
    OPENGPU_TESSELLATION_JOINT_REPORT_GNZ2,
    OPENGPU_TESSELLATION_JOINT_REPORT_GNZ3,
};

struct opengpu_protected_tessellation_joint_profile {
    u32 version, domains, compute_units, lanes, warps, engines;
    bool available, scalar_fpu, advanced_blend;
    bool owned_vertex_source, patch_zero_initialized, owned_patch_reads;
    bool owned_tessellation_info, primitive_inputs, sample_inputs;
    bool joint_directory_route, whole_owner_retirement, transform_feedback;
};

/* Export the same verified GJP1 and GNC records through DRM GET_PARAM.
 * The kernel cache is populated only after the hardware and LDS probes. */
static inline u32 opengpu_tessellation_joint_profile_word(
    const struct opengpu_protected_tessellation_joint_profile *profile, unsigned word)
{
    if (!profile->available)
        return 0;
    switch (word) {
    case 0:
        return OPENGPU_TESS_JOINT_HOST_PROFILE_MAGIC;
    case 1:
        return OPENGPU_TESS_JOINT_HOST_PROFILE_REQUIRED_FLAGS |
            ((u32)profile->scalar_fpu << OPENGPU_TESS_JOINT_HOST_PROFILE_SCALAR_FPU_BIT) |
            ((u32)profile->advanced_blend << OPENGPU_TESS_JOINT_HOST_PROFILE_ADVANCED_BLEND_BIT) |
            ((u32)profile->sample_inputs << OPENGPU_TESS_JOINT_HOST_PROFILE_SAMPLES_BIT) |
            ((u32)profile->transform_feedback << OPENGPU_TESS_JOINT_HOST_PROFILE_FEEDBACK_BIT);
    case 2:
        return profile->warps << 24 | profile->lanes << 16 |
            profile->compute_units << 8 | profile->domains;
    case 3:
        return profile->engines;
    default:
        return 0;
    }
}

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

struct opengpu_protected_tessellation_joint_geometry_profile {
    u32 version, domains, compute_units, lanes, warps, engines;
    char provider[48];
    u32 owner_resource_version, payload_bytes, max_payload_bytes, payload_owners, payload_ports;
    u32 image_producer_pins, single_domain_total_pins;
    bool available, scalar_fpu, advanced_blend;
    bool owned_vertex_source, patch_zero_initialized, owned_patch_reads;
    bool owned_tessellation_info, geometry_inputs, sample_inputs;
    bool geometry_route, geometry_uniforms;
    bool joint_directory_route, whole_owner_retirement;
};

static inline bool opengpu_read_tessellation_joint_geometry_profile(u32 flags, u32 capabilities,
    unsigned long long aperture, const struct opengpu_native_source_limits *native,
    bool sample_transport, void *registers,
    u32 (*read_reg)(void *, u32), struct opengpu_protected_tessellation_joint_geometry_profile *out)
{
    const u32 base = OPENGPU_JOINT_GEOMETRY_PROFILE_BASE;
    const u32 required = OPENGPU_COMPUTE_RESOURCE_NATIVE_PROTECTED_SOURCES_V1 |
        OPENGPU_COMPUTE_RESOURCE_NATIVE_DESCRIPTOR_PREFLIGHT_V1 |
        OPENGPU_COMPUTE_RESOURCE_NATIVE_GEOMETRY_TRANSPORT_V1 | 7u;
    const u32 transport = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE |
        GPU_CAP_COMPUTE_SCALAR_FPU | GPU_CAP_SHADER_CONFIG_INFO | GPU_CAP_NATIVE_PIXEL_FORMATS;
    u32 words[OPENGPU_JOINT_GEOMETRY_PROFILE_WORDS], i;

    if (!out)
        return false;
    if (!(flags & OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_GEOMETRY_V1)) {
        *out = (struct opengpu_protected_tessellation_joint_geometry_profile){0};
        return true;
    }
    if (!read_reg || !native || !native->available || sample_transport ||
        (capabilities & transport) != transport ||
        ((capabilities & GPU_CAP_FRAGMENT_BATCH_MASK) >> GPU_CAP_FRAGMENT_BATCH_SHIFT) != 128 ||
        (flags & required) != required ||
        (flags & (OPENGPU_COMPUTE_RESOURCE_TESSELLATION_COMMANDS |
                  OPENGPU_COMPUTE_RESOURCE_TESSELLATION_FEEDBACK |
                  OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_V1)) ||
        aperture < OPENGPU_JOINT_GEOMETRY_PROFILE_END)
        return false;
    for (i = 0; i < OPENGPU_JOINT_GEOMETRY_PROFILE_WORDS; ++i)
        words[i] = read_reg(registers, base + 4u * i);
    if (words[0] != OPENGPU_JOINT_GEOMETRY_PROFILE_MAGIC ||
        words[1] != OPENGPU_JOINT_GEOMETRY_PROFILE_FLAGS ||
        words[2] != OPENGPU_JOINT_GEOMETRY_PROFILE_SHAPE_WORD ||
        words[3] != OPENGPU_JOINT_GEOMETRY_PROFILE_CAPACITY_WORD ||
        native->geometry_words[0] != OPENGPU_NATIVE_GEOMETRY_TRANSPORT_MAGIC)
        return false;
#define JOINT_GEOMETRY_SOURCE_FIELD(name) \
    ((native->frame.words[OPENGPU_NATIVE_BINNED_HOST_##name##_WORD] & \
        OPENGPU_NATIVE_BINNED_HOST_##name##_MASK) >> OPENGPU_NATIVE_BINNED_HOST_##name##_SHIFT)
    if (native->lanes != 16 || native->warps != 8 || native->engines != 4 ||
        JOINT_GEOMETRY_SOURCE_FIELD(CUS) != 2 ||
        JOINT_GEOMETRY_SOURCE_FIELD(ATTRIBUTES) != 16 ||
        JOINT_GEOMETRY_SOURCE_FIELD(BINDINGS) != 16 ||
        JOINT_GEOMETRY_SOURCE_FIELD(VARYINGS) != 16 ||
        JOINT_GEOMETRY_SOURCE_FIELD(COLORS) != 4 ||
        JOINT_GEOMETRY_SOURCE_FIELD(SAMPLES) != 4)
        return false;
#undef JOINT_GEOMETRY_SOURCE_FIELD
    *out = (struct opengpu_protected_tessellation_joint_geometry_profile) {
        .version = 1, .domains = 7, .compute_units = 2, .lanes = 16, .warps = 8, .engines = 4,
        .provider = OPENGPU_JOINT_GEOMETRY_PROFILE_PROVIDER, .owner_resource_version = 3,
        .payload_bytes = 524288, .max_payload_bytes = 69632, .payload_owners = 5,
        .payload_ports = 12, .image_producer_pins = 34, .single_domain_total_pins = 37,
        .available = true, .scalar_fpu = true, .advanced_blend = false,
        .owned_vertex_source = true, .patch_zero_initialized = true, .owned_patch_reads = true,
        .owned_tessellation_info = true, .geometry_inputs = true, .sample_inputs = false,
        .geometry_route = true, .geometry_uniforms = true, .joint_directory_route = true,
        .whole_owner_retirement = true,
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

struct opengpu_tessellation_joint_geometry_completion {
    u32 status, fault_stage, fault_address, context, pass, generation;
    u32 captured_entries, retired_ranges;
    unsigned long long read_bytes, retired_patches, published, stored_bytes, resolved_bytes;
    unsigned long long actual_gs_published[3], received[3], dropped[3], admitted[2];
    unsigned long long geometry_tasks, next_gs_sequence_exclusive[3], next_gs_sequence, next_bin_sequence;
    bool clear_only, geometry_report_valid, valid;
};

static inline bool opengpu_decode_tessellation_joint_geometry_completion(
    const u32 *words, size_t count, u32 status, u32 context, u32 pass, u32 generation,
    struct opengpu_tessellation_joint_geometry_completion *out)
{
    struct opengpu_tessellation_joint_geometry_completion decoded = {0};
    unsigned long long sum[6] = {0}, carry = 0;
    u32 i;

    if (!words || !out || count < OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS || status > 7 ||
        words[0] != OPENGPU_JOINT_GEOMETRY_COMPLETION_MAGIC || (words[1] & ~0xffu) ||
        (words[1] & 7u) != status || words[3] != context || words[4] != pass ||
        words[5] != generation || (words[20] & ~0x3fffffffu) ||
        (words[26] & ~1u) || (words[32] & ~1u) || (words[36] & ~1u) ||
        (words[41] & ~1u) || (words[45] & ~1u))
        return false;
    decoded.status = status;
    decoded.fault_stage = (words[1] >> 3) & 7u;
    decoded.clear_only = !!(words[1] & 0x40u);
    decoded.geometry_report_valid = !!(words[1] & 0x80u);
    decoded.fault_address = words[2];
    decoded.context = context;
    decoded.pass = pass;
    decoded.generation = generation;
    decoded.captured_entries = words[6];
    decoded.retired_ranges = words[7];
    decoded.retired_patches = words[8] | ((unsigned long long)words[9] << 32);
    decoded.published = words[10] | ((unsigned long long)words[11] << 32);
    decoded.stored_bytes = words[12] | ((unsigned long long)words[13] << 32);
    decoded.resolved_bytes = words[14] | ((unsigned long long)words[15] << 32);
    for (i = 0; i < 5; ++i)
        decoded.actual_gs_published[i / 2] |= (unsigned long long)words[16 + i] << (32u * (i % 2));
    for (i = 0; i < 6; ++i) {
        decoded.received[i / 2] |= (unsigned long long)words[21 + i] << (32u * (i % 2));
        decoded.dropped[i / 2] |= (unsigned long long)words[27 + i] << (32u * (i % 2));
    }
    for (i = 0; i < 4; ++i)
        decoded.admitted[i / 2] |= (unsigned long long)words[33 + i] << (32u * (i % 2));
    decoded.geometry_tasks = words[37] | ((unsigned long long)words[38] << 32);
    for (i = 0; i < 3; ++i)
        decoded.next_gs_sequence_exclusive[i / 2] |= (unsigned long long)words[39 + i] << (32u * (i % 2));
    decoded.next_gs_sequence = words[42] | ((unsigned long long)words[43] << 32);
    decoded.next_bin_sequence = words[44] | ((unsigned long long)words[45] << 32);
    if (!decoded.geometry_report_valid) {
        for (i = 16; i < OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS; ++i)
            if (words[i])
                return false;
    }
    if ((!status && (!decoded.geometry_report_valid || decoded.fault_stage || decoded.fault_address)) ||
        (!decoded.fault_stage && decoded.fault_address) ||
        (decoded.fault_stage && !status))
        return false;
    if (decoded.next_gs_sequence != decoded.next_gs_sequence_exclusive[0])
        return false;
    for (i = 0; i < 4; ++i)
        sum[i] = (decoded.admitted[i / 2] >> (32u * (i % 2))) & 0xffffffffu;
    for (i = 0; i < 6; ++i) {
        unsigned long long value = sum[i] +
            ((decoded.dropped[i / 2] >> (32u * (i % 2))) & 0xffffffffu) + carry;
        sum[i] = value & 0xffffffffu;
        carry = value >> 32;
    }
    if (carry)
        return false;
    for (i = 0; i < 6; ++i)
        if (sum[i] != ((decoded.received[i / 2] >> (32u * (i % 2))) & 0xffffffffu))
            return false;
    decoded.valid = true;
    *out = decoded;
    return true;
}

static inline bool opengpu_capture_tessellation_joint_geometry_completion(u32 status,
    unsigned long long bytes, unsigned long long aperture, u32 context, u32 pass,
    u32 generation, void *registers, u32 (*read_reg)(void *, u32),
    struct opengpu_tessellation_joint_geometry_completion *out)
{
    u32 words[OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS], i;
    struct opengpu_tessellation_joint_geometry_completion decoded;

    if (!read_reg || !out || status > 7 || (bytes & 63ull) ||
        aperture < OPENGPU_JOINT_GEOMETRY_COMPLETION_END ||
        read_reg(registers, OPENGPU_JOINT_GEOMETRY_COMPLETION_PREFIX_BASE) !=
            OPENGPU_JOINT_GEOMETRY_COMPLETION_MAGIC)
        return false;
    words[0] = OPENGPU_JOINT_GEOMETRY_COMPLETION_MAGIC;
    for (i = 1; i < OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS; ++i) {
        u32 address = i < 12 ? OPENGPU_JOINT_GEOMETRY_COMPLETION_PREFIX_BASE + 4u * i :
            i < OPENGPU_JOINT_GEOMETRY_COMPLETION_PREFIX_WORDS ?
                OPENGPU_JOINT_GEOMETRY_COMPLETION_LEGACY_TAIL_BASE + 4u * (i - 12u) :
                OPENGPU_JOINT_GEOMETRY_COMPLETION_GEOMETRY_BASE +
                    4u * (i - OPENGPU_JOINT_GEOMETRY_COMPLETION_PREFIX_WORDS);
        words[i] = read_reg(registers, address);
    }
    for (i = 0; i < OPENGPU_JOINT_GEOMETRY_COMPLETION_RESERVED_WORDS; ++i)
        if (read_reg(registers, OPENGPU_JOINT_GEOMETRY_COMPLETION_RESERVED_BASE + 4u * i))
            return false;
    if (!opengpu_decode_tessellation_joint_geometry_completion(words,
            OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS, status, context, pass, generation, &decoded))
        return false;
    decoded.read_bytes = bytes;
    *out = decoded;
    return true;
}

#endif
