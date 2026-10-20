/* FP32 unary smoke: vfsqrt, vfrsqrt7, vfrec7, vfclass. Lane 0 is stored.
 * Matches fp_unary.S. Profile 4. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fp_unary(unsigned *kernarg)
{
    vfloat32m1_t value = opengpu_kload_f(kernarg, 0);
    vfloat32m1_t root = __riscv_vfsqrt_v_f32m1(value, 4);
    vfloat32m1_t rsqrt = __riscv_vfrsqrt7_v_f32m1(value, 4);
    vuint32m1_t cls = __riscv_vfclass_v_u32m1(value, 4);
    vfloat32m1_t rec = __riscv_vfrec7_v_f32m1(opengpu_kload_f(kernarg, 16), 4);

    opengpu_kstore1_f(kernarg, 32, root);
    opengpu_kstore1_f(kernarg, 36, rsqrt);
    opengpu_kstore1_f(kernarg, 40, rec);
    opengpu_kstore1(kernarg, 44, cls);
}
