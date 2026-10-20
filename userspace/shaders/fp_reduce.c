/* vfredusum.vs and vfredosum.vs. The destinations start as the integer sum
 * of each element with itself, and a reduction rewrites only element 0.
 * The whole vector is stored. Matches fp_reduce.S. Profile 4. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fp_reduce(unsigned *kernarg)
{
    vuint32m1_t data = opengpu_kload(kernarg, 0);
    vfloat32m1_t values = __riscv_vreinterpret_v_u32m1_f32m1(data);
    vfloat32m1_t seed = __riscv_vreinterpret_v_u32m1_f32m1(
        opengpu_kload(kernarg, 16));
    vfloat32m1_t unordered = __riscv_vreinterpret_v_u32m1_f32m1(
        __riscv_vadd_vv_u32m1(data, data, 4));
    vfloat32m1_t ordered = __riscv_vreinterpret_v_u32m1_f32m1(
        __riscv_vadd_vv_u32m1(data, data, 4));

    /* One asm block keeps both destinations tied to the doubled vectors.
     * Separate reduction intrinsics copy one of them with vmv1r.v when the
     * seed load changes VL, and this device does not implement that move. */
    asm("vfredusum.vs %0, %2, %3\n\t"
        "vfredosum.vs %1, %2, %3"
        : "+vr"(unordered), "+vr"(ordered)
        : "vr"(values), "vr"(seed));
    opengpu_kstore4_f(kernarg, 32, unordered);
    opengpu_kstore4_f(kernarg, 48, ordered);
}
