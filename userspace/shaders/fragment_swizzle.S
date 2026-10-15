/* Texture sample with red/blue channel swap for GtkGLArea composition.
 * The driver chooses this program when the scalar swap uniform is 1.0. */
    .option norvc
    .text
    .globl shader
shader:
    slli x5, x8, 2
    add x5, x1, x5
    .word 0xc1027057 /* vsetivli x0,4,e32,m1,ta,ma */
    addi x6, x5, 128
    vle32.v v1, (x6)
    addi x6, x5, 160
    vle32.v v2, (x6)
    .word 0x0620812b /* vtex.sample v2, v1, v2 */
    addi x7, x0, 255
    /* Canonical word is RRGGBBAA: retain A, then put B/G/R at 24/16/8. */
    vand.vx v4, v2, x7
    vsrl.vi v5, v2, 8
    vand.vx v5, v5, x7
    vsll.vi v5, v5, 24
    vor.vv v4, v4, v5
    vsrl.vi v5, v2, 16
    vand.vx v5, v5, x7
    vsll.vi v5, v5, 16
    vor.vv v4, v4, v5
    vsrl.vi v5, v2, 24
    vsll.vi v5, v5, 8
    vor.vv v4, v4, v5
    addi x6, x5, 192
    vse32.v v4, (x6)
    .insn i 0x73, 0, zero, zero, 0x305
