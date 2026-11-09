# Texture gather ISA and driver admission

The 2x2 sampler gather owner is exposed through scalar and vector texture
instructions:

| Form | Encoding |
|---|---|
| `tex.gather.c` | custom-0, funct7=28, bit14=0, funct3[1:0]=returned tap |
| `vtex.gather.c` | custom-1, funct6=28, bit14=0, funct3[1:0]=returned tap |

The returned tap index follows GLSL ES order: 0=`i0j1`, 1=`i1j1`,
2=`i1j0`, 3=`i0j0`. One instruction returns one 32-bit tap; a `vec4`
lowering issues the four tap indices.

The captured control word selects the operation:

| Bits | Meaning |
|---|---|
| 1:0 | Zero: gather is implicit-LOD |
| 2 | Shadow comparison |
| 4:3 | 0=`textureGather`, 1=`textureGatherOffset`, 2=`textureGatherOffsets` |
| 6:5 | Texture component (0..3) |
| 31:7 | Must be zero |

Parameter words carry coordinates 0..2, layer, reference, LOD/bias and the
existing captured tuples. For mode 1, word 13 nibbles 0/1 are the shared
`ivec2` offset applied by tap 0. For mode 2, the four tap offsets occupy word
13 nibbles 0..3 (taps 0/1) and word 14 nibbles 0..3 (taps 2/3), each as signed
4-bit `x,y`. Mode 0 requires zero offset words.

Cube and cube-array coordinates use the existing seamless face projection. GLES
3.2 has no 3D gather form; the sampler rejects 3D gather requests. The driver validator admits function 28 only with
texture enabled, an unmasked instruction, defined operands and all eight
parameter pairs captured. GTO1 advertises functions 24 and 28 through mask
`0x1100007c`.

Directed checks cover all three modes through the native execute unit
(`TEXTURE_GATHER_ISA`) and the complete opcode/parameter decode space
(`NATIVE_TEXTURE_OPERATION_DECODE`). The sampler-level cube, cube-array,
shadow and offset behavior is covered by `TextureImageLevelSampleUnitSpec`.
