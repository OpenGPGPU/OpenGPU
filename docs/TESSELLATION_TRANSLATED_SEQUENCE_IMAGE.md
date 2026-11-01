# Translated sequenced tessellation images

`TessellationTranslatedSequenceImage` connects the sequenced image frame owner
to `TessellationInputMemoryFabric`. Renderer and patch-input requests share
one physical memory port after independent Sv32 translation. Frame completion
is held until both the image owner and memory fabric have drained. The caller
retains `satp`, mappings, shader resources and allocations until completion;
physical alias provenance remains the caller's responsibility.

The bounded admission test checks malformed frames before memory or shader
traffic. The local two-CU, 4×4 test uses Sv32 mappings for frame and input
traffic, including non-identity mappings for inputs and image output. It loads
two patches, runs control, evaluation and fragment shaders, and
checks shaded and clear RGBA8 pixels plus stored primitive IDs. Physical requests
are checked to ensure virtual input and image addresses do not escape translation. It exercises
input-memory faults, a held input request during cancellation, invalid draw
admission, and subsequent reuse. Native command records, driver submission,
other tessellation domains, product-sized workloads and physical timing remain
open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationTranslatedSequenceImageAdmissionSpec opengpu.graphics.TessellationTranslatedSequenceImageSharedSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
