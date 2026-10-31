# Controlled isoline window bin pass

`ControlledIsolineWindowBinPass` sends shared-CU isoline evaluation through
clipping and viewport preparation into acknowledged window-primitive bin
storage. It snapshots each task's raster state, state handle and token; assigns
pass-wide 32-bit bin sequences; and leaves geometry invocation/layer metadata
unset. A task owns the source until its completion is consumed; the pass owns
its arenas until both the source and bin engines drain. Mismatched pass
identities are rejected before source launch, and a bin fault cancels the
source while retaining the bin failure at pass completion.

The bounded admission check is selected for CI. The local 4×4 real-CU test
covers two ordered patches, replay backpressure, identity rejection, a
record-store fault and a clean pass after the fault:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineWindowBinPassSpec opengpu.system.ControlledIsolineWindowBinAdmissionSpec'
```

CI runs `ControlledIsolineWindowBinAdmissionSpec` for identity and
sequence-boundary admission; the real-CU qualification above stays local.

Replay into public fragment builtins continues in
[CONTROLLED_ISOLINE_REPLAY_FRAGMENT.md](CONTROLLED_ISOLINE_REPLAY_FRAGMENT.md).
Typed image publication continues in
[CONTROLLED_ISOLINE_WINDOW_BIN_TYPED_IMAGE.md](CONTROLLED_ISOLINE_WINDOW_BIN_TYPED_IMAGE.md).
Native draw submission, other tessellation domains and product qualification
remain open. This test does not establish product frame rate or physical
timing; H8 remains open.
