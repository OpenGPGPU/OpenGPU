# Native tessellation job-to-image owner

`TessellationNativeJobImage` connects the GNQ1 job/GNF1 frame reader, GNT1
draw snapshots, sequenced tessellation image owner and translated descriptor
fabrics. A single physical port carries Sv32-translated job, draw, input and
image traffic. Its completion waits for the job, child frame and fabric to
drain; cancellation is delayed across held child offers.

This is an internal job interface. The caller must retain `satp`, mappings,
code, state and all allocations, and establish physical alias provenance until
completion. The GNQ1 job format is a draft; host MMIO/AXI integration, public
capability discovery, driver/compiler support, other tessellation domains,
full-size workloads and physical timing qualification remain open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeJobImageAdmissionSpec opengpu.graphics.TessellationNativeJobImageSharedSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
