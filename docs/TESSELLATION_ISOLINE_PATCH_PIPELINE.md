# Control patch to isoline generation

`TessellationIsolinePatchPipeline` owns a control patch through its barrier
result, reads outer levels 0 and 1 from the completed patch, and passes them to
the isoline generator. The patch remains available for read-only output
inspection until the downstream evaluator releases it. Accepted inspection
requests and replies drain before close; primitives must be treated as
provisional until the patch completes successfully.

The control issuer must initialize the levels and all components that the
evaluator will read, issue each invocation's operations in program order and
consume opened, reply, barrier and control results. The evaluator must release
the patch only after its dependent work drains, including after cancellation or
a generation fault. Control-stage cancellation and invalid layouts report
failure after their accepted work drains.

The bounded tests cover level transfer, held output, retained patch inspection,
concurrent inspection and release, cancellation with held output, divergent
barriers, held completion and reuse:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolinePatchPipelineSpec opengpu.graphics.TessellationIsolinePatchCancellationSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This is an internal handoff. It does not execute a control shader, supply a
complete tessellation-evaluation shader ABI, implement triangle or quad domains,
or establish native/API integration or product throughput. H8 remains open.
