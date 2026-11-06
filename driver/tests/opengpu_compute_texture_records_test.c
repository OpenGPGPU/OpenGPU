#include <assert.h>
#include <string.h>
#include "../opengpu_compute_texture_records.h"
#include "../opengpu_native_texture_abi.h"
#include "../opengpu_texture_sampler_abi.h"
#include "../opengpu_texture_image_binding_abi.h"
#include "../opengpu_texture_image_format_abi.h"
#include "../opengpu_texture_image_level_abi.h"

_Static_assert(OPENGPU_TEXTURE_FORMAT_RGB9_E5 == 48u &&
               OPENGPU_TEXTURE_FORMAT_DEPTH_COMPONENT16 == 49u &&
               OPENGPU_TEXTURE_FORMAT_RGB8_ETC2 == 54u &&
               OPENGPU_TEXTURE_FORMAT_SIGNED_RG11_EAC == 63u,
               "image target checks must follow the generated format ABI");

static void buffer_records(void)
{
    /* GLES3.2 table8.18, independently enumerated from the generated policy. */
    static const struct { u32 format, bytes; } formats[] = {
        {OPENGPU_TEXTURE_FORMAT_R8,1}, {OPENGPU_TEXTURE_FORMAT_R16F,2}, {OPENGPU_TEXTURE_FORMAT_R32F,4},
        {OPENGPU_TEXTURE_FORMAT_R8I,1}, {OPENGPU_TEXTURE_FORMAT_R16I,2}, {OPENGPU_TEXTURE_FORMAT_R32I,4},
        {OPENGPU_TEXTURE_FORMAT_R8UI,1}, {OPENGPU_TEXTURE_FORMAT_R16UI,2}, {OPENGPU_TEXTURE_FORMAT_R32UI,4},
        {OPENGPU_TEXTURE_FORMAT_RG8,2}, {OPENGPU_TEXTURE_FORMAT_RG16F,4}, {OPENGPU_TEXTURE_FORMAT_RG32F,8},
        {OPENGPU_TEXTURE_FORMAT_RG8I,2}, {OPENGPU_TEXTURE_FORMAT_RG16I,4}, {OPENGPU_TEXTURE_FORMAT_RG32I,8},
        {OPENGPU_TEXTURE_FORMAT_RG8UI,2}, {OPENGPU_TEXTURE_FORMAT_RG16UI,4}, {OPENGPU_TEXTURE_FORMAT_RG32UI,8},
        {OPENGPU_TEXTURE_FORMAT_RGB32F,12}, {OPENGPU_TEXTURE_FORMAT_RGB32I,12}, {OPENGPU_TEXTURE_FORMAT_RGB32UI,12},
        {OPENGPU_TEXTURE_FORMAT_RGBA8,4}, {OPENGPU_TEXTURE_FORMAT_RGBA16F,8}, {OPENGPU_TEXTURE_FORMAT_RGBA32F,16},
        {OPENGPU_TEXTURE_FORMAT_RGBA8I,4}, {OPENGPU_TEXTURE_FORMAT_RGBA16I,8}, {OPENGPU_TEXTURE_FORMAT_RGBA32I,16},
        {OPENGPU_TEXTURE_FORMAT_RGBA8UI,4}, {OPENGPU_TEXTURE_FORMAT_RGBA16UI,8}, {OPENGPU_TEXTURE_FORMAT_RGBA32UI,16},
    };
    u32 words[32], saved[32];
    _Static_assert(sizeof(formats)/sizeof(formats[0]) == 30, "required buffer formats");
    _Static_assert(OPENGPU_TEXTURE_BUFFER_MAX_TEXELS >= 65536u, "GLES buffer minimum");
    for (u32 format = 0; format < 64; ++format) {
        u32 bytes = 0;
        for (unsigned i = 0; i < sizeof(formats)/sizeof(formats[0]); ++i)
            if (formats[i].format == format) bytes = formats[i].bytes;
        assert(opengpu_texture_buffer_format_bytes(format) == bytes);
        struct opengpu_compute_texture_image_config image = {
            .target=5, .format=format, .width=1, .height=1, .depth=1, .layers=1,
            .allocation_limit=1ULL<<32, .levels_limit=128,
            .buffer_texels=65536, .buffer_bytes=65536ULL*(bytes ? bytes : 4) + (bytes ? bytes-1 : 0),
        };
        assert(opengpu_encode_compute_texture_image(words,&image) == (bytes != 0));
        if (!bytes) continue;
        assert(words[7] == 5 && words[11] == 1 && words[28] == 65536 &&
               words[29] == image.buffer_bytes && words[30] == 0 && words[31] == 0);
        struct opengpu_compute_texture_level_config level = {
            .target=5, .width=1, .height=1, .depth=1, .layers=1,
            .allocation_limit=1ULL<<32, .base=(u32)((1ULL<<32)-image.buffer_bytes),
            .block_bytes=bytes, .buffer_texels=65536, .buffer_bytes=image.buffer_bytes,
        };
        assert(opengpu_encode_compute_texture_level(words,&level));
        assert(words[7] == 5 && words[13] == 1 && words[25] == 65536 &&
               words[26] == level.buffer_bytes && words[27] == 0 && words[31] == 0);
        memcpy(saved,words,sizeof(words));
        for (unsigned i = 0; i < 12; ++i) {
            struct opengpu_compute_texture_level_config bad = level;
            switch (i) {
            case 0: bad.buffer_texels=0; break;
            case 1: bad.buffer_texels=OPENGPU_TEXTURE_BUFFER_MAX_TEXELS+1; break;
            case 2: --bad.buffer_texels; break;
            case 3: bad.level=1; break;
            case 4: bad.width=2; break;
            case 5: bad.row_pitch=bytes; break;
            case 6: bad.block_4x4=true; break;
            case 7: bad.block_bytes=3; break;
            case 8: ++bad.base; break;
            case 9: bad.buffer_bytes=(1ULL<<32)+1; break;
            case 10: bad.buffer_bytes=65536ULL*bytes-1; break;
            case 11: bad.target=0; break;
            }
            assert(!opengpu_encode_compute_texture_level(words,&bad));
            assert(!memcmp(words,saved,sizeof(words)));
        }
    }
    /* The whole declared byte range is checked even when texel count clamps. */
    struct opengpu_compute_texture_level_config full = {
        .target=5, .width=1, .height=1, .depth=1, .layers=1, .block_bytes=1,
        .allocation_limit=1ULL<<32, .buffer_texels=OPENGPU_TEXTURE_BUFFER_MAX_TEXELS, .buffer_bytes=1ULL<<32,
    };
    assert(opengpu_encode_compute_texture_level(words,&full));
    assert(words[26] == 0 && words[27] == 1);
    full.base=1; assert(!opengpu_encode_compute_texture_level(words,&full));
    struct opengpu_compute_texture_binding_config binding = {.target=5,.unit=31};
    struct opengpu_compute_texture_root_config samplerless = {
        .count=1,.bindings_limit=64,.images_limit=128,.buffer_only=true,
    };
    assert(opengpu_encode_compute_texture_root(words,&samplerless));
    assert(!words[14] && !words[16] && !words[17]);
    assert(opengpu_encode_compute_texture_binding(words,&binding));
}

