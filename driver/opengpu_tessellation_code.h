/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_TESSELLATION_CODE_H
#define OPENGPU_TESSELLATION_CODE_H

#include "opengpu_tessellation_uniform.h"

/* The existing private fragment-code window; no new address-space carveout. */
#define OPENGPU_TESS_FRAGMENT_CODE_SLOT 1u

/* Stable kernel inputs, sorted by unique state_handle. Only draw-referenced
 * states consume code. Their programs are packed in handle order, aligned to
 * 64 bytes, in the existing 4 MiB code window. expected_base must equal that
 * program's planned VA: the producer must compile/relocate for this address.
 * The driver copies bytes and rewrites the entry PC, not embedded addresses or
 * instructions. This is an ownership contract, not an arbitrary-code validator.
 * No caller bytes or plan need survive a successful submit. */
struct opengpu_tessellation_fragment_code_plan {
    unsigned int state_handle;
    const void *code;
    opengpu_tess_uniform_u64 bytes, entry_offset, expected_base;
};

#endif
