# Isoline evaluation handoff

`TessellationIsolineEvaluation` holds one generated point or line while an
external evaluation executor processes its vertices. It translates the
generator's Q0.24 coordinates into exact IEEE binary32 `gl_TessCoord` values,
issues one tagged request at a time, and retains the 64 component words returned
for each vertex until the downstream consumer accepts the primitive. Point
mode issues one request; line mode issues two. A mismatched response tag or
execution failure produces a held fault instead of a primitive.

The executor must return exactly one response per accepted request, including
while an enclosing task is being cancelled. The enclosing owner must drain this
handoff before releasing patch storage or shader resources. Inputs must be
coordinates from the bounded isoline generator (0 through 1 in Q0.24). This
handoff is an internal execution contract, not a shared-CU shader implementation
or a native API. It does not establish shader invocation reuse, overall
tessellation conformance, physical timing, or product throughput.

The focused check covers exact coordinate conversion, ordered point and line
requests, payload retention under backpressure, tag and execution faults, and
reuse after completion:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolineEvaluationSpec'
```
