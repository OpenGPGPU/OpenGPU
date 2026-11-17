# GPU package layout

- `config`: elaboration-time architectural configuration.
- `core/frontend/decode`: instruction classification, decode contracts, and decode pipelines.
- `core/frontend/warp`: hardware-warp allocation, runnable state, and round-robin scheduling.
- `core/backend/register`: scalar register storage, bypass, and atomic issue/writeback boundary.
- `core/backend/scoreboard`: per-warp dependency reservation and release.
- `core/execute`: execution-unit control types and datapaths.
- `core/vector`: lane vector ALUs and configuration.
- `core/memory`: caches, TLBs, shared memory, and CU memory services.
- `graphics`: raster, texture, fragment/vertex stages, and tessellation leaves
  (`graphics/tessellation`).
- `system`: SoC/host integration; layout subdirs under `system/` keep package
  `opengpu.system` (see `system/README.md`).
- `command` / `dispatch` / `dma`: host command routing, kernel launch, and DMA.
- `elaboration`: RTL generation entry points; not instantiated as hardware.
- `util`: shared helpers.

Vendored FP32 FMA sources live at `src/main/scala/yunsuan/` and are consumed by
`core/execute/fpu`.

Dependencies should point inward from integration to leaf blocks:

`system/graphics -> core -> config`

ISA encodings may be added under `opengpu/isa`; leaf decoders may depend on those
definitions, but ISA packages must not depend on pipeline or execution modules.
