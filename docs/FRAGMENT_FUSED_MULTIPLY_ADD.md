# Fragment fused multiply-add lowering

The Gallium fragment emitter accepts bounded TGSI `FMA` and `MAD` operations
and emits `vfmacc.vv` with separate copies of all three operands. Destination
aliasing, scalar and vector sources, and negation are covered by an independent
host interpreter of the emitted words. A fused-rounding discriminator is included:
`(1 + 2^-23) * (1 - 2^-23) - 1` produces `-2^-46` when fused and zero when
rounded after multiplication. TGSI MAD permits a fused result.

The emitter refuses unavailable or spilled sources, packed texture values,
insufficient scratch registers, and an in-place cross-channel swizzle that
would read a channel after the same instruction overwrote it. Direct input
operands are limited to the declared position input at index zero. These
restrictions do not cover every otherwise valid TGSI program.

The pre-existing position-Y MAD workaround remains in place for a scalar
scale and bias. Its pattern does not establish that those constants belong to
Mesa's window-coordinate transform; a user MAD with the same shape can be
misidentified. Mesa can translate NIR `ffma` to MAD, so this path does not yet
establish general GLSL `fma` behavior or `precise` invariance. Fragment program
execution through the complete hardware pipeline, other shader stages, API
exposure, nonfinite values and physical qualification also remain open.

The host tests execute 1,024 finite operand/negation/alias combinations and
exercise actual Mesa TGSI parsing when Mesa and DRM headers are available. A
separate 32-case signed finite smoke test passed against the FP32 lane emitted
as part of the frozen product host at commit `7617dcf4882d4b2c06ce9ecdd3e2428dc03f4a5c`;
the lane and YunSuan sources match the current tree. This lane check does not
exercise the compiler or the full fragment pipeline.

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
```
