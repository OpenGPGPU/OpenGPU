# Compiled native product qualification

This explicit local runner executes the same full-profile native host, six input recipes and independent stored-image oracle as `QualifyNativeProductRecipes`. The C++ driver services external AXI transactions inside the Verilator process. Rasterization, shaders, texture sampling, depth/stencil, blending, stores and resolves execute in the generated RTL.

The hardware profile retains four CUs, sixteen lanes, eight resident warps, four native fragment workers, the original storage capacities, characterized SRAM models and a 256-bit AXI port. Smoke mode renders 32×32 scenes; it does not reduce execution or storage capacities. Product mode renders 1280×720 and explicitly sets `opengpu.productQualification=1` when invoking Scala. Both modes run all six scenes together. Neither the RTL build nor these simulations belongs in regular PR/nightly CI.

## Reproduce

Initialize the pinned SRAM submodule and install the repository's Scala/C++/Verilator toolchain. Use an absolute output directory under `.artifacts/`:

```sh
python3 scripts/qualify_native_product_compiled.py --smoke \
  --output "$PWD/.artifacts/native-compiled-smoke" --jobs 2
```

Run a deliberate product qualification separately:

```sh
python3 scripts/qualify_native_product_compiled.py --product \
  --output "$PWD/.artifacts/native-compiled-product" --jobs 2
```

`--stage prepare|build|run|verify` operates one stage. Preserve source and output bytes while a stage is live. If a stage fails, inspect its actual exit and log before retrying. An observation timeout does not terminate a build or simulation. The build cache is bound to emitted RTL, hardware profile, driver inputs, compiler/tool versions, flags and executable hash. Preparation, execution and verification reject stale source/model/fixture/output bytes. Raw outputs remain local under `.artifacts/`.

The optional `--reference PATH` binds smoke qualification to a retained Scala six-scene report. It requires exact agreement in service/GPU cycles, external bus bytes, enabled write bytes and all six fragment phase counters.

## Qualification gates and limits

Execution alone does not create a passing qualification. Every scene must complete without unmapped traffic and drain all AXI ownership. Scala then reloads the full output memory and runs the original independent oracle on every stored color, depth/stencil, resolve and guard region. Only successful verification of all six named scenes creates `qualification.json`.

CPU-only AXI protocol and stale-artifact guard tests run through `bash scripts/test_scripts.sh`. They do not replace the complete native image gate. Simulation wall time is separate from GPU cycles. Frame-rate qualification additionally requires a current complete-host clock and matching memory assumptions. This runner by itself closes no feature/resource/coherence, whole-host timing or H0–H8 gate.

## Verified development result

The complete image result below used the hardware baseline at `cc6377b24db956acb18df8917039d6b1f89920d2` (PR #108), before the shared-memory SRAM change in PR #109. The runner was subsequently rebased onto PR #109 with its memory/codec and host-script checks passing. That rebase does not qualify the changed complete host; prepare, build, run and verify a fresh model to qualify a newer hardware revision. The source guards reject reuse of the earlier model against changed hardware sources.

All six 32×32 scenes passed with the original Scala stored-image oracle: desktop composition, textured 3D, opaque overdraw, transparent layers, small triangles and 4× MSAA. Service/GPU cycles, read/write bus bytes, enabled write bytes and all six fragment phase counters match the retained Scala reference exactly for every scene. The complete build, execution and independent verification each completed with exit zero.

The 13 memory/codec checks and seven CPU runner guard checks pass. The latter execute eleven C++ AXI protocol cases, including narrow upper-beat words, held responses, W before AW, concurrent reads/writes, masked traffic and fault replies. The full host-script entry point also passes.

Full-profile simulation remains expensive: the six development scenes used approximately 49 minutes of simulator loop time on this host with concurrent local validation. Build time is separate. These measurements establish correctness of the compiled stimulus path; no simulator speedup, current whole-host clock, 720p frame-rate or H0–H8 completion is established here.
