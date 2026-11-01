# Native sequenced tessellation image integration

`TessellationNativeSequenceImage` connects the draft GNT1 draw-record reader to
an Sv32-translated sequenced image frame. The accepted frame supplies the
identity checked against each record; the submitter supplies the command tag and
record view. Record reads, image traffic and patch inputs share one physical
memory port. A held draw survives cancellation, and frame completion waits for
both the reader and memory fabric to drain.

This is a trusted internal interface. Its caller must pin mappings and resources
and verify physical aliasing until completion. It does not implement the native
job ABI, driver submission, other tessellation domains, or product-size and
physical-timing qualification.

Validation uses a bounded admission check and a local 4×4, two-CU shared
simulation covering translated descriptor and input reads, shaded output,
backpressure, cancellation, descriptor and input faults, and reuse:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeSequenceImageAdmissionSpec opengpu.graphics.TessellationNativeSequenceImageSharedSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
