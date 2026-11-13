/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FRAME_ABI_H
#define OPENGPU_NATIVE_FRAME_ABI_H
#ifdef __KERNEL__
typedef unsigned int opengpu_native_frame_u32;
typedef unsigned long long opengpu_native_frame_u64;
#else
#include <stdint.h>
typedef uint32_t opengpu_native_frame_u32;
typedef uint64_t opengpu_native_frame_u64;
#endif

/* Typed relocation roles, never caller-selected GNF word offsets. The kernel
 * chooses private VAs and patches only the role's generated address fields.
 * GFS state is copied into the metadata owner and patched independently; it
 * cannot be substituted with an external GEM resource role.
 */
#define OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE       1u /* index 0..5 */
#define OPENGPU_NATIVE_FRAME_RESOURCE_RESOLVE     2u /* index 0..5 */
#define OPENGPU_NATIVE_FRAME_RESOURCE_PRIMITIVES  3u /* index 0 */
#define OPENGPU_NATIVE_FRAME_RESOURCE_POOL        4u /* index 0 */
#define OPENGPU_NATIVE_FRAME_RESOURCE_DIRECTORY   5u /* index 0 */
#define OPENGPU_NATIVE_FRAME_RESOURCE_SCRATCH     6u /* index 0..engines-1 */
#define OPENGPU_NATIVE_FRAME_RESOURCE_READ  1u
#define OPENGPU_NATIVE_FRAME_RESOURCE_WRITE 2u

/* offset/bytes name an exact view within the original local GEM object.
 * The producer retains that object and its dependencies through retirement.
 * Layout/role/permission validation precedes every private address patch.
 * This 32-byte record is independent of host pointer size and end addresses
 * are formed in widened arithmetic, preserving the exclusive RV32 2^32 end.
 */
struct opengpu_native_frame_resource {
    opengpu_native_frame_u32 role, index, gem_handle, access;
    opengpu_native_frame_u64 offset, bytes;
};
#endif
