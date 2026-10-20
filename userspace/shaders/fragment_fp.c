/* Per-lane fragment FP: build 1.5f and 2.0f, then vfmul, vfdiv and vfmacc.
 * 1.5 * 2.0 = 3.0, 3.0 / 2.0 = 1.5, and 1.5 * 2.0 + 3.0 = 6.0. The 6.0 bits
 * are XORed into the interpolated colour. Constants are integer materialised
 * so this profile does not use the scalar FPU. Matches fragment_fp.S. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_fp(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    vuint32m1_t colour = opengpu_load(lane, 96);
    vuint32m1_t zero = __riscv_vxor_vv_u32m1(colour, colour, 4);
    vfloat32m1_t one_half = __riscv_vreinterpret_v_u32m1_f32m1(
        __riscv_vadd_vx_u32m1(zero, 0x3fc00000u, 4));
    vfloat32m1_t two = __riscv_vreinterpret_v_u32m1_f32m1(
        __riscv_vadd_vx_u32m1(
            __riscv_vxor_vv_u32m1(colour, colour, 4), 0x40000000u, 4));
    vfloat32m1_t product = __riscv_vfmul_vv_f32m1(one_half, two, 4);
    vfloat32m1_t quot = __riscv_vfdiv_vv_f32m1(product, two, 4);
    /* 3.0 + 1.5 * 2.0. The vfmacc intrinsic is rewritten into a vfmadd that
     * adds the 3.0 onto the 1.5 instead, so the mnemonic stays in asm. */
    vfloat32m1_t six = product;

    asm("vfmacc.vv %0, %1, %2" : "+vr"(six) : "vr"(quot), "vr"(two));

    opengpu_store(lane, 192, __riscv_vxor_vv_u32m1(
        colour, __riscv_vreinterpret_v_f32m1_u32m1(six), 4));
}
