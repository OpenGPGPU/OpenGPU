/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COUNTER_SMOKE_PROGRAM_H
#define OPENGPU_COUNTER_SMOKE_PROGRAM_H
#include <stdint.h>
/* vsetivli 4; v2=counter index 0; increment, read and decrement.
 * Each result vector is stored to a separate 16-byte kernarg slice. */
static const uint32_t opengpu_counter_smoke_program[] = {
    0xc1027057u, 0x5e003157u, 0x4a0101abu, 0x0200e1a7u,
    0x4601022bu, 0x01008293u, 0x0202e227u, 0x4e0102abu,
    0x02008293u, 0x0202e2a7u, 0x30500073u,
};
#endif
