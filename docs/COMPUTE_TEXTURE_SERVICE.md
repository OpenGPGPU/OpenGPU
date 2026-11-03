# Compute texture resource service

The compute texture service snapshots a task's native texture resource records and translation context. Stage ID 2 uses the existing 32-binding layout; compute-stage native sample, fetch and size operations share the established descriptor and translated memory readers. Requests retain the captured task context through completion and report translation faults.

Local checks cover sixteen stage-2 bindings, rejection of graphics-stage records, native sample/fetch/size behavior, and the standalone service under Bare, permitted Sv32 and denied Sv32 translation. Reproduce with:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ComputeTextureCuServiceSpec' \
  'testOnly opengpu.graphics.NativeShaderTextureUnitSpec -- -z compute-stage' \
  'testOnly opengpu.graphics.TextureSampleExecuteUnitSpec -- -z compute-stage'
```

The [CU execution path](COMPUTE_TEXTURE_EXECUTION.md) and internal [host command transport](COMPUTE_TEXTURE_HOST_TRANSPORT.md) are connected and have separate bounded checks. [Linux submission](COMPUTE_TEXTURE_SUBMISSION.md) now admits retained compute texture views, with a bounded translated GPU smoke. General texture formats, cross-domain coherence, API/compiler integration, full capacity and physical qualification remain open. H7 remains open.
