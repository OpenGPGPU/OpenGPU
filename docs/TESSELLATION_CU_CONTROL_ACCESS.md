# CU tessellation control patch access

`GpuComputeUnit(tessellationAvailable=true, finishOnTrap=true)` can execute
task-owned control patch word operations through its vector issue and commit
path. The feature is absent by default. `tessellationEnabled` and the physical
warp-slot to patch-invocation base table are captured on kernel acceptance;
the patch lease remains asserted until the kernel completion is consumed. The
enclosing owner must supply correct, disjoint warp mappings, retain the patch,
route requests and results, and propagate cancellation and terminal context
errors until all accepted work drains.

The opt-in internal CUSTOM-1 functions 20, 21 and 22 implement parameter
capture, load and store. They use the operand format in
[the vector bridge](TESSELLATION_CONTROL_VECTOR_ACCESS.md). Decode rejects the
operations when this capability is unavailable or disabled, even if texture
sampling is enabled. The CU asserts that an enabled patch task has one fully
resident, one-dimensional workgroup of 1–32 invocations. A failed patch access
suppresses its destination write and uses the existing vector fault path: the
fault records the instruction PC and load/store cause, with the active warp
mask and a zero address. It does not identify an individual failed lane.
Earlier lane stores can already be visible.

The bounded real-CU test executes parameter capture, store and load against
four- and eight-invocation patches, verifies task enable and warp bases are
snapshotted, checks a store access fault and instruction PC, and executes a
healthy successor. The eight-invocation case uses two distinct physical warps
with checked patch bases. Decoder checks exercise both vector mask-bit states
and disabled capability profiles. These checks do not establish the full
32-invocation capacity.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitTessellationSpec opengpu.core.frontend.decode.TessellationExecutionAvailabilitySpec opengpu.graphics.TessellationControlVectorContextSpec'
```

This is an internal CU interface, not a GLSL or driver ABI. CU barrier and
finish events are not yet connected to the control warp scheduler. Real
multi-warp control execution, fault lane attribution, compiler and driver
admission, native submission, other tessellation domains, physical timing and
product throughput remain open; H8 remains open.
