/* SPDX-License-Identifier: MIT */
#include "opengpu_native_runtime.h"
#include "opengpu.h"
#include "../driver/opengpu_tessellation_joint_host.h"
#include <errno.h>

#define FIELD(c, name) (((c)->words[OPENGPU_NATIVE_BINNED_HOST_##name##_WORD] & \
    OPENGPU_NATIVE_BINNED_HOST_##name##_MASK) >> OPENGPU_NATIVE_BINNED_HOST_##name##_SHIFT)

int opengpu_native_runtime_query(int fd, struct opengpu_native_runtime *output)
{
    struct opengpu_native_runtime result;
    uint64_t features, layout;
    int error;
    if (!output) { errno = EINVAL; return -1; }
    if (opengpu_driver_features(fd,&features)) return -1;
    if (!(features & OPENGPU_DRIVER_FEATURE_NATIVE_BINNED)) { errno = ENOTSUP; return -1; }
    if (opengpu_native_layout(fd,&layout) || opengpu_native_caps(fd,result.frame.words)) return -1;
    if (!opengpu_native_layout_decode(layout,&result.shader)) { errno = EPROTO; return -1; }
    error = opengpu_native_frame_caps_validate(&result.frame);
    if (error) { errno = error == -EOPNOTSUPP ? ENOTSUP : EPROTO; return -1; }
    /* GET8's physical item capacity comes from GCR. GNC payload capacity is
     * measured in bytes and must not be substituted for that item count. */
    if (result.shader.attributes != FIELD(&result.frame, ATTRIBUTES) ||
        result.shader.bindings != FIELD(&result.frame, BINDINGS) ||
        result.shader.varyings != FIELD(&result.frame, VARYINGS) ||
        result.shader.colors != FIELD(&result.frame, COLORS)) {
        errno = EPROTO; return -1;
    }
    /* Preserve the existing validator's eight-bit batch contract explicitly. */
    if (result.shader.capacity > 255) { errno = ENOTSUP; return -1; }
    *output = result;
    return 0;
}

int opengpu_native_joint_runtime_query(int fd, struct opengpu_native_joint_runtime *output)
{
    struct opengpu_native_joint_runtime result = {0};
    uint32_t profile[OPENGPU_PARAM_TESS_JOINT_PROFILE_WORDS];
    const uint32_t required = OPENGPU_TESS_JOINT_HOST_PROFILE_REQUIRED_FLAGS |
        (1u << OPENGPU_TESS_JOINT_HOST_PROFILE_SCALAR_FPU_BIT);
    const uint32_t optional = (1u << OPENGPU_TESS_JOINT_HOST_PROFILE_ADVANCED_BLEND_BIT) |
        (1u << OPENGPU_TESS_JOINT_HOST_PROFILE_SAMPLES_BIT) |
        (1u << OPENGPU_TESS_JOINT_HOST_PROFILE_FEEDBACK_BIT);
    if (!output) { errno = EINVAL; return -1; }
    if (opengpu_tessellation_joint_caps(fd, profile, result.frame.words)) return -1;
    if (!profile[0] && !profile[1] && !profile[2] && !profile[3]) {
        errno = ENOTSUP;
        return -1;
    }
    if (profile[0] != OPENGPU_TESS_JOINT_HOST_PROFILE_MAGIC ||
        (profile[1] & required) != required || (profile[1] & ~(required | optional)) ||
        profile[2] != 0x08100207u || profile[3] < 1 || profile[3] > 4 ||
        !opengpu_native_source_frame_valid_profile(&result.frame,
            result.frame.words[0]==0x474e4305u) ||
        FIELD(&result.frame, CUS) != 2 || FIELD(&result.frame, SAMPLES) != 4 ||
        FIELD(&result.frame, ATTRIBUTES) != 16 || FIELD(&result.frame, BINDINGS) != 16 ||
        FIELD(&result.frame, VARYINGS) != 16 || FIELD(&result.frame, COLORS) != 4 ||
        FIELD(&result.frame, ENGINES) != profile[3]) {
        errno = EPROTO;
        return -1;
    }
    result.engines = profile[3];
    result.sample_inputs = !!(profile[1] & (1u << OPENGPU_TESS_JOINT_HOST_PROFILE_SAMPLES_BIT));
    if (result.sample_inputs && result.frame.words[0]!=0x474e4305u) {
        errno = EPROTO;
        return -1;
    }
    *output = result;
    return 0;
}
