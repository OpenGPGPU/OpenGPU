# Control and evaluation shaders for retained isolines

`TessellationCuIsolineSharedEvaluator` connects the control-CU patch owner to
`IsolinePatchInputReader` and `IsolineGeometryCuExecutor`. After control
completion, each generated coordinate receives an ordered, checked snapshot of
up to 61 selected control-patch words before the evaluation shader runs. The
shader sees tessellation coordinates in input vertex 0 components 0–2, followed
by the selected words, using the existing scalar geometry instruction ABI. A
bad selection fails evaluation. External inspection and internal reads share the
retained context, with held offers and replies routed to their issuer.

The caller reserves the control CU interface, shared evaluation command ID and
shader resources through completion. The two launch interfaces can be routed to
one CU after control completion; the enclosing owner must provide that routing,
forward cancellation and drain accepted launches, responses and outputs.
Outputs are provisional until successful completion. This is an internal
one-patch isoline path; native scheduling and submission, other tessellation
domains, compiler integration and product qualification remain open.

The four-lane/two-warp same-CU simulation executes a control shader that writes
both tessellation levels and a per-patch word and crosses a barrier. A real
evaluation shader reads the generated coordinate and the selected patch word;
the test checks both endpoints, invalid-selection failure, cancellation while
the evaluation CU is owned and subsequent reuse. It does not render an image or qualify the default product profile.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationCuIsolineSharedEvaluatorSpec'
```
