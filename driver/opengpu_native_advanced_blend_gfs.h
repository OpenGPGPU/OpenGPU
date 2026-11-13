/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_ADVANCED_BLEND_GFS_H
#define OPENGPU_NATIVE_ADVANCED_BLEND_GFS_H
#include "opengpu_native_advanced_blend_abi.h"

static inline unsigned int opengpu_native_advanced_blend_word(
    const unsigned char *bytes, unsigned int word)
{
    bytes += 4u * word;
    return (unsigned int)bytes[0] | ((unsigned int)bytes[1] << 8) |
        ((unsigned int)bytes[2] << 16) | ((unsigned int)bytes[3] << 24);
}

/* Run after the existing bounded canonical/scratch GFS precheck, before
 * enqueue. Legacy state retains its existing validation path. This helper
 * validates only the new payload/control contract, not shader provenance or
 * unrelated fragment-state/resource semantics. Generated offsets come from
 * the actual GFS1/GNF1 hardware layouts and need no libc/kernel types. */
static inline int opengpu_native_advanced_blend_gfs_precheck(
    const unsigned char *frame, unsigned long long frame_bytes,
    const unsigned char *state, unsigned long long state_bytes, int transport)
{
    static const unsigned int shared[] = {
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_ENABLE_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_SRC_RGB_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_DST_RGB_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_SRC_ALPHA_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_DST_ALPHA_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_EQUATION_RGB_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_EQUATION_ALPHA_WORD,
    };
    static const unsigned int buffers[] = {
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BUFFER0_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BUFFER1_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BUFFER2_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BUFFER3_WORD,
    };
    static const unsigned int types[] = {
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_COLOR_TYPE0_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_COLOR_TYPE1_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_COLOR_TYPE2_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_GFS_COLOR_TYPE3_WORD,
    };
    static const unsigned int enabled[] = {
        OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_ENABLED0_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_ENABLED1_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_ENABLED2_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_ENABLED3_WORD,
    };
    static const unsigned int formats[] = {
        OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_FORMAT0_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_FORMAT1_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_FORMAT2_WORD,
        OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_FORMAT3_WORD,
    };
    unsigned int version, blend, i;
    if (!frame || frame_bytes < 2048u || !state || state_bytes < 512u)
        return 0;
    version = opengpu_native_advanced_blend_word(state, OPENGPU_NATIVE_ADVANCED_BLEND_GFS_PAYLOAD_VERSION_WORD);
    blend = opengpu_native_advanced_blend_word(state, OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_VERSION_WORD);
    if (version != OPENGPU_NATIVE_ADVANCED_BLEND_FRAGMENT_STATE_PAYLOAD_VERSION &&
        blend != OPENGPU_NATIVE_ADVANCED_BLEND_BLEND_STATE_VERSION)
        return 1;
    if (!transport || version != OPENGPU_NATIVE_ADVANCED_BLEND_FRAGMENT_STATE_PAYLOAD_VERSION ||
        blend != OPENGPU_NATIVE_ADVANCED_BLEND_BLEND_STATE_VERSION)
        return 0;
    for (i = 0; i < sizeof(shared) / sizeof(shared[0]); ++i)
        if (opengpu_native_advanced_blend_word(state, shared[i]))
            return 0;
    for (i = 0; i < 4u; ++i) {
        unsigned int w = opengpu_native_advanced_blend_word(state, buffers[i]);
        if (w & (1u << 27)) {
            unsigned int format;
            if (!(w & 1u) || (w & 0x7ffffeu) || (w >> 28) >= 15u ||
                opengpu_native_advanced_blend_word(state, types[i]))
                return 0;
            if (!opengpu_native_advanced_blend_word(frame, enabled[i]))
                continue;
            format = opengpu_native_advanced_blend_word(frame, formats[i]);
            if (format >= 64u || !(format < 32u ?
                (OPENGPU_NATIVE_ADVANCED_BLEND_FORMATS_LOW >> format) & 1u :
                (OPENGPU_NATIVE_ADVANCED_BLEND_FORMATS_HIGH >> (format - 32u)) & 1u))
                return 0;
        } else if ((w >> 28) || ((w >> 1) & 15u) > 14u || ((w >> 5) & 15u) > 14u ||
                   ((w >> 9) & 15u) > 14u || ((w >> 13) & 15u) > 14u ||
                   ((w >> 17) & 7u) > 4u || ((w >> 20) & 7u) > 4u) {
            return 0;
        }
    }
    return 1;
}
#endif
