/* Broadcast the packed RGBA word at kernarg byte 288 to slice 6.
 * Profile 6: the load sits past the 288-byte fragment window. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_uniform_color(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    unsigned color = *(unsigned *)((unsigned char *)kernarg + 288);

    opengpu_store(lane, 192, __riscv_vmv_v_x_u32m1(color, 4));
}
