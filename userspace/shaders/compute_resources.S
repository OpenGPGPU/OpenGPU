/* Combined texture, storage and atomic compute example. x1 is the kernarg. */
    .option norvc
    .text
    .globl shader
shader:
    opengpu.tex.size.0 x5, x0, x0
    sw x5, 16(x1)
    addi x6, x0, 1
    opengpu.tex.size.0 x5, x6, x0
    sw x5, 20(x1)
    opengpu.tex.params.0 x6, x6
    opengpu.tex.params.1 x0, x0
    opengpu.tex.params.2 x0, x0
    opengpu.tex.params.3 x0, x0
    opengpu.tex.params.4 x0, x0
    opengpu.tex.params.5 x0, x0
    opengpu.tex.params.6 x0, x0
    opengpu.tex.params.7 x0, x0
    opengpu.tex.fetchi.0 x7, x6, x0
    sw x7, 24(x1)
    vsetivli zero, 4, e32, m1, tu, mu
    vadd.vi v2, v1, 0 /* local IDs select storage bindings 0–3 */
    vadd.vi v3, v1, 7 /* each lane writes a distinct value */
    vmv.v.i v4, 0
    /* Storage CUSTOM-1: capture v3, store v3 to binding v2 at v4,
     * then load the same word into v5. The .insn register fields name the
     * corresponding vector register numbers. LLVM storage mnemonics remain open. */
    .insn r 0x2b, 0, 29, x0, x3, x0
    .insn r 0x2b, 0, 33, x0, x2, x4
    .insn r 0x2b, 0, 31, x5, x2, x4
    vse32.v v5, (x1)
    addi x9, x1, 28
    addi x10, x0, 1
    amoadd.w x11, x10, (x9)
    sw x11, 32(x1)
    opengpu.cease
