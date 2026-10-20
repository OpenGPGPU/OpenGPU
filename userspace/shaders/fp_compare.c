/* vmfeq and vmflt each gate a masked add of 1. Inactive lanes keep the
 * source. Lane 0 is stored. Matches fp_compare.S. Profile 4. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fp_compare(unsigned *kernarg)
{
    vfloat32m1_t left = opengpu_kload_f(kernarg, 0);
    vfloat32m1_t right = opengpu_kload_f(kernarg, 16);
    vfloat32m1_t other = opengpu_kload_f(kernarg, 32);
    vuint32m1_t src = opengpu_kload(kernarg, 48);
    vbool32_t eq = __riscv_vmfeq_vv_f32m1_b32(left, right, 4);
    vbool32_t lt = __riscv_vmflt_vv_f32m1_b32(left, other, 4);

    opengpu_kstore1(kernarg, 64,
        __riscv_vadd_vx_u32m1_mu(eq, src, src, 1, 4));
    opengpu_kstore1(kernarg, 68,
        __riscv_vadd_vx_u32m1_mu(lt, src, src, 1, 4));
}
