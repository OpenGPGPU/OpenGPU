/* Texture sample scaled by floor(byte * 255 / 256). Used when every staged
 * vertex colour is white. Profile 3. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_modulate_white(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);

    opengpu_store(lane, 192, opengpu_modulate_white(opengpu_sample(lane)));
}
