#include <assert.h>
#include <stddef.h>
#include "../opengpu_compute_texture_sampler_validation.h"

int main(void)
{
    static const struct opengpu_compute_texture_format_info expected[64] = {
        {0, 0, 1}, /* 0: R8 */
        {0, 0, 1}, /* 1: RG8 */
        {0, 0, 1}, /* 2: RGB8 */
        {0, 0, 1}, /* 3: RGBA8 */
        {0, 0, 1}, /* 4: RGB565 */
        {0, 0, 1}, /* 5: RGBA4 */
        {0, 0, 1}, /* 6: RGB5_A1 */
        {0, 0, 1}, /* 7: RGB10_A2 */
        {0, 0, 1}, /* 8: SRGB8_ALPHA8 */
        {2, 0, 0}, /* 9: R8I */
        {1, 0, 0}, /* 10: R8UI */
        {2, 0, 0}, /* 11: R16I */
        {1, 0, 0}, /* 12: R16UI */
        {2, 0, 0}, /* 13: R32I */
        {1, 0, 0}, /* 14: R32UI */
        {2, 0, 0}, /* 15: RG8I */
        {1, 0, 0}, /* 16: RG8UI */
        {2, 0, 0}, /* 17: RG16I */
        {1, 0, 0}, /* 18: RG16UI */
        {2, 0, 0}, /* 19: RG32I */
        {1, 0, 0}, /* 20: RG32UI */
        {2, 0, 0}, /* 21: RGBA8I */
        {1, 0, 0}, /* 22: RGBA8UI */
        {2, 0, 0}, /* 23: RGBA16I */
        {1, 0, 0}, /* 24: RGBA16UI */
        {2, 0, 0}, /* 25: RGBA32I */
        {1, 0, 0}, /* 26: RGBA32UI */
        {1, 0, 0}, /* 27: RGB10_A2UI */
        {0, 0, 1}, /* 28: R16F */
        {0, 0, 0}, /* 29: R32F */
        {0, 0, 1}, /* 30: RG16F */
        {0, 0, 0}, /* 31: RG32F */
        {0, 0, 1}, /* 32: RGB16F */
        {0, 0, 0}, /* 33: RGB32F */
        {0, 0, 1}, /* 34: RGBA16F */
        {0, 0, 0}, /* 35: RGBA32F */
        {0, 0, 1}, /* 36: R8_SNORM */
        {0, 0, 1}, /* 37: RG8_SNORM */
        {0, 0, 1}, /* 38: RGB8_SNORM */
        {0, 0, 1}, /* 39: RGBA8_SNORM */
        {2, 0, 0}, /* 40: RGB8I */
        {1, 0, 0}, /* 41: RGB8UI */
        {2, 0, 0}, /* 42: RGB16I */
        {1, 0, 0}, /* 43: RGB16UI */
        {2, 0, 0}, /* 44: RGB32I */
        {1, 0, 0}, /* 45: RGB32UI */
        {0, 0, 1}, /* 46: SRGB8 */
        {0, 0, 1}, /* 47: R11F_G11F_B10F */
        {0, 0, 1}, /* 48: RGB9_E5 */
        {0, 1, 0}, /* 49: DEPTH_COMPONENT16 */
        {0, 1, 0}, /* 50: DEPTH_COMPONENT24 */
        {0, 1, 0}, /* 51: DEPTH_COMPONENT32F */
        {0, 1, 0}, /* 52: DEPTH24_STENCIL8 */
        {0, 1, 0}, /* 53: DEPTH32F_STENCIL8 */
        {0, 0, 1}, /* 54: RGB8_ETC2 */
        {0, 0, 1}, /* 55: SRGB8_ETC2 */
        {0, 0, 1}, /* 56: RGB8_PUNCHTHROUGH_ALPHA1_ETC2 */
        {0, 0, 1}, /* 57: SRGB8_PUNCHTHROUGH_ALPHA1_ETC2 */
        {0, 0, 1}, /* 58: RGBA8_ETC2_EAC */
        {0, 0, 1}, /* 59: SRGB8_ALPHA8_ETC2_EAC */
        {0, 0, 1}, /* 60: R11_EAC */
        {0, 0, 1}, /* 61: SIGNED_R11_EAC */
        {0, 0, 1}, /* 62: RG11_EAC */
        {0, 0, 1}, /* 63: SIGNED_RG11_EAC */
    };
    struct opengpu_compute_texture_binding_config binding = {
        .context = 7, .pass = 11, .generation = 13, .unit = 3,
        .image_handle = 3, .sampler_handle = 4,
    };
    struct opengpu_compute_texture_image_config image = {
        .context = 7, .pass = 11, .generation = 13, .handle = 3,
        .width = 2, .height = 2, .depth = 1, .layers = 1,
        .allocation_limit = 64, .levels_limit = 128,
    };
    struct opengpu_compute_texture_sampler_config sampler = {
        .context = 7, .pass = 11, .generation = 13, .handle = 4,
    };
    assert(!opengpu_validate_compute_texture_sampler(NULL, &image, &sampler, true, false));
    assert(!opengpu_validate_compute_texture_sampler(&binding, NULL, &sampler, true, false));
    assert(!opengpu_validate_compute_texture_sampler(&binding, &image, NULL, true, false));
    for (u32 f = 0; f < 64; ++f) {
        const struct opengpu_compute_texture_format_info *info = &expected[f];
        assert(opengpu_compute_texture_formats[f].kind == info->kind);
        assert(opengpu_compute_texture_formats[f].depth == info->depth);
        assert(opengpu_compute_texture_formats[f].filterable == info->filterable);
        image.format = f; binding.expected_type = info->kind;
        for (u32 min = 0; min < 6; ++min) for (u32 mag = 0; mag < 2; ++mag)
        for (u32 compare = 0; compare < 2; ++compare) for (u32 shadow = 0; shadow < 2; ++shadow) {
            bool nearest = mag == 0 && (min == 0 || min == 2);
            bool fetch_ok = !compare && (info->filterable || nearest);
            bool sample_ok = (info->depth ? (compare == shadow) : !shadow) &&
                (shadow ? (info->depth && info->kind == 0) : (info->filterable || nearest));
            sampler.min_filter = min; sampler.mag_filter = mag; sampler.compare_enable = compare;
            assert(opengpu_validate_compute_texture_sampler(&binding, &image, &sampler, false, shadow) == fetch_ok);
            assert(opengpu_validate_compute_texture_sampler(&binding, &image, &sampler, true, shadow) == sample_ok);
        }
        binding.expected_type = (info->kind + 1u) % 3u;
        assert(!opengpu_validate_compute_texture_sampler(&binding, &image, &sampler, true, false));
    }
    image.format = 3; binding.expected_type = 0;
    sampler.min_filter = 0; sampler.mag_filter = 0; sampler.compare_enable = false;
    assert(opengpu_validate_compute_texture_sampler(&binding, &image, &sampler, true, false));
    for (unsigned i = 0; i < 8; ++i) {
        struct opengpu_compute_texture_binding_config b = binding;
        struct opengpu_compute_texture_image_config im = image;
        struct opengpu_compute_texture_sampler_config sa = sampler;
        if (i == 0) b.context++;
        if (i == 1) im.pass++;
        if (i == 2) sa.generation++;
        if (i == 3) b.image_handle++;
        if (i == 4) b.sampler_handle++;
        if (i == 5) b.target = 1;
        if (i == 6) sa.min_lod_bits = 0x7fc00000u;
        if (i == 7) im.format = 64;
        assert(!opengpu_validate_compute_texture_sampler(&b, &im, &sa, true, false));
    }
    return 0;
}
