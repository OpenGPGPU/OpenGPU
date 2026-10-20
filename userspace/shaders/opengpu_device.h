/* Helpers for clang --target=opengpu device kernels.
 * The lane base is the form the shader validator admits: x1 + (x8 << 2).
 * x8 is reserved, so the shift is inline asm rather than a C local.
 * opengpu.vtex.sample is likewise inline asm: the MC instruction exists, and
 * the C wrappers around it are what the Gallium fragment programs share.
 * Helpers are always_inline so -O0 does not outline them: the device ABI
 * rejects calls. */
#ifndef OPENGPU_DEVICE_H
#define OPENGPU_DEVICE_H

#include <stdint.h>
#include <riscv_vector.h>

/* x8 is reserved, so the lane byte offset is inline asm. The add with the
 * kernarg pointer stays in C: clang keeps it as x1+(x8<<2), which the
 * shader validator admits. An asm block around a later vector use makes
 * LLVM emit vmv1r.v, and this device does not implement that move. */
static inline __attribute__((always_inline)) unsigned char *opengpu_lane(unsigned *kernarg)
{
    uintptr_t bytes;

    asm("slli %0, x8, 2" : "=r"(bytes));
    return (unsigned char *)((uintptr_t)kernarg + bytes);
}

static inline __attribute__((always_inline)) vuint32m1_t opengpu_vtex_sample(vuint32m1_t u, vuint32m1_t v)
{
    vuint32m1_t texel;

    asm("opengpu.vtex.sample %0, %1, %2" : "=vr"(texel) : "vr"(u), "vr"(v));
    return texel;
}

/* Sample and the quad derivative of U in one asm block. A later asm while
 * either result is live makes LLVM copy it with vmv1r.v, which this device
 * does not run. */
static inline __attribute__((always_inline)) void opengpu_sample_dfdx(vuint32m1_t u, vuint32m1_t v,
                                        vuint32m1_t *texel, vuint32m1_t *dudx)
{
    vuint32m1_t sample, derivative;

    asm("opengpu.vtex.sample %0, %2, %3\n\t"
        "opengpu.vquad.dfdx %1, %2"
        : "=&vr"(sample), "=&vr"(derivative)
        : "vr"(u), "vr"(v));
    *texel = sample;
    *dudx = derivative;
}

static inline __attribute__((always_inline)) vuint32m1_t opengpu_load(unsigned char *lane, unsigned offset)
{
    return __riscv_vle32_v_u32m1((const unsigned *)(lane + offset), 4);
}

static inline __attribute__((always_inline)) vfloat32m1_t opengpu_load_f(unsigned char *lane, unsigned offset)
{
    return __riscv_vle32_v_f32m1((const float *)(lane + offset), 4);
}

static inline __attribute__((always_inline)) void opengpu_store(unsigned char *lane, unsigned offset,
                                  vuint32m1_t value)
{
    __riscv_vse32_v_u32m1((unsigned *)(lane + offset), value, 4);
}

static inline __attribute__((always_inline)) void opengpu_store_i(unsigned char *lane, unsigned offset,
                                    vint32m1_t value)
{
    __riscv_vse32_v_i32m1((int *)(lane + offset), value, 4);
}

static inline __attribute__((always_inline)) vuint32m1_t opengpu_byte(vuint32m1_t packed, unsigned shift)
{
    if (shift)
        packed = __riscv_vsrl_vx_u32m1(packed, shift, 4);
    return __riscv_vand_vx_u32m1(packed, 255, 4);
}

/* (a_byte * b_byte) >> 8, placed back at the same byte position. */
static inline __attribute__((always_inline)) vuint32m1_t opengpu_channel_product(vuint32m1_t a, vuint32m1_t b,
                                                  unsigned shift)
{
    vuint32m1_t product = __riscv_vmul_vv_u32m1(
        opengpu_byte(a, shift), opengpu_byte(b, shift), 4);

    product = __riscv_vsrl_vx_u32m1(product, 8, 4);
    if (shift)
        product = __riscv_vsll_vx_u32m1(product, shift, 4);
    return product;
}

static inline __attribute__((always_inline)) vuint32m1_t opengpu_modulate(vuint32m1_t texel, vuint32m1_t color)
{
    vuint32m1_t out = opengpu_channel_product(texel, color, 24);

    out = __riscv_vor_vv_u32m1(out, opengpu_channel_product(texel, color, 16), 4);
    out = __riscv_vor_vv_u32m1(out, opengpu_channel_product(texel, color, 8), 4);
    return __riscv_vor_vv_u32m1(out, opengpu_channel_product(texel, color, 0), 4);
}

