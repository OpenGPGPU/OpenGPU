#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "opengpu_tessellation_joint_host.h"

typedef uint32_t u32;
typedef uint64_t u64;

struct geometry_registers {
    u32 profile[4], words[46], padding[4];
    unsigned reads, detail_reads, padding_reads;
};

static u32 geometry_address(unsigned word)
{
    return word < 12 ? 0x180u + 4u * word :
        word < 16 ? 0x540u + 4u * (word - 12u) : 0x560u + 4u * (word - 16u);
}

static u32 geometry_read(void *opaque, u32 address)
{
    struct geometry_registers *regs = opaque;
    unsigned i;
    ++regs->reads;
    if (address >= 0x600u && address < 0x610u)
        return regs->profile[(address - 0x600u) / 4u];
    for (i = 0; i < 46; ++i) {
        if (address == geometry_address(i)) {
            ++regs->detail_reads;
            return regs->words[i];
        }
    }
    for (i = 0; i < 4; ++i) {
        if (address == 0x5d8u + 4u * i) {
            ++regs->padding_reads;
            return regs->padding[i];
        }
    }
    assert(0);
    return 0;
}

static void geometry_profile_test(void)
{
    const u32 flags = OPENGPU_COMPUTE_RESOURCE_NATIVE_PROTECTED_SOURCES_V1 |
        OPENGPU_COMPUTE_RESOURCE_NATIVE_DESCRIPTOR_PREFLIGHT_V1 |
        OPENGPU_COMPUTE_RESOURCE_NATIVE_GEOMETRY_TRANSPORT_V1 |
        OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_GEOMETRY_V1 | 7u;
    const u32 caps = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE |
        GPU_CAP_COMPUTE_SCALAR_FPU | GPU_CAP_SHADER_CONFIG_INFO | GPU_CAP_NATIVE_PIXEL_FORMATS |
        (128u << GPU_CAP_FRAGMENT_BATCH_SHIFT);
    struct geometry_registers regs = {.profile = {0x474a5002u, 0x8038ffu,
        0x08100207u, 0x8ce58b2cu}};
    struct opengpu_native_source_limits native = {.available = true, .lanes = 16,
        .warps = 8, .engines = 4};
    struct opengpu_protected_tessellation_joint_geometry_profile profile;
    native.geometry_words[0] = OPENGPU_NATIVE_GEOMETRY_TRANSPORT_MAGIC;
    native.frame.words[4] = (16u << 16) | (16u << 21) | (16u << 26);
    native.frame.words[6] = (4u << 21) | (4u << 24);
    native.frame.words[7] = 2;
    memset(&profile, 0xa5, sizeof(profile));
    assert(opengpu_read_tessellation_joint_geometry_profile(flags & ~(1u << 23), caps,
        0x610, &native, true, false, &regs, geometry_read, &profile));
    assert(!profile.available && !regs.reads);
    assert(!opengpu_read_tessellation_joint_geometry_profile(flags, caps, 0x60f,
        &native, true, false, &regs, geometry_read, &profile) && !regs.reads);
    assert(opengpu_read_tessellation_joint_geometry_profile(flags, caps, 0x610,
        &native, true, false, &regs, geometry_read, &profile));
    assert(regs.reads == 4 && profile.available && profile.version == 1 &&
        profile.domains == 7 && profile.compute_units == 2 && profile.lanes == 16 &&
        profile.warps == 8 && profile.engines == 4 && profile.scalar_fpu &&
        profile.advanced_blend && !profile.sample_inputs && profile.geometry_route &&
        profile.geometry_uniforms && profile.whole_owner_retirement &&
        !strcmp(profile.provider, OPENGPU_JOINT_GEOMETRY_PROFILE_PROVIDER));
    for (unsigned word = 0; word < 4; ++word) {
        for (unsigned bit = 0; bit < 32; ++bit) {
            struct opengpu_protected_tessellation_joint_geometry_profile saved;
            u32 value = regs.profile[word];
            memcpy(&saved, &profile, sizeof(saved));
            regs.profile[word] ^= 1u << bit;
            assert(!opengpu_read_tessellation_joint_geometry_profile(flags, caps, 0x610,
                &native, true, false, &regs, geometry_read, &profile));
            assert(!memcmp(&saved, &profile, sizeof(saved)));
            regs.profile[word] = value;
        }
    }
    assert(!opengpu_read_tessellation_joint_geometry_profile(flags, caps, 0x610,
        &native, false, false, &regs, geometry_read, &profile));
    assert(!opengpu_read_tessellation_joint_geometry_profile(flags, caps, 0x610,
        &native, true, true, &regs, geometry_read, &profile));
    assert(!opengpu_read_tessellation_joint_geometry_profile(flags |
        OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_V1, caps, 0x610,
        &native, true, false, &regs, geometry_read, &profile));
    native.lanes = 8;
    assert(!opengpu_read_tessellation_joint_geometry_profile(flags, caps, 0x610,
        &native, true, false, &regs, geometry_read, &profile));
}

