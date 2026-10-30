# Compute texture resource service

The compute texture service snapshots a task's native texture resource records and translation context. Stage ID 2 uses the existing sixteen-binding layout; compute-stage native sample, fetch and size operations share the established descriptor and translated memory readers. Requests retain the captured task context through completion and report translation faults.

Local checks cover all sixteen stage-2 bindings, rejection of graphics-stage records, native sample/fetch/size behavior, and the standalone service under Bare, permitted Sv32 and denied Sv32 translation. The rebased changes to these service and test files are unchanged from the validated versions. Reproduce with:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ComputeTextureCuServiceSpec' \
  'testOnly opengpu.graphics.NativeShaderTextureUnitSpec -- -z compute-stage' \
  'testOnly opengpu.graphics.TextureSampleExecuteUnitSpec -- -z compute-stage'
```

This service is not yet connected to compute-unit task execution or native host commands in this change. General texture formats, cross-domain coherence, API/compiler integration, full capacity and physical qualification remain open. H7 remains open.
