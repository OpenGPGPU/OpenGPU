/* Lane-local vsext.vf2, vzext.vf2, vnclip.wi and vsmul.vx. vnclip reads the
 * v2/v3 pair. vxrm is set before any vector value exists. VL is 1. Matches
 * fixed_width.S. Profile 0. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void fixed_width(unsigned *kernarg)
{
    asm volatile(
        "csrwi vxrm, 0\n\t"
        "vsetivli x0, 1, e32, m1, tu, mu\n\t"
        "vle32.v v2, (%0)\n\t"
        "addi t0, %0, 4\n\t"
        "vle32.v v3, (t0)\n\t"
        "vsext.vf2 v4, v2\n\t"
        "vzext.vf2 v5, v2\n\t"
        "vnclip.wi v6, v2, 1\n\t"
        "lw t1, 20(%0)\n\t"
        "vsmul.vx v7, v2, t1\n\t"
        "addi t0, %0, 8\n\t"
        "vse32.v v4, (t0)\n\t"
        "addi t0, %0, 12\n\t"
        "vse32.v v5, (t0)\n\t"
        "addi t0, %0, 16\n\t"
        "vse32.v v6, (t0)\n\t"
        "addi t0, %0, 24\n\t"
        "vse32.v v7, (t0)"
        :
        : "r"(kernarg)
        : "t0", "t1", "v2", "v3", "v4", "v5", "v6", "v7", "memory");
}
