# GPU-memory tessellation patch inputs

`TessellationPatchInputDma` loads raw 32-bit per-vertex input components from a
strided GPU-memory view into an already opened patch context. It checks the
vertex/component limits, widened final address, logical extent, four-byte
alignment and complete cache-line allocation bounds before issuing traffic.
Zero stride repeats an element; zero components complete without a memory
request. A line is retained for subsequent words in that line.

The enclosing owner supplies the patch layout, pins translation and the
published allocation, reserves memory transaction ID zero, and routes only
input-load replies to the DMA until completion. It starts the control shader
only after successful loading. Offered requests and patch writes remain held
through cancellation; accepted memory and patch responses must drain before a
completion can be consumed. Earlier patch writes are not rolled back after a
fault or cancellation. Completion distinguishes invalid descriptors, memory
faults, patch faults and cancellation, and reports the acknowledged word count.

`TessellationSystemIsolineInput` owns the loader and the shared-system shader
task. It opens the patch, drains loading, and seals the patch only after a
successful load; input failures cancel and drain the source before task
completion. The enclosing owner must drain provisional output and retain the
input allocation and translation throughout the task.

The bounded tests cover unaligned and out-of-range descriptors, the last word
at the 4 GiB boundary, strided input spanning two cache lines, stalled offers,
fault and cancellation drain, and reuse. A patch-context test reads the loaded
components through the control-access interface. A real shared-CU test runs a
control shader that loads the GPU-supplied value and drives isoline evaluation,
then checks invalid-descriptor, memory-fault and held-request cancellation
recovery. Native submission and compiler-generated descriptors remain open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationPatchInputDmaSpec opengpu.system.TessellationPatchInputDmaContextSpec opengpu.system.TessellationSystemIsolineInputSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

`TessellationSystemIsolineInputImage` combines this loader with the image
frame owner. Each task loads its input before control-shader execution; the
frame owner reports input faults and drains the source and accepted input
traffic before completion. The enclosing system must reserve input memory ID
zero on the separate input port and retain the translation and allocation until
frame completion. Provisional output must be drained on a failed or cancelled
frame. The bounded image tests cover descriptor admission, real two-CU shader
execution and image output, input faults, cancellation and subsequent reuse.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSystemIsolineInputImageAdmissionSpec opengpu.graphics.TessellationSystemIsolineInputImageSharedSpec'
```
