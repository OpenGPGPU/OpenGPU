// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stdint.h>
#include "../opengpu_shader_validator.h"

static uint32_t storage(unsigned fn, unsigned rd, unsigned vs1, unsigned vs2)
{
    return fn << 26 | 1u << 25 | vs2 << 20 | vs1 << 15 | rd << 7 | 0x2bu;
}
static bool valid(const uint32_t *p, unsigned n)
{
    return opengpu_compute_shader_validate_words_storage(p, n, 64, 4, false);
}
int main(void)
{
    const uint32_t vl4 = 0xc1027057u;
    uint32_t p[] = { vl4, storage(14, 0, 1, 0), storage(16, 0, 1, 1),
                     storage(15, 2, 1, 1), OPENGPU_SHADER_CEASE };
    assert(valid(p, 5));
    for (unsigned fn = 0; fn < 64; ++fn) {
        uint32_t load[] = { vl4, storage(fn, 2, 1, 1), OPENGPU_SHADER_CEASE };
        assert(valid(load, 3) == (fn == 15));
    }
    {
        uint32_t use[] = { vl4, storage(15, 2, 1, 1),
                           storage(14, 0, 2, 0), storage(16, 0, 1, 1),
                           OPENGPU_SHADER_CEASE };
        assert(valid(use, 5));
    }
    assert(!opengpu_compute_shader_validate_words(p, 5, 64, 4));
    assert(!opengpu_compute_shader_validate_words_disjoint_textures(p, 5, 64, 4, false, true));
    p[1] = storage(14, 0, 3, 0); assert(!valid(p, 5));
    p[1] = storage(14, 1, 1, 0); assert(!valid(p, 5));
    p[1] = storage(14, 0, 1, 0);
    p[2] = storage(16, 0, 1, 3); assert(!valid(p, 5));
    p[2] = storage(16, 1, 1, 1); assert(!valid(p, 5));
    p[2] = storage(16, 0, 1, 1) & ~(1u << 25); assert(!valid(p, 5));
    p[2] = storage(16, 0, 1, 1) | (1u << 12); assert(!valid(p, 5));
    p[2] = storage(16, 0, 1, 1);
    p[3] = storage(15, 2, 3, 1); assert(!valid(p, 5));
    p[3] = storage(15, 2, 1, 3); assert(!valid(p, 5));
    p[3] = storage(15, 2, 1, 1);
    p[1] = vl4; assert(!valid(p, 5)); /* no store-value capture */
    p[1] = storage(14, 0, 1, 0); p[2] = vl4;
    p[3] = storage(16, 0, 1, 1); assert(!valid(p, 5)); /* VL reset */
    {
        uint32_t branch[] = { vl4, 0x00000463u, storage(14, 0, 1, 0),
                              storage(16, 0, 1, 1), OPENGPU_SHADER_CEASE };
        assert(!valid(branch, 5)); /* capture occurs on only one path */
    }
    return 0;
}
