/* SPDX-License-Identifier: MIT */
#include "opengpu_native_runtime.h"
#include "opengpu.h"
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
