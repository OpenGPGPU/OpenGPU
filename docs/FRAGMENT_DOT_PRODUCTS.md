# Fragment dot products

The Gallium fragment ALU fallback accepts TGSI `DP3` alongside `DP2` and
`DP4`. All three compute one scalar result and copy it to the original write
mask. A private temporary preserves every source component until the reduction
finishes, including when the destination aliases either input. This also fixes
the previous multi-channel destination index and reduction scratch-register
errors in `DP2`/`DP4`.

An ordinary GLSL `dot(vec3, vec3)` can reach this path: Mesa's state tracker
calls `nir_to_tgsi`, which maps `fdot3` to `TGSI_OPCODE_DP3`; OpenGPU's
`create_fs_state`/`lower_shader` falls back to `opengpu_fs_emit` when the
fragment menu does not match. Binding still uses the existing runtime ABI
lowerer and strict shader validator. No instruction, hardware capacity or
advertised API capability changes.

The reduction uses separately rounded FP32 multiplication and left-to-right
addition, starting with the first product rather than an artificial positive
zero. It emits existing `vfmul.vv`/`vfadd.vv`, not FMA. The CPU oracle checks
signed-zero bits and NaN classification; it does not promise NaN payloads.
TGSI specifies the component sum without requiring fused evaluation. A dot
program requesting `LEGACY_MATH_RULES` is rejected because those rules permit
`0 * Inf = 0`, unlike this IEEE path. Mesa's default translation does not set
that property for this driver, which does not advertise the legacy capability.

Numeric constants, immediates, resident temporaries, the existing single spilled
temporary and supported FragCoord channels retain their existing limits.
Packed RGBA8 texture results are not four FP32 values and are rejected as dot
operands; the existing packed texture-to-output move remains supported.
Absolute/indirect operands, unsupported inputs, malformed tokens and programs
exceeding the register or 256-word budget fail lowering. Three free vector work
registers are required, or four when the existing spill must be reloaded.

## CPU validation

Run the existing entry point:

```sh
python3 scripts/test_driver.py
```

The mandatory, Mesa-independent target executes final instruction words for
673 arithmetic/spill cases and three budget rejections. It covers `DP2/3/4`,
scalar/vector operands, negation, swizzles, signed zero, infinities, NaNs and a
rounding counterexample that distinguishes separate operations from FMA. It
also forces the real allocator's spill/store/reload and checks its memory
guards. This target covers the emitter core, not TGSI integration.

When Mesa and DRM headers are available, the same entry point also runs the
real TGSI builder/parser, public fragment-menu fallback, runtime ABI lowerer
and validator. Use `OPENGPU_MESA_DIR` and `OPENGPU_DRM_INCLUDE` for nondefault
dependency paths. Its 159 programs cover every nonempty write mask, both
destination aliases, negation, swizzles and saturation. An independent RV32/RVV
CPU decoder executes 1,908 warps across the historical ABI and 4x2/16x8
resource profiles, checking packed output and every other byte of the bounded
kernarg memory. Twelve rejection cases include malformed operands, legacy
math, packed texture operands and register/instruction budgets.

The entry point honors `CC`, including compiler options. For sanitizer
reproduction with the same available dependencies:

```sh
CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_driver.py
```

Local validation passed with Clang `-O2`, Clang ASan/UBSan and Linux GCC
`-O2`, with `-ffp-contract=off`. The full driver CPU entry point passed. The
old public emitter fails the new output oracle and rejects `DP3`, providing
negative controls. These are compiler CPU checks; they do not constitute a
GPU rendering run, full GLSL/API conformance or completion of H6.
