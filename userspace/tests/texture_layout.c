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
    puts("packed/pitched texture rows, bounds and padding isolation: PASS");
    return 0;
}
