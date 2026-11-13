/* SPDX-License-Identifier: MIT */
#ifndef GRAPHICS_NATIVE_OWNER_IPC_H
#define GRAPHICS_NATIVE_OWNER_IPC_H
#include <stdint.h>

/* Qualification transport only. These values are actual external DUT reads;
 * this codec creates no native record, address or completion of its own. */
struct graphics_native_probe {
    uint32_t words[96][2];
    uint32_t registers[5][2];
};
struct graphics_native_observation {
    uint32_t record, command_id, completion, gnx[12];
    uint64_t append_read_bytes;
    uint32_t held_status, held_irq, held_irq_asserted;
    uint32_t retired_status, retired_irq, retired_irq_asserted, external_drained;
};
int graphics_native_probe_read(const char *path, struct graphics_native_probe *output);
int graphics_native_held_wait(const char *directory, unsigned record, unsigned timeout_seconds,
    struct graphics_native_observation *output);
/* A per-record response proves observed execution/drain only. It never grants
 * permission to release private mappings, leases, job references or storage. */
int graphics_native_execution_wait(const char *directory, unsigned record, unsigned timeout_seconds,
    struct graphics_native_observation *output);
/* Only the separate final two-record response follows both image/guard checks.
 * The caller must still use its real driver's observed-retirement contract. */
int graphics_native_final_wait(const char *directory, unsigned timeout_seconds,
    struct graphics_native_observation output[2]);
#endif
