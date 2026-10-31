# Controlled isoline primitive mapping

`TessellationControlledIsolineRenderer` maps evaluated control-patch outputs into
assembled point or line primitives for the existing graphics path. The owner
supplies the output linkage and unique sequence and primitive-ID bases. Sequence
or primitive-ID overflow and invalid linkage report a mapping failure.

Evaluation requests, inspection replies and mapped output are drained during
cancellation. Mapped output remains provisional until successful completion;
completion waits for any held mapping result. Patch storage can be released
once evaluation and inspection drain because mapping uses copied output words.
The external evaluator, native control/evaluation shader ABI, remaining domains,
whole renderer integration and product qualification are separate requirements.

Reproduce the bounded test:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationControlledIsolineRendererSpec'
```
