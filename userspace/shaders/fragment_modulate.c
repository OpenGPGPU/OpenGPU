/* Texture sample modulated by the interpolated colour at slice 3.
 * Each channel is (texel * colour) >> 8. Profile 3. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_modulate(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);

    opengpu_store(lane, 192,
                  opengpu_modulate(opengpu_sample(lane), opengpu_load(lane, 96)));
}
