# Controlled isoline patch evaluation

`TessellationIsolinePatchEvaluation` connects controlled patch generation to
serial evaluation requests. The patch remains resident until accepted evaluator
requests, held evaluated outputs, and accepted patch inspections and replies
have drained. Generated and evaluated results are provisional until the final
completion succeeds. The evaluator must answer accepted requests during
cancellation; the consumer must drain a held output.

The bounded test checks successful evaluation, reading patch data while an
executor response is outstanding, cancellation and drain, and evaluation
failure:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolinePatchEvaluationSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The external evaluator still owns shader execution and resource binding. This
handoff does not provide a complete TCS/TES instruction ABI, triangle or quad
domains, native submission or product throughput qualification; H8 remains open.
