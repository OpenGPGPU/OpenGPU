# Geometry primitive pipeline

`GeometryPrimitivePipeline` owns one assembled input primitive across linked input mapping, ordered geometry invocations on the shared CU scheduler and output mapping into downstream primitives. It snapshots the input, link layouts, shader launch, output sequence and command tag; the enclosing router must reserve that tag through the held completion. The pipeline assigns output sequence numbers in order and reports emitted primitive and completed-invocation counts. A mapping fault or sequence exhaustion cancels remaining work and reports an invalid result; an assigned sequence is not reclaimed after a fault.

Cancellation during input mapping drains and discards the input task. After CU admission it requests cancellation on the owning CU and waits for worker acknowledgement. An output already offered to the consumer remains stable and must be accepted before completion. A held completion prevents reuse. Accepted output is not rolled back.

`GeometryPrimitivePipelineSpec` covers two invocations, retained metadata, output backpressure, malformed output mapping, cancellation before and after CU admission, and cancellation with held output. Its real-system case sends an assembled triangle through a physical shared CU and checks two ordered mapped primitives, shader-written IDs and completion. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryPrimitivePipelineSpec'
python3 scripts/test_test_selection.py
```

This is a serial internal pipeline, not a production draw path. Adjacency input, transform feedback, queries, layered framebuffer selection, native host/API/compiler integration, throughput and physical qualification remain open. H8 remains open.
