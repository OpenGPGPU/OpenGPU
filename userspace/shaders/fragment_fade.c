/* Scale the interpolated colour at slice 3 by the low byte of the packed
 * opacity word at kernarg byte 288. Each channel is (colour * opacity) >> 8.
 * Profile 6. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_fade(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    unsigned opacity = *(unsigned *)((unsigned char *)kernarg + 288);
    vuint32m1_t factor = __riscv_vmv_v_x_u32m1(opacity, 4);

    factor = __riscv_vand_vx_u32m1(factor, 255, 4);
    opengpu_store(lane, 192, opengpu_scale_splat(opengpu_load(lane, 96), factor));
}
