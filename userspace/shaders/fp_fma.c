/* Eight unmasked OPFVV fused multiply-adds. Each form reads a fresh copy of
 * the 1.0 addend so the previous result is not reused. Lane 0 is stored.
 * Matches fp_fma.S. Profile 4. */
#include "opengpu_device.h"

static vfloat32m1_t addend(unsigned *kernarg)
{
    return opengpu_kload_f(kernarg, 32);
}

__attribute__((device_kernel))
void fp_fma(unsigned *kernarg)
{
    vfloat32m1_t three = opengpu_kload_f(kernarg, 0);
    vfloat32m1_t two = opengpu_kload_f(kernarg, 16);

    opengpu_kstore1_f(kernarg, 48,
        __riscv_vfmadd_vv_f32m1(addend(kernarg), two, three, 4));
    opengpu_kstore1_f(kernarg, 52,
        __riscv_vfnmadd_vv_f32m1(addend(kernarg), two, three, 4));
    opengpu_kstore1_f(kernarg, 56,
        __riscv_vfmsub_vv_f32m1(addend(kernarg), two, three, 4));
    opengpu_kstore1_f(kernarg, 60,
        __riscv_vfnmsub_vv_f32m1(addend(kernarg), two, three, 4));
    opengpu_kstore1_f(kernarg, 64,
        __riscv_vfmacc_vv_f32m1(addend(kernarg), two, three, 4));
    opengpu_kstore1_f(kernarg, 68,
        __riscv_vfnmacc_vv_f32m1(addend(kernarg), two, three, 4));
    opengpu_kstore1_f(kernarg, 72,
        __riscv_vfmsac_vv_f32m1(addend(kernarg), two, three, 4));
    opengpu_kstore1_f(kernarg, 76,
        __riscv_vfnmsac_vv_f32m1(addend(kernarg), two, three, 4));
}
