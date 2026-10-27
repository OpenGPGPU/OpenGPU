# Primitive bin store admission

`PrimitiveBinStore` owns one arena binding from accepted `begin` through the
final completion handshake. It returns to idle only after the writer, page
pool and replay reader have completed. The writer consumes the page-pool
completion only after outstanding writes and append replies drain.

Admission therefore depends on the parent idle state and cancellation. It
need not forward the writer/page-pool readiness path from memory reply tags.
An assertion checks that the child writer accepts every parent admission.
This preserves binding ownership, completion backpressure and same-arena reuse;
it adds no admission cycle or buffering and changes no capacities.

All 12 `PrimitiveBinStoreSpec` cases pass, including one/three/eight write
credits, reordered replies, exact exhausted-prefix restart, write/read faults,
held completions, build/replay cancellation and healthy reuse. Reproduce with:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 'testOnly opengpu.graphics.PrimitiveBinStoreSpec'
```

This one-bin writer/replay composition is distinct from the production
multi-tile directory and two-engine renderer. Its validation alone does not
close H2 or qualify the whole-GPU clock or frame rate.
