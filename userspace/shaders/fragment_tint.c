/* Per-lane fragment tint: XOR the interpolated colour at slice 3 with
 * 0x00ff00ff and store the result at slice 6. Profile 1. Matches
 * fragment_tint.S. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_tint(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    vuint32m1_t colour = opengpu_load(lane, 96);

    opengpu_store(lane, 192, __riscv_vxor_vx_u32m1(colour, 0x00ff00ffu, 4));
}
