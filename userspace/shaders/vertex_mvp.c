/* Column-major mat4 * vec4. Position slices 0..3 are IEEE floats. The
 * matrix is 16 floats at byte 512 and byte 576 holds 65536.0. Colour and
 * the Q16.16 UVs are copied. Profile 7. */
#include "opengpu_device.h"

static vint32m1_t row_product(unsigned *kernarg, unsigned row,
                              vfloat32m1_t x, vfloat32m1_t y,
                              vfloat32m1_t z, vfloat32m1_t w,
                              float zero, float scale)
{
    unsigned base = 512 + row * 4;
    vint32m1_t sum = opengpu_q16_term(
        x, opengpu_uniform_f(kernarg, base), zero, scale);

    sum = __riscv_vadd_vv_i32m1(sum, opengpu_q16_term(
        y, opengpu_uniform_f(kernarg, base + 16), zero, scale), 4);
    sum = __riscv_vadd_vv_i32m1(sum, opengpu_q16_term(
        z, opengpu_uniform_f(kernarg, base + 32), zero, scale), 4);
    return __riscv_vadd_vv_i32m1(sum, opengpu_q16_term(
        w, opengpu_uniform_f(kernarg, base + 48), zero, scale), 4);
}

__attribute__((device_kernel))
void vertex_mvp(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    float scale = opengpu_uniform_f(kernarg, 576);
    float zero = opengpu_uniform_f(kernarg, 580);
    vfloat32m1_t x = opengpu_load_f(lane, 0);
    vfloat32m1_t y = opengpu_load_f(lane, 32);
    vfloat32m1_t z = opengpu_load_f(lane, 64);
    vfloat32m1_t w = opengpu_load_f(lane, 96);

    opengpu_store(lane, 384, opengpu_load(lane, 128));
    opengpu_store(lane, 448, opengpu_load(lane, 192));
    opengpu_store(lane, 480, opengpu_load(lane, 224));
    opengpu_store_i(lane, 256, row_product(kernarg, 0, x, y, z, w, zero, scale));
    opengpu_store_i(lane, 288, row_product(kernarg, 1, x, y, z, w, zero, scale));
    opengpu_store_i(lane, 320, row_product(kernarg, 2, x, y, z, w, zero, scale));
    opengpu_store_i(lane, 352, row_product(kernarg, 3, x, y, z, w, zero, scale));
}
