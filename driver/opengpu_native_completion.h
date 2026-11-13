/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_COMPLETION_H
#define OPENGPU_NATIVE_COMPLETION_H

#include "opengpu_native_binned_host_abi.h"

/* Host-order GNX1 snapshot, copied while its unified opcode-7 slot is held.
 * Recorded address/draw are raw owner fields, including on success. Consumers
 * must interpret fault stages/valid bits before reporting them as fault data.
 * This structure is internal, not a userspace or on-wire ABI. */
struct opengpu_native_completion {
    struct opengpu_native_binned_completion_info info;
    unsigned long long append_read_bytes;
};

/* NativeBinnedGraphicsJobReader counts 64 bytes for every accepted GND line,
 * including padding/faulting requests, excluding GNJ/GNF/GFS fetches. A checked
 * RV32 draw array (stride >= 2048) occupies at most 2^32 bytes; GNP1 scans it
 * once before execution, then execution reads it once more. Thus all current
 * GNX1 native profiles have a conservative 2^33-byte per-command upper bound.
 * Zero is valid for an empty job or a failure before any draw request. */
#define OPENGPU_NATIVE_COMPLETION_MAX_APPEND_BYTES (1ULL << 33)

/* Return record validity, not job success. Legal error completions remain
 * available for diagnostics even if a failed GNJ fetch never decoded identity.
 * Success must belong to the exact expected identity and have no nested error
 * or fault flags. Malformed records leave *out unchanged. */
static inline int opengpu_native_completion_decode(
    const opengpu_native_host_word *words, unsigned count,
    opengpu_native_host_word status, unsigned long long append_read_bytes,
    opengpu_native_host_word expected_context, opengpu_native_host_word expected_pass,
    opengpu_native_host_word expected_generation, struct opengpu_native_completion *out)
{
    struct opengpu_native_completion decoded;
    opengpu_native_host_word flags, nested;
    unsigned i;
    const opengpu_native_host_word flag_mask =
        OPENGPU_NATIVE_BINNED_COMPLETION_STATUS_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_READER_STAGE_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_FRAME_STAGE_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_GEOMETRY_STAGE_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_TILE_VALID_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_GEOMETRY_DRAW_VALID_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_SOURCE_VALID_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_VIEWPORT_VALID_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_CLIP_VALID_MASK;
    const opengpu_native_host_word source_status_mask =
        OPENGPU_NATIVE_BINNED_COMPLETION_VIEWPORT_STATUS_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_CLIP_STATUS_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_ASSEMBLY_STATUS_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_VERTEX_STATUS_MASK;
    const opengpu_native_host_word nested_mask = source_status_mask |
        OPENGPU_NATIVE_BINNED_COMPLETION_FRAME_STATUS_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_GEOMETRY_STATUS_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_BIN_REPLAY_STATUS_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_BIN_PRODUCER_STATUS_MASK |
        OPENGPU_NATIVE_BINNED_COMPLETION_BIN_REJECTION_STATUS_MASK;

    if (!words || !out || count != OPENGPU_NATIVE_BINNED_COMPLETION_WORDS || status > 7 ||
        (append_read_bytes & 63ULL) || append_read_bytes > OPENGPU_NATIVE_COMPLETION_MAX_APPEND_BYTES)
        return 0;
    flags = words[OPENGPU_NATIVE_BINNED_COMPLETION_STATUS_WORD];
    nested = words[OPENGPU_NATIVE_BINNED_COMPLETION_FRAME_STATUS_WORD];
    if (words[OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC_WORD] != OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC ||
        (flags & ~flag_mask) || (nested & ~nested_mask) ||
        (flags & OPENGPU_NATIVE_BINNED_COMPLETION_STATUS_MASK) != status ||
        words[OPENGPU_NATIVE_BINNED_COMPLETION_RESERVED_WORD])
        return 0;
    if (!(flags & OPENGPU_NATIVE_BINNED_COMPLETION_TILE_VALID_MASK) &&
        words[OPENGPU_NATIVE_BINNED_COMPLETION_TILE_WORD])
        return 0;
    if (!(flags & OPENGPU_NATIVE_BINNED_COMPLETION_GEOMETRY_DRAW_VALID_MASK) &&
        (words[OPENGPU_NATIVE_BINNED_COMPLETION_GEOMETRY_DRAW_LO_WORD] ||
         words[OPENGPU_NATIVE_BINNED_COMPLETION_GEOMETRY_DRAW_HI_WORD]))
        return 0;
    if (!(flags & OPENGPU_NATIVE_BINNED_COMPLETION_SOURCE_VALID_MASK) &&
        ((flags & (OPENGPU_NATIVE_BINNED_COMPLETION_VIEWPORT_VALID_MASK |
                   OPENGPU_NATIVE_BINNED_COMPLETION_CLIP_VALID_MASK)) || (nested & source_status_mask)))
        return 0;
    if (!status && (flags || nested ||
        words[OPENGPU_NATIVE_BINNED_COMPLETION_CONTEXT_WORD] != expected_context ||
        words[OPENGPU_NATIVE_BINNED_COMPLETION_PASS_WORD] != expected_pass ||
        words[OPENGPU_NATIVE_BINNED_COMPLETION_GENERATION_WORD] != expected_generation))
        return 0;
    for (i = 0; i < OPENGPU_NATIVE_BINNED_COMPLETION_WORDS; ++i)
        decoded.info.words[i] = words[i];
    decoded.append_read_bytes = append_read_bytes;
    *out = decoded;
    return 1;
}

/* Caller first verifies the native GNC profile and pending native submission:
 * opcode 7 is also used by legacy ABIs and alone does not guarantee GNX1.
 * It owns the held slot and software lock through capture and subsequent POP. No register is
 * written here. Missing apertures/invalid arguments cause no MMIO access; a
 * different completion magic causes only one read. All twelve words, including
 * successful GNX1 identity, must be copied before POP clears their visibility. */
static inline int opengpu_native_completion_capture(
    opengpu_native_host_word status, unsigned long long append_read_bytes,
    unsigned long long aperture_bytes, opengpu_native_host_word expected_context,
    opengpu_native_host_word expected_pass, opengpu_native_host_word expected_generation,
    void *context, opengpu_native_host_word (*read_reg)(void *, opengpu_native_host_word),
    struct opengpu_native_completion *out)
{
    opengpu_native_host_word words[OPENGPU_NATIVE_BINNED_COMPLETION_WORDS];
    unsigned i;
    if (!out || !read_reg || status > 7 || (append_read_bytes & 63ULL) ||
        append_read_bytes > OPENGPU_NATIVE_COMPLETION_MAX_APPEND_BYTES ||
        aperture_bytes < OPENGPU_NATIVE_BINNED_COMPLETION_BASE +
            OPENGPU_NATIVE_BINNED_COMPLETION_WORDS * 4ULL)
        return 0;
    words[0] = read_reg(context, OPENGPU_NATIVE_BINNED_COMPLETION_BASE);
    if (words[0] != OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC)
        return 0;
    for (i = 1; i < OPENGPU_NATIVE_BINNED_COMPLETION_WORDS; ++i)
        words[i] = read_reg(context, OPENGPU_NATIVE_BINNED_COMPLETION_BASE + i * 4u);
    return opengpu_native_completion_decode(words, OPENGPU_NATIVE_BINNED_COMPLETION_WORDS,
        status, append_read_bytes, expected_context, expected_pass, expected_generation, out);
}
#endif
