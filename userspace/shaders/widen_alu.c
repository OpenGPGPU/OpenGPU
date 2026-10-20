/* Lane-local vwadd/vwsub/vwmul. The encodings are OpenGPU funct6 0x30..0x32,
 * not the standard RVV mnemonics. VL is 1. Matches widen_alu.S. Profile 0. */
#include "opengpu_device.h"

__attribute__((device_kernel))
void widen_alu(unsigned *kernarg)
{
    asm volatile(
        "vsetivli x0, 1, e32, m1, tu, mu\n\t"
        "vle32.v v2, (%0)\n\t"
        "addi t0, %0, 4\n\t"
        "vle32.v v3, (t0)\n\t"
        ".insn r 0x57, 0, 0x61, v4, v3, v2\n\t"
        ".insn r 0x57, 0, 0x63, v5, v3, v2\n\t"
        ".insn r 0x57, 0, 0x65, v6, v3, v2\n\t"
        "addi t0, %0, 8\n\t"
        "vse32.v v4, (t0)\n\t"
        "addi t0, %0, 12\n\t"
        "vse32.v v5, (t0)\n\t"
        "addi t0, %0, 16\n\t"
        "vse32.v v6, (t0)"
        :
        : "r"(kernarg)
        : "t0", "v2", "v3", "v4", "v5", "v6", "memory");
}
