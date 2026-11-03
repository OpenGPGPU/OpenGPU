// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stdint.h>
#include "../opengpu_shader_validator.h"

static uint32_t image(unsigned fn, unsigned sub, unsigned rd, unsigned a, unsigned b)
{
    return fn << 26 | 1u << 25 | b << 20 | a << 15 | sub << 12 | rd << 7 | 0x2bu;
}
static bool valid(const uint32_t *p, unsigned n)
{
    return opengpu_compute_shader_validate_words_images(p, n, 64, 4, false, true);
}
int main(void)
{
    const uint32_t vl4 = 0xc1027057u;
    uint32_t p[] = { vl4, image(8,0,0,1,1), image(8,1,0,1,1),
        image(9,0,2,1,1), image(11,1,3,0,0), image(8,2,0,2,3),
        image(8,3,0,2,3), image(10,0,0,1,1), OPENGPU_SHADER_CEASE };
    assert(valid(p, 9));
    assert(!opengpu_compute_shader_validate_words(p, 9, 64, 4));
    assert(!opengpu_compute_shader_validate_words_storage(p, 9, 64, 4, false));
    p[1] = image(8,0,1,1,1); assert(!valid(p, 9));
    p[1] = image(8,4,0,1,1); assert(!valid(p, 9));
    p[1] = image(8,0,0,3,1); assert(!valid(p, 9));
    p[1] = image(8,0,0,1,1);
    p[2] = vl4; assert(!valid(p, 9));
    p[2] = image(8,1,0,1,1);
    p[3] = image(9,1,2,1,1); assert(!valid(p, 9));
    p[3] = image(9,0,2,1,1) & ~(1u << 25); assert(!valid(p, 9));
    p[3] = image(9,0,2,1,1);
    p[4] = image(11,0,3,0,0); assert(!valid(p, 9));
    p[4] = image(11,1,3,1,0); assert(!valid(p, 9));
    p[4] = image(11,1,3,0,0);
    p[6] = image(8,0,0,2,3); assert(!valid(p, 9));
    p[6] = image(8,3,0,2,3);
    p[7] = image(10,0,1,1,1); assert(!valid(p, 9));
    p[7] = image(10,0,0,1,1); assert(valid(p, 9));
    {
        uint32_t branch[] = { vl4, image(8,0,0,1,1), 0x00000463u,
            image(8,1,0,1,1), image(9,0,2,1,1), OPENGPU_SHADER_CEASE };
        assert(!valid(branch, 6));
    }
    {
        uint32_t stale[] = { vl4, image(8,0,0,1,1), image(8,1,0,1,1),
            image(9,0,2,1,1), image(8,0,0,1,1), image(11,1,3,0,0), OPENGPU_SHADER_CEASE };
        assert(!valid(stale, 7));
    }
    {
        uint32_t sync[] = { 0x0330000fu, OPENGPU_SHADER_CEASE };
        assert(!valid(sync, 2));
        sync[0] = 0x0000000bu;
        assert(!valid(sync, 2));
    }
    {
        uint32_t guest[] = {
            0xc100f057u, (23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u,
            0x3a800513u, (23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u,
            image(8,0,0,2,3), image(8,1,0,2,2),
            (23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(4u<<7)|0x57u,
            image(8,2,0,4,2), image(8,3,0,2,2),
            image(10,0,0,2,2), image(9,0,5,2,2),
            0x02006027u|(1u<<15)|(5u<<7), OPENGPU_SHADER_CEASE,
        };
        assert(opengpu_compute_shader_validate_words_images(guest,13,64,1,false,true));
    }
    return 0;
}
