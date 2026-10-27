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

The complete 11-module synthesis screen passes 1,000 ps TC/SLVT with 25% I/O
delay: core +82.279 ps and I/O +77.969 ps, with zero skipped or unresolved
instances. The previous I/O slack was -95.834 ps. Standard ABC and preserved
hierarchy are used, without retiming, memory mocks or false paths. Reproduce
with ChipAgent's Python environment (a sibling `../chipagent` checkout below):

```sh
sbt -J-Xmx2G 'runMain opengpu.elaboration.EmitPpaRtl mobile-bin-store .artifacts/bin-store/emitted'
python3 scripts/extract_emitted_rtl.py .artifacts/bin-store/emitted PrimitiveBinStore .artifacts/bin-store/complete
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py .artifacts/bin-store/complete PrimitiveBinStore \
  --stage synthesis --synthesis-engine yosys --yosys-preserve-hierarchy \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/bin-store/qualification
```

This one-bin writer/replay composition is distinct from the production
multi-tile directory and two-engine renderer. Its validation alone does not
close H2 or qualify the whole-GPU clock or frame rate.
