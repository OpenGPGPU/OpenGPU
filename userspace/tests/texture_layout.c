/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "opengpu_texture_layout.h"

static void check_rows(unsigned width, unsigned height, unsigned pitch)
{
    unsigned char source[16384], packed[16386];
    unsigned row, byte;
    size_t bytes = (size_t)width * height * 4;
    assert((size_t)pitch * height <= sizeof(source));
    memset(source, 0xa5, sizeof(source));
    memset(packed, 0x5a, sizeof(packed));
    for (row = 0; row < height; row++)
        for (byte = 0; byte < width * 4; byte++)
            source[row * pitch + byte] = (unsigned char)(17 * row + 11 * byte);
    assert(!opengpu_texture_pack_rows(packed + 1, bytes, source,
        (size_t)(height - 1) * pitch + width * 4, width, height, pitch));
    for (row = 0; row < height; row++)
        for (byte = 0; byte < width * 4; byte++)
            assert(packed[1 + row * width * 4 + byte] ==
                   (unsigned char)(17 * row + 11 * byte));
    assert(packed[0] == 0x5a && packed[bytes + 1] == 0x5a);
}

static void check_swizzles(void)
{
    /* Two pitched rows with deliberately distinct RGBA values and padding. */
    const unsigned char rgba[2][2][4] = {
        {{11, 22, 33, 44}, {55, 66, 77, 88}},
        {{99, 111, 123, 135}, {147, 159, 171, 183}},
    };
    unsigned char src[40], dst[18], before[18];
    unsigned format, combination, x, y, c;
    uint8_t swizzle[4];

    for (format = 0; format < 4; format++) {
        int reversed = format & 1, opaque = format & 2;
        memset(src, 0xa5, sizeof(src));
        for (y = 0; y < 2; y++) for (x = 0; x < 2; x++) {
            unsigned char *p = src + y * 20 + x * 4;
            p[0] = rgba[y][x][reversed ? 2 : 0];
            p[1] = rgba[y][x][1];
            p[2] = rgba[y][x][reversed ? 0 : 2];
            p[3] = rgba[y][x][3];
        }
        /* All 6^4 legal selectors, including duplicate channels and 0/1. */
        for (combination = 0; combination < 1296; combination++) {
            unsigned digits = combination;
            for (c = 0; c < 4; c++) { swizzle[c] = digits % 6; digits /= 6; }
            memset(dst, 0x5a, sizeof(dst));
            assert(!opengpu_texture_swizzle_rows(dst + 1, 16, src, 28,
                2, 2, 20, reversed, opaque, swizzle));
            assert(dst[0] == 0x5a && dst[17] == 0x5a);
            for (y = 0; y < 2; y++) for (x = 0; x < 2; x++) {
                unsigned char expect[6] = {rgba[y][x][0], rgba[y][x][1],
                    rgba[y][x][2], opaque ? 255 : rgba[y][x][3], 0, 255};
                for (c = 0; c < 4; c++)
                    assert(dst[1 + (y * 2 + x) * 4 + c] == expect[swizzle[c]]);
            }
        }
    }
    memset(dst, 0x5a, sizeof(dst));
    memcpy(before, dst, sizeof(dst));
    swizzle[0] = 0; swizzle[1] = 1; swizzle[2] = 2; swizzle[3] = 6;
    assert(opengpu_texture_swizzle_rows(dst, 16, src, 28, 2, 2, 20, 0, 0, swizzle));
    swizzle[3] = 3;
    assert(opengpu_texture_swizzle_rows(dst, 15, src, 28, 2, 2, 20, 0, 0, swizzle));
    assert(opengpu_texture_swizzle_rows(dst, 16, src, 27, 2, 2, 20, 0, 0, swizzle));
    assert(opengpu_texture_swizzle_rows(dst, 16, src, 28, 2, 2, 7, 0, 0, swizzle));
    assert(!memcmp(dst, before, sizeof(dst)));
}

int main(void)
{
    unsigned char source[128] = {0}, target[128], before[128];
    check_rows(4, 4, 16);
    check_rows(4, 4, 64);
    check_rows(4, 4, 256);
    check_rows(3, 5, 13);
    check_rows(1, 64, 64);
    memset(target, 0x5a, sizeof(target));
    memcpy(before, target, sizeof(before));
    assert(opengpu_texture_pack_rows(target, 63, source, 64, 4, 4, 16));
    assert(opengpu_texture_pack_rows(target, 64, source, 63, 4, 4, 16));
    assert(opengpu_texture_pack_rows(target, 64, source, 111, 4, 4, 32));
    assert(opengpu_texture_pack_rows(target, 64, source, 128, 5, 4, 16));
    assert(opengpu_texture_pack_rows(target, 64, source, 128, 0, 4, 16));
    assert(opengpu_texture_pack_rows(target, 64, source, 128, 4, 0, 16));
    assert(opengpu_texture_pack_rows(target, 64, source, 128, UINT32_MAX, 4, 16));
    assert(opengpu_texture_pack_rows(target, 64, source, 128, 4, UINT32_MAX, 256));
    assert(opengpu_texture_pack_rows(NULL, 64, source, 64, 4, 4, 16));
    assert(opengpu_texture_pack_rows(target, 64, NULL, 64, 4, 4, 16));
    assert(!memcmp(target, before, sizeof(target)));
    check_swizzles();
    puts("packed/pitched texture rows, bounds and padding isolation: PASS");
    return 0;
}
