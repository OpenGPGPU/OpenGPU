# Fragment fused multiply-add lowering

The Gallium fragment emitter accepts bounded TGSI `FMA` and `MAD` operations
and emits `vfmacc.vv` with separate copies of all three operands. Destination
aliasing, scalar and vector sources, and negation are covered by an independent
host interpreter of the emitted words. A fused-rounding discriminator is included:
`(1 + 2^-23) * (1 - 2^-23) - 1` produces `-2^-46` when fused and zero when
rounded after multiplication. TGSI MAD permits a fused result.

For straight-line in-place cross-channel swizzles, the emitter copies affected
original channels to a private temporary before writing the destination. It
continues to reject that case inside conditional regions, where allocator joins
do not yet preserve the required values. It also refuses unavailable or spilled
sources, packed texture values and insufficient scratch registers. Direct input
operands are limited to the declared position input at index zero. These
restrictions do not cover every otherwise valid TGSI program.

Position-Y MAD uses the same fused path. The former integer Y-transform
shortcut assumed that any scalar scale was +1 or -1 and that its bias was an
integer; that pattern can also describe an ordinary shader expression. The
emitter now respects the actual scale and bias, including the identity and
flipped window-coordinate transforms. Mesa can translate NIR `ffma` to MAD,
but this limited lowering does not establish general GLSL `fma` behavior or
`precise` invariance. Execution through the complete fragment pipeline, other
shader stages, API exposure, nonfinite values and physical qualification remain
open.

The host tests execute 1,024 finite operand/negation/alias combinations and
exercise actual Mesa TGSI parsing when Mesa and DRM headers are available. A
separate 32-case signed finite smoke test passed against the FP32 lane emitted
as part of the frozen product host at commit `7617dcf4882d4b2c06ce9ecdd3e2428dc03f4a5c`;
the lane and YunSuan sources match the current tree. This lane check does not
exercise the compiler or the full fragment pipeline.

A separate local four-active-lane test feeds real Mesa TGSI `MAD` and `FMA`
programs through the emitter and runtime ABI lowerer, then executes their
instructions on the 16-lane/eight-warp product CU model. Both programs pass an
ordinary `y / 8 + 1 / 8` expression, an identity Y transform and a `4 - y`
flip, including inactive-lane guards. On the same model, the previous MAD
shortcut returned red 0 instead of 32 in the first case. This is a CU test,
not a raster-to-image or API conformance test. The model was built from
`7617dcf4882d4b2c06ce9ecdd3e2428dc03f4a5c`; its emitted CU RTL matches the
later main-source emission used for this comparison.

The same CU test runs the fused-rounding discriminator through the TGSI
emitter and runtime ABI lowerer. It multiplies the resulting `-2^-46` by
`-2^45`, so fused MAD and FMA produce red 128. An explicitly separate MUL/ADD
program produces red 0. Both comparisons are also run with the expectations
swapped and must fail, demonstrating that the harness detects the difference.
This covers one finite cancellation case, not general `precise` invariance.

The local CU test also checks in-place `xy = FMA(yx, scale, bias)` and MAD
with the swizzle in each of the three operand positions, plus two consecutive
swizzles and a four-channel case requiring three snapshots. A separately encoded
pair of scalar instructions has a deliberately different result; a swapped
expectation must fail. The conditional version was
observed to miscompile on the CU and is now rejected for both branch sides and
both supported predicate forms. This is four-active-lane CU coverage, not a
full fragment-pipeline or API test.

```sh
python3 scripts/test_driver.py

# Optional: include the Mesa TGSI parser tests on Linux.
CC='cc -DHAVE_ENDIAN_H -D_POSIX_C_SOURCE=200809L' OPENGPU_MESA_DIR=/path/to/mesa \
  OPENGPU_DRM_INCLUDE=/path/to/drm/include python3 scripts/test_driver.py

# Optional: regenerate a lane, then run the small RTL smoke test.
sbt 'runMain opengpu.elaboration.EmitPpaRtl fma-lane .artifacts/fma-lane-rtl'
verilator --cc --exe --build -j 1 -Wno-fatal -CFLAGS -std=c++17 \
  --top-module Fp32FmaLane --Mdir "$PWD/.artifacts/fma-lane-build" \
  -y "$PWD/.artifacts/fma-lane-rtl" +libext+.sv \
  "$PWD/.artifacts/fma-lane-rtl/Fp32FmaLane.sv" "$PWD/userspace/tests/fs_fma_lane.cpp"
.artifacts/fma-lane-build/VFp32FmaLane

# Optional CU execution: use a completed 16-lane/eight-warp native product
# runner build with the GpuComputeUnit hierarchy library, plus Mesa and DRM.
python3 scripts/test_fragment_mad_cu.py --model-dir .artifacts/native-compiled-smoke \
  --mesa-dir depends/mesa --drm-include depends/aarch64-root/include \
  --output .artifacts/fragment-mad-cu
```