int main(void)
{
    buffer_records();
    struct opengpu_compute_texture_root_config root = {
        .context = 7, .pass = 11, .generation = 13, .count = 32,
        .bindings_base = 0xfffff800u, .bindings_limit = 1ULL << 32,
        .images_base = 0xfffff800u, .images_limit = 1ULL << 32,
        .samplers_base = 0xfffff800u, .samplers_limit = 1ULL << 32,
    };
    struct opengpu_compute_texture_binding_config binding = {
        .context = 7, .pass = 11, .generation = 13, .unit = 31,
        .image_handle = 29, .sampler_handle = 30, .expected_type = 2, .target = 3,
    };
    u32 words[32], saved[32];
    assert(OPENGPU_COMPUTE_TEXTURE_STAGE_BITS == 2u);
    assert(OPENGPU_NATIVE_TEXTURE_ROOT_STAGE_BITS == 1u); /* graphics schema */
    assert(opengpu_encode_compute_texture_root(words, &root));
    assert(words[0] == OPENGPU_NATIVE_TEXTURE_ROOT_MAGIC && words[1] == 128 && words[2] == 1);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_IDENTITY_CONTEXT_WORD] == 7);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_IDENTITY_PASS_WORD] == 11);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_IDENTITY_GENERATION_WORD] == 13);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_STAGE_WORD] == OPENGPU_COMPUTE_TEXTURE_STAGE);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_BINDINGCOUNT_WORD] == 32);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_BINDINGSBASE_WORD] == 0xfffff800u);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_BINDINGSLIMIT_WORD] == 0 && words[10] == 1);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_IMAGESBASE_WORD] == 0xfffff800u && words[13] == 1);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_IMAGESLIMIT_WORD] == 0);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_SAMPLERSBASE_WORD] == 0xfffff800u && words[17] == 1);
    assert(words[OPENGPU_NATIVE_TEXTURE_ROOT_SAMPLERSLIMIT_WORD] == 0);
    assert(words[15] == 0 && words[18] == 0 && words[31] == 0);
    memcpy(saved, words, sizeof(words));
    for (unsigned i = 0; i < 12; ++i) {
        struct opengpu_compute_texture_root_config bad = root;
        switch (i) {
        case 0: bad.count = 0; break;
        case 1: bad.count = 33; break;
        case 2: bad.bindings_base++; break;
        case 3: bad.bindings_limit -= 64; break;
        case 4: bad.bindings_limit = (1ULL << 32) + 64; break;
        case 5: bad.images_base++; break;
        case 6: bad.images_limit -= 64; break;
        case 7: bad.samplers_base++; break;
        case 8: bad.samplers_limit = bad.samplers_base; break;
        case 9: bad.samplers_limit -= 1; break;
        case 10: bad.images_limit = (1ULL << 32) + 128; break;
        case 11: bad.samplers_limit = (1ULL << 32) + 64; break;
        }
        assert(!opengpu_encode_compute_texture_root(words, &bad));
        assert(!memcmp(words, saved, sizeof(words)));
    }
    assert(!opengpu_encode_compute_texture_root(NULL, &root));
    assert(!opengpu_encode_compute_texture_root(words, NULL));
    {
        struct opengpu_compute_texture_root_config zero = {
            .count = 1, .bindings_limit = 64, .images_limit = 128, .samplers_limit = 64,
        };
        assert(opengpu_encode_compute_texture_root(words, &zero));
        assert(words[8] == 0 && words[9] == 64 && words[11] == 0 && words[14] == 0);
        zero.samplers_limit = 0;
        assert(!opengpu_encode_compute_texture_root(words, &zero));
        zero.size_only = true;
        assert(opengpu_encode_compute_texture_root(words, &zero));
        assert(words[14] == 0 && words[16] == 0 && words[17] == 0);
        memcpy(saved, words, sizeof(words));
        zero.samplers_base = 64;
        assert(!opengpu_encode_compute_texture_root(words, &zero));
        assert(!memcmp(words, saved, sizeof(words)));
    }
    assert(opengpu_encode_compute_texture_binding(words, &binding));
    assert(words[0] == OPENGPU_NATIVE_TEXTURE_BINDING_MAGIC && words[1] == 64 && words[2] == 1);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_IDENTITY_CONTEXT_WORD] == 7);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_IDENTITY_PASS_WORD] == 11);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_IDENTITY_GENERATION_WORD] == 13);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_STAGE_WORD] == OPENGPU_COMPUTE_TEXTURE_STAGE);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_UNIT_WORD] == 31);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_IMAGEHANDLE_WORD] == 29);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_SAMPLERHANDLE_WORD] == 30);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_EXPECTEDTYPE_WORD] == 2);
    assert(words[OPENGPU_NATIVE_TEXTURE_BINDING_TARGET_WORD] == 3);
    assert(words[12] == 0 && words[15] == 0);
    memcpy(saved, words, sizeof(words));
    for (unsigned i = 0; i < 3; ++i) {
        struct opengpu_compute_texture_binding_config bad = binding;
        if (i == 0) bad.unit = 32;
        if (i == 1) bad.expected_type = 3;
        if (i == 2) bad.target = 4;
        assert(!opengpu_encode_compute_texture_binding(words, &bad));
        assert(!memcmp(words, saved, sizeof(words)));
    }
    assert(!opengpu_encode_compute_texture_binding(NULL, &binding));
    assert(!opengpu_encode_compute_texture_binding(words, NULL));
    /* Exact stage-2 records used by NativeTextureFetchTestData at binding 31.
     * Check every reserved word as well as the independently declared fields. */
    {
        struct opengpu_compute_texture_root_config fixture = {
            .context = 7, .pass = 11, .generation = 13, .count = 32,
            .bindings_base = 0xb000, .bindings_limit = 0xb800,
            .images_base = 0x10000, .images_limit = 0x11000,
            .samplers_base = 0x14000, .samplers_limit = 0x14800,
        };
        const u32 expected_root[32] = {
            0x47585201u, 128, 1, 7, 11, 13, 2, 32, 0xb000, 0xb800,
            0, 0x10000, 0x11000, 0, 0x14000, 0, 0x14800, 0,
        };
        const u32 expected_binding[16] = {
            0x47584201u, 64, 1, 7, 11, 13, 2, 31, 31, 31, 0, 0,
        };
        const u32 expected_packet[16] = {
            0x47585101u, 64, 1, 7, 11, 13, 1, 1, 0, 0, 0, 0,
        };
        const u32 expected_sampler[16] = {
            0x47585301u, 64, 1, 7, 11, 13, 31, 0, 0, 0, 0, 0, 0, 0x41700000u, 0, 3,
        };
        struct opengpu_compute_texture_binding_config fixture_binding = {
            .context = 7, .pass = 11, .generation = 13, .unit = 31,
            .image_handle = 31, .sampler_handle = 31, .expected_type = 0, .target = 0,
        };
        struct opengpu_compute_texture_packet_config fixture_packet = {
            .context = 7, .pass = 11, .generation = 13, .x = 1, .y = 1,
        };
        struct opengpu_compute_texture_sampler_config sampler = {
            .context = 7, .pass = 11, .generation = 13, .handle = 31,
            .max_lod_bits = 0x41700000u, .compare_function = 3,
        };
        assert(opengpu_encode_compute_texture_root(words, &fixture));
        assert(!memcmp(words, expected_root, sizeof(expected_root)));
        assert(opengpu_encode_compute_texture_binding(words, &fixture_binding));
        assert(!memcmp(words, expected_binding, sizeof(expected_binding)));
        assert(opengpu_encode_compute_texture_packet(words, &fixture_packet));
        assert(!memcmp(words, expected_packet, sizeof(expected_packet)));
        assert(words[OPENGPU_NATIVE_TEXTURE_PACKET_X_WORD] == 1);
        assert(words[OPENGPU_NATIVE_TEXTURE_PACKET_Y_WORD] == 1);
        fixture_packet.x = 0xffffffffu; fixture_packet.face = 7;
        assert(opengpu_encode_compute_texture_packet(words, &fixture_packet));
        assert(words[OPENGPU_NATIVE_TEXTURE_PACKET_X_WORD] == 0xffffffffu);
        assert(words[OPENGPU_NATIVE_TEXTURE_PACKET_FACE_WORD] == 7);
        memcpy(saved, words, sizeof(words));
        fixture_packet.face = 8;
        assert(!opengpu_encode_compute_texture_packet(words, &fixture_packet));
        assert(!memcmp(words, saved, sizeof(words)));
        assert(!opengpu_encode_compute_texture_packet(NULL, &fixture_packet));
        assert(!opengpu_encode_compute_texture_packet(words, NULL));
        assert(opengpu_encode_compute_texture_sampler(words, &sampler));
        assert(!memcmp(words, expected_sampler, sizeof(expected_sampler)));
        assert(words[OPENGPU_TEXTURE_SAMPLER_HANDLE_WORD] == 31);
        assert(words[OPENGPU_TEXTURE_SAMPLER_STATE_MAXLOD_WORD] == 0x41700000u);
        sampler.min_lod_bits = 0x7f800000u; sampler.max_lod_bits = 0xff800000u;
        sampler.min_filter = 5; sampler.mag_filter = 1; sampler.wrap_s = 2;
        sampler.wrap_t = 2; sampler.wrap_r = 2; sampler.compare_enable = true;
        sampler.compare_function = 7;
        assert(opengpu_encode_compute_texture_sampler(words, &sampler));
        memcpy(saved, words, sizeof(words));
        for (unsigned i = 0; i < 8; ++i) {
            struct opengpu_compute_texture_sampler_config bad = sampler;
            if (i == 0) bad.min_filter = 6;
            if (i == 1) bad.mag_filter = 2;
            if (i == 2) bad.wrap_s = 3;
            if (i == 3) bad.wrap_t = 3;
            if (i == 4) bad.wrap_r = 3;
            if (i == 5) bad.compare_function = 8;
            if (i == 6) bad.min_lod_bits = 0x7f800001u;
            if (i == 7) bad.max_lod_bits = 0xffc00000u;
            assert(!opengpu_encode_compute_texture_sampler(words, &bad));
            assert(!memcmp(words, saved, sizeof(words)));
        }
        assert(!opengpu_encode_compute_texture_sampler(NULL, &sampler));
        assert(!opengpu_encode_compute_texture_sampler(words, NULL));
    }
    {
        struct opengpu_compute_texture_image_config image = {
            .context = 7, .pass = 11, .generation = 13, .handle = 31,
            .target = 0, .format = 3, .width = 2, .height = 2, .depth = 1, .layers = 1,
            .allocation_base = 0x3f000, .allocation_limit = 0x40000,
            .levels_base = 0x12f80, .levels_limit = 0x13000,
        };
        const u32 expected_image[32] = {
            0x47584901u, 128, 1, 7, 11, 13, 31, 0, 3, 0, 0, 2, 2, 1, 1,
            0x3f000, 0x40000, 0, 1, 0x12f80, 0x13000, 0, 0, 0, 0, 0, 0, 0,
        };
        assert(opengpu_encode_compute_texture_image(words, &image));
        assert(!memcmp(words, expected_image, sizeof(expected_image)));
        assert(words[OPENGPU_TEXTURE_IMAGE_BINDING_LEVELSBASE_WORD] == 0x12f80);
        assert(words[OPENGPU_TEXTURE_IMAGE_BINDING_ALLOCATIONLIMIT_WORD] == 0x40000);
        memcpy(saved, words, sizeof(words));
        for (unsigned i = 0; i < 16; ++i) {
            struct opengpu_compute_texture_image_config bad = image;
            if (i == 0) bad.target = 4;
            if (i == 1) bad.format = 64;
            if (i == 2) { bad.target = 3; bad.format = 49; }
            if (i == 3) bad.width = 0;
            if (i == 4) bad.height = 16384;
            if (i == 5) bad.depth = 2;
            if (i == 6) bad.layers = 2;
            if (i == 7) { bad.target = 2; bad.width = 3; }
            if (i == 8) bad.base_level = 1;
            if (i == 9) bad.swizzle_version = 2;
            if (i == 10) bad.swizzle[0] = 1;
            if (i == 11) { bad.swizzle_version = 1; bad.swizzle[3] = 6; }
            if (i == 12) bad.allocation_limit = bad.allocation_base;
            if (i == 13) bad.allocation_limit = (1ULL << 32) + 1;
            if (i == 14) bad.levels_base++;
            if (i == 15) bad.first_level_handle = 0xffffffffu;
            assert(!opengpu_encode_compute_texture_image(words, &bad));
            assert(!memcmp(words, saved, sizeof(words)));
        }
        assert(!opengpu_encode_compute_texture_image(NULL, &image));
        assert(!opengpu_encode_compute_texture_image(words, NULL));
        image.target = 3; image.format = 48; image.width = 16383; image.height = 1;
        image.depth = 16383; image.swizzle_version = 1; image.swizzle[0] = 5;
        assert(opengpu_encode_compute_texture_image(words, &image));
        image.target = 1; image.width = 1; image.depth = 1; image.layers = 65535;
        assert(opengpu_encode_compute_texture_image(words, &image));
    }
    {
        struct opengpu_compute_texture_level_config level = {
            .context = 7, .pass = 11, .generation = 13, .target = 0, .level = 0,
            .allocation_base = 0x3f000, .allocation_limit = 0x40000, .base = 0x3f03c,
            .width = 2, .height = 2, .depth = 1, .layers = 1, .row_pitch = 13,
            .block_bytes = 4,
        };
        const u32 expected_level[32] = {
            0x47584c01u, 128, 1, 7, 11, 13, 0, 0, 0, 0x3f000, 0x40000, 0,
            0x3f03c, 2, 2, 1, 1, 13, 0, 0, 0, 0, 0, 0, 4,
        };
        assert(opengpu_encode_compute_texture_level(words, &level));
        assert(!memcmp(words, expected_level, sizeof(expected_level)));
        assert(words[OPENGPU_TEXTURE_IMAGE_LEVEL_VIEW_ROWPITCH_WORD] == 13);
        assert(words[OPENGPU_TEXTURE_IMAGE_LEVEL_VIEW_BLOCKBYTES_WORD] == 4);
        memcpy(saved, words, sizeof(words));
        for (unsigned i = 0; i < 17; ++i) {
            struct opengpu_compute_texture_level_config bad = level;
            if (i == 0) bad.target = 4;
            if (i == 1) bad.level = 16;
            if (i == 2) bad.width = 0;
            if (i == 3) bad.height = 16384;
            if (i == 4) bad.depth = 2;
            if (i == 5) bad.layers = 2;
            if (i == 6) { bad.target = 2; bad.width = 3; }
            if (i == 7) bad.slice_pitch = 1;
            if (i == 8) bad.layer_pitch = 1;
            if (i == 9) bad.block_bytes = 5;
            if (i == 10) { bad.block_4x4 = true; bad.block_bytes = 4; }
            if (i == 11) bad.allocation_limit = bad.allocation_base;
            if (i == 12) bad.allocation_limit = (1ULL << 32) + 1;
            if (i == 13) bad.base = bad.allocation_base - 1;
            if (i == 14) bad.row_pitch = 7;
            if (i == 15) bad.allocation_limit = bad.base + 20;
            if (i == 16) bad.row_pitch = 1ULL << 33;
            assert(!opengpu_encode_compute_texture_level(words, &bad));
            assert(!memcmp(words, saved, sizeof(words)));
        }
        assert(!opengpu_encode_compute_texture_level(NULL, &level));
        assert(!opengpu_encode_compute_texture_level(words, NULL));
        level.allocation_base = 0; level.allocation_limit = 1ULL << 32; level.base = 0;
        level.width = 1; level.height = 1; level.block_bytes = 1;
        level.row_pitch = (1ULL << 33) - 1;
        assert(opengpu_encode_compute_texture_level(words, &level));
        assert(words[17] == 0xffffffffu && words[18] == 1);
        level.target = 3; level.depth = 2; level.row_pitch = 1; level.slice_pitch = 1;
        assert(opengpu_encode_compute_texture_level(words, &level));
        level.slice_pitch = 0;
        assert(!opengpu_encode_compute_texture_level(words, &level));
        level.target = 1; level.depth = 1; level.layers = 2;
        level.slice_pitch = 0; level.layer_pitch = 1;
        assert(opengpu_encode_compute_texture_level(words, &level));
        level.layer_pitch = 0;
        assert(!opengpu_encode_compute_texture_level(words, &level));
        level.target = 0; level.layers = 1; level.layer_pitch = 0;
        level.width = 7; level.height = 5; level.block_4x4 = true;
        level.block_bytes = 8; level.row_pitch = 16;
        assert(opengpu_encode_compute_texture_level(words, &level));
    }
    return 0;
}
