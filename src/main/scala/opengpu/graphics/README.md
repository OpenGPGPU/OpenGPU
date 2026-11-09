# Graphics RTL

This package is the rendering pipeline: vertex/fragment shader staging,
geometry, rasterization, texture, tile storage and pixel backends. It is
deliberately one flat package with 342 Scala files (about 57k lines); the
file-name prefixes below are the de-facto subpackages until a split lands.
Tests mirror it file-for-file under `src/test/scala/opengpu/graphics/`
(597 files). Emission entry points live in
`src/main/scala/opengpu/elaboration/`, host integration in
`src/main/scala/opengpu/system/GpuHostSystemAxi.scala`, and the
capability index is
[docs/MOBILE_GPU_CAPABILITY_MATRIX.md](../../../../../docs/MOBILE_GPU_CAPABILITY_MATRIX.md).

## Pipeline map

```text
RISC-V SIMT front-end (core/)
  scalar ALU/FPU + RVV + warp control
              │
              ▼
        unified shader core ─────── shared memory / L2 / Sv32
              │
              ▼
       command router and host AXI
          ┌──────────┴──────────┐
          ▼                     ▼
   compute + DMA jobs      render host
                                │
                                ▼
  transform → clip → rasterize → interpolate → depth/blend → scanout
```

## External paths (mutually exclusive)

`GpuHostSystemAxi` selects at most one external render path by parameter
(see `GpuHostSystemAxi.scala`: `binnedRendering`, `typedGraphics`,
`nativeBinnedGraphics`):

| Path | Entry point | Used for |
|---|---|---|
| Legacy immediate | `RenderPipeline` / `RenderHost` + `KernelFragStage` / `KernelVertStage` | Desktop/Mesa proof path in the main README |
| Binned | `MemoryTileBinRenderPipeline` | Mobile tile-binning experiments |
| Native typed | `MemoryNativeTypedGraphicsPipeline` | Typed attachment native submission |
| Native binned | `MemoryNativeBinnedGraphicsPipeline` | Four-engine product-qualification profile (`docs/NATIVE_FOUR_ENGINE_HOST.md`) |
| Tessellation (on native binned) | `TessellationNativeThreeDomainJobImage` | GLES 3.2 geometry/tessellation host path |

## Multiple generations of the same function

Many functions exist in several generations (for example
`FragmentInterpolator`, `Fp32FragmentInterpolator`,
`Fp32StreamingFragmentInterpolator` and `Fp32PreparedQuadInterpolator`).
This is intentional: each generation is qualified separately in the
capability matrix. To tell which one a pipeline actually uses, follow the
instantiations from the entry point above (for example,
`Fp32PrimitiveRasterStage` instantiates `Fp32StreamingFragmentInterpolator`)
rather than assuming the newest file wins. Leaf `*Unit` blocks are
usually qualification-scoped owners, not yet wired into a production
pipeline; their docs say so.

## Naming for new files

- `*Unit` — bounded leaf owner with a Decoupled request/result handshake
  (the current default for new blocks, e.g. `ColorDitherUnit`,
  `FragmentInterpolationLocationUnit`).
- `*Stage` — one pipeline stage with an internal FSM.
- `*Pipeline` — composition of several modules/stages.
- `*Service` / `*Owner` — memory-backed or ownership-taking blocks.
- Request/result bundles are named `*Request` / `*Result` in the same
  file; status codes get a companion `*Status` object when the block can
  fail.

## Adding a bounded owner

Follow the recent small-block pattern: `ColorDitherUnit` and
`FragmentInterpolationLocationUnit` are the templates.

1. Module: `idle`/`respond` FSM, `busy` output, result held until
   `out.ready`, tag echoed in the result.
2. Directed spec in `src/test/scala/opengpu/graphics/<Name>Spec.scala`
   using `WindowRecordTest.zero` and `GpuSim.simulate`; print one
   `info("MARKER ...")` line with the covered properties.
3. One doc in `docs/<FEATURE>.md` with the contract table and the exact
   `sbt 'testOnly ...'` reproduction command.
4. Update the capability-matrix row (and `docs/GLES32_HARDWARE_GAPS.md`
   for 3.2 rows) without promoting the row past `Partial` unless the
   native/API hookup is also qualified.

## Prefix groups (approximate, by file name)

| Prefix | Files | Area |
|---|---|---|
| Tessellation | 40 | GLES 3.2 patch/control/evaluation |
| Texture | 35 | Samplers, coordinates, image addressing |
| Native | 32 | Native descriptors, host submission, fabrics |
| Tile | 26 | Tile storage, binning, replay |
| Typed | 22 | Typed color/depth formats and stores |
| Shader | 20 | Payload store, task routing, shared-CU clients |
| Fragment / Vertex | 15 / 13 | Fragment/vertex stages, interpolation |
| Astc | 10 | ASTC LDR decode owners |
| Primitive / Fp32 / Banked | 8 each | Assembly, FP32 geometry, banked SRAM services |
| Geometry / Window / Render / Kernel / Debug | 4-6 each | Geometry stages, window bins, render host, shader kernels, debug log |
