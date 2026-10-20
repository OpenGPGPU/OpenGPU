/* Modelview at byte 640, then the projection at byte 512. The modelview
 * product is already scaled by 65536, so the projection does not scale
 * again. Its z column and z row are omitted, and the z output is left
 * unwritten. Profile 7. */
#include "opengpu_device.h"

static vint32m1_t project_row(unsigned *kernarg, unsigned row,
                              vint32m1_t x, vint32m1_t y, vint32m1_t w,
                              int bias, vfloat32m1_t bias_f,
                              float zero, float positive)
{
    unsigned base = 512 + row * 4;
    vint32m1_t sum = opengpu_biased_term(
        x, opengpu_uniform_f(kernarg, base), bias, bias_f, zero, positive);

    sum = __riscv_vadd_vv_i32m1(sum, opengpu_biased_term(
        y, opengpu_uniform_f(kernarg, base + 16), bias, bias_f, zero, positive), 4);
    return __riscv_vadd_vv_i32m1(sum, opengpu_biased_term(
        w, opengpu_uniform_f(kernarg, base + 48), bias, bias_f, zero, positive), 4);
}

__attribute__((device_kernel))
void vertex_mvp2(unsigned *kernarg)
{
    unsigned char *lane = opengpu_lane(kernarg);
    float scale = opengpu_uniform_f(kernarg, 576);
    float zero = opengpu_uniform_f(kernarg, 580);
    int bias = 8388608;
    vfloat32m1_t x = opengpu_load_f(lane, 0);
    vfloat32m1_t y = opengpu_load_f(lane, 32);
    vfloat32m1_t z = opengpu_load_f(lane, 64);
    vfloat32m1_t w = opengpu_load_f(lane, 96);
    vfloat32m1_t bias_f;
    vint32m1_t mx, my, mw;

    opengpu_store(lane, 384, opengpu_load(lane, 128));
    opengpu_store(lane, 448, opengpu_load(lane, 192));
    opengpu_store(lane, 480, opengpu_load(lane, 224));
    mx = opengpu_row_product(kernarg, 640, 0, x, y, z, w, zero, scale);
    my = opengpu_row_product(kernarg, 640, 1, x, y, z, w, zero, scale);
    mw = opengpu_row_product(kernarg, 640, 3, x, y, z, w, zero, scale);
    bias_f = __riscv_vfcvt_f_x_v_f32m1(__riscv_vmv_v_x_i32m1(bias, 4), 4);
    opengpu_store_i(lane, 256, project_row(kernarg, 0, mx, my, mw, bias, bias_f,
                                           zero, scale));
    opengpu_store_i(lane, 288, project_row(kernarg, 1, mx, my, mw, bias, bias_f,
                                           zero, scale));
    opengpu_store_i(lane, 352, project_row(kernarg, 3, mx, my, mw, bias, bias_f,
                                           zero, scale));
}
