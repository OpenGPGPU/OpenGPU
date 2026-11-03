/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_TESSELLATION_TRANSPORT_H
#define OPENGPU_TESSELLATION_TRANSPORT_H

#ifndef __KERNEL__
#include <stdbool.h>
#endif
#include "gpu_abi.h"
#include "opengpu_compute_resource_abi.h"

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
#endif
