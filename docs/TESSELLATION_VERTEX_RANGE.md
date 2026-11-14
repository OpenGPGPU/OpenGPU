# Original VS range completion and transport drain

`TessellationVertexPatchSequence` emits original draw-relative VS slices in
patch/instance order. Its retirement input belongs to the complete patch
owner: an acknowledged VS publication alone is not TCS/TES retirement.
The parent must retain the original draw and VM resources for the sequence.
The range does not compute geometry or infer publication counts.

Every first completion waits for the actual `childBusy` input to clear,
including invalid, empty and canceled descriptors that never launch a child.
The assertion applies when that result is captured. A later valid-only
transport flush may make the child busy again while the completion is held;
the captured status and counts remain valid and unchanged. Consuming that
report does not authorize another begin: `begin.ready` still requires the
current child transport to drain. Offered tasks, cancellation and actual
retirement handshakes retain their existing behavior.

## Focused final results

Six current source units passed incremental Scala 2.13.18 / Chisel-plugin
compilation against a read-only frozen dependency classpath:

- `graphics/VertexInputSlice.scala`
- `graphics/VertexInputFetcher.scala`
- `graphics/VertexShaderStage.scala`
- `system/TessellationVertexPatchSource.scala`
- `system/TessellationVertexPatchSequence.scala`
- `graphics/TessellationVertexPatchSequenceSpec.scala` (test source)

All four focused component RTL cases passed: original ordering and whole-patch
retirement; range/count/task-ID overflow and reuse; held-offer cancellation
and failed/mismatched retirement; and held completion across a subsequent
flush. The last case also checks next-begin exclusion until real drain,
actual subsequent task acceptance/retirement, and first-report drain for
invalid, empty and legally canceled descriptors. The three existing cases
and the final expanded case all passed. Compilation took about seven seconds and each simulation about
31 seconds.

Two independent negative controls were executed. The original implementation
failed its own whole-patch-drain assertion when a later flush arrived during
the held report. Removing the next-begin drain gate failed the test's explicit
idle-but-child-busy ready assertion. Neither result depended on a timeout.

Each separate process group had a 180-second limit, one CPU, 128MiB Java heap,
128MiB metaspace, 32MiB code cache, one Verilator job, optimization zero and
simulation cache disabled. The six source inputs were unchanged through each
run. All groups terminated before private classes, simulations and logs were
removed.

The simulations instantiate the real range state machine with a modeled
patch-retirement peer. They execute no VS/TCS/TES ISA, instantiate no CU and
do not prove the complete typed parent, protected source admission, native
owner lifecycle or product qualification. No serialized ABI, discovery bit,
default factory or hardware capacity changes here.

Focused reproduction in an explicitly permitted resource window:

```sh
sbt 'testOnly opengpu.graphics.TessellationVertexPatchSequenceSpec'
```

The bounded local runner compiles the six listed current files with the
Chisel plugin, then uses ScalaTest's runner with
`-s opengpu.graphics.TessellationVertexPatchSequenceSpec`; `-z` selects one case
per simulation. Do not enable or dispatch Scala regression.
