/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_TESSELLATION_TRANSPORT_H
#define OPENGPU_TESSELLATION_TRANSPORT_H

#ifndef __KERNEL__
#include <stdbool.h>
#endif
#include "gpu_abi.h"
#include "opengpu_compute_resource_abi.h"
#include "opengpu_native_tessellation_job_abi.h"
#include "opengpu_native_tessellation_sequence_abi.h"

/* GNC1..4 offsets and masks, checked against the generated host ABI in the
 * userspace discovery test. The generated host header is not kernel-safe. */
#define OPENGPU_TESS_HOST_DISCOVERY GPU_REG_BIN_CAPABILITY_PTR
#define OPENGPU_TESS_HOST_BASE GPU_REG_BIN_CAPABILITIES
#define OPENGPU_TESS_HOST_WORDS 8u
#define OPENGPU_TESS_HOST_MAGIC_PHYSICAL 0x474e4301u
#define OPENGPU_TESS_HOST_MAGIC_VIRTUAL 0x474e4302u
#define OPENGPU_TESS_HOST_MAGIC_WIDE_VIRTUAL 0x474e4303u
#define OPENGPU_TESS_HOST_MAGIC_WIDE_PHYSICAL 0x474e4304u
#define OPENGPU_TESS_HOST_JOB_MAGIC 0x474e4a01u
#define OPENGPU_TESS_HOST_COMPLETION_MASK 0x80000000u
#define OPENGPU_TESS_HOST_ENGINES_MASK 0x38000000u
#define OPENGPU_TESS_HOST_ENGINES_SHIFT 27u

/* GCR1 reports the internal GNQ1/GNQ2/GNQ3 command transport, not a
 * userspace submission interface or GLES conformance. Leave outputs unchanged
 * if the advertised combination cannot safely support that transport. */
static inline bool opengpu_tessellation_transport_features(
    u32 resource_flags, u32 host_caps, bool *transport, bool *feedback)
{
    bool available, feedback_available;

    if (!transport || !feedback)
        return false;
    available = !!(resource_flags & OPENGPU_COMPUTE_RESOURCE_TESSELLATION_COMMANDS);
    feedback_available = !!(resource_flags & OPENGPU_COMPUTE_RESOURCE_TESSELLATION_FEEDBACK);
    if ((feedback_available && !available) ||
        (available && (host_caps & (GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE)) !=
            (GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE)))
        return false;
    *transport = available;
    *feedback = feedback_available;
    return true;
}

/* An advertised GNQ transport must have the native owner's real engine
 * count and image-address mode. Do not probe optional GNC registers when the
 * transport is absent, and leave outputs unchanged for malformed records. */
static inline bool opengpu_read_tessellation_host(
    bool available, unsigned long long mmio_bytes, void *context,
    u32 (*read_reg)(void *, u32), u32 *engines, bool *virtual_images)
{
    u32 magic, geometry, count;
    bool translated;
    if (!engines || !virtual_images)
        return false;
    if (!available) {
        *engines = 0;
        *virtual_images = false;
        return true;
    }
    if (!read_reg || mmio_bytes < OPENGPU_TESS_HOST_BASE + 4u * OPENGPU_TESS_HOST_WORDS ||
        read_reg(context, OPENGPU_TESS_HOST_DISCOVERY) != OPENGPU_TESS_HOST_BASE)
        return false;
    magic = read_reg(context, OPENGPU_TESS_HOST_BASE);
    if (magic != OPENGPU_TESS_HOST_MAGIC_PHYSICAL && magic != OPENGPU_TESS_HOST_MAGIC_VIRTUAL &&
        magic != OPENGPU_TESS_HOST_MAGIC_WIDE_VIRTUAL && magic != OPENGPU_TESS_HOST_MAGIC_WIDE_PHYSICAL)
        return false;
    translated = magic == OPENGPU_TESS_HOST_MAGIC_VIRTUAL || magic == OPENGPU_TESS_HOST_MAGIC_WIDE_VIRTUAL;
    if (read_reg(context, OPENGPU_TESS_HOST_BASE + 4u) != OPENGPU_TESS_HOST_WORDS ||
        read_reg(context, OPENGPU_TESS_HOST_BASE + 8u) != OPENGPU_TESS_HOST_JOB_MAGIC ||
        read_reg(context, OPENGPU_TESS_HOST_BASE + 12u) !=
            ((OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES << 16) | OPENGPU_NATIVE_TESSELLATION_JOB_BYTES))
        return false;
    geometry = read_reg(context, OPENGPU_TESS_HOST_BASE + 16u);
    if ((geometry & 0xffffu) != OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES ||
        !(geometry & OPENGPU_TESS_HOST_COMPLETION_MASK))
        return false;
    count = (read_reg(context, OPENGPU_TESS_HOST_BASE + 24u) & OPENGPU_TESS_HOST_ENGINES_MASK) >>
            OPENGPU_TESS_HOST_ENGINES_SHIFT;
    if (!count || count > 4u)
        return false;
    *engines = count;
    *virtual_images = translated;
    return true;
}
#endif
