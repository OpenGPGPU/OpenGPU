# Transform feedback owner transfer

`TransformFeedbackOwnerTransfer` is an internal control-plane coordinator for
two feedback exporters. It keeps a bounded directory of live object identities
and routes external object commands to the selected owner. The enclosing owner
must stop admitting draws while `quiesce` is asserted and report both renderers
and their traffic drained through `ownerIdle` before a switch is accepted.

A switch pauses each running object, evicts its complete checkpoint, restores
it on the other exporter and resumes it if necessary. Already paused objects
remain paused. The checkpoint carries the root, context, cookie, descriptor,
cursors, counters and latched fault. An unexpected failed or mismatched
transfer poisons the coordinator and blocks further use until global reset;
it does not pretend a partially moved table is usable. Accepted command offers
remain held across reset and both exporters must drain before admission resumes.
Held public replies remain stable across reset.

Local tests use two actual feedback exporters to check exact stores and counters
across switches in both directions, multiple address-space roots, paused and
running objects, rejected commands, faulted objects, held replies, reset during
a held offer, and fail-closed recovery. Reproduce with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.TransformFeedbackOwnerTransferSpec opengpu.graphics.TransformFeedbackExportSpec'
```

Native host admission uses this coordinator to switch feedback between the
binned and tessellation renderers. A poisoned coordinator rejects jobs for
either owner until reset; a job waiting for renderer readiness is also rejected
if poisoning occurs before it is accepted. The directed admission and reset
tests pass with:

```sh
sbt 'testOnly opengpu.system.TessellationFeedbackAdmissionSpec'
```

Feedback store translation, reset routing and physical alias provenance still
need integrated verification before advertising a product capability.
