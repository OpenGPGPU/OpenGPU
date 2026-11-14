/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_NATIVE_INPUT_SNAPSHOT_H
#define OPENGPU_NATIVE_INPUT_SNAPSHOT_H
#include "opengpu_drm.h"
#include "opengpu_native_frame_snapshot.h"
struct opengpu_private_snapshot;

/* Patch one canonical GND in an already copied contiguous private draw array.
 * The caller verifies plan index/state identity, full GND/GFS/GNF semantics and
 * cross-frame resource disjointness, retains original local GEM references and
 * waits producer reservations. resolve returns those actual GEM allocations.
 * capacity is the verified physical shader item capacity, not a draw count.
 *
 * This helper validates input versions 1/2, all sixteen bindings, indexed and
 * indirect modes, and the complete VS1/2 SoA scratch prefix. Only generated
 * input/scratch address fields receive resource relocations. PC and GUR roots
 * remain for the caller. The copied GND must match raw_draw byte for byte and
 * all input/scratch address words and padding must initially be zero.
 *
 * Inputs map read-only/non-executable; scratch maps read/write/non-executable.
 * Runtime index/indirect contents remain checked by the real input hardware.
 * No VA is returned or guessed. On any failure discard/retire the unpublished
 * owner, including after partial resource/relocation registration.
 */
int opengpu_native_input_snapshot_patch(struct opengpu_private_snapshot *owner,
    u32 draw_array_segment,u64 draw_byte_offset,const void *raw_draw,
    const struct drm_opengpu_native_draw_plan *plan,u32 capacity,
    opengpu_native_frame_resolve resolve,void *resolve_context);
/* Explicit GND2 version only, selected after full GNG/GS admission. The
 * ordinary entry above continues to reject GND2. Input/output capacities and
 * original backing permissions are identical; no metadata/code is patched. */
int opengpu_native_input_snapshot_patch_geometry(struct opengpu_private_snapshot *owner,
    u32 draw_array_segment,u64 draw_byte_offset,const void *raw,
    const struct drm_opengpu_native_draw_plan *plan,u32 capacity,
    opengpu_native_frame_resolve resolve,void *context);
#endif
