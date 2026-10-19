/* Per-lane fragment colour copy, compiled with clang --target=opengpu.
 * The lane base is x1 + (x8 << 2). Slice 3 (byte 96) is the interpolated
 * colour and slice 6 (byte 192) is the colour output. vl is 4 words, the
 * same width as fragment_color.S. The fragment validator profile uses batch
 * capacity 8, so the proven access covers the whole slice.
 * Gallium still binds the assembly words in opengpu_shaders.h. */
#include <stdint.h>
#include <riscv_vector.h>

__attribute__((device_kernel))
void fragment_color(unsigned *kernarg)
{
    uintptr_t bytes;

    asm("slli %0, x8, 2" : "=r"(bytes));
    unsigned char *lane = (unsigned char *)((uintptr_t)kernarg + bytes);
    vuint32m1_t color = __riscv_vle32_v_u32m1(
        (const unsigned *)(lane + 96), 4);
    __riscv_vse32_v_u32m1((unsigned *)(lane + 192), color, 4);
}
