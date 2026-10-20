/* Programmable fragment texture sample. Lane 0 stores zero at slice 8 and
 * stops. Every other lane stores dFdx(U) at slice 7 and the packed texel at
 * slice 6. Profile 3. Matches fragment_texture.S. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fragment_texture(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    unsigned char *colour = lane + 192;
    unsigned char *depth = lane + 224;
    unsigned char *discard = lane + 256;
    unsigned index;
    vuint32m1_t u, v, texel, dudx;

    asm volatile("addi %0, x8, 0" : "=r"(index));
    /* Materialise the three slice pointers before the branch. Clang
     * otherwise sinks the addis into each path, and the ABI lowerer drops
     * lane facts at a branch. */
    asm volatile("" : "+r"(colour), "+r"(depth), "+r"(discard));
    u = opengpu_load(lane, 128);
    v = opengpu_load(lane, 160);
    opengpu_sample_dfdx(u, v, &texel, &dudx);
    if (index == 0) {
        opengpu_store(discard, 0, __riscv_vmv_v_x_u32m1(0, 4));
        asm volatile("opengpu.cease");
        __builtin_unreachable();
    }
    opengpu_store(depth, 0, dudx);
    opengpu_store(colour, 0, texel);
}