static void geometry_words(u32 words[46], u32 status)
{
    memset(words, 0, 46 * sizeof(*words));
    words[0] = 0x474e5a03u;
    words[1] = 0x80u | status;
    words[3] = 42;
    words[4] = 8;
    words[5] = 9;
    words[6] = 10;
    words[7] = 11;
    words[8] = 0x89abcdefu;
    words[9] = 0x01234567u;
    words[10] = 0xfedcba98u;
    words[11] = 0x76543210u;
    words[12] = 0x11111111u;
    words[13] = 0x22222222u;
    words[14] = 0x33333333u;
    words[15] = 0x44444444u;
    for (unsigned i = 16; i < 21; ++i)
        words[i] = 0x10203040u + i;
    words[20] = 0x3fffffffu;
    words[32] = 1;
    words[33] = 0xffffffffu;
    words[21] = 0xffffffffu;
    words[26] = 1;
    words[37] = 0x13579bdfu;
    words[38] = 0x2468ace0u;
    words[39] = 0;
    words[40] = 0;
    words[41] = 1;
    words[42] = 0;
    words[43] = 0;
    words[44] = 0xffffffffu;
    words[45] = 1;
}

static bool geometry_decode(const u32 words[46], u32 status,
    struct opengpu_tessellation_joint_geometry_completion *out)
{
    return opengpu_decode_tessellation_joint_geometry_completion(words, 46, status,
        42, 8, 9, out);
}

