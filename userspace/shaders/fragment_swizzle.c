/* Texture sample, then exchange the packed channels the way
 * fragment_swizzle.S does. Profile 3. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_swizzle(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);

    opengpu_store(lane, 192, opengpu_swizzle_rb(opengpu_sample(lane)));
}
