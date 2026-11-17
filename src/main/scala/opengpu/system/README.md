# System integration layout

`opengpu.system` is the SoC/host integration package. Sources stay in one
Scala package so CU ports, owners, and fabrics can share types without
cross-package imports. Directories below are layout only:

- `tessellation/`: patch owners, native job/sequence images, CU clients, and
  Isoline/Quad/Triangle system pipelines.
- `geometry/`: geometry→fragment bridges, isoline shared-CU paths, and
  controlled isoline window/bin pipelines.
- `native/`: native host profiles and memory fabrics used by compiled product
  paths.
- package root: `GpuSystem`, host AXI, shared-CU graphics ports, and other
  integration anchors.

Matching Specs remain under `src/test/scala/opengpu/system/` (flat) so test
selection keeps mapping to `opengpu.system.*`.
