# Shader-controlled isoline image path

`TessellationSystemIsolineImagePipeline` connects the shared-system control
and evaluation shaders, clipped window-bin storage and replay to the typed
fragment and image engines. The tessellation-aware shader router retains the
control task's translated ownership while fragment workers share the same CU
pool. The pass retains shader, patch, bin, tile, payload and image ownership
until all accepted work and publication responses drain. Its physical-address
interfaces and shader instruction encodings are internal, not a native
submission or compiler ABI.

The local 4×4 qualification uses two four-lane/two-warp CUs and two image
engines. Two control shaders write tessellation levels and a patch value; real
evaluation and fragment shaders carry that value to an RGBA8 attachment. The
test checks shaded and clear image bytes, completion, memory drain and payload
reuse. It does not establish native submission, all tessellation domains,
multi-CU contention under sustained load, physical timing or product
qualification. The full shared-system qualification remains local; CI selects
the bounded admission, shared-router, system-bin and typed-image tests listed in
`scripts/changed_test_suites.py`.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSystemIsolineImageSharedSystemSpec'
# With the same sbt options: testOnly opengpu.graphics.TessellationSystemIsolineImageAdmissionSpec
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
