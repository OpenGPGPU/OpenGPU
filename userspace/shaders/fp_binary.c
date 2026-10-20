/* FP32 binary smoke: vfadd, vfsub, vfmul, vfmin, vfmax, vfsgnj.
 * Lane 0 is stored. Matches fp_binary.S. Profile 4. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fp_binary(unsigned *kernarg)
{
    vfloat32m1_t left = opengpu_kload_f(kernarg, 0);
    vfloat32m1_t right = opengpu_kload_f(kernarg, 16);
    vfloat32m1_t sign = opengpu_kload_f(kernarg, 48);

    opengpu_kstore1_f(kernarg, 32, __riscv_vfadd_vv_f32m1(left, right, 4));
    opengpu_kstore1_f(kernarg, 36, __riscv_vfsub_vv_f32m1(left, right, 4));
    opengpu_kstore1_f(kernarg, 40, __riscv_vfmul_vv_f32m1(left, right, 4));
    opengpu_kstore1_f(kernarg, 44, __riscv_vfmin_vv_f32m1(left, right, 4));
    opengpu_kstore1_f(kernarg, 52, __riscv_vfmax_vv_f32m1(left, right, 4));
    opengpu_kstore1_f(kernarg, 56, __riscv_vfsgnj_vv_f32m1(left, sign, 4));
}
