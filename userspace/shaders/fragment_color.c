/* Per-lane fragment colour copy. Slice 3 (byte 96) is the interpolated
 * colour and slice 6 (byte 192) is the colour output. Profile 1. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_color(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);

    opengpu_store(lane, 192, opengpu_load(lane, 96));
}
