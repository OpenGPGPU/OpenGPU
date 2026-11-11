/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_UNIFIED_COMPLETION_H
#define OPENGPU_UNIFIED_COMPLETION_H

#include "gpu_abi.h"

/* Most unified engines report transferred bytes. Visibility queries instead
 * return a two-bit value/availability pair in the same completion field.
 * A true value cannot be observed before it is available. */
static inline int opengpu_unified_completion_data_valid(u32 opcode,
    unsigned long long data, unsigned long long expected_bytes)
{
    if (opcode >= GPU_UCMD_OP_QUERY_BEGIN && opcode <= GPU_UCMD_OP_QUERY_RESULT)
        return !(data & ~3ull) && (!(data & 1ull) || (data & 2ull));
    return data == expected_bytes;
}

#endif
