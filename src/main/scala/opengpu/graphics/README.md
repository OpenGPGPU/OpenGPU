# Graphics RTL

This package is the rendering pipeline: vertex/fragment shader staging,
geometry, rasterization, texture, tile storage and pixel backends. Sources
stay in package `opengpu.graphics` so stages can share types freely.
Directories below are layout only (except `tessellation/`, a real subpackage):

- `texture/`: ASTC and other compressed decode, RGBA/sRGB services, typed color
  encode/decode, texture image address/binding units.
- `raster/`: clip/viewport/raster/interpolate/depth/blend, tile/bin memory,
  fragment setup, window primitive replay.
- `vertex/`: vertex input fetch/slice/conversion and vertex pipelines.
- `shader/`: kernel stages, shader image/uniform access, payload stores,
  shared shader task router.
- `host/`: native descriptors/capabilities, command/debug ports, transform
  feedback, draw/capture helpers.
- `tessellation/`: package `opengpu.graphics.tessellation` (patch stores,
  generators, CU patch adapters).
- package root: render/native/tile-bin entry pipelines and shared config
  (`RenderPipeline`, `MemoryNative*`, `GpuCapabilities`, …).

Matching Specs remain under `src/test/scala/opengpu/graphics/` (flat), except
tessellation Specs under `src/test/scala/opengpu/graphics/tessellation/`.
Emission entry points live in `src/main/scala/opengpu/elaboration/`, host
integration in `opengpu.system`, and the capability index is
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
instantiations from the entry point above rather than assuming the newest
file wins. Entry-point files carry a `Path:` header stating which external
path instantiates them. Leaf `*Unit` blocks are usually qualification-scoped
owners, not yet wired into a production pipeline; their docs say so.

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

Place new sources in the matching layout directory above; keep
`package opengpu.graphics` unless they belong in `tessellation/`.

## Adding a bounded owner

Follow the recent small-block pattern: `ColorDitherUnit` and
`FragmentInterpolationLocationUnit` are the templates.

1. Module: `idle`/`respond` FSM, `busy` output, result held until
   `out.ready`, tag echoed in the result.
2. Spec under `src/test/scala/opengpu/graphics/` covering success and the
   fault/status codes the unit exposes.
3. Capability-matrix row when the block is a product-visible owner.
