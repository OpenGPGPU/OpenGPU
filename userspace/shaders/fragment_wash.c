/* ((texel * colour) >> 8) * uniform_byte >> 8. The uniform at kernarg byte
 * 288 is packed RGBA, one factor per channel. Profile 8. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_wash(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    unsigned packed = *(unsigned *)((unsigned char *)kernarg + 288);
    vuint32m1_t shaded = opengpu_modulate(opengpu_sample(lane), opengpu_load(lane, 96));

    opengpu_store(lane, 192, opengpu_modulate(shaded, __riscv_vmv_v_x_u32m1(packed, 4)));
}
