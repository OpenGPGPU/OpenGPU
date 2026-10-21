#include "opengpu_sampler_lowering.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>

/* Reuse the interpreter for the original shader's actual RVV instructions. */
#define main existing_shader_color_main
#include "shader_color.c"
#undef main

static uint32_t random_state = 0x98714329u;

static uint32_t random_word(void)
{
    random_state ^= random_state << 13;
    random_state ^= random_state >> 17;
    return random_state ^= random_state << 5;
}

/* Decode bytes independently of the sampler-format selection helper. */
static uint32_t decode(uint32_t raw, int format)
{
    unsigned bytes[] = {raw & 255, (raw >> 8) & 255,
                        (raw >> 16) & 255, raw >> 24};
    unsigned rgba = format == 1 || format == 3;
    unsigned red = bytes[rgba ? 0 : 2];
    unsigned blue = bytes[rgba ? 2 : 0];
    unsigned alpha = format == 3 || format == 4 ? 255 : bytes[3];

    return red << 24 | bytes[1] << 16 | blue << 8 | alpha;
}

static uint32_t filter(const uint32_t raw[4], int format,
                       unsigned fx, unsigned fy)
{
    unsigned weights[] = {(256 - fx) * (256 - fy), fx * (256 - fy),
                          (256 - fx) * fy, fx * fy};
    uint32_t result = 0;

    for (unsigned shift = 0; shift < 32; shift += 8) {
        uint64_t sum = 0;

        for (unsigned k = 0; k < 4; k++)
            sum += ((decode(raw[k], format) >> shift) & 255) *
                   (uint64_t)weights[k];
        result |= (uint32_t)(sum >> 16) << shift;
    }
    return result;
}

static uint32_t mix(uint32_t a, uint32_t b, unsigned fraction)
{
    uint32_t result = 0;

    for (unsigned shift = 0; shift < 32; shift += 8) {
        unsigned value = (((a >> shift) & 255) * (256 - fraction) +
                          ((b >> shift) & 255) * fraction) >> 8;

        result |= value << shift;
    }
    return result;
}

int main(void)
{
    const int formats[] = {-1, 0, 1, 2, 3, 4, 5, 255};
    unsigned cases = 0;

    assert(opengpu_apply_sample_rb_swap(OPENGPU_FORMAT_RGBA8,
                                        OPENGPU_FORMAT_RGBA8, true));
    assert(opengpu_apply_sample_rb_swap(OPENGPU_FORMAT_BGRA8,
                                        OPENGPU_FORMAT_BGRX8, true));
    assert(!opengpu_apply_sample_rb_swap(OPENGPU_FORMAT_RGBA8,
                                         OPENGPU_FORMAT_BGRA8, true));
    assert(!opengpu_apply_sample_rb_swap(OPENGPU_FORMAT_BGRA8,
                                         OPENGPU_FORMAT_RGBA8, true));
    assert(!opengpu_apply_sample_rb_swap(OPENGPU_FORMAT_RGBA8,
                                         OPENGPU_FORMAT_BGRA8, false));
    assert(opengpu_apply_sample_rb_swap(-1, OPENGPU_FORMAT_BGRA8, true));

    for (unsigned k = 0; k < sizeof(formats) / sizeof(formats[0]); k++) {
        for (unsigned want = 0; want < 2; want++) {
            for (unsigned allow = 0; allow < 2; allow++) {
                int format = formats[k];
                struct opengpu_sample_plan plan =
                    opengpu_lower_sample_swap(format, want, allow);

                if (!want || !allow || format < 1 || format > 4) {
                    assert(plan.texture_format == format);
                    assert(plan.shader_swizzle == (bool)want);
                } else {
                    assert(!plan.shader_swizzle);
                    assert(plan.texture_format >= 1 && plan.texture_format <= 4);
                    assert((format <= 2) == (plan.texture_format <= 2));
                }
            }
        }
    }
    for (int format = 1; format <= 4; format++) {
        struct opengpu_sample_plan plan =
            opengpu_lower_sample_swap(format, true, true);

        assert(!plan.shader_swizzle);
        for (unsigned fx = 0; fx < 256; fx++) {
            for (unsigned fy = 0; fy < 256; fy++) {
                uint32_t first[4], second[4];

                for (unsigned k = 0; k < 4; k++) {
                    first[k] = random_word();
                    second[k] = random_word();
                }
                unsigned fraction = random_word() & 255;
                uint32_t original = run_swizzle(mix(
                    filter(first, format, fx, fy),
                    filter(second, format, fy, fx), fraction));
                uint32_t folded = mix(
                    filter(first, plan.texture_format, fx, fy),
                    filter(second, plan.texture_format, fy, fx), fraction);

                assert(original == folded);
                cases++;
            }
        }
    }
    printf("sampler format folding: %u filtered RVV-reference cases, "
           "%zu policy cases PASS\n", cases,
           sizeof(formats) / sizeof(formats[0]) * 4);
    return 0;
}
