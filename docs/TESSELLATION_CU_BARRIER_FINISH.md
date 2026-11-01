# CU tessellation barrier and finish ownership

For an enabled tessellation control task, CU barrier instructions are routed to
the patch scheduler. The adapter snapshots each blocked warp's next PC, mask
and task-owned patch base, waits for CU shared memory to be idle before
offering a park, and retains the path until the matching resume. Barrier IDs
use the post-barrier PC, so divergent program locations are detected by the
patch context. Cancellation and invalid resumes route an attributed trap through
the existing finish-on-trap owner: cancellation reports shader interruption,
and an invalid resume reports an illegal barrier instruction. An offered park remains held until accepted
or until the context's terminal error is accepted.

Normal CU warp completion is delayed until each active invocation's finish is
accepted by the patch context. The original launch mask and base remain owned,
and a held finish prevents the CU kernel completion. Faulted warps retire
without reporting successful patch finishes; the enclosing owner must cancel
and drain the patch. The owner also accepts context errors and forwards the
terminal pulse to the CU. Finished stores are not rolled back. Ordinary
non-tessellation tasks retain the existing CU-local barrier and finish path.

The bounded adapter tests cover held offers and responses, mismatched resumes,
cancellation, terminal drain and faulted warp retirement. The real-CU test
executes patch stores, a shader barrier and loads across two and eight
four-lane warps, including a 32-invocation patch. It holds a finish handshake
and verifies that kernel completion waits, checks patch barrier and completion
counts, and exercises fault and cancellation drain. This is not a default-lane
product workload, GLSL or native submission ABI, nor physical timing or product
throughput qualification. A two-warp ordinary kernel also exercises the local
barrier with the capability built in but disabled for its task.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationCuBarrierSpec opengpu.graphics.TessellationCuFinishSpec opengpu.core.GpuComputeUnitTessellationSpec opengpu.core.frontend.decode.TessellationExecutionAvailabilitySpec opengpu.core.GpuComputeUnitBarrierEpochSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The enclosing task still supplies and retains warp-base mappings, shader and
patch resources, completion and cancellation ownership. Full driver/compiler,
native submission, other tessellation domains, complete resource and fault
semantics, physical timing and product workloads remain H8 requirements.