/* Every channel is scaled by the same low-byte factor. */
static inline __attribute__((always_inline)) vuint32m1_t opengpu_scale_splat(vuint32m1_t packed,
                                              vuint32m1_t factor)
{
    vuint32m1_t out = __riscv_vsll_vx_u32m1(
        __riscv_vsrl_vx_u32m1(
            __riscv_vmul_vv_u32m1(opengpu_byte(packed, 24), factor, 4), 8, 4),
        24, 4);
    unsigned shift;

    for (shift = 16; shift != 0; shift -= 8) {
        vuint32m1_t channel = __riscv_vsrl_vx_u32m1(
            __riscv_vmul_vv_u32m1(opengpu_byte(packed, shift), factor, 4), 8, 4);

        out = __riscv_vor_vv_u32m1(
            out, __riscv_vsll_vx_u32m1(channel, shift, 4), 4);
    }
    return __riscv_vor_vv_u32m1(
        out, __riscv_vsrl_vx_u32m1(
                 __riscv_vmul_vv_u32m1(opengpu_byte(packed, 0), factor, 4),
                 8, 4), 4);
}

/* floor(byte * 255 / 256): byte-1 when the byte is nonzero, else zero.
 * The addend is 0x7f per byte, so the sum never carries into the next byte. */
static inline __attribute__((always_inline)) vuint32m1_t opengpu_modulate_white(vuint32m1_t texel)
{
    vuint32m1_t low = __riscv_vand_vx_u32m1(texel, 0x7f7f7f7fu, 4);
    vuint32m1_t sum = __riscv_vadd_vx_u32m1(low, 0x7f7f7f7fu, 4);
    vuint32m1_t high = __riscv_vor_vv_u32m1(sum, texel, 4);

    high = __riscv_vand_vx_u32m1(high, 0x80808080u, 4);
    high = __riscv_vsrl_vx_u32m1(high, 7, 4);
    return __riscv_vsub_vv_u32m1(texel, high, 4);
}

/* Retain the low byte and move bytes 1, 2, 3 to positions 24, 16, 8. */
static inline __attribute__((always_inline)) vuint32m1_t opengpu_swizzle_rb(vuint32m1_t texel)
{
    vuint32m1_t out = opengpu_byte(texel, 0);
    vuint32m1_t byte1 = __riscv_vsll_vx_u32m1(opengpu_byte(texel, 8), 24, 4);
    vuint32m1_t byte2 = __riscv_vsll_vx_u32m1(opengpu_byte(texel, 16), 16, 4);
    vuint32m1_t byte3 = __riscv_vsll_vx_u32m1(
        __riscv_vsrl_vx_u32m1(texel, 24, 4), 8, 4);

    out = __riscv_vor_vv_u32m1(out, byte1, 4);
    out = __riscv_vor_vv_u32m1(out, byte2, 4);
    return __riscv_vor_vv_u32m1(out, byte3, 4);
}

/* One mat4 column term. vfmul by zero clears the accumulator, then vfmacc
 * adds the product. A fused multiply-add of two nonzero values is wrong on
 * this device, and so is vfmul of a negative coefficient. The scale is the
 * positive 65536.0 at byte 576; byte 580 is 0.0. */
static inline __attribute__((always_inline)) vint32m1_t opengpu_q16_term(vfloat32m1_t component, float coeff,
                                          float zero, float scale)
{
    vfloat32m1_t acc = __riscv_vfmul_vf_f32m1(component, zero, 4);

    acc = __riscv_vfmacc_vf_f32m1(acc, coeff, component, 4);
    acc = __riscv_vfmul_vf_f32m1(acc, scale, 4);
    return __riscv_vfcvt_x_f_v_i32m1(acc, 4);
}

static inline __attribute__((always_inline)) float opengpu_uniform_f(unsigned *kernarg, unsigned offset)
{
    return *(float *)((unsigned char *)kernarg + offset);
}

static inline __attribute__((always_inline)) vint32m1_t opengpu_row_product(unsigned *kernarg, unsigned base,
                                             unsigned row, vfloat32m1_t x,
                                             vfloat32m1_t y, vfloat32m1_t z,
                                             vfloat32m1_t w, float zero,
                                             float scale)
{
    base += row * 4;
    vint32m1_t sum = opengpu_q16_term(
        x, opengpu_uniform_f(kernarg, base), zero, scale);

    sum = __riscv_vadd_vv_i32m1(sum, opengpu_q16_term(
        y, opengpu_uniform_f(kernarg, base + 16), zero, scale), 4);
    sum = __riscv_vadd_vv_i32m1(sum, opengpu_q16_term(
        z, opengpu_uniform_f(kernarg, base + 32), zero, scale), 4);
    return __riscv_vadd_vv_i32m1(sum, opengpu_q16_term(
        w, opengpu_uniform_f(kernarg, base + 48), zero, scale), 4);
}

