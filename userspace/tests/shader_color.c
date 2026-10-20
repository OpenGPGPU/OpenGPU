/* SPDX-License-Identifier: MIT */
#include "opengpu_shaders.h"
#include <assert.h>
#include <stdio.h>

/* Execute the channel permutation's actual RVV words after vtex.sample.
 * Golden RGBA values below are independent of its instruction sequence. */
static uint32_t run_swizzle(uint32_t input)
{
    uint32_t x[32] = {0}, v[32] = {0};
    int sampled = 0;
    for (size_t i = 0; i < OPENGPU_FRAGMENT_SWIZZLE_BYTES / 4; i++) {
        uint32_t w = opengpu_fragment_swizzle[i];
        unsigned op = w & 127, rd = (w >> 7) & 31, f3 = (w >> 12) & 7;
        unsigned r1 = (w >> 15) & 31, r2 = (w >> 20) & 31;
        if ((w & 0xfe00707fu) == 0x0600002bu) {
            v[rd] = input;
            sampled = 1;
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
        if (!sampled) continue;
        if (op == 0x57) {
            uint32_t rhs = f3 == 4 ? x[r1] : f3 == 3 ? r1 : v[r1];
            assert(f3 == 0 || f3 == 3 || f3 == 4);
            switch (w >> 26) {
            case 9: v[rd] = v[r2] & rhs; break;
            case 10: v[rd] = v[r2] | rhs; break;
            case 37: v[rd] = v[r2] << (rhs & 31); break;
            case 40: v[rd] = v[r2] >> (rhs & 31); break;
            default: assert(!"unexpected channel-permutation instruction");
            }
        } else if (op == 0x27) {
            return v[rd];
        } else {
            assert(!"unexpected channel-permutation opcode");
        }
        x[0] = 0;
    }
    assert(!"missing swizzled output store");
    return 0;
}

int main(void)
{
    const uint32_t colors[][2] = {
        {0x115395d3, 0x955311d3}, {0xff0000ff, 0x0000ffff},
        {0x00ff00ff, 0x00ff00ff}, {0x0000ffff, 0xff0000ff},
        {0x01020304, 0x03020104}, {0x0080ff00, 0xff800000},
        {0xffffffff, 0xffffffff}, {0, 0},
    };
    for (size_t i = 0; i < sizeof(colors) / sizeof(colors[0]); i++) {
        uint32_t got = run_swizzle(colors[i][0]);
        if (got != colors[i][1]) {
            fprintf(stderr, "RGBA swizzle input=%08x actual=%08x expected=%08x\n",
                    colors[i][0], got, colors[i][1]);
            return 1;
        }
    }
    puts("packed RGBA shader swizzles and alpha preservation: PASS");
    return 0;
}
