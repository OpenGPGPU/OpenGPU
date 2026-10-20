/* Per-lane fragment uniform float. flw reads the first uniform at byte 288,
 * vfadd.vf broadcasts it onto a zeroed vector, and the bits are XORed into
 * the interpolated colour. Needs the scalar FPU. Matches fragment_fp_scalar.S. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_fp_scalar(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    float uniform = opengpu_uniform_f(kernarg, 288);
    vuint32m1_t colour = opengpu_load(lane, 96);
    vfloat32m1_t broadcast = __riscv_vfadd_vf_f32m1(
        __riscv_vreinterpret_v_u32m1_f32m1(
            __riscv_vxor_vv_u32m1(colour, colour, 4)),
        uniform, 4);

    opengpu_store(lane, 192, __riscv_vxor_vv_u32m1(
        colour, __riscv_vreinterpret_v_f32m1_u32m1(broadcast), 4));
}
