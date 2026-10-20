/* Scalar-broadcast FP: flw one uniform, then vfadd/vfrsub/vfrdiv/vfmul/vfmacc
 * and a vmfgt-masked add. Lane 0 is stored. Matches fp_scalar.S. Profile 5. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fp_scalar(unsigned *kernarg)
{
    float scale = *(float *)kernarg;
    vfloat32m1_t value = opengpu_kload_f(kernarg, 16);
    vfloat32m1_t acc = opengpu_kload_f(kernarg, 32);
    vuint32m1_t src = opengpu_kload(kernarg, 48);
    vbool32_t gt = __riscv_vmfgt_vf_f32m1_b32(value, scale, 4);

    opengpu_kstore1_f(kernarg, 64, __riscv_vfadd_vf_f32m1(value, scale, 4));
    opengpu_kstore1_f(kernarg, 68, __riscv_vfrsub_vf_f32m1(value, scale, 4));
    opengpu_kstore1_f(kernarg, 72, __riscv_vfrdiv_vf_f32m1(value, scale, 4));
    opengpu_kstore1_f(kernarg, 76, __riscv_vfmul_vf_f32m1(value, scale, 4));
    opengpu_kstore1_f(kernarg, 80,
        __riscv_vfmacc_vf_f32m1(acc, scale, value, 4));
    opengpu_kstore1(kernarg, 84,
        __riscv_vadd_vx_u32m1_mu(gt, src, src, 1, 4));
}
