/* Vertex-core copy of eight 32-byte slices into the fragment varyings,
 * compiled with clang --target=opengpu. Input slices 0..7, output slices
 * 8..15. The lane base is x1 + (x8 << 2) and each copy moves four words,
 * matching vertex_passthrough.S. Gallium still binds the assembly words. */
#include <stdint.h>
#include <riscv_vector.h>

__attribute__((device_kernel))
void vertex_passthrough(unsigned *kernarg)
{
    uintptr_t bytes;
    int slice;

    asm("slli %0, x8, 2" : "=r"(bytes));
    unsigned char *lane = (unsigned char *)((uintptr_t)kernarg + bytes);
    for (slice = 0; slice < 8; slice++) {
        vuint32m1_t value = __riscv_vle32_v_u32m1(
            (const unsigned *)(lane + slice * 32), 4);
        __riscv_vse32_v_u32m1(
            (unsigned *)(lane + 256 + slice * 32), value, 4);
    }
}
