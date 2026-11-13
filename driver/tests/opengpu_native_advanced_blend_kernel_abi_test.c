/* Kernel-facing GFS admission must not include libc headers or types. */
#include "../opengpu_native_advanced_blend_gfs.h"

int opengpu_native_advanced_blend_kernel_check(const unsigned char *frame,
    const unsigned char *state, int transport)
{
    return opengpu_native_advanced_blend_gfs_precheck(frame, 2048u, state, 512u, transport);
}
