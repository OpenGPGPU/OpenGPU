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

The optional `--hierarchy-profile fp32` is an experimental compiler profile. It adds separately compiled FP32 clip, raster and fragment-preparation wrappers to the existing CU/blend boundaries, including specialized emitted variants. The default `baseline` keeps the original two boundaries. The selected profile and module list enter the build identity; run and verify reject a different requested profile. General-format `FloatFMA` is kept inside its FP32 wrappers. Nested boundaries are supported by [Verilator hierarchical compilation](https://verilator.org/guide/latest/verilating.html#hierarchical-verilation), but can also add scheduling overhead, so a speedup must be measured.

Use distinct output directories for a comparison and run all four stages with the same profile. Both profiles must use identical RTL, fixtures, toolchain and driver, pass all six image oracles, and agree exactly on GPU and traffic counters. Report build and simulation wall times separately. CPU guard checks alone establish neither model correctness nor a performance improvement; the matched six-scene result below qualifies the optional profile on its stated hardware revision.

## Matched hierarchy result, 2026-10-28

Both profiles pass all six independent 32×32 stored-image/guard oracles and drain
all external traffic. GPU/service cycles, bus/enabled bytes and fragment phase
counters match exactly. The experiment uses identical prepared RTL, fixtures,
driver, tools and flags outside the selected hierarchy profile, using one
build job, four CUs, sixteen lanes, eight warps and full execution/storage capacities.

| Measurement | Baseline (seconds) | FP32 profile (seconds) |
|---|---:|---:|
| Complete model build | 3,588.07 | 1,901.99 |
| Six-scene simulation loop | 2,447.42 | 1,450.53 |

Preparation is shared and measured separately. These are one sequential pair
on a contended host: build time was 47.0% lower and simulation-loop time 40.7%
lower in this experiment. They are not an idle-host or general speedup guarantee.
The default remains `baseline`; the optional profile has eight passing CPU
guard/protocol tests and a passing full host-script entry point.

The qualified source is `15e2b8f580c0` with hardware base `645a46a506b5`.
Rebasing/publishing the compiler option does not qualify subsequent RTL changes;
prepare, build, run and verify fresh models for that hardware. This comparison
establishes no current whole-host clock, 720p FPS or H0–H8 completion. Raw
logs, complete source/build inventories and qualification outputs stay local
under `.artifacts/`.

Reproduce a matched single-job pair on a fixed source revision:

```sh
python3 scripts/qualify_native_product_compiled.py --smoke --jobs 1 \
  --output "$PWD/.artifacts/native-matched-baseline"
python3 scripts/qualify_native_product_compiled.py --smoke --jobs 1 \
  --hierarchy-profile fp32 --output "$PWD/.artifacts/native-matched-fp32"
```

## Qualification gates and limits

Execution alone does not create a passing qualification. Every scene must complete without unmapped traffic and drain all AXI ownership. Scala then reloads the full output memory and runs the original independent oracle on every stored color, depth/stencil, resolve and guard region. Only successful verification of all six named scenes creates `qualification.json`.

CPU-only AXI protocol and stale-artifact guard tests run through `bash scripts/test_scripts.sh`. They do not replace the complete native image gate. Simulation wall time is separate from GPU cycles. Frame-rate qualification additionally requires a current complete-host clock and matching memory assumptions. This runner by itself closes no feature/resource/coherence, whole-host timing or H0–H8 gate.

## Verified development result

The complete image result below used the hardware baseline at `cc6377b24db956acb18df8917039d6b1f89920d2` (PR #108), before the shared-memory SRAM change in PR #109. The runner was subsequently rebased onto PR #109 with its memory/codec and host-script checks passing. That rebase does not qualify the changed complete host; prepare, build, run and verify a fresh model to qualify a newer hardware revision. The source guards reject reuse of the earlier model against changed hardware sources.

All six 32×32 scenes passed with the original Scala stored-image oracle: desktop composition, textured 3D, opaque overdraw, transparent layers, small triangles and 4× MSAA. Service/GPU cycles, read/write bus bytes, enabled write bytes and all six fragment phase counters match the retained Scala reference exactly for every scene. The complete build, execution and independent verification each completed with exit zero.

The 13 memory/codec checks and seven CPU runner guard checks pass. The latter execute eleven C++ AXI protocol cases, including narrow upper-beat words, held responses, W before AW, concurrent reads/writes, masked traffic and fault replies. The full host-script entry point also passes.

That earlier six-scene result used approximately 49 minutes of simulator loop time on this host with concurrent local validation. Build time is separate. It established correctness of the compiled stimulus path; it did not qualify compiler-profile improvements, a whole-host clock, 720p frame rate or H0–H8 completion.
