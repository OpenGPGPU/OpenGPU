#include <assert.h>
#include <string.h>
#include "../opengpu_compute_texture_records.h"
#include "../opengpu_native_texture_abi.h"
#include "../opengpu_texture_sampler_abi.h"

int main(void)
{
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
    return 0;
}
