/* SPDX-License-Identifier: MIT */
#ifndef GRAPHICS_SAMPLE_OWNER_IPC_H
#define GRAPHICS_SAMPLE_OWNER_IPC_H
#include "graphics_native_owner_ipc.h" /* observation storage only; no old wire */
#include "opengpu_native_sample_graphics.h"

/* Private ordinary112 two-owner transport. No codec call runs a shader,
 * creates an observed receipt or authorizes a public graphics capability.
 * These interfaces do not accept the old96 or record0-only sample wires.
 * Controllers must publish complete files atomically. All errors leave outputs
 * unchanged; a successful held read zeroes the unobserved retirement fields. */
int graphics_sample_graphics_profile_read(const char *,struct opengpu_native_sample_runtime *);
int graphics_sample_graphics_held_wait(const char *directory,unsigned record,unsigned timeout_seconds,
    struct graphics_native_observation *);
int graphics_sample_graphics_execution_wait(const char *directory,unsigned record,unsigned timeout_seconds,
    struct graphics_native_observation *);
int graphics_sample_graphics_cleanup_wait(const char *directory,unsigned timeout_seconds);
int graphics_sample_graphics_final_wait(const char *directory,unsigned timeout_seconds,
    struct graphics_native_observation output[2]);

/* Held: record-N.held.bin = OGNGH001, LE count1, 84-byte (15I,Q,4I).
 * Retired: record-N.response.bin = OGNGR001, count1, held + four LE u32.
 * Cleanup: cleanup.authorize.bin = OGNGB001, count2, no payload.
 * Final: response.bin = OGNGF001, count2, two 100-byte retired payloads 0,1.
 * Receipt syntax is not a lifecycle proof: callers must bind each record and
 * command to actual enqueue/held/retired observations, compare all held fields,
 * verify independent images/GNY2, then separately observe actual cleanup ACKs.
 * Neither a per-record response nor a final file grants cleanup permission. */
#endif
