/* FP32 divide smoke. Lane 0 is stored. Matches fp_div.S. Profile 4. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fp_div(unsigned *kernarg)
{
    vfloat32m1_t quot = __riscv_vfdiv_vv_f32m1(
        opengpu_kload_f(kernarg, 0), opengpu_kload_f(kernarg, 16), 4);

    opengpu_kstore1_f(kernarg, 32, quot);
}
