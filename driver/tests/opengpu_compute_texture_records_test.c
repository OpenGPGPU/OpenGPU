#include <assert.h>
#include <string.h>
#include "../opengpu_compute_texture_records.h"
#include "../opengpu_native_texture_abi.h"

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
    return 0;
}