static void geometry_completion_test(void)
{
    u32 words[46];
    struct opengpu_tessellation_joint_geometry_completion out, saved;
    geometry_words(words, 0);
    assert(geometry_decode(words, 0, &out) && out.valid && out.geometry_report_valid &&
        out.context == 42 && out.pass == 8 && out.generation == 9 &&
        out.retired_patches == 0x0123456789abcdefull &&
        out.actual_gs_published[2] == 0x3fffffffu && out.received[2] == (1ull << 32) &&
        out.dropped[2] == (1ull << 32) && out.admitted[0] == 0xffffffffu && !out.admitted[1] &&
        out.next_gs_sequence_exclusive[1] == 1 && out.next_gs_sequence == 0 &&
        out.next_bin_sequence == 0x1ffffffffull);
    memcpy(&saved, &out, sizeof(saved));
    const u32 high_words[] = {20, 26, 32, 36, 41, 45};
    const u32 high_bits[] = {0x40000000u, 2u, 2u, 2u, 2u, 2u};
    for (unsigned i = 0; i < sizeof(high_words) / sizeof(high_words[0]); ++i) {
        u32 value = words[high_words[i]];
        words[high_words[i]] |= high_bits[i];
        assert(!geometry_decode(words, 0, &out) && !memcmp(&saved, &out, sizeof(saved)));
        words[high_words[i]] = value;
    }
    words[1] |= 0x100u;
    assert(!geometry_decode(words, 0, &out) && !memcmp(&saved, &out, sizeof(saved)));
    words[1] ^= 0x100u;
    words[1] = 0x81u;
    assert(!geometry_decode(words, 0, &out) && !memcmp(&saved, &out, sizeof(saved)));
    words[1] = 0x80u;
    words[0] ^= 1u;
    assert(!geometry_decode(words, 0, &out) && !memcmp(&saved, &out, sizeof(saved)));
    words[0] ^= 1u;
    for (unsigned word = 3; word <= 5; ++word) {
        words[word] ^= 1u;
        assert(!geometry_decode(words, 0, &out) && !memcmp(&saved, &out, sizeof(saved)));
        words[word] ^= 1u;
    }
    words[21] ^= 1u;
    assert(!geometry_decode(words, 0, &out) && !memcmp(&saved, &out, sizeof(saved)));
    words[21] ^= 1u;
    words[27] = 1;
    assert(!geometry_decode(words, 0, &out) && !memcmp(&saved, &out, sizeof(saved)));
    words[27] = 0;
    words[42] = 1;
    assert(!geometry_decode(words, 0, &out));
    words[42] = 0;
    words[1] = 0;
    memset(words + 16, 0, 30 * sizeof(*words));
    assert(!geometry_decode(words, 0, &out));
    geometry_words(words, 3);
    words[1] = 3;
    memset(words + 16, 0, 30 * sizeof(*words));
    assert(geometry_decode(words, 3, &out) && out.valid && !out.geometry_report_valid);
    words[16] = 1;
    assert(!geometry_decode(words, 3, &out));
    words[16] = 0;
    words[2] = 0x1240u;
    assert(!geometry_decode(words, 3, &out));
    words[2] = 0;
    words[1] |= 6u << 3;
    assert(geometry_decode(words, 3, &out) && out.fault_stage == 6);
    geometry_words(words, 0);
    words[1] |= 6u << 3;
    assert(!geometry_decode(words, 0, &out));
}

static void geometry_capture_test(void)
{
    struct geometry_registers regs = {0};
    struct opengpu_tessellation_joint_geometry_completion out, saved;
    geometry_words(regs.words, 0);
    memset(&out, 0xa5, sizeof(out));
    assert(opengpu_capture_tessellation_joint_geometry_completion(0, 4096, 0x5e8, 42, 8, 9,
        &regs, geometry_read, &out));
    assert(out.valid && regs.detail_reads == 46 && regs.padding_reads == 4 && regs.reads == 50);
    memcpy(&saved, &out, sizeof(saved));
    regs.padding[2] = 1;
    assert(!opengpu_capture_tessellation_joint_geometry_completion(0, 4096, 0x5e8, 42, 8, 9,
        &regs, geometry_read, &out) && !memcmp(&saved, &out, sizeof(saved)));
    regs.padding[2] = 0;
    regs.words[0] = 0;
    assert(!opengpu_capture_tessellation_joint_geometry_completion(0, 4096, 0x5e8, 42, 8, 9,
        &regs, geometry_read, &out) && !memcmp(&saved, &out, sizeof(saved)));
    geometry_words(regs.words, 0);
    regs.words[5] = 10;
    assert(!opengpu_capture_tessellation_joint_geometry_completion(0, 4096, 0x5e8, 42, 8, 9,
        &regs, geometry_read, &out) && !memcmp(&saved, &out, sizeof(saved)));
    geometry_words(regs.words, 0);
    unsigned reads = regs.reads;
    assert(!opengpu_capture_tessellation_joint_geometry_completion(0, 4095, 0x5e8, 42, 8, 9,
        &regs, geometry_read, &out) && regs.reads == reads);
    assert(!opengpu_capture_tessellation_joint_geometry_completion(0, 4096, 0x5e7, 42, 8, 9,
        &regs, geometry_read, &out) && regs.reads == reads);
    assert(!opengpu_capture_tessellation_joint_geometry_completion(8, 4096, 0x5e8, 42, 8, 9,
        &regs, geometry_read, &out) && regs.reads == reads);
}

int main(void)
{
    geometry_profile_test();
    geometry_completion_test();
    geometry_capture_test();
    puts("GJP2/GNZ3 private geometry host codec: PASS");
    return 0;
}
