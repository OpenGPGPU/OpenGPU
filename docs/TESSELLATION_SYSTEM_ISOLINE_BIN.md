# Shader-controlled isolines in window bins

`TessellationSystemIsolineBinPass` retains a pass across shared-system control
and evaluation shaders, primitive mapping, clipping, viewport conversion and
acknowledged window-record bin storage. It replays complete window records in
pass sequence order to independent tile engines. The task identity must match
the pass, the tag and shader resources must remain reserved through task
completion, and all accepted writes, reads and offered replay records must
drain before pass completion. Outputs remain provisional until successful pass
completion. Geometry invocation and layer metadata are not supplied by this
isoline path.

The bounded actual-system test runs two real control/evaluation shader pairs,
checks clipped window coordinates and a control-written varying after storage
and replay, holds replay output under backpressure, and checks identity
rejection, a failed record write and subsequent reuse. It uses one four-lane,
two-warp CU and a 4×4 frame with two replay engines. This does not establish
native submission, fragment shading or image publication, multi-CU contention,
default-profile performance, physical timing or product qualification.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationSystemIsolineBinPassSpec'
python3 scripts/test_test_selection.py
```
