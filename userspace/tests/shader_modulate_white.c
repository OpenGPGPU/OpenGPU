/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "opengpu_shaders.h"
#include "opengpu_shader_abi.h"

#define COUNT(a) (sizeof(a) / sizeof((a)[0]))

/* Execute one independent lane of the actual shader after vtex.sample.
 * The general program's colour load returns packed white. Address formation
 * still executes; shader_abi.c separately verifies every effective address.
 */
static uint32_t run(const uint32_t *code, size_t words, uint32_t input)
{
    uint32_t x[32] = {0}, v[32] = {0};
    bool sampled = false;

    for (size_t i = 0; i < words; i++) {
        uint32_t w = code[i];
        unsigned op = w & 127, rd = w >> 7 & 31, f3 = w >> 12 & 7;
        unsigned r1 = w >> 15 & 31, r2 = w >> 20 & 31, f6 = w >> 26;

        if ((w & 0xfe00707fu) == 0x0600002bu) {
            v[rd] = input;
            sampled = true;
            continue;
        }
        if (op == 0x13 && (f3 == 0 || f3 == 1 || f3 == 5)) {
            if (f3 == 0)
                x[rd] = x[r1] + (uint32_t)((int32_t)w >> 20);
            else if (f3 == 1)
                x[rd] = x[r1] << (r2 & 31u);
            else
                x[rd] = x[r1] >> (r2 & 31u);
            x[0] = 0;
            continue;
        }
        if (op == 0x37) {
            x[rd] = w & 0xfffff000u;
            x[0] = 0;
            continue;
        }
        if (op == 0x33 && f3 == 0) {
            x[rd] = (w >> 25) == 1 ? x[r1] * x[r2] :
                    (w >> 25) == 0x20 ? x[r1] - x[r2] : x[r1] + x[r2];
            x[0] = 0;
            continue;
        }
        if (op == 0x57 && f3 == 7)
            continue;
        if (!sampled)
            continue;
        if (op == 0x07) {
            v[rd] = UINT32_MAX;
        } else if (op == 0x57) {
            uint32_t rhs = (f3 == 4 || f3 == 6) ? x[r1] :
                           f3 == 3 ? r1 : v[r1];
            assert(f3 == 0 || f3 == 2 || f3 == 3 || f3 == 4 || f3 == 6);
            if (f6 == 37 && f3 == 2) {
                v[rd] = v[r2] * rhs;
            } else {
                switch (f6) {
                case 0: v[rd] = v[r2] + rhs; break;
                case 2: v[rd] = v[r2] - rhs; break;
                case 9: v[rd] = v[r2] & rhs; break;
                case 10: v[rd] = v[r2] | rhs; break;
                case 37: v[rd] = v[r2] << (rhs & 31); break;
                case 40: v[rd] = v[r2] >> (rhs & 31); break;
                default: abort();
                }
            }
        } else if (op == 0x27) {
            return v[rd];
        } else {
            abort();
        }
        x[0] = 0;
    }
    abort();
}

static uint32_t golden(uint32_t x, bool force_alpha)
{
    uint32_t result = 0;
    if (force_alpha)
        x = (x & 0xffffff00u) | 255u;
    for (unsigned shift = 0; shift < 32; shift += 8)
        result |= (((x >> shift & 255u) * 255u) / 256u) << shift;
    return result;
}

static uint32_t random_state = 0x3a901a17u;
static uint32_t random_word(void)
{
    random_state ^= random_state << 13;
    random_state ^= random_state >> 17;
    return random_state ^= random_state << 5;
}

static size_t with_alpha(const uint32_t *source, size_t words,
                         bool force_alpha, uint32_t *target)
{
    size_t count = 0;
    for (size_t i = 0; i < words; i++) {
        target[count++] = source[i];
        if (force_alpha && (source[i] & 0xfe00707fu) == 0x0600002bu) {
            unsigned vd = (source[i] >> 7) & 31u;
            target[count++] = 0x0ff00393u;
            target[count++] = (10u << 26) | (1u << 25) | (vd << 20) |
                              (7u << 15) | (4u << 12) | (vd << 7) | 0x57u;
        }
    }
    return count;
}

int main(int argc, char **argv)
{
    uint32_t original[2][512], fast[2][512], old_code[512], fast_code[512];
    size_t old_words[2], fast_words[2];
    const bool negative_control = argc > 1;
    unsigned cases = 0;
    (void)argv;

    for (unsigned force = 0; force < 2; force++) {
        const uint32_t *source = negative_control ? opengpu_fragment_sample :
                                                   opengpu_fragment_modulate_white;
        size_t words = negative_control ? COUNT(opengpu_fragment_sample) :
                                          COUNT(opengpu_fragment_modulate_white);
        size_t a = with_alpha(opengpu_fragment_modulate,
            COUNT(opengpu_fragment_modulate), force, old_code);
        size_t b = with_alpha(source, words, force, fast_code);
        assert(!opengpu_shader_lower_runtime_abi(old_code, a, false,
            original[force], COUNT(original[force]), &old_words[force]));
        assert(!opengpu_shader_lower_runtime_abi(fast_code, b, false,
            fast[force], COUNT(fast[force]), &fast_words[force]));
    }
    for (unsigned mode = 0; mode < 7; mode++) {
        const unsigned pairs[6][2] = {
            {0, 8}, {0, 16}, {0, 24}, {8, 16}, {8, 24}, {16, 24}
        };
        unsigned count = mode == 6 ? 500000u : 65536u;
        for (unsigned i = 0; i < count; i++) {
            uint32_t x;
            if (mode == 6) {
                x = random_word();
            } else {
                unsigned a = pairs[mode][0], b = pairs[mode][1];
                x = (0xff800100u & ~((255u << a) | (255u << b))) |
                    ((i & 255u) << a) | ((i >> 8) << b);
            }
            for (unsigned force = 0; force < 2; force++) {
                uint32_t expected = golden(x, force);
                assert(run(original[force], old_words[force], x) == expected);
                if (run(fast[force], fast_words[force], x) != expected) {
                    fprintf(stderr, "white modulation mismatch input=%08x force=%u\n",
                            x, force);
                    return 1;
                }
                cases++;
            }
        }
    }
    printf("white modulation: %u exact actual-RVV and independent byte-reference cases PASS\n", cases);
    return 0;
}
