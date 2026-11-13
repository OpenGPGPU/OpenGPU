/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <string.h>
#include "../opengpu_native_advanced_blend_gfs.h"

static void put(unsigned char *p, unsigned int word, unsigned int v)
{
    for (unsigned int i = 0; i < 4; ++i)
        p[4u * word + i] = (unsigned char)(v >> (8u * i));
}
int main(void)
{
    unsigned char frame[2048] = {0}, state[512] = {0};
    unsigned int mode, equation, color, format, i, version, blend;
    /* Literal GFS1/GNF1 offsets and numeric format classes are an independent
     * software oracle; the Scala generator separately checks actual layouts. */
    for (color = 0; color < 4; ++color) {
        put(frame, 73u + 9u * color, 1u);
        put(frame, 74u + 9u * color, 3u);
    }
    put(state, 94u, 3u); put(state, 27u, 3u);
    for (color = 0; color < 4; ++color) {
        for (equation = 0; equation < 15; ++equation) {
            put(state, 8u + color, (equation << 28) | (1u << 27) | (15u << 23) | 1u);
            for (format = 0; format < 36; ++format) {
                put(frame, 74u + 9u * color, format);
                assert(opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1) ==
                    (format < 9u || format >= 28u));
                assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 0));
            }
            put(frame, 74u + 9u * color, 3u);
            for (i = 1; i <= 3; ++i) {
                put(state, 32u + color, i);
                assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
            }
            put(state, 32u + color, 0u);
        }
        put(state, 8u + color, 0u);
    }
    put(state, 8u, (1u << 27) | (15u << 23) | 1u);
    for (i = 20; i <= 26; ++i) {
        put(state, i, 1u);
        assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
        put(state, i, 0u);
    }
    for (i = 1; i <= 22; ++i) {
        put(state, 8u, (1u << 27) | (1u << i) | 1u);
        assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
    }
    put(state, 8u, 1u << 27);
    assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
    put(state, 8u, (15u << 28) | (1u << 27) | 1u);
    assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
    put(state, 8u, (1u << 27) | 1u);
    for (i = 28; i <= 31; ++i) {
        put(state, 9u, 1u << i);
        assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
    }
    put(state, 9u, 0u);
    for (format = 36; format < 64; ++format) {
        put(frame, 74u, format);
        assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
    }
    put(frame, 74u, ~0u);
    assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
    put(frame, 74u, 3u);
    for (version = 0; version < 5; ++version)
        for (blend = 0; blend < 5; ++blend)
            for (mode = 0; mode < 2; ++mode) {
                const int expected = version == 3u || blend == 3u ?
                    mode && version == 3u && blend == 3u : 1;
                put(state, 94u, version); put(state, 27u, blend);
                assert(opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), mode) == expected);
            }
    put(state, 94u, 3u); put(state, 27u, 3u);
    assert(!opengpu_native_advanced_blend_gfs_precheck(frame, 2047u, state, sizeof(state), 1));
    assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, 511u, 1));
    assert(!opengpu_native_advanced_blend_gfs_precheck(0, sizeof(frame), state, sizeof(state), 1));
    assert(!opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), 0, sizeof(state), 1));
    assert(opengpu_native_advanced_blend_gfs_precheck(frame, sizeof(frame), state, sizeof(state), 1));
    return 0;
}
