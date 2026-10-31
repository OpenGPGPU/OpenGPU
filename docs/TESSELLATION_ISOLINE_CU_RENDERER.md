# Isoline rendering through a shared CU

`IsolineGeometryCuRenderer` connects isoline generation and output mapping to
the scalar shared-CU executor. The enclosing renderer retains the command ID,
shader program and resources until patch completion. Generated points or lines
are provisional until success; on failure or cancellation the owner must
discard them. A held evaluation request is still accepted during cancellation,
then its live CU is cancelled and its response drained.

The bounded check uses the actual generator, evaluator, mapper and scheduler
with a simulated CU event/completion interface. It verifies coordinate-to-task
transport, assembled line output, backpressure, cancellation and task reuse.
Both checks below passed locally; the real-CU case generated one line and
evaluated both endpoints through the shared CU.

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuRendererSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuRendererQualificationSpec'
```

This composition uses the internal geometry scalar instruction ABI. Patch data,
GLSL TES compilation, native submission, triangle and quad tessellation,
integration with binning and product throughput remain open. The bounded check
does not itself simulate a CU; the local-only qualification executes the
generated line's coordinate shader on a real shared CU.
