# Sequenced tessellation image frames

`TessellationSequenceImage` connects an input-backed patch sequence to the
shared-CU tessellation image frame owner. A draw template supplies the patch
count, GPU input view and patch stride. Each accepted draw produces a held
completion; frame completion waits for that report and all frame work to drain.
Multiple draws may use an open frame. The enclosing owner retains the input
allocation, translation and input-memory transaction ID zero until frame
completion and drains provisional outputs on fault or cancellation.

A cancellation does not withdraw an offered patch: the frame sees cancellation
only after the offer is accepted, or after the sequence completes. If the
frame reaches a terminal result first, it flushes a pending unaccepted patch.
A sequence admission failure cancels and drains the frame; the draw completion
contains the cause. Child input and shader failures retain the image owner's
reported fault. Native descriptor reading and driver submission, other
primitive domains, and product timing and throughput qualification remain open.

The bounded admission test checks invalid frame rejection and reuse. The local
4×4 two-CU integration uses one draw to load two patches from GPU memory, run
control, evaluation and fragment shaders, and publish shaded and clear RGBA8
pixels. It also exercises input faults, cancellation with a held request,
sequence admission failure and a recovery frame.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSequenceImageAdmissionSpec opengpu.graphics.TessellationSequenceImageSharedSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
