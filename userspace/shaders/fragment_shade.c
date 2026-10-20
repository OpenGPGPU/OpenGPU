/* ((texel * colour) >> 8) * opacity >> 8. Opacity is the low byte of the
 * packed word at kernarg byte 288. Profile 8. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_shade(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    unsigned opacity = *(unsigned *)((unsigned char *)kernarg + 288);
    vuint32m1_t factor = __riscv_vmv_v_x_u32m1(opacity, 4);
    vuint32m1_t shaded = opengpu_modulate(opengpu_sample(lane), opengpu_load(lane, 96));

    factor = __riscv_vand_vx_u32m1(factor, 255, 4);
    opengpu_store(lane, 192, opengpu_scale_splat(shaded, factor));
}