/* Project one already-scaled Q16.16 component. A negative integer cannot
 * convert back to a float, so the value is biased by +2^23 and that bias is
 * removed after the multiply. A negative coefficient is multiplied as its
 * absolute value, then the integer product is negated. */
static inline __attribute__((always_inline)) vint32m1_t opengpu_biased_term(vint32m1_t component, float coeff,
                                             int bias, vfloat32m1_t bias_f,
                                             float zero, float positive)
{
    vfloat32m1_t value = __riscv_vfcvt_f_x_v_f32m1(
        __riscv_vadd_vx_i32m1(component, bias, 4), 4);
    vfloat32m1_t factor = __riscv_vfmv_v_f_f32m1(coeff, 4);
    vbool32_t negative = __riscv_vmflt_vf_f32m1_b32(factor, zero, 4);
    vint32m1_t product, bias_term, delta, flipped;

    factor = __riscv_vfsgnj_vf_f32m1(factor, positive, 4);
    product = __riscv_vfcvt_x_f_v_i32m1(
        __riscv_vfmul_vv_f32m1(value, factor, 4), 4);
    bias_term = __riscv_vfcvt_x_f_v_i32m1(
        __riscv_vfmul_vv_f32m1(bias_f, factor, 4), 4);
    delta = __riscv_vsub_vv_i32m1(product, bias_term, 4);
    flipped = __riscv_vrsub_vx_i32m1(delta, 0, 4);
    return __riscv_vmerge_vvm_i32m1(delta, flipped, negative, 4);
}

static inline __attribute__((always_inline)) vuint32m1_t opengpu_sample(unsigned char *lane)
{
    return opengpu_vtex_sample(opengpu_load(lane, 128), opengpu_load(lane, 160));
}

/* Compute kernels address the kernarg base itself. A unit-stride load from
 * x1, or from x1 plus a constant, is the form the compute profile admits.
 * A lane-0 store uses VL=1 so the write is one word. */
static inline __attribute__((always_inline)) vuint32m1_t opengpu_kload(unsigned *kernarg, unsigned byte)
{
    return __riscv_vle32_v_u32m1(
        (const unsigned *)((unsigned char *)kernarg + byte), 4);
}

static inline __attribute__((always_inline)) vfloat32m1_t opengpu_kload_f(unsigned *kernarg, unsigned byte)
{
    return __riscv_vle32_v_f32m1(
        (const float *)((unsigned char *)kernarg + byte), 4);
}

static inline __attribute__((always_inline)) void opengpu_kstore1(unsigned *kernarg, unsigned byte,
                                    vuint32m1_t value)
{
    __riscv_vse32_v_u32m1(
        (unsigned *)((unsigned char *)kernarg + byte), value, 1);
}

static inline __attribute__((always_inline)) void opengpu_kstore1_f(unsigned *kernarg, unsigned byte,
                                      vfloat32m1_t value)
{
    __riscv_vse32_v_f32m1(
        (float *)((unsigned char *)kernarg + byte), value, 1);
}

static inline __attribute__((always_inline)) void opengpu_kstore4_f(unsigned *kernarg, unsigned byte,
                                      vfloat32m1_t value)
{
    __riscv_vse32_v_f32m1(
        (float *)((unsigned char *)kernarg + byte), value, 4);
}

/* OpenGPU vwadd/vwsub/vwmul are funct6 0x30/0x31/0x32. Clang's mnemonics
 * encode the standard RVV forms, which this core decodes as different ops.
 * .insn funct7 is (funct6 << 1) | 1 so the mask bit stays set. The mnemonic
 * order is vd, vs2, vs1, so rs1 of the R-type is vs1 and rs2 is vs2. */
static inline __attribute__((always_inline)) vuint32m1_t opengpu_vwadd(vuint32m1_t vs2, vuint32m1_t vs1)
{
    vuint32m1_t vd;

    asm(".insn r 0x57, 0, 0x61, %0, %2, %1" : "=&vr"(vd) : "vr"(vs2), "vr"(vs1));
    return vd;
}

static inline __attribute__((always_inline)) vuint32m1_t opengpu_vwsub(vuint32m1_t vs2, vuint32m1_t vs1)
{
    vuint32m1_t vd;

    asm(".insn r 0x57, 0, 0x63, %0, %2, %1" : "=&vr"(vd) : "vr"(vs2), "vr"(vs1));
    return vd;
}

static inline __attribute__((always_inline)) vuint32m1_t opengpu_vwmul(vuint32m1_t vs2, vuint32m1_t vs1)
{
    vuint32m1_t vd;

    asm(".insn r 0x57, 0, 0x65, %0, %2, %1" : "=&vr"(vd) : "vr"(vs2), "vr"(vs1));
    return vd;
}

#endif
