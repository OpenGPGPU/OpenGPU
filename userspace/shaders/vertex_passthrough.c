/* Copy eight 32-byte slices into the fragment varyings. Input slices 0..7,
 * output slices 8..15. Profile 2. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void vertex_passthrough(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    int slice;

    for (slice = 0; slice < 8; slice++)
        opengpu_store(lane, 256 + slice * 32, opengpu_load(lane, slice * 32));
}
