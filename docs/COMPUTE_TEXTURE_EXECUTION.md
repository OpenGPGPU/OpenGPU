# Compute texture execution

An optional compute texture owner uses the native texture resource records with
stage ID 2. Direct and indirect unified compute commands capture the resource
root, identity and translation context for each task. The grid dispatcher
retains the publication through child execution and a held parent completion.
Scalar and vector native texture requests use the compute unit's texture
service; legacy graphics texture instructions remain separately gated. The
service shares the existing 16-binding native resource layout and reserves CU
memory IDs 13–14 for data and 15 for page walks. It can coexist with the image,
storage and compute-uniform services when enabled.

The bounded checks exercise stage-2 binding and native sample/fetch/size
service behavior, actual scalar/vector fetches and size queries in a CU with Bare and Sv32
translation, and direct and indirect dispatch across two busy CUs with all 16
bindings. They also exercise denied texture translation and retained trap and
completion ownership. Existing graphics and compute-uniform checks cover their
respective compatibility paths.

```sh
sbt 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ComputeTextureCuServiceSpec opengpu.graphics.ComputeTextureActualCuSpec opengpu.graphics.ComputeTextureSystemSpec opengpu.core.GpuComputeUnitUniformSpec'
sbt 'testOnly opengpu.graphics.NativeShaderTextureUnitSpec -- -z compute-stage' \
  'testOnly opengpu.graphics.TextureSampleExecuteUnitSpec -- -z compute-stage'
```

This is a command/system execution contract, not a complete compute API. Native
host MMIO publication, driver admission, broader texture operation and format
coverage in actual kernels, cross-domain coherence, full mobile capacity and
physical qualification remain open. It does not close H7 or establish 720p
performance.
