# Geometry metadata in bin records

Window-primitive bin records retain the existing payload offsets and record
size. Version 1 keeps the reserved header words zero. Version 2 uses header
words 8–10 for the geometry input primitive ID, invocation ID and layer;
remaining reserved words stay zero. Readers reject unknown versions, nonzero
legacy reserved words and invocation IDs with nonzero upper bits before
publishing a record. The ordinary vertex-bin path continues to emit version 1.

Local validation:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.WindowPrimitiveRecordsSpec opengpu.graphics.WindowPrimitiveBinReplayPassSpec opengpu.graphics.WindowPrimitiveBinProducerSpec opengpu.graphics.VertexBinPipelineSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This record extension provides storage for geometry metadata; connecting a
geometry producer to the native bin/replay and layered attachment paths is
still required for H8.
