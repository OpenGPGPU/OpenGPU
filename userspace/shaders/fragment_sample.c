/* Sample the bound texture at the Q16.16 UVs in slices 4 and 5 and store
 * the packed texel at slice 6. Profile 3. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_sample(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);

    opengpu_store(lane, 192, opengpu_sample(lane));
}
